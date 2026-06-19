package aiplay.config.global;

/**
 * Configuration for cross-bot ONNX inference batching.
 *
 * When enabled, each bot's predict() call is handed to a per-(model,device)
 * dispatcher that coalesces concurrent submissions from all bots in the JVM
 * into a single [N][T][F] session.run() call. The dispatcher uses take+drainTo
 * coalescing — no artificial sleep — so batches form naturally during the time
 * the previous batch is being processed.
 *
 * All fields are required (no defaults).
 */
public record InferenceBatchingConfig(
    boolean enabled,
    int maxBatchSize,
    int submitTimeoutMs
) {}
