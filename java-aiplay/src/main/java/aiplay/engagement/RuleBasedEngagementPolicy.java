package aiplay.engagement;

import aiplay.config.global.EngagementConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.mission.WorldFacts;
import aiplay.shared.engagement.AttentionTargetType;
import aiplay.shared.engagement.EngagementIntent;
import aiplay.shared.engagement.EngagementReason;
import aiplay.shared.engagement.EngagementType;
import aiplay.shared.mission.MissionIntent;

/**
 * Rule-based V1 engagement policy.
 *
 * Determines tactical posture (engagement) and attention target based on
 * enemy visibility, distance, flag state, and current mission context.
 *
 * Implements hysteresis via:
 * - engagement min dwell: prevents rapid engagement type flips
 * - attention target min dwell: prevents rapid target flips
 * - commit shot hold: once committed, holds for a minimum duration
 * - visible grace: after enemy lost, maintains engagement briefly
 *
 * Stateful: tracks current engagement, last change timestamps, and
 * last-seen-visible timestamp for grace period.
 */
public class RuleBasedEngagementPolicy implements EngagementPolicy {

    private EngagementIntent current;
    private long lastEngagementChangeMs;
    private long lastAttentionChangeMs;
    private long lastEnemyVisibleMs;

    private final int engagementMinDwellMs;
    private final int attentionTargetMinDwellMs;
    private final int commitShotHoldMs;
    private final int visibleGraceMs;
    private final double commitShotDistanceThreshold;

    public RuleBasedEngagementPolicy() {
        EngagementConfig cfg = GlobalConfigRepository.shared().engagement();
        this.engagementMinDwellMs = cfg.engagementMinDwellMs();
        this.attentionTargetMinDwellMs = cfg.attentionTargetMinDwellMs();
        this.commitShotHoldMs = cfg.commitShotHoldMs();
        this.visibleGraceMs = cfg.visibleGraceMs();
        this.commitShotDistanceThreshold = cfg.commitShotDistanceThreshold();

        this.current = new EngagementIntent(
            EngagementType.IGNORE_ENEMY,
            AttentionTargetType.NONE,
            EngagementReason.NO_ENEMY,
            0L);
        this.lastEngagementChangeMs = 0;
        this.lastAttentionChangeMs = 0;
        this.lastEnemyVisibleMs = Long.MIN_VALUE;
    }

