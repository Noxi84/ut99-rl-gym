package aiplay.tactical;

import aiplay.mission.WorldFacts;
import aiplay.shared.mission.MissionIntent;
import aiplay.shared.tactical.TacticalIntent;

/**
 * Decides active tactical spatial constraints based on world facts and
 * the current mission context.
 *
 * Orthogonal to mission/engagement: tactical constraints restrict allowed
 * movement execution without changing the objective or attention target.
 *
 * Contract: stateful (tracks dwell). Must be called sequentially per frame
 * in timestamp order.
 */
public interface TacticalPolicy {

    TacticalIntent evaluate(WorldFacts facts, MissionIntent mission);
}
