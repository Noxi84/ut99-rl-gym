package aiplay.scanners.feature.resolver.navigation;

import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.shared.objective.CarrierObjectiveResolver;
import aiplay.shared.objective.CounterGrabResolver;
import aiplay.shared.objective.EscortObjectiveResolver;
import aiplay.util.NormalizationUtils;

/**
 * Computes egocentric bearing (sin/cos) toward the current mission objective.
 *
 * Target resolution mirrors RewardUtils.resolveMovementPrimaryObjective():
 *   0. Bot CARRIES the enemy flag → CarrierObjectiveResolver: home base (preserve + stage capture),
 *      or a nearby DROPPED own flag when returning it is a cheap on-route detour
 *   1. Own flag dropped → own flag location (recover it) — non-carriers
 *   2. Own flag CARRIED by enemy → counter-grab split (CounterGrabResolver): closest bot grabs the
 *      enemy flag (defensive grab → standoff), the rest intercept the EFC at a cut-off point
 *   3. Role default (Defender → own base)
 *   4. Else → enemy flag location (attack)
 *
 * By providing a single "follow this direction" signal, the model no longer
 * needs to learn the conditional mission→bearing mapping from separate features.
 */
public class NavigationTargetFeatureValueResolver implements TrainingFeatureValueResolver {

