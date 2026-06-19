package aiplay.shared.engagement;

/**
 * Tactical posture toward enemies. Orthogonal to mission/skill/route:
 * mission determines where the bot goes, engagement determines how the bot
 * relates to enemies along the way.
 */
public enum EngagementType {
    /** Objective-first, enemy gets no direct attention. */
    IGNORE_ENEMY,
    /** Temporarily track/monitor a visible enemy, navigation stays objective-first. */
    TRACK_ENEMY,
    /** Commit to firing — aim and shoot get priority while opportunity lasts. */
    COMMIT_SHOT,
    /** Disengage from combat — bot consciously avoids enemy. */
    BREAK_CONTACT,
    /** Tactical focus on enemy flag carrier without necessarily flipping the whole mission. */
    PRESSURE_CARRIER
}
