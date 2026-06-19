package aiplay.config.global;

public record LoggingConfig(
    boolean enabled,
    String level,
    int maxBytes,
    int maxFiles
) {}
