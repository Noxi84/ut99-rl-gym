package aiplay.scanners.feature.resolver.projectile;

import aiplay.dto.GameStateDto;
import aiplay.dto.ProjectileRelationDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves features voor per-owner projectile slot IDs:
 * <ul>
 *   <li>{@code self_projectile{M}_{suffix}}      → {@code enrichments.selfProjectileRels[M]}</li>
 *   <li>{@code enemy{N}_projectile{M}_{suffix}}  → {@code enrichments.enemyProjectileRels[N][M]}</li>
 *   <li>{@code teammate{N}_projectile{M}_{suffix}} → {@code enrichments.teammateProjectileRels[N][M]}</li>
 * </ul>
 */
public class ProjectileSlotFeatureValueResolver implements TrainingFeatureValueResolver {

    private enum Kind { SELF, ENEMY, TEAMMATE }

    private record ParsedSlot(Kind kind, int ownerSlot, int projSlot, String suffix) {}

    private static final Map<String, ParsedSlot> SLOT_CACHE;

    static {
        int totalOwners = 1 + ProjectileSlotFeatureComponent.MAX_ENEMY_OWNERS
            + ProjectileSlotFeatureComponent.MAX_TEAMMATE_OWNERS;
        Map<String, ParsedSlot> cache = new HashMap<>(
            totalOwners * ProjectileSlotFeatureComponent.MAX_PROJ_PER_OWNER * ProjectileSlotFeatureComponent.FEATURE_SUFFIXES.length);

        for (int m = 0; m < ProjectileSlotFeatureComponent.MAX_PROJ_PER_OWNER; m++) {
            String prefix = "self_projectile" + m + "_";
            for (String s : ProjectileSlotFeatureComponent.FEATURE_SUFFIXES) {
                cache.put(prefix + s, new ParsedSlot(Kind.SELF, 0, m, s));
            }
        }
        for (int n = 0; n < ProjectileSlotFeatureComponent.MAX_ENEMY_OWNERS; n++) {
            for (int m = 0; m < ProjectileSlotFeatureComponent.MAX_PROJ_PER_OWNER; m++) {
                String prefix = "enemy" + n + "_projectile" + m + "_";
                for (String s : ProjectileSlotFeatureComponent.FEATURE_SUFFIXES) {
                    cache.put(prefix + s, new ParsedSlot(Kind.ENEMY, n, m, s));
                }
            }
        }
        for (int n = 0; n < ProjectileSlotFeatureComponent.MAX_TEAMMATE_OWNERS; n++) {
            for (int m = 0; m < ProjectileSlotFeatureComponent.MAX_PROJ_PER_OWNER; m++) {
                String prefix = "teammate" + n + "_projectile" + m + "_";
                for (String s : ProjectileSlotFeatureComponent.FEATURE_SUFFIXES) {
                    cache.put(prefix + s, new ParsedSlot(Kind.TEAMMATE, n, m, s));
                }
            }
        }
        SLOT_CACHE = Map.copyOf(cache);
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        ParsedSlot parsed = SLOT_CACHE.get(featureId);
        if (parsed == null) return null;

        ProjectileRelationDto rel = getSlot(f, parsed);
        if (rel == null) {
            // Lege slot: de meeste features 0.0, maar de enemy-relatie-defaults zijn 1.0 — 0.0 zou
            // "projectiel raakt enemy exact" betekenen (sterkste combo-signaal), niet "geen projectiel".
            return switch (parsed.suffix) {
                case "enemyClosestApproach_norm", "enemyTimeToClosest_norm" -> 1.0f;
                default -> 0.0f;
            };
        }

        return switch (parsed.suffix) {
            case "present" -> rel.present;
            case "relSin" -> (float) rel.relSin;
            case "relCos" -> (float) rel.relCos;
            case "distance_norm" -> (float) rel.distance_norm;
            case "pitchBearing_norm" -> (float) rel.pitchBearing_norm;
            case "forwardVelocity_norm" -> (float) rel.forwardVelocity_norm;
            case "rightVelocity_norm" -> (float) rel.rightVelocity_norm;
            case "speed_norm" -> (float) rel.speed_norm;
            case "timeToImpact_norm" -> (float) rel.timeToImpact_norm;
            case "isGrenade" -> rel.isGrenade;
            case "isChunk" -> rel.isChunk;
            case "isShockBall" -> rel.isShockBall;
            case "isRocket" -> rel.isRocket;
            case "isRocketGrenade" -> rel.isRocketGrenade;
            case "isBioBlob" -> rel.isBioBlob;
            case "isBioGlob" -> rel.isBioGlob;
            case "isPulsePlasma" -> rel.isPulsePlasma;
            case "isRazor" -> rel.isRazor;
            case "isRedeemerMissile" -> rel.isRedeemerMissile;
            case "isTranslocatorDisc" -> rel.isTranslocatorDisc;
            case "damage_norm" -> (float) rel.damage_norm;
            case "chargeScale_norm" -> (float) rel.chargeScale_norm;
            case "enemyClosestApproach_norm" -> (float) rel.enemyClosestApproach_norm;
            case "enemyTimeToClosest_norm" -> (float) rel.enemyTimeToClosest_norm;
            default -> 0.0f;
        };
    }

    private static ProjectileRelationDto getSlot(GameStateDto f, ParsedSlot parsed) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        var en = f.playerPawn.enrichments;
        return switch (parsed.kind) {
            case SELF -> {
                ProjectileRelationDto[] rels = en.selfProjectileRels;
                yield (rels == null || parsed.projSlot >= rels.length) ? null : rels[parsed.projSlot];
            }
            case ENEMY -> {
                ProjectileRelationDto[][] rels = en.enemyProjectileRels;
                if (rels == null || parsed.ownerSlot >= rels.length) yield null;
                ProjectileRelationDto[] inner = rels[parsed.ownerSlot];
                yield (inner == null || parsed.projSlot >= inner.length) ? null : inner[parsed.projSlot];
            }
            case TEAMMATE -> {
                ProjectileRelationDto[][] rels = en.teammateProjectileRels;
                if (rels == null || parsed.ownerSlot >= rels.length) yield null;
                ProjectileRelationDto[] inner = rels[parsed.ownerSlot];
                yield (inner == null || parsed.projSlot >= inner.length) ? null : inner[parsed.projSlot];
            }
        };
    }
}
