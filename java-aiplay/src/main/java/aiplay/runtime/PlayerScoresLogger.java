package aiplay.runtime;

import aiplay.config.global.BotConfig;
import aiplay.config.global.BotModelConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.logging.SessionLogPaths;
import aiplay.logging.SessionRollingLogger;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.util.WeaponNameCanonicalizer;
import java.util.logging.Logger;

/**
 * Per-bot per-minute snapshot of all 6 players in the match (self + teammates +
 * enemies), written to PlayerPawn.log under the tag {@code PLAYER_SCORES}.
 *
 * <p>Format (Plan A/B/D2 + damage + policy-role-code + weapon, 15 colon-velden per speler):
 * <pre>
 * PLAYER_SCORES t=&lt;ms&gt; self=&lt;name&gt;:&lt;team&gt;:&lt;score&gt;:&lt;deaths&gt;:&lt;frags&gt;:&lt;flagsT&gt;:&lt;flagsC&gt;:&lt;flagsR&gt;:&lt;shots&gt;:&lt;shotsOn&gt;:&lt;dmgDealt&gt;:&lt;dmgTaken&gt;:&lt;rl&gt;:&lt;roleCode&gt;:&lt;weapon&gt;
 *   tm0=... tm1=... en0=... en1=... en2=...
 * </pre>
 *
 * <p>Het 15e veld is de raw PlayerDto.weaponClass (bv. "Botpack.PulseGun" of
 * "NeuralNetWebserver.RLPulseGun" voor RL-overrides), of "none" wanneer de speler
 * geen wapen draagt. Python-side wordt dit gecanonicaliseerd naar wapen-key
 * voor per-weapon KPI attribution (DualKPIDeltaGate AND-promotion over actieve
 * wapens binnen het meet-window).
 *
 * <p>Iedere RL bot logt onafhankelijk; meerdere bots in dezelfde match loggen
 * redundant — de Python parser dedupliceert per match-id (instance prefix) en
 * tijds-bucket. {@code rl=1} betekent {@link PlayerDto#bIsRLControlled}, anders
 * {@code rl=0}. {@code roleCode=10+mask}; current joint runtime gebruikt
 * mask bit3 voor full-joint {@code rl_pawn}. Oudere logs met
 * {@code roleCode=1} worden Python-side nog als "alle modellen champion"
 * gelezen. DeltaGate
 * filtert champions uit zijn vloer-meting (RL vs UT99); de joint
 * DualKPIDeltaGate gebruikt het juist om current vs champion buckets per
 * model te splitsen.
 *
 * <p>Counter-velden ({@code frags}, {@code flagsT/C/R}, {@code shots}, {@code shotsOn})
 * komen uit de UC binary frame, monotonisch oplopend per match. Python parser
 * berekent rolling-window deltas en kiest per DeltaGate-instance één KPI:
 * {@code score} (legacy), {@code frags} (shooting), {@code aim_accuracy}
 * (viewrotation, = shotsOn-rate), {@code flag_score} (movement, = 1·taken +
 * 7·captured + 3·returned).
 *
 * <p>Wordt aangeroepen vanuit {@link BotRuntime}'s live producer per tick met
 * de meest recente {@link GameStateDto}; throttled tot 1 emit per 60s.
 */
final class PlayerScoresLogger {

    private static final long EMIT_INTERVAL_MS = 60_000L;

    private final String sessionId;
    private final Logger logger;
    private long lastEmitMs = 0L;

    PlayerScoresLogger(String sessionId) {
        this.sessionId = sessionId;
        this.logger = SessionRollingLogger.get(
            sessionId, SessionLogPaths.featureLog("PlayerPawn"));
    }

    void maybeEmit(GameStateDto state) {
        if (state == null || state.playerPawn == null) return;

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastEmitMs < EMIT_INTERVAL_MS) return;

        StringBuilder sb = new StringBuilder(256);
        sb.append("PLAYER_SCORES t=").append(nowMs);
        sb.append(" self=").append(format(state.playerPawn));

        appendSlots(sb, "tm", state.teammates);
        appendSlots(sb, "en", state.enemies);

