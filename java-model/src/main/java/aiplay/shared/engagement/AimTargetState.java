package aiplay.shared.engagement;

/**
 * Sticky-state voor {@link AimTargetSelector}: onthoudt het gecommitteerde aim-target en de
 * hysterese-timers over opeenvolgende frames. Caller-owned — de aanroeper bepaalt de levensduur:
 * een verse instantie per offline batch (deterministisch per shard) of per-sessie persistent voor
 * live incremental verwerking. De velden zijn package-private: alleen {@link AimTargetSelector}
 * (zelfde package) leest en muteert ze; de adapter maakt enkel instanties aan en geeft ze door.
 */
public final class AimTargetState {
    String currentName = null;
    long committedAtTs = Long.MIN_VALUE;
    long lastSeenVisibleTs = Long.MIN_VALUE;
}