    private static final double DIST_TAU = 600.0;

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto frame) {
        if (frame == null || frame.playerPawn == null) return 0.0f;

        PlayerDto pawn = frame.playerPawn;
        if (pawn.location == null || pawn.viewRotation == null) return 0.0f;

        ResolvedTarget resolved = resolveTarget(frame);
        CoordinatesDto target = resolved.target();
        if (target == null) return 0.0f;

        int viewX = pawn.viewRotation.x & 0xFFFF;

        double[] sc = NormalizationUtils.relativeAngleSinCos(
                viewX, pawn.location.x, pawn.location.y, target.x, target.y);
        double sin = sc[0];
        double cos = sc[1];

        double dist2D = Math.hypot(target.x - pawn.location.x, target.y - pawn.location.y);
        // Carrier staging-zone: binnen de zone-radius rond de eigen base is er geen richting-doel (vrij
        // manoeuvreren wanneer de capture geblokkeerd is en een enemy over midfield staat). Effectieve
        // afstand max(0, dist−R) → stabilizeSinCosNear squasht de navTarget-bearing naar neutraal (0,1)
        // binnen de zone, terwijl hij buiten de zone naar de zone-rand (= richting base) wijst.
        // stageZoneRadiusUu is 0 wanneer de zone niet actief → ongewijzigd gedrag. Houdt de feature
        // synchroon met de objective_progress-floor (gedeelde CarrierObjectiveResolver — dual source).
        double effDist = dist2D;
        if (pawn.hasFlag) {
            double stageR = CarrierObjectiveResolver.stageZoneRadiusUu(frame);
            if (stageR > 0.0) {
                effDist = Math.max(0.0, dist2D - stageR);
            }
        } else if (resolved.teammateCarrierEscort()) {
            // Escort-standoff + capture-funnel-release (2026-06-06): het objective is de teammate-
            // carrier (priority 6 met CARRIED enemy-vlag). Funnel actief (carrier kan scoren en zit
            // in de last-mile bij base) → effDist 0: bearing volledig neutraal, geen feature-richting
            // de capture-funnel in. Anders effDist = max(0, dist − standoff): de bearing wijst naar
            // de band-rand rond de carrier en squasht binnen de band naar neutraal — zelfde patroon
            // als de staging-zone hierboven. Houdt de feature synchroon met de objective_progress-
            // floor en cover_escort (gedeelde EscortObjectiveResolver — dual source).
            if (EscortObjectiveResolver.isCaptureFunnelActive(frame)) {
                effDist = 0.0;
            } else {
                effDist = Math.max(0.0, dist2D - EscortObjectiveResolver.escortStandoffUu(frame));
            }
        }
        double soft2D = NormalizationUtils.softDistance01(effDist, DIST_TAU);
        double[] scStab = NormalizationUtils.stabilizeSinCosNear(sin, cos, soft2D, true);
        sin = scStab[0];
        cos = scStab[1];

        return switch (featureId) {
            case "navTarget_relSin" -> (float) sin;
            case "navTarget_relCos" -> (float) cos;
            default -> 0.0f;
        };
    }

    /**
     * Navigation target + de vlag of dat target de teammate-carrier is (priority 6 met CARRIED
     * enemy-vlag), zodat de caller de escort-standoff/funnel-squash alleen in exact die branch
     * toepast — de priority-kennis blijft hier, niet bij de afstands-logica.
     */
    private record ResolvedTarget(CoordinatesDto target, boolean teammateCarrierEscort) {
        static ResolvedTarget plain(CoordinatesDto target) {
            return new ResolvedTarget(target, false);
        }
    }

    /**
     * Resolve the navigation target using the same priority logic as
     * RewardUtils.resolveMovementPrimaryObjective().
     */
    private static ResolvedTarget resolveTarget(GameStateDto state) {
        PlayerDto pawn = state.playerPawn;
        int team = pawn.team;

        FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
        FlagDto enemyFlag = (team == 0) ? state.blueFlag : state.redFlag;

        // Priority 0 (carrier-first, 2026-05-29 user-fix): bot draagt de enemy-vlag → naar huis
        // (preserve + stage capture), nooit een bewegende EFC achterna. Verfijning 2026-05-31: ligt
        // onze EIGEN vlag DROPPED ongeveer op de route, dan haalt de carrier hem zelf op (kleine
        // detour) zodat de capture meteen unblocked is. Gedeelde CarrierObjectiveResolver houdt dit
        // byte-for-byte gelijk aan RewardUtils.resolveMovementPrimaryObjective (Objective dual source).
        if (pawn.hasFlag) {
            return ResolvedTarget.plain(CarrierObjectiveResolver.carrierObjective(state));
        }

        // Priority 1 & 2: own flag dropped — go recover it (mirrors RewardUtils
        // priority order so feature bearing + dense progress reward agree).
        if (ownFlag != null && ownFlag.status == FlagStatusDto.DROPPED
                && ownFlag.location != null && ownFlag.baseLocation != null
                && enemyFlag != null && enemyFlag.baseLocation != null) {
            boolean flagOnEnemyHalf = isOnEnemyHalf(ownFlag.location, ownFlag.baseLocation, enemyFlag.baseLocation);
            if (flagOnEnemyHalf) {
                if (isDefendRole()) {
                    return ResolvedTarget.plain(ownFlag.baseLocation);
                }
                return ResolvedTarget.plain(ownFlag.location);
            } else if (pawn.location != null
                    && !isOnEnemyHalf(pawn.location, ownFlag.baseLocation, enemyFlag.baseLocation)) {
                return ResolvedTarget.plain(ownFlag.location);
            }
        }

        // Priority 3: own flag CARRIED by enemy (NON-carriers only; de carrier is al via priority 0
        // naar huis). Counter-grab split (2026-05-31): de bot die het dichtst bij de enemy-vlag staat
        // pakt die (defensive grab → standoff); de overige bots onderscheppen de EFC op een cut-off
        // punt richting de enemy base i.p.v. de huidige EFC-positie achterna te lopen. Gedeelde
        // CounterGrabResolver houdt dit synchroon met RewardUtils + de mission policy.
        boolean ourFlagCarried = ownFlag != null && ownFlag.status == FlagStatusDto.CARRIED
                && ownFlag.location != null;
        if (ourFlagCarried) {
            return ResolvedTarget.plain(CounterGrabResolver.carriedFlagObjective(state));
        }

        // Priority 5: role-conditioned default — Defender stays anchored at own base
        // when no flag is in play. Keeps the navTarget bearing identical to the reward
        // path (RewardUtils.resolveMovementPrimaryObjective) so feature input + dense
        // progress reward are pulling on the same direction. Without this the model
        // sees "enemy flag" bearing for the Defender too, which is what produced the
        // observed offensive-Defender behaviour.
        if (isDefendRole() && ownFlag != null && ownFlag.baseLocation != null) {
            return ResolvedTarget.plain(ownFlag.baseLocation);
        }

        // Priority 6: Cover / Attack / DeathMatch → enemy flag location (live carrier
        // position when carried, else base location). Een CARRIED enemy-vlag is hier per
        // definitie een TEAMMATE-carrier (priority 0 ving de eigen carry al af) → markeer de
        // branch zodat de caller de escort-standoff/funnel-squash toepast (2026-06-06).
        if (enemyFlag != null && enemyFlag.location != null) {
            boolean teammateCarrier = enemyFlag.status == FlagStatusDto.CARRIED;
            return new ResolvedTarget(enemyFlag.location, teammateCarrier);
        }
        return ResolvedTarget.plain((enemyFlag != null) ? enemyFlag.baseLocation : null);
    }

    private static boolean isDefendRole() {
        try {
            return "Defend".equals(PlayerIdentityContext.effectiveRole());
        } catch (IllegalStateException ignore) {
            return false;
        }
    }

    private static boolean isOnEnemyHalf(CoordinatesDto pos,
                                         CoordinatesDto homeBase,
                                         CoordinatesDto enemyBase) {
        double axisX = enemyBase.x - homeBase.x;
        double axisY = enemyBase.y - homeBase.y;
        double midX = (homeBase.x + enemyBase.x) * 0.5;
        double midY = (homeBase.y + enemyBase.y) * 0.5;
        double dot = (pos.x - midX) * axisX + (pos.y - midY) * axisY;
        return dot > 0;
    }
}
