package aiplay.util;

/**
 * Canonicaliseer UT99 raw weaponClass strings naar stabiele keys. Gebruikt door
 * zowel de {@code self_weapon_is<X>} feature-resolver als PlayerScoresLogger,
 * zodat probe-strata en DeltaGate-baselines exact dezelfde wapen-identity zien.
 *
 * <p>Mapping (zelfde semantiek als Python {@code _canonical_weapon} in
 * {@code player_scores_eval.py}, plus dual-flag split voor Enforcer):
 * <ul>
 *   <li>{@code "Botpack.PulseGun"} → {@code "PulseGun"}</li>
 *   <li>{@code "NeuralNetWebserver.RLPulseGun"} → {@code "PulseGun"}</li>
 *   <li>{@code "Botpack.UT_Eightball"} → {@code "Eightball"}</li>
 *   <li>{@code "Botpack.Enforcer"} + {@code isDual=false} → {@code "Enforcer"}</li>
 *   <li>{@code "Botpack.Enforcer"} + {@code isDual=true} → {@code "DoubleEnforcer"}</li>
 *   <li>{@code null} / {@code ""} / {@code "none"} → {@code "none"}</li>
 * </ul>
 */
public final class WeaponNameCanonicalizer {

    private WeaponNameCanonicalizer() {}

    public static String canonicalize(String raw, boolean isDual) {
        if (raw == null || raw.isEmpty() || "none".equals(raw)) return "none";
        String s = raw;
        if (s.startsWith("Botpack.")) {
            s = s.substring("Botpack.".length());
        } else if (s.startsWith("NeuralNetWebserver.")) {
            s = s.substring("NeuralNetWebserver.".length());
            if (s.startsWith("RL")) {
                s = s.substring("RL".length());
            }
        }
        if (s.startsWith("UT_")) {
            s = s.substring("UT_".length());
        }
        if (s.isEmpty()) return "none";
        if ("Enforcer".equals(s) && isDual) return "DoubleEnforcer";
        return s;
    }
}