        logger.info(sb.toString());
        lastEmitMs = nowMs;
    }

    private static void appendSlots(StringBuilder sb, String prefix, PlayerDto[] slots) {
        if (slots == null) return;
        for (int i = 0; i < slots.length; i++) {
            PlayerDto p = slots[i];
            if (p == null) continue;
            sb.append(' ').append(prefix).append(i).append('=').append(format(p));
        }
    }

    private static String format(PlayerDto p) {
        // name:team:score:deaths:frags:flagsT:flagsC:flagsR:shots:shotsOn:dmgDealt:dmgTaken:rl:roleCode:weapon
        // roleCode = 10 + roleMask. The +10 version tag avoids ambiguity with
        // legacy role=1 logs, where 1 meant "any/all champion". Current runtime
        // only emits bit3 for full-joint rl_pawn.
        // 1 = bot uses a frozen champion snapshot for that model; 0 = current.
        // Naam kan spaties bevatten — vervang door _ voor parse-eenvoud.
        // weapon = raw PlayerDto.weaponClass ("Botpack.PulseGun"), or "none"
        // wanneer geen wapen. Python parser normaliseert naar canonical key.
        String name = p.name == null ? "?" : p.name.replace(' ', '_');
        int roleMask = lookupPolicyRoleMask(p);
        int roleCode = 10 + roleMask;
        // Canonicalize hier zodat Python parser direct de gate-/strata-key
        // ontvangt (geen aparte Python-side fallback voor de Enforcer-dual
        // splitsing nodig). Single source of truth voor canonical naming
        // is WeaponNameCanonicalizer.canonicalize.
        String weapon = WeaponNameCanonicalizer.canonicalize(
            p.weaponClass, p.weaponIsDual);
        return name + ':' + p.team + ':' + p.score + ':' + p.deaths
                + ':' + p.frags + ':' + p.flagsTaken + ':' + p.flagsCaptured
                + ':' + p.flagsReturned + ':' + p.shots + ':' + p.shotsOnTarget
                + ':' + p.damageDealtTotal + ':' + p.damageTakenTotal
                + ':' + (p.bIsRLControlled ? '1' : '0')
                + ':' + roleCode
                + ':' + weapon;
    }

    private static final String MODEL_KEY_VR_SHOOTING = "rl_pawn";
    private static final int ROLE_MASK_VR_SHOOTING = 8;

    /**
     * Returns a 4-bit mask indicating which models this bot is running as a
     * frozen champion (vs. live current). Current runtime only uses bit 3 for
     * full-joint rl_pawn; older bit positions remain parser-side legacy.
     * 0 means UT99 stock, unknown bot, or all-current RL.
     *
     * <p>The lookup walks {@link GlobalConfigRepository} once per emit (60s
     * cadence), which is cheap relative to log I/O. The originating bot's
     * own {@link aiplay.runtime.context.PlayerIdentityContext} cannot be used
     * here — PlayerScoresLogger emits all 6 players (self + teammates +
     * enemies), each potentially a different bot identity.
     */
    private static int lookupPolicyRoleMask(PlayerDto p) {
        if (p == null || p.name == null) return 0;
        String botName = p.name;
        try {
            for (BotConfig bot : GlobalConfigRepository.shared().bots()) {
                if (!bot.name().equals(botName)) continue;
                if (!bot.isRl() || bot.models() == null) return 0;
                int mask = 0;
                String modelKey = ModelRoleRegistry.shared().getModelKey(ModelRole.PAWN_POLICY);
                if (modelKey == null || modelKey.isBlank()) {
                    modelKey = MODEL_KEY_VR_SHOOTING;
                }
                BotModelConfig mc = bot.models().get(modelKey);
                if (mc != null && mc.snapshot() != null && !"current".equals(mc.snapshot())) {
                    mask |= ROLE_MASK_VR_SHOOTING;
                }
                return mask;
            }
        } catch (Exception ignore) {
            // Defensive: config lookup is best-effort during runtime emit.
        }
        return 0;
    }

}
