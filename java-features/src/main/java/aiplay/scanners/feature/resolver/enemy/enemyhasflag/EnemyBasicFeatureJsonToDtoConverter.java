package aiplay.scanners.feature.resolver.enemy.enemyhasflag;

import aiplay.runtime.config.CoordinatesConverter;
import aiplay.scanners.feature.jsontodtoconverters.CollisionsConverter;
import aiplay.scanners.feature.jsontodtoconverters.ViewRotationConverter;
import aiplay.scanners.feature.resolver.enemy.PlayerSlotConverter;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.Player;
import aiplay.ut99webmodel.PlayerVisibilityEntry;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;

/**
 * Populates all enemy and teammate slots on GameStateDto from the raw game state.
 * Uses PlayerSlotConverter for distance-sorted slot assignment with hysteresis.
 *
 * On first call per frame (detected via enemies == null guard), populates:
 * - dto.enemies[] (max 2 slots, sorted by 2D distance)
 * - dto.teammates[] (max 1 slot)
 * - dto.player1 = dto.enemies[0] (backward-compat alias)
 *
 * Subsequent calls for the same frame are no-ops.
 */
public class EnemyBasicFeatureJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    private final CoordinatesConverter coordinatesConverter = new CoordinatesConverter();
    private final ViewRotationConverter viewRotationConverter = new ViewRotationConverter();
    private final CollisionsConverter collisionsConverter = new CollisionsConverter();
    private final PlayerSlotConverter slotConverter = new PlayerSlotConverter();

    @Override
    public Integer priority() {
        return 0;
    }

    @Override
    public GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        if (gs.Players == null) {
            return dto;
        }

        // Guard: only populate once per frame — enemies[] presence is the marker
        if (dto.enemies != null) {
            return dto;
        }

        String aiName = aiplay.runtime.context.PlayerIdentityContext.effectivePlayerName();
        PlayerSlotConverter.SlotResult result = slotConverter.classify(gs, aiName);

        // Populate enemy slots
        dto.enemies = new PlayerDto[PlayerSlotConverter.MAX_ENEMY_SLOTS];
        for (int i = 0; i < result.enemies().length; i++) {
            Player raw = result.enemies()[i];
            if (raw != null) {
                dto.enemies[i] = convertPlayer(raw, gs, aiName);
            }
        }

        // Populate teammate slots
        dto.teammates = new PlayerDto[PlayerSlotConverter.MAX_TEAMMATE_SLOTS];
        for (int i = 0; i < result.teammates().length; i++) {
            Player raw = result.teammates()[i];
            if (raw != null) {
                dto.teammates[i] = convertPlayer(raw, gs, aiName);
            }
        }

        // Backward-compat: player1 = enemies[0]
        dto.player1 = dto.enemies[0];

        return dto;
    }

    @Override
    public GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto) {
        return enrichAll(sessionId, gs, dto);
    }

    /**
     * Fully populate a PlayerDto from a raw Player webservice object.
     */
    private PlayerDto convertPlayer(Player raw, GameState gs, String aiName) {
        PlayerDto p = new PlayerDto();
        p.name = raw.Name;
        p.location = coordinatesConverter.convert(raw.Location);
        p.viewRotation = viewRotationConverter.convert(raw.ViewRotation);
        p.hasFlag = !"None".equalsIgnoreCase(raw.HasFlag);
        p.hasFlag_norm = p.hasFlag ? 1.0f : 0.0f;
        p.health = parseIntSafe(raw.Health);
        p.team = parseIntSafe(raw.Team);
        p.score = (int) Math.round(parseDoubleSafe(raw.Score));
        p.baseEyeHeight = parseDoubleSafe(raw.BaseEyeHeight);
        p.bFeigningDeath = isTrue(raw.bFeigningDeath);
        p.bIsSpectator = isTrue(raw.bIsSpectator);
        p.bIsABot = isTrue(raw.bIsABot);
        p.bIsRLControlled = isTrue(raw.bIsRLControlled);
        p.bWaitingPlayer = isTrue(raw.bWaitingPlayer);
        p.dodgeState = DodgeState.fromInt(parseIntSafe(raw.DodgeState));
        p.physics = aiplay.dto.Ut99PhysicsType.fromInt(parseIntSafe(raw.Physics));
        p.headUnderwater = isTrue(raw.bHeadUnderwater);
        if (raw.BreathRemaining != null && !raw.BreathRemaining.isEmpty()) {
            p.breathRemaining = (float) parseDoubleSafe(raw.BreathRemaining);
        }
        p.collisions = collisionsConverter.convert(raw.Collisions);
        p.enemyVisible = isPlayerVisible(gs, aiName, raw.Name);
        p.slot = parseSlotSafe(raw.Slot);
        p.lastDamageAmount = parseIntSafe(raw.LastDamageAmount);
        p.lastDamageType = raw.LastDamageType;
        p.lastDamageSelfInflicted = isTrue(raw.LastDamageSelfInflicted);
        p.lastDamageInstigatorSlot = parseSlotSafe(raw.LastDamageInstigatorSlot);

        // KPI counters (Plan A/B/D2)
        p.frags          = parseIntSafe(raw.Frags);
        p.flagsTaken     = parseIntSafe(raw.FlagsTaken);
        p.flagsCaptured  = parseIntSafe(raw.FlagsCaptured);
        p.flagsReturned  = parseIntSafe(raw.FlagsReturned);
        p.shots          = parseIntSafe(raw.Shots);
        p.shotsOnTarget  = parseIntSafe(raw.ShotsOnTarget);
        p.damageDealtTotal = parseIntSafe(raw.DamageDealtTotal);
        p.damageTakenTotal = parseIntSafe(raw.DamageTakenTotal);

        // Velocity: world-frame components + projection onto target's own forward/right axes.
        // velocityX/Y/Z_norm zijn nodig zodat EnemySlotRelativeBatchEnricher.applyRelativeVelocity
        // ze in de bot-view-frame kan projecteren (relVelForward/Right/Up_norm).
        if (raw.Velocity != null) {
            try {
                String[] vParts = raw.Velocity.split(",");
                if (vParts.length >= 2) {
                    double vx = Double.parseDouble(vParts[0].trim());
                    double vy = Double.parseDouble(vParts[1].trim());
                    p.velocityX_norm = (float) Math.max(-1.0, Math.min(1.0, vx / 1000.0));
                    p.velocityY_norm = (float) Math.max(-1.0, Math.min(1.0, vy / 1000.0));
                    double vz = 0.0;
                    if (vParts.length >= 3) {
                        vz = Double.parseDouble(vParts[2].trim());
                        p.velocityZ_norm = (float) Math.max(-1.0, Math.min(1.0, vz / 1000.0));
                    }
                    double speedMag = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    p.speed_norm = (float) Math.min(1.0, speedMag / 1000.0);
                    if (p.viewRotation != null) {
                        double yaw = aiplay.util.NormalizationUtils.viewRotationXToRad(p.viewRotation.x);
                        double fx = Math.cos(yaw), fy = Math.sin(yaw);
                        double rx = aiplay.util.NormalizationUtils.RIGHT_IS_POSITIVE ? fy : -fy;
                        double ry = aiplay.util.NormalizationUtils.RIGHT_IS_POSITIVE ? -fx : fx;
                        double forwardVel = vx * fx + vy * fy;
                        double rightVel = vx * rx + vy * ry;
                        p.forwardVelocity_norm = (float) Math.max(-1.0, Math.min(1.0, forwardVel / 1000.0));
                        p.rightVelocity_norm = (float) Math.max(-1.0, Math.min(1.0, rightVel / 1000.0));
                    }
                }
            } catch (Exception ignore) {}
        }

        return p;
    }

    /**
     * Check if a player is visible to our bot by looking up the bot's Visibility array.
     */
    private static boolean isPlayerVisible(GameState gs, String aiName, String targetName) {
        if (gs.Players == null || aiName == null || targetName == null) return false;
        for (Player p : gs.Players) {
            if (p != null && p.Name != null && p.Name.equalsIgnoreCase(aiName)) {
                if (p.Visibility != null) {
                    for (PlayerVisibilityEntry v : p.Visibility) {
                        if (v.Name != null && v.Name.equalsIgnoreCase(targetName)) {
                            return isTrue(v.bVisible);
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Slot fields default to -1 (unknown / unassigned) rather than 0 — slot 0 is a real slot. */
    private static int parseSlotSafe(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static boolean isTrue(String v) {
        return "True".equalsIgnoreCase(v) || "1".equalsIgnoreCase(v);
    }
}
