package aiplay.scanners.feature.resolver.enemy;

import aiplay.config.model.ModelConfig;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.resolver.PlayerSlotDodgeTracker;
import aiplay.shared.view.ViewTargeting;
import aiplay.util.NormalizationUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enriches frames with relative features for ALL enemy slots.
 * Populates playerPawn.enrichments.enemyRels[N] for each populated slot.
 * Also maintains backward-compat alias: enrichments.enemyRel = enemyRels[0].
 *
 * Additionally tracks enemy dodge state per slot via {@link PlayerSlotDodgeTracker}.
 */
public class EnemySlotRelativeBatchEnricher implements TrainingFeatureEnricher {

    private static final double DIST_TAU = 600.0;
    /** Tanh-schaal voor egocentric rel_z. Bij dz=512 → 0.46, dz=1024 → 0.76, dz=2048 → 0.96.
     *  Past combat-encounter Z-delta's (pairwise spawn p95 ~752 UU, max ~1152 UU over 10 maps). */
    private static final double REL_Z_TANH_SCALE = 512.0;
    private static final int MAX_ENEMY_SLOTS = EnemySlotFeatureComponent.MAX_SLOTS;
    public static final int DODGE_COOLDOWN_MS = loadDodgeCooldownMs();
    private static final int CSV_FPS = loadCsvFps();

    private static int loadDodgeCooldownMs() {
        try {
            ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
            int v = cfg.runtime().dodgeCooldownMs();
            return v > 0 ? v : 300;
        } catch (Exception e) {
            return 300;
        }
    }

    private static int loadCsvFps() {
        try {
            ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
            return cfg.trainingCsv().csvFps() > 0 ? cfg.trainingCsv().csvFps() : 30;
        } catch (Exception e) {
            return 30;
        }
    }

    private final ConcurrentHashMap<String, PlayerSlotDodgeTracker.IncrementalState[]> sessions =
        new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;

        PlayerSlotDodgeTracker.BatchState dodgeState = new PlayerSlotDodgeTracker.BatchState(MAX_ENEMY_SLOTS);
        double frameDurationMs = 1000.0 / CSV_FPS;

        for (int i = 0; i < frames.size(); i++) {
            GameStateDto f = frames.get(i);
            if (f == null) continue;
            enrichRelativePositions(f);
            PlayerSlotDodgeTracker.updateBatch(f.enemies, i, dodgeState, frameDurationMs, DODGE_COOLDOWN_MS);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        PlayerSlotDodgeTracker.IncrementalState[] slots = sessions.computeIfAbsent(
            sessionId, k -> PlayerSlotDodgeTracker.createIncrementalSlots(MAX_ENEMY_SLOTS));
        long now = System.currentTimeMillis();
        for (GameStateDto f : frames) {
            if (f == null) continue;
            enrichRelativePositions(f);
            PlayerSlotDodgeTracker.updateIncremental(f.enemies, slots, now, DODGE_COOLDOWN_MS);
        }
    }

    // --- Relative position enrichment (unchanged) ---

    private void enrichRelativePositions(GameStateDto f) {
        PlayerDto self = f.playerPawn;
        if (self == null || self.location == null || self.viewRotation == null) return;

        int numSlots = (f.enemies != null) ? f.enemies.length : 0;
        if (numSlots == 0) {
            self.enrichments.enemyRel = null;
            self.enrichments.enemyRels = null;
            return;
        }

        if (self.enrichments.enemyRels == null || self.enrichments.enemyRels.length != numSlots) {
            self.enrichments.enemyRels = new PlayerRelationDto[numSlots];
        }

        final int viewX = self.viewRotation.x & 0xFFFF;

        for (int i = 0; i < numSlots; i++) {
            PlayerDto enemy = f.enemies[i];
            if (enemy == null || enemy.health <= 0 || enemy.location == null) {
                self.enrichments.enemyRels[i] = null;
            } else {
                PlayerRelationDto rel = buildRelation(self.location, viewX, enemy.location);
                rel.pitchBearing_norm = computePitchBearingNorm(
                    self.location, self.baseEyeHeight, enemy.location, enemy.baseEyeHeight);
                rel.relZ_norm = computeRelZNorm(self.location, enemy.location);
                applyRelativeVelocity(rel, viewX,
                    enemy.velocityX_norm, enemy.velocityY_norm, enemy.velocityZ_norm);
                rel.aimAlignmentDot_norm = computeAimAlignmentDotNorm(self, enemy);
                self.enrichments.enemyRels[i] = rel;
            }
        }

        self.enrichments.enemyRel = (numSlots > 0) ? self.enrichments.enemyRels[0] : null;
    }

    /**
     * Eye-to-eye 3D aim alignment in [-1,+1] — cosine van bot's view direction
     * (yaw + pitch) tegen de eenheidsvector van bot-eye naar target-eye. 1.0 =
     * perfect op target gericht, vergelijkbaar met de reward-side aim-score
     * (zonder lead-correctie). Gebruikt {@link ViewTargeting#computeAimDot3D}
     * zodat reward en feature dezelfde definitie hanteren.
     */
    public static double computeAimAlignmentDotNorm(PlayerDto self, PlayerDto target) {
        if (self == null || self.location == null || self.viewRotation == null
                || target == null || target.location == null) {
            return 0.0;
        }
        double srcEyeH = Double.isFinite(self.baseEyeHeight) ? self.baseEyeHeight : 0.0;
        double tgtEyeH = Double.isFinite(target.baseEyeHeight) ? target.baseEyeHeight : 0.0;
        double dot = ViewTargeting.computeAimDot3D(
            self.location.x, self.location.y, self.location.z + srcEyeH,
            self.viewRotation.x, self.viewRotation.y,
            target.location.x, target.location.y, target.location.z + tgtEyeH);
        return NormalizationUtils.clampM11(dot);
    }

