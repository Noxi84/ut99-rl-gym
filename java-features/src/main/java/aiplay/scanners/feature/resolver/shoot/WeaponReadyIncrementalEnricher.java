package aiplay.scanners.feature.resolver.shoot;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.shooting.WeaponFireModeConfig;
import aiplay.config.global.shooting.WeaponFireProfile;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconstrueert de refire-klok van het vastgehouden wapen uit de {@code bFire}/{@code bAltFire}
 * rising edges + {@link WeaponFireProfile} (fire_duration_ms + cooldown_ms per mode), inclusief
 * de cross-mode block (een alt-schot blokkeert primary tot de alt-cyclus afloopt en vice versa —
 * zelfde semantiek als {@code RLPawnFireDecisionProcessor}). Schrijft
 * {@code playerPawn.weaponReadyInNorm}: 0 = nu schietbaar, 1 = ≥1s te gaan.
 *
 * <p>Waarom: de fire-state-machine bepaalt WANNEER een gewenst schot daadwerkelijk valt, maar die
 * klok was voor de policy onzichtbaar ({@code fireCooldown} is maar een 1-frame falling-edge bit).
 * Getimede acties — de shock-combo-klik voorop — zijn zonder deze observatie een POMDP-gat: de
 * klik-economie (shock_combo_click) kan wachten belonen, maar de policy kan niet zien wanneer
 * "nu klikken" überhaupt effect heeft. Analoog aan {@code dodgeCooldownNorm} voor movement.
 *
 * <p>Zelfde batch/incremental-splitsing als {@link FireCooldownIncrementalEnricher}; klok via
 * {@code frame.timestampMillis} (precedent: PitchAngularVelocityEnricher). Respawn (health
 * ≤0 → >0) reset de timers. HOLD-wapens (pulse/minigun) houden bFire vast → zeldzame edges →
 * feature blijft ~0: voor die wapens is een refire-klok ook niet betekenisvol.
 */
public class WeaponReadyIncrementalEnricher implements TrainingFeatureEnricher {

    /** Normalisatie-horizon: shock-cycli zijn 521/794 ms; 1s dekt alle edge-wapens met resolutie. */
    private static final double NORM_HORIZON_MS = 1000.0;

    private static final class ClockState {
        long primaryBusyUntilMs;
        long secondaryBusyUntilMs;
        boolean prevFire;
        boolean prevAlt;
        int prevHealth = Integer.MIN_VALUE;
        long prevTs;
    }

    private final ConcurrentHashMap<String, ClockState> bySession = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        ClockState st = new ClockState();
        for (GameStateDto f : frames) {
            apply(st, f);
        }
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        ClockState st = bySession.computeIfAbsent(sessionId, k -> new ClockState());
        for (GameStateDto f : frames) {
            apply(st, f);
        }
    }

    private static void apply(ClockState st, GameStateDto f) {
        if (f == null || f.playerPawn == null) return;
        PlayerDto p = f.playerPawn;

        long t = f.timestampMillis;
        if (t <= 0) {
            // Geen klok in dit frame (oude recordings): schat monotoon door (~10Hz).
            t = st.prevTs + 100;
        }
        // Sessie-herstart / map-wissel: klok springt terug → timers resetten i.p.v. eeuwig busy.
        if (t < st.prevTs) {
            st.primaryBusyUntilMs = 0;
            st.secondaryBusyUntilMs = 0;
        }
        st.prevTs = t;

        boolean fire = p.bFire != null && p.bFire.value_norm > 0.5f;
        boolean alt = p.bAltFire != null && p.bAltFire.value_norm > 0.5f;

        // Respawn: vers wapen, geen lopende cyclus.
        if (p.health > 0 && st.prevHealth <= 0 && st.prevHealth != Integer.MIN_VALUE) {
            st.primaryBusyUntilMs = 0;
            st.secondaryBusyUntilMs = 0;
        }

        // EERST de feature zetten op basis van de klok ZOALS DIE WAS op het beslismoment van dit
        // frame (vóór de eigen edge van dit frame) — een legitiem schot leest dan 0 ("ik kon
        // vuren"), en de cyclus loopt in de frames erná zichtbaar af. Cross-mode block: het
        // eerstvolgende schot (welke mode dan ook) kan pas wanneer BEIDE mode-cycli afliepen.
        long readyAt = Math.max(st.primaryBusyUntilMs, st.secondaryBusyUntilMs);
        double remaining = Math.max(0.0, readyAt - t);
        p.weaponReadyInNorm = (float) Math.min(1.0, remaining / NORM_HORIZON_MS);

        // DAARNA de edges van dit frame verwerken voor de volgende frames.
        WeaponFireProfile profile =
            GlobalConfigRepository.shared().shooting().profileFor(p.weaponClass);
        if (fire && !st.prevFire) {
            st.primaryBusyUntilMs = t + cycleMs(profile != null ? profile.primary() : null);
        }
        if (alt && !st.prevAlt) {
            st.secondaryBusyUntilMs = t + cycleMs(profile != null ? profile.secondary() : null);
        }
        st.prevFire = fire;
        st.prevAlt = alt;
        st.prevHealth = p.health;
    }

    private static long cycleMs(WeaponFireModeConfig mode) {
        if (mode == null) return 0L;
        return Math.max(0L, mode.fireDurationMs() + mode.cooldownMs());
    }
}
