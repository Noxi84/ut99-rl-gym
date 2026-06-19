package aiplay.runtime;

import aiplay.config.global.BotConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.instance.InstanceConfig;
import aiplay.play.udpstate.StateFrameSource;

/**
 * Immutable per-instance identity. Everything that identifies one bot.
 * When {@code botConfig} is present, this context represents one specific bot
 * in a multi-bot-per-server setup.
 */
public final class InstanceContext {

    private final String sessionId;
    private final InstanceConfig instanceConfig;
    private final BotConfig botConfig;
    private final int botIdx;
    private final StateFrameSource stateFrameSource;

    public InstanceContext(String sessionId, InstanceConfig instanceConfig) {
        this(sessionId, instanceConfig, null, 0, null);
    }

    public InstanceContext(String sessionId, InstanceConfig instanceConfig, BotConfig botConfig) {
        this(sessionId, instanceConfig, botConfig, 0, null);
    }

    public InstanceContext(String sessionId, InstanceConfig instanceConfig, BotConfig botConfig, int botIdx) {
        this(sessionId, instanceConfig, botConfig, botIdx, null);
    }

    public InstanceContext(String sessionId, InstanceConfig instanceConfig, BotConfig botConfig,
                           int botIdx, StateFrameSource stateFrameSource) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
        this.instanceConfig = instanceConfig;
        this.botConfig = botConfig;
        this.botIdx = botIdx;
        this.stateFrameSource = stateFrameSource;
    }

    public String getSessionId() {
        return sessionId;
    }

    public InstanceConfig getInstanceConfig() {
        return instanceConfig;
    }

    /** Returns the bot config if in multi-bot mode, null otherwise. */
    public BotConfig getBotConfig() {
        return botConfig;
    }

    /** Returns the effective bot name (from BotConfig if present, else from player config). */
    public String getEffectiveBotName() {
        return botConfig != null ? botConfig.name() : null;
    }

    /** Returns the effective team (from BotConfig if present, else -1). */
    public int getEffectiveTeam() {
        return botConfig != null ? botConfig.team() : -1;
    }

    /** Returns the effective role selector (from BotConfig if present, else player config). */
    public String getEffectiveRole() {
        return botConfig != null ? botConfig.role() : GlobalConfigRepository.shared().player().role();
    }

    /** Zero-based index of this bot within its instance (matches UScript RLBots[] slot). */
    public int getBotIdx() {
        return botIdx;
    }

    /** Shared state-frame source for this instance (null if UDP state channel is disabled). */
    public StateFrameSource getStateFrameSource() {
        return stateFrameSource;
    }

    public boolean isUseGpu() {
        return instanceConfig != null && instanceConfig.isUseGpu();
    }

    @Override
    public String toString() {
        String botStr = botConfig != null ? " bot=" + botConfig.name() + "/team=" + botConfig.team() : "";
        return "InstanceContext[session=" + sessionId + botStr + " config=" + instanceConfig + "]";
    }
}
