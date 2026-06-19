package aiplay.scanners.feature.resolver.projectile;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.ProjectileDto;
import aiplay.dto.ProjectileRelationDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.scanners.feature.resolver.enemy.EnemySlotRelativeBatchEnricher;
import aiplay.util.NormalizationUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick enricher die projectielen attribueert aan hun eigenaar
 * (self / enemy slot N / teammate slot N) en de top-{@code MAX_PROJ_PER_OWNER}
 * per eigenaar invult in de juiste enrichment-array.
 *
 * <p>Ownership-matching gebeurt via {@code instigatorName == player.name}.
 * Server-emitted projectielen ({@code instigatorTeam &lt; 0}) worden geskipt.
 *
 * <p>Sortering binnen een eigenaar:
 * <ul>
 *   <li>self: {@code distFromBot} ascending (proxy voor recency — net afgevuurde
 *       projectielen zijn nog dichtbij de firing-origin = bot).</li>
 *   <li>enemy / teammate: threat-score ({@code time-to-closest-approach}) — meest
 *       urgente eerst.</li>
 * </ul>
 */
public class ProjectileSlotRelativeBatchEnricher implements TrainingFeatureEnricher {

    private static final int MAX_ENEMY_OWNERS = ProjectileSlotFeatureComponent.MAX_ENEMY_OWNERS;
    private static final int MAX_TEAMMATE_OWNERS = ProjectileSlotFeatureComponent.MAX_TEAMMATE_OWNERS;
    private static final int MAX_PROJ_PER_OWNER = ProjectileSlotFeatureComponent.MAX_PROJ_PER_OWNER;

