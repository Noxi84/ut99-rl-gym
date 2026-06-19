package aiplay.engagement;

import aiplay.mission.WorldFacts;
import aiplay.shared.engagement.EngagementIntent;
import aiplay.shared.mission.MissionIntent;

/**
 * Decides the tactical engagement posture and attention target based on
 * world facts and the current mission context.
 *
 * Orthogonal to mission: engagement determines how the bot relates to
 * enemies, not where it navigates.
 *
 * Contract: stateful (tracks dwell/hysteresis). Must be called sequentially
 * per frame in timestamp order.
 */
public interface EngagementPolicy {

    EngagementIntent evaluate(WorldFacts facts, MissionIntent mission);
}
