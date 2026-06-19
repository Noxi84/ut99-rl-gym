package aiplay.runtime.config;

/**
 * Indicates whether a bot's model is using the live-trained policy
 * ({@link #CURRENT}) or a frozen champion snapshot ({@link #CHAMPION}).
 *
 * <p>Tagged per (bot, model) pair via {@link aiplay.runtime.context.PlayerIdentityContext}.
 * BC must never learn to imitate frozen policies (BC reads pro-recording
 * CSVs only). For SAC the {@code policy_role} tag is informational unless
 * {@code sac.json:champion_experience_enabled=false}, in which case Java
 * skips the experience flush and the Python trainer drops role=1 rows on
 * ingest.
 */
public enum PolicyRole {
    CURRENT,
    CHAMPION;

    public static PolicyRole forSnapshot(String snapshot) {
        return (snapshot == null || "current".equals(snapshot))
            ? CURRENT : CHAMPION;
    }
}