    /** Max flight-time voor normalisatie: 2 sec. */
    private static final double TIME_NORM_SECONDS = 2.0;
    /** Max projectile speed voor normalisatie: flak chunk ~2000 UT/s. */
    private static final double SPEED_NORM = 2000.0;
    /** Damage normalisatie-anker. Range UT99 single-shot projectile damage: ~5 (UTChunk) ..
     *  ~250 (WarShell). 250 HP klap (insta-frag boven super-shield) is een natuurlijke top. */
    private static final double DAMAGE_NORM = 250.0;
    /** ChargeScale normalisatie: DrawScale 1.0 → 0, DrawScale 4.0 → 1. */
    private static final double CHARGE_BASE = 1.0;
    private static final double CHARGE_RANGE = 3.0;

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;
        for (GameStateDto f : frames) enrichOne(f);
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null) return;
        for (GameStateDto f : frames) enrichOne(f);
    }

    private void enrichOne(GameStateDto f) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return;
        if (f.playerPawn.location == null || f.playerPawn.viewRotation == null) return;

        PlayerDto self = f.playerPawn;

        ProjectileRelationDto[] selfArr = new ProjectileRelationDto[MAX_PROJ_PER_OWNER];
        ProjectileRelationDto[][] enemyArr = new ProjectileRelationDto[MAX_ENEMY_OWNERS][MAX_PROJ_PER_OWNER];
        ProjectileRelationDto[][] teammateArr = new ProjectileRelationDto[MAX_TEAMMATE_OWNERS][MAX_PROJ_PER_OWNER];

        self.enrichments.selfProjectileRels = selfArr;
        self.enrichments.enemyProjectileRels = enemyArr;
        self.enrichments.teammateProjectileRels = teammateArr;

        if (f.projectiles == null || f.projectiles.isEmpty()) return;

        String selfName = self.name;
        int selfTeam = self.team;

        List<Ranked> selfList = new ArrayList<>();
        Map<String, List<Ranked>> enemyByName = new HashMap<>();
        Map<String, List<Ranked>> teammateByName = new HashMap<>();

        for (ProjectileDto p : f.projectiles) {
            if (p == null || p.location == null || p.velocity == null) continue;
            if (p.instigatorTeam < 0) continue;
            Ranked r = compute(self, p, f.enemies);
            if (r == null) continue;

            String owner = p.instigatorName;
            if (owner == null || owner.isEmpty()) continue;

            if (owner.equals(selfName)) {
                selfList.add(r);
            } else if (p.instigatorTeam == selfTeam) {
                teammateByName.computeIfAbsent(owner, k -> new ArrayList<>()).add(r);
            } else {
                enemyByName.computeIfAbsent(owner, k -> new ArrayList<>()).add(r);
            }
        }

        selfList.sort(Comparator.comparingDouble(r -> r.distFromBot));
        fillSlots(selfArr, selfList);

        if (f.enemies != null) {
            for (int n = 0; n < Math.min(MAX_ENEMY_OWNERS, f.enemies.length); n++) {
                PlayerDto en = f.enemies[n];
                if (en == null || en.name == null) continue;
                List<Ranked> list = enemyByName.get(en.name);
                if (list == null) continue;
                list.sort(Comparator.comparingDouble(r -> r.threatScore));
                fillSlots(enemyArr[n], list);
            }
        }

        if (f.teammates != null) {
            for (int n = 0; n < Math.min(MAX_TEAMMATE_OWNERS, f.teammates.length); n++) {
                PlayerDto tm = f.teammates[n];
                if (tm == null || tm.name == null) continue;
                List<Ranked> list = teammateByName.get(tm.name);
                if (list == null) continue;
                list.sort(Comparator.comparingDouble(r -> r.threatScore));
                fillSlots(teammateArr[n], list);
            }
        }
    }

    private static void fillSlots(ProjectileRelationDto[] slots, List<Ranked> sorted) {
        int n = Math.min(sorted.size(), slots.length);
        for (int i = 0; i < n; i++) slots[i] = sorted.get(i).rel;
    }

    private static Ranked compute(PlayerDto self, ProjectileDto p, PlayerDto[] enemies) {
        double dx = p.location.x - self.location.x;
        double dy = p.location.y - self.location.y;
        double dz = p.location.z - self.location.z;
        double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double vx = p.velocity.x;
        double vy = p.velocity.y;
        double vz = p.velocity.z;
        double vmag = Math.sqrt(vx * vx + vy * vy + vz * vz);

        double tClosest;
        double closestDist;
        if (vmag > 1.0) {
            double pdotv = dx * vx + dy * vy + dz * vz;
            tClosest = -pdotv / (vmag * vmag);
            if (tClosest > 0) {
                double cx = dx + vx * tClosest;
                double cy = dy + vy * tClosest;
                double cz = dz + vz * tClosest;
                closestDist = Math.sqrt(cx * cx + cy * cy + cz * cz);
            } else {
                tClosest = Double.POSITIVE_INFINITY;
                closestDist = dist3D;
            }
        } else {
            tClosest = Double.POSITIVE_INFINITY;
            closestDist = dist3D;
        }
        double threat = (Double.isFinite(tClosest) && tClosest > 0)
            ? tClosest + closestDist / SPEED_NORM
            : 1e9 + dist3D;

        ProjectileRelationDto rel = new ProjectileRelationDto();
        rel.present = 1.0f;

        int viewX = self.viewRotation.x & 0xFFFF;
        double[] sc = NormalizationUtils.relativeAngleSinCos(viewX, self.location.x, self.location.y, p.location.x, p.location.y);
        rel.relSin = sc[0];
        rel.relCos = sc[1];
        rel.distance_norm = NormalizationUtils.normalizeDistance3D(dist3D);
        rel.pitchBearing_norm = EnemySlotRelativeBatchEnricher.computePitchBearingNorm(
            self.location, self.baseEyeHeight, p.location, 0.0);

        double yawRad = (viewX & 0xFFFF) * (Math.PI * 2.0 / 65536.0);
        double cosY = Math.cos(yawRad);
        double sinY = Math.sin(yawRad);
        double forwardV = vx * cosY + vy * sinY;
        double rightV = -vx * sinY + vy * cosY;
        rel.forwardVelocity_norm = NormalizationUtils.clampM11(forwardV / SPEED_NORM);
        rel.rightVelocity_norm = NormalizationUtils.clampM11(rightV / SPEED_NORM);
        rel.speed_norm = Math.min(1.0, vmag / SPEED_NORM);

        if (Double.isFinite(tClosest) && tClosest > 0) {
            rel.timeToImpact_norm = Math.min(1.0, tClosest / TIME_NORM_SECONDS);
        } else {
            rel.timeToImpact_norm = 1.0;
        }
        rel.closestApproachDistance_norm = NormalizationUtils.normalizeDistance3D(closestDist);

        classifyProjectileType(rel, p.projectileClass);

        rel.damage_norm = Math.max(0.0, Math.min(1.0, p.damage / DAMAGE_NORM));
        rel.chargeScale_norm = Math.max(0.0,
            Math.min(1.0, (p.drawScale - CHARGE_BASE) / CHARGE_RANGE));

        fillEnemyClosestApproach(rel, p, enemies);

        return new Ranked(rel, threat, dist3D);
    }

    /**
     * Closest-approach van projectiel {@code p} tot de DICHTSTBIJZIJNDE enemy op de huidige baan —
     * dezelfde punt-tot-baan-projectie als de bot-closest-approach hierboven, maar tegen elke
     * enemy-positie, met het minimum (de enemy die het projectiel het dichtst nadert). Geeft het
     * model de offensieve grootheid die we tot nu toe alleen in de reward berekenden: "scheert mijn
     * projectiel langs een enemy, en wanneer" — generiek over alle wapens (shock-combo-detonatie,
     * rocket/flak-splash-timing). Vooral zinvol voor eigen projectielen; voor enemy/teammate-slots
     * wordt het berekend maar (nog) niet als feature gepubliceerd. Geen enemies → DTO-defaults (1.0).
     */
    private static void fillEnemyClosestApproach(ProjectileRelationDto rel, ProjectileDto p, PlayerDto[] enemies) {
        if (enemies == null) return;
        double vx = p.velocity.x, vy = p.velocity.y, vz = p.velocity.z;
        double vmag2 = vx * vx + vy * vy + vz * vz;
        double bestDist = Double.POSITIVE_INFINITY;
        double bestTime = 0.0;
        for (PlayerDto e : enemies) {
            if (e == null || e.location == null) continue;
            double dx = p.location.x - e.location.x;
            double dy = p.location.y - e.location.y;
            double dz = p.location.z - e.location.z;
            double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double tClosest = 0.0;
            double closestDist = dist3D;
            if (vmag2 > 1.0) {
                double pdotv = dx * vx + dy * vy + dz * vz;
                double t = -pdotv / vmag2;
                if (t > 0) { // projectiel nadert de enemy nog; closest ligt in de toekomst
                    double cx = dx + vx * t;
                    double cy = dy + vy * t;
                    double cz = dz + vz * t;
                    closestDist = Math.sqrt(cx * cx + cy * cy + cz * cz);
                    tClosest = t;
                }
                // t <= 0: projectiel beweegt al weg → "nu" (dist3D, t=0) is het dichtst
            }
            if (closestDist < bestDist) {
                bestDist = closestDist;
                bestTime = tClosest;
            }
        }
        if (Double.isFinite(bestDist)) {
            rel.enemyClosestApproach_norm = NormalizationUtils.normalizeDistance3D(bestDist);
            rel.enemyTimeToClosest_norm = Math.min(1.0, bestTime / TIME_NORM_SECONDS);
        }
    }

    /**
     * Zet de relevante one-hot flag op basis van de projectile-classnaam. Botpack-
     * classes komen via {@link aiplay.play.WeaponClassNameTable} terug als
     * "Botpack.&lt;Class&gt;" of een lege string wanneer de hash niet bekend is.
     * Onbekende classes laten alle flags op 0.
     *
     * <p>Class-names matchen de stock UT99 Botpack package:
     * <ul>
     *   <li>flakslug / UTFlakShell — flak alt-fire grenade</li>
     *   <li>UTChunk1..4 — flak primary chunks</li>
     *   <li>ShockProj — shock-rifle secondary blue ball</li>
     *   <li>RocketMk2 — rocket-launcher primary rocket</li>
     *   <li>Grenade — rocket-launcher alt-fire grenade</li>
     *   <li>UT_BioGel — bio-rifle primary spray glob (low damage)</li>
     *   <li>BioGlob — bio-rifle charged alt-fire glob (extends UT_BioGel, dmg ~75 × DrawScale)</li>
     *   <li>PlasmaSphere — pulse-gun primary plasma chunk</li>
     *   <li>Razor2 — ripper sawblade (primary én secondary, alleen explosion-flag verschilt)</li>
     *   <li>WarShell — redeemer warhead missile</li>
     *   <li>TranslocatorTarget — gegooide disc (technically Decoration, niet Projectile;
     *       wordt via separate UC-pad als pseudo-projectile gepubliceerd indien gewenst)</li>
     * </ul>
     */
    static void classifyProjectileType(ProjectileRelationDto rel, String cls) {
        if (cls == null) return;
        if ("Botpack.flakslug".equalsIgnoreCase(cls) || "Botpack.UTFlakShell".equalsIgnoreCase(cls)) {
            rel.isGrenade = 1.0f;
        } else if (cls.startsWith("Botpack.UTChunk")) {
            rel.isChunk = 1.0f;
        } else if ("Botpack.ShockProj".equalsIgnoreCase(cls)) {
            rel.isShockBall = 1.0f;
        } else if ("Botpack.RocketMk2".equalsIgnoreCase(cls)) {
            rel.isRocket = 1.0f;
        } else if ("Botpack.Grenade".equalsIgnoreCase(cls)) {
            rel.isRocketGrenade = 1.0f;
        } else if ("Botpack.UT_BioGel".equalsIgnoreCase(cls)) {
            rel.isBioBlob = 1.0f;
        } else if ("Botpack.BioGlob".equalsIgnoreCase(cls)) {
            rel.isBioGlob = 1.0f;
        } else if ("Botpack.PlasmaSphere".equalsIgnoreCase(cls)) {
            rel.isPulsePlasma = 1.0f;
        } else if ("Botpack.Razor2".equalsIgnoreCase(cls)) {
            rel.isRazor = 1.0f;
        } else if ("Botpack.WarShell".equalsIgnoreCase(cls)) {
            rel.isRedeemerMissile = 1.0f;
        } else if ("Botpack.TranslocatorTarget".equalsIgnoreCase(cls)) {
            rel.isTranslocatorDisc = 1.0f;
        }
    }

    private record Ranked(ProjectileRelationDto rel, double threatScore, double distFromBot) {}
}