    @Override
    public EngagementIntent evaluate(WorldFacts facts, MissionIntent mission) {
        long ts = facts.frameTimestampMs();

        // Track last time enemy was visible (for grace period)
        if (facts.enemyVisible()) {
            lastEnemyVisibleMs = ts;
        }

        boolean withinVisibleGrace = lastEnemyVisibleMs >= 0 && (ts - lastEnemyVisibleMs) < visibleGraceMs;

        // Determine proposed engagement and attention
        EngagementType proposedEngagement;
        AttentionTargetType proposedAttention;
        EngagementReason proposedReason;

        // Role-aware attention prior:
        //   Defend → carrier if there is one, else the enemy nearest to our home flag
        //            (pre-aim aan op de meest waarschijnlijke aanvaller). Markeert het verschil
        //            tussen Attack/Cover (closest enemy) en Defend in zowel feature target_index
        //            als reward attention.
        //   Other  → closest enemy (ENEMY_PLAYER). ViewTargeting valt terug op objective wanneer
        //            er geen enemy is (resolveAttentionTarget returns null → objective resolves).
        proposedAttention = pickAttentionPrior(facts);

        if (!facts.enemyPresent()) {
            // No enemy at all
            proposedEngagement = EngagementType.IGNORE_ENEMY;
            proposedReason = EngagementReason.NO_ENEMY;
        } else if (facts.hasFlag() && facts.enemyVisible() && facts.enemyNearby()) {
            // Bot carrying flag, enemy close — disengage
            proposedEngagement = EngagementType.BREAK_CONTACT;
            proposedReason = EngagementReason.FLAG_CARRIER_EVASION;
        } else if (facts.carrierIsPlayer1() && facts.enemyVisible()) {
            // Closest enemy IS the carrier and visible — direct pressure
            proposedEngagement = EngagementType.PRESSURE_CARRIER;
            proposedReason = EngagementReason.ENEMY_CARRIER_VISIBLE;
        } else if (facts.enemyTeamHasOurFlag() && facts.enemyVisible()) {
            // Enemy team has our flag but carrier is NOT player1 — track visible enemy
            proposedEngagement = EngagementType.TRACK_ENEMY;
            proposedReason = EngagementReason.ENEMY_VISIBLE;
        } else if (facts.enemyVisible() && facts.enemyDistanceNorm() < commitShotDistanceThreshold) {
            // Enemy visible and close — commit to shot
            proposedEngagement = EngagementType.COMMIT_SHOT;
            proposedReason = EngagementReason.ENEMY_CLOSE_VISIBLE;
        } else if (facts.enemyVisible() || withinVisibleGrace) {
            // Enemy visible or just lost — track
            proposedEngagement = EngagementType.TRACK_ENEMY;
            proposedReason = EngagementReason.ENEMY_VISIBLE;
        } else if (facts.enemyFiring()) {
            // Enemy is firing (possibly at us) — track the threat
            proposedEngagement = EngagementType.TRACK_ENEMY;
            proposedReason = EngagementReason.ENEMY_FIRING;
        } else if (facts.enemyFacingUs() && facts.enemyNearby()) {
            // Enemy looking at us and close — they can see/attack us
            proposedEngagement = EngagementType.TRACK_ENEMY;
            proposedReason = EngagementReason.ENEMY_FACING_US;
        } else if (facts.enemyNearby()) {
            // Enemy close but not visible/facing — still worth tracking
            proposedEngagement = EngagementType.TRACK_ENEMY;
            proposedReason = EngagementReason.ENEMY_NEARBY;
        } else {
            // Enemy present but far away and no threat signals
            proposedEngagement = EngagementType.IGNORE_ENEMY;
            proposedReason = EngagementReason.NO_ENEMY;
        }

        // Apply hysteresis: commit shot hold
        if (current.engagementType == EngagementType.COMMIT_SHOT
                && proposedEngagement != EngagementType.COMMIT_SHOT
                && (ts - lastEngagementChangeMs) < commitShotHoldMs
                && facts.enemyPresent()) {
            // Hold commit shot until hold period expires (unless enemy gone entirely)
            return current;
        }

        // Apply engagement dwell
        boolean engagementChanged = proposedEngagement != current.engagementType;
        if (engagementChanged && (ts - lastEngagementChangeMs) < engagementMinDwellMs) {
            // Check for immediate overrides
            boolean immediate = proposedReason == EngagementReason.FLAG_CARRIER_EVASION
                    || proposedReason == EngagementReason.ENEMY_CARRIER_VISIBLE
                    || proposedReason == EngagementReason.NO_ENEMY;
            // Stale PRESSURE_CARRIER: carrier is no longer player1, don't hold dwell
            if (current.engagementType == EngagementType.PRESSURE_CARRIER && !facts.carrierIsPlayer1()) {
                immediate = true;
            }
            if (!immediate) {
                return current;
            }
        }

        // Apply attention target dwell
        boolean attentionChanged = proposedAttention != current.attentionTarget;
        if (!engagementChanged && attentionChanged
                && (ts - lastAttentionChangeMs) < attentionTargetMinDwellMs) {
            // Keep current attention target, update engagement if needed
            proposedAttention = current.attentionTarget;
            attentionChanged = false;
        }

        // Build new intent if anything changed
        if (engagementChanged || attentionChanged || proposedReason != current.reason) {
            EngagementIntent next = new EngagementIntent(
                proposedEngagement, proposedAttention, proposedReason, ts);

            if (engagementChanged) {
                lastEngagementChangeMs = ts;
            }
            if (attentionChanged) {
                lastAttentionChangeMs = ts;
            }

            current = next;
        }

        return current;
    }

