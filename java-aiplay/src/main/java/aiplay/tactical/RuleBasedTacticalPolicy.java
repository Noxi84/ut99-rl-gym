package aiplay.tactical;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.TacticalConfig;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.mission.WorldFacts;
import aiplay.shared.mission.MissionIntent;
import aiplay.shared.tactical.TacticalConstraintMode;
import aiplay.shared.tactical.TacticalIntent;
import aiplay.shared.tactical.TacticalReason;
import aiplay.shared.tactical.TacticalTerritoryBoundary;
import aiplay.shared.tactical.TacticalType;

/**
 * Rule-based V1 tactical constraint policy — carrier shadow variant.
 * <p>
 * Evaluates spatial constraints that restrict allowed movement execution without changing the bot's objective or attention target.
 * <p>
 * V2 rules: - CARRIER_SHADOW_DENY: when enemy team has our flag, bot does not carry the enemy flag, and carrier position is reliable → block homeward past the dynamic carrier line. - MIDFIELD_FALLBACK_DENY: same conditions but carrier position unknown → block homeward past midfield.
 * <p>
 * Unlike V1, the constraint is NOT limited to when the bot is on the enemy half. It remains active regardless of bot/carrier position on the field.
 * <p>
 * Stateful: tracks current tactical state, dwell protection, and last-known carrier position for grace period.
 */
public class RuleBasedTacticalPolicy implements TacticalPolicy {

  private TacticalIntent current;
  private long lastChangeMs;

  private final int tacticalMinDwellMs;
  private final int carrierGraceMs;

  // Grace tracking for carrier line
  private double lastKnownCarrierProgress = -1.0;
  private long lastCarrierSeenMs = 0;

  public RuleBasedTacticalPolicy() {
    TacticalConfig cfg = GlobalConfigRepository.shared().tactical();
    this.tacticalMinDwellMs = cfg.tacticalMinDwellMs();
    this.carrierGraceMs = cfg.carrierGraceMs();

    this.current = TacticalIntent.unconstrained(0L);
    this.lastChangeMs = 0;
  }

  @Override
  public TacticalIntent evaluate(WorldFacts facts, MissionIntent mission) {
    long frameTs = facts.frameTimestampMs();

    // Compute effective carrier line with grace period
    EffectiveCarrierLine effective = resolveEffectiveCarrierLine(facts);

    // Propose new tactical state
    TacticalType proposedType = proposeType(facts, effective.reliable);
    TacticalReason proposedReason = proposeReason(facts, proposedType);

    // Dwell protection: don't flip tactical state too rapidly
    if (proposedType != current.tacticalType) {
      long elapsed = frameTs - lastChangeMs;
      if (elapsed < tacticalMinDwellMs) {
        // Hold current state but update carrier line position (it moves continuously)
        if (current.constraintMode != TacticalConstraintMode.UNCONSTRAINED) {
          return new TacticalIntent(current.tacticalType, current.constraintMode,
              current.territoryBoundary, TacticalReason.DWELL_HOLD,
              frameTs, effective.progressNorm);
        }
        return current;
      }
    }

    // Build new intent if type changed
    if (proposedType != current.tacticalType) {
      TacticalConstraintMode mode = modeForType(proposedType);
      TacticalTerritoryBoundary boundary = boundaryForType(proposedType);
      double lineValue = carrierLineForType(proposedType, effective.progressNorm);
      current = new TacticalIntent(proposedType, mode, boundary, proposedReason, frameTs, lineValue);
      lastChangeMs = frameTs;
    } else if (proposedType != TacticalType.NONE) {
      // Same type but update carrier line position (it moves every frame)
      double lineValue = carrierLineForType(proposedType, effective.progressNorm);
      current = new TacticalIntent(current.tacticalType, current.constraintMode,
          current.territoryBoundary, current.reason, frameTs, lineValue);
    }

    return current;
  }

