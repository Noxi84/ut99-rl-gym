package aiplay.mission;

import aiplay.shared.mission.MissionIntent;

/**
 * Decides the active mission based on world facts.
 *
 * Contract: stateful (tracks dwell). Must be called sequentially per frame in timestamp order.
 * Output: MissionIntent (immutable, same contract as before).
 */
public interface MissionPolicy {

    MissionIntent evaluate(WorldFacts facts);
}
