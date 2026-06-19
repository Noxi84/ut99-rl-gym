package aiplay.mission;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.shared.mission.MissionIntent;
import aiplay.shared.mission.MissionReason;
import aiplay.shared.mission.MissionType;

/**
 * Rule-based V1 mission policy.
 * priority order stuck > dual-flag intercept > hasFlag > intercept > default capture, with dwell protection.
 *
 * Stateful: tracks current mission and last change timestamp for dwell.
 */
public class RuleBasedMissionPolicy implements MissionPolicy {

    private MissionIntent current;
    private long lastChangeMs;
    private final int dwellMs;

    public RuleBasedMissionPolicy() {
        this.dwellMs = GlobalConfigRepository.shared().mission().missionMinDwellMs();
        this.current = new MissionIntent(MissionType.CAPTURE_FLAG, reasonFor(MissionType.CAPTURE_FLAG), 0L);
        this.lastChangeMs = 0;
    }

    private static boolean isDefendRole() {
        try {
            return "Defend".equals(PlayerIdentityContext.effectiveRole());
        } catch (IllegalStateException ignore) {
            return false;
        }
    }

    private static MissionReason reasonFor(MissionType type) {
        return switch (type) {
            case RETURN_HOME -> MissionReason.HAS_FLAG;
            case STUCK_RECOVER -> MissionReason.STUCK_DETECTED;
            case INTERCEPT_CARRIER -> MissionReason.ENEMY_HAS_FLAG;
            default -> MissionReason.DEFAULT_CAPTURE;
        };
    }

    @Override
    public MissionIntent evaluate(WorldFacts facts) {
        MissionType proposed;
        MissionReason reason;

        // Priority: stuck > dual-flag standoff > hasFlag > enemyTeamHasOurFlag > default capture
        if (facts.isStuck()) {
            proposed = MissionType.STUCK_RECOVER;
            reason = MissionReason.STUCK_DETECTED;
        } else if (facts.hasFlag() && facts.enemyTeamHasOurFlag()) {
            // Dual-flag standoff: can't capture until our flag returns.
            // Actively intercept the carrier instead of waiting at home.
            proposed = MissionType.INTERCEPT_CARRIER;
            reason = MissionReason.ENEMY_HAS_FLAG;
        } else if (facts.hasFlag()) {
            proposed = MissionType.RETURN_HOME;
            reason = MissionReason.HAS_FLAG;
        } else if (facts.isCounterGrabber()) {
            // Counter-grab: enemy carries our flag, we don't hold theirs, and this bot is closest
            // to the enemy flag. Grab it (defensive grab) instead of joining the EFC chase — capture
            // is blocked until our flag returns, but holding their flag blocks THEIR capture too →
            // flag standoff rather than a free enemy capture. Teammates intercept the EFC.
            proposed = MissionType.CAPTURE_FLAG;
            reason = MissionReason.DEFAULT_CAPTURE;
        } else if (facts.enemyTeamHasOurFlag()
                && (facts.ownTeamHasEnemyFlag() || facts.enemyVisible() || facts.enemyNearby() || isDefendRole())) {
            // Recover our flag when:
            //  - a teammate already carries the enemy flag (capture is impossible until ours returns), OR
            //  - the enemy carrier is perceived (visible / nearby) and worth chasing now, OR
            //  - the bot is the Defender (always commits to recovery, regardless of perception).
            proposed = MissionType.INTERCEPT_CARRIER;
            reason = MissionReason.ENEMY_HAS_FLAG;
        } else if (isDefendRole()) {
            // Defender default — stay anchored at home base. RETURN_HOME is reused as
            // the "guard objective" label so MissionIntent semantics + navTarget align
            // (resolveMovementPrimaryObjective routes Defend-role idle bots to own base).
            proposed = MissionType.RETURN_HOME;
            reason = MissionReason.DEFAULT_CAPTURE;
        } else {
            proposed = MissionType.CAPTURE_FLAG;
            reason = MissionReason.DEFAULT_CAPTURE;
        }

        if (proposed != current.missionType) {
            boolean immediate = reason == MissionReason.HAS_FLAG
                    || reason == MissionReason.STUCK_DETECTED
                    || reason == MissionReason.ENEMY_HAS_FLAG;
            if (immediate || facts.frameTimestampMs() - lastChangeMs >= dwellMs) {
                current = new MissionIntent(proposed, reason, facts.frameTimestampMs());
                lastChangeMs = facts.frameTimestampMs();
            }
        }

        return current;
    }
}