  /**
   * Decision tree for carrier shadow:
   * <p>
   * 1. Enemy team has our flag? No -> NONE 2. Bot carries enemy flag? Yes -> NONE (must be able to return home) 3. Role is Defend or Cover? Yes -> NONE (defenders patrol own half, escorts may fall back) 4. Carrier line reliable (with grace)? Yes -> CARRIER_SHADOW_DENY 5. Otherwise -> MIDFIELD_FALLBACK_DENY
   */
  private static TacticalType proposeType(WorldFacts facts, boolean carrierReliable) {
    if (!facts.enemyTeamHasOurFlag()) {
      return TacticalType.NONE;
    }
    if (facts.hasFlag()) {
      return TacticalType.NONE;
    }
    if (isHomeAlignedRole()) {
      return TacticalType.NONE;
    }
    if (carrierReliable) {
      return TacticalType.CARRIER_SHADOW_DENY;
    }
    return TacticalType.MIDFIELD_FALLBACK_DENY;
  }

  /**
   * Defend & Cover roles must be free to retreat homeward — Defender to intercept
   * the carrier at the flag base or scoop a dropped flag, Cover to fall back and
   * escort an inbound friendly carrier. Only Attack (and DeathMatch / unset roles)
   * stay under the midfield-fallback / carrier-shadow constraint that prevents
   * a flag-hunter from retreating to safety while the enemy holds our flag.
   */
  private static boolean isHomeAlignedRole() {
    String role = null;
    try {
      role = PlayerIdentityContext.effectiveRole();
    } catch (IllegalStateException ignore) {
      return false;
    }
    return "Defend".equals(role) || "Cover".equals(role);
  }

  private static TacticalReason proposeReason(WorldFacts facts, TacticalType type) {
    if (type == TacticalType.CARRIER_SHADOW_DENY) {
      return TacticalReason.CARRIER_SHADOW_ACTIVE;
    }
    if (type == TacticalType.MIDFIELD_FALLBACK_DENY) {
      return TacticalReason.MIDFIELD_FALLBACK_ACTIVE;
    }
    // Explain why constraint is not active
    if (facts.hasFlag()) {
      return TacticalReason.SELF_HAS_ENEMY_FLAG;
    }
    if (!facts.enemyTeamHasOurFlag()) {
      return TacticalReason.ENEMY_NO_LONGER_HAS_FLAG;
    }
    return TacticalReason.NO_CONSTRAINT;
  }

  private static TacticalConstraintMode modeForType(TacticalType type) {
    return switch (type) {
      case NONE -> TacticalConstraintMode.UNCONSTRAINED;
      case MIDFIELD_FALLBACK_DENY -> TacticalConstraintMode.BLOCK_REENTRY_TO_HOME_HALF;
      case CARRIER_SHADOW_DENY -> TacticalConstraintMode.BLOCK_REENTRY_PAST_CARRIER_LINE;
    };
  }

  private static TacticalTerritoryBoundary boundaryForType(TacticalType type) {
    return switch (type) {
      case NONE -> TacticalTerritoryBoundary.NONE;
      case MIDFIELD_FALLBACK_DENY -> TacticalTerritoryBoundary.MIDFIELD;
      case CARRIER_SHADOW_DENY -> TacticalTerritoryBoundary.CARRIER_LINE;
    };
  }

  private static double carrierLineForType(TacticalType type, double effectiveProgress) {
    return switch (type) {
      case NONE -> -1.0;
      case MIDFIELD_FALLBACK_DENY -> 0.5;
      case CARRIER_SHADOW_DENY -> effectiveProgress;
    };
  }

  // ---- Grace period for carrier line ----

  private record EffectiveCarrierLine(double progressNorm, boolean reliable) {

  }

  /**
   * Resolve the effective carrier line position with grace period. Uses raw carrier data from WorldFacts, falls back to last-known with grace, then to midfield (0.5) when all sources are stale.
   */
  private EffectiveCarrierLine resolveEffectiveCarrierLine(WorldFacts facts) {
    if (facts.carrierProgressNorm() >= 0) {
      // Fresh carrier data available
      lastKnownCarrierProgress = facts.carrierProgressNorm();
      lastCarrierSeenMs = facts.frameTimestampMs();
      return new EffectiveCarrierLine(facts.carrierProgressNorm(), true);
    }

    // No fresh data — try grace period on last-known
    if (lastKnownCarrierProgress >= 0
        && (facts.frameTimestampMs() - lastCarrierSeenMs) < carrierGraceMs) {
      return new EffectiveCarrierLine(lastKnownCarrierProgress, true);
    }

    // All stale — midfield fallback
    lastKnownCarrierProgress = -1.0;
    return new EffectiveCarrierLine(0.5, false);
  }
}