    /**
     * Attention prior, evaluated in priority order so the bot always watches what is relevant to
     * its own situation rather than a role assumption that ignores its position:
     * <ol>
     *   <li><b>Carrier survival</b> (role-blind): while the bot holds the flag it returns
     *       {@code ENEMY_THREAT_TO_SELF} — watch the attacker, not the EFC, to stay alive until a
     *       teammate returns our flag.</li>
     *   <li><b>Recovering our flag</b> (role-blind, enemy team holds our flag): look at the EFC we
     *       are running down ({@code ENEMY_CARRIER}). Survival still wins inside this branch — if
     *       the closest enemy is on us and engaging it returns {@code ENEMY_THREAT_TO_SELF}. This
     *       stops a Cover-recoverer near the enemy base from locking its view onto the enemy beside
     *       our flag-carrying Attacker on the far side of the map, and bypasses an Attacker's stale
     *       hard-pin; aim then matches the INTERCEPT/counter-grab navTarget (both head enemy-ward).</li>
     *   <li><b>Role prior</b> (no flag in play): Defend → enemy nearest our home flag (pre-aim at
     *       the likely incoming attacker); Cover → enemy nearest our Attack teammate (track the
     *       threat the attacker is about to engage; resolver yields null with no living attacker);
     *       Attack/DeathMatch → {@code ENEMY_PLAYER}, sticky-pin enforced in {@code AimTargetSelector}
     *       (Attack pins until dead, DeathMatch uses distance-hysteresis).</li>
     * </ol>
     *
     * <p>ThreadLocal role lookup is tolerant of an unset identity (offline annotator threads might
     * not have one) — falls back to ENEMY_PLAYER in that case.
     */
    private static AttentionTargetType pickAttentionPrior(WorldFacts facts) {
        // 1. Carrier survival (role-blind): we hold the flag → watch our attacker (see
        //    FLAG_CARRIER_EVASION engagement below), never the EFC we happen to share space with.
        if (facts.hasFlag()) {
            return AttentionTargetType.ENEMY_THREAT_TO_SELF;
        }
        // 2. Enemy team holds our flag → we are recovering (role-blind). The EFC, not a role prior,
        //    is the relevant target — this fixes the recoverer near the enemy base whose role prior
        //    (Cover's nearest-to-attacker / a stale Attack hard-pin) aimed it back at a far enemy by
        //    our flag-carrying teammate. Acute self-defense still comes first: if the closest enemy
        //    is on us and engaging, watch it (it may be the EFC itself) instead of a distant carrier.
        if (facts.enemyTeamHasOurFlag()) {
            if (facts.enemyVisible() && facts.enemyNearby()
                    && (facts.enemyFiring() || facts.enemyFacingUs())) {
                return AttentionTargetType.ENEMY_THREAT_TO_SELF;
            }
            return AttentionTargetType.ENEMY_CARRIER;
        }
        // 3. Role-based prior, no flag in play (normal offense/defense).
        String role = null;
        try {
            role = PlayerIdentityContext.effectiveRole();
        } catch (IllegalStateException ignore) {
            // ThreadLocal not set (offline path). Fall back to default ENEMY_PLAYER.
        }
        if ("Defend".equals(role)) {
            return AttentionTargetType.ENEMY_NEAREST_TO_HOME_FLAG;
        }
        if ("Cover".equals(role)) {
            // ENEMY_NEAREST_TO_ATTACKER resolves to null if no living Attack-teammate exists;
            // ViewTargeting + AimTargetSelector handle that gracefully (null target → fall
            // through to objective via resolveHeadingTarget). Setting the type unconditionally
            // is fine — the actual enemy choice happens in the resolver.
            return AttentionTargetType.ENEMY_NEAREST_TO_ATTACKER;
        }
        return AttentionTargetType.ENEMY_PLAYER;
    }
}