    /**
     * Eye-to-eye verticale bearing in [-1,+1]. Matcht de aim-semantiek van
     * {@link aiplay.shared.view.ViewTargeting#computePitchNormToward} — source en
     * target z worden verhoogd met baseEyeHeight. Onafhankelijk van de huidige
     * view-pitch, dus robuust tegen drift en bruikbaar bij hoogteverschillen.
     */
    /**
     * Egocentric vertical offset (tgt.z - src.z) tanh-geschaald naar [-1, +1].
     * Map-onafhankelijk en complementair aan {@link #computePitchBearingNorm}: bij
     * korte afstanden wordt pitch-bearing ambigu (kleine dist2D, grote dz), terwijl
     * relZ_norm puur lineair in fysieke UU werkt en de policy / critic direct
     * inzicht geeft of de tegenstander boven of onder de bot zit.
     */
    public static double computeRelZNorm(CoordinatesDto src, CoordinatesDto tgt) {
        if (src == null || tgt == null) return 0.0;
        double dz = tgt.z - src.z;
        if (!Double.isFinite(dz)) return 0.0;
        return NormalizationUtils.clampM11(Math.tanh(dz / REL_Z_TANH_SCALE));
    }

    public static double computePitchBearingNorm(
            CoordinatesDto src, double srcEyeH, CoordinatesDto tgt, double tgtEyeH) {
        double dx = tgt.x - src.x;
        double dy = tgt.y - src.y;
        double dz = (tgt.z + (Double.isFinite(tgtEyeH) ? tgtEyeH : 0.0))
                  - (src.z + (Double.isFinite(srcEyeH) ? srcEyeH : 0.0));
        double dist2D = Math.hypot(dx, dy);
        if (!Double.isFinite(dist2D) || dist2D < 1.0) return 0.0;
        double pitchRad = Math.atan2(dz, dist2D);
        return NormalizationUtils.clampM11(pitchRad / (Math.PI / 2.0));
    }

    /**
     * Project target world-frame normalized velocity onto bot view-frame axes
     * (forward / right / up). Mirrors the convention used for self_forwardVelocity_norm
     * en self_rightVelocity_norm in PlayerPawnBasicFeatureJsonToDtoConverter, zodat
     * enemy{N}_relVelRight_norm dezelfde teken-conventie heeft als self_rightVelocity_norm
     * (RIGHT_IS_POSITIVE).
     *
     * <p>Voor lead-aim is {@code relVelRight_norm} de cruciale component: hoe snel
     * de target dwarst over de bot's kijklijn. {@code relVelForward_norm} zegt of
     * de target nadert of vlucht, wat ook nuttig is voor projectile timing.
     */
    public static void applyRelativeVelocity(PlayerRelationDto rel, int viewYawX,
                                             float vxNorm, float vyNorm, float vzNorm) {
        if (rel == null) return;
        double yaw = NormalizationUtils.viewRotationXToRad(viewYawX);
        double fx = Math.cos(yaw);
        double fy = Math.sin(yaw);
        double rx = NormalizationUtils.RIGHT_IS_POSITIVE ? fy : -fy;
        double ry = NormalizationUtils.RIGHT_IS_POSITIVE ? -fx : fx;
        double forward = vxNorm * fx + vyNorm * fy;
        double right = vxNorm * rx + vyNorm * ry;
        rel.relVelForward_norm = NormalizationUtils.clampM11(forward);
        rel.relVelRight_norm = NormalizationUtils.clampM11(right);
        rel.relVelUp_norm = NormalizationUtils.clampM11(vzNorm);
    }

    public static PlayerRelationDto buildRelation(CoordinatesDto src, int viewYawX, CoordinatesDto tgt) {
        PlayerRelationDto dto = new PlayerRelationDto();

        final double dx = tgt.x - src.x;
        final double dy = tgt.y - src.y;
        final double dz = tgt.z - src.z;

        final double dist2D = Math.hypot(dx, dy);
        final double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double soft2D = NormalizationUtils.softDistance01(dist2D, DIST_TAU);

        double[] sc = NormalizationUtils.relativeAngleSinCos(viewYawX, src.x, src.y, tgt.x, tgt.y);
        double sin = sc[0], cos = sc[1];

        double[] scStab = NormalizationUtils.stabilizeSinCosNear(sin, cos, soft2D, true);
        sin = scStab[0];
        cos = scStab[1];

        double[] fr = NormalizationUtils.forwardRightDistNorm(sin, cos, soft2D);

        dto.relSin = sin;
        dto.relCos = cos;
        dto.forwardDist_norm = fr[0];
        dto.rightDist_norm = fr[1];
        dto.relDistanceLog = Math.log1p(dist3D);
        dto.distance_norm = NormalizationUtils.normalizeDistance3D(dist3D);

        return dto;
    }
}
