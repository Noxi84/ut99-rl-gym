package aiplay.config.global;

import java.util.List;

public record DebugConfig(
    List<String> features,
    boolean sanityEnabled,
    int logEveryN,
    long logMinIntervalMs,
    boolean logOnlyOnChange,
    double logChangeEpsilon,
    int logMaxLinesPerFeature
) {}
