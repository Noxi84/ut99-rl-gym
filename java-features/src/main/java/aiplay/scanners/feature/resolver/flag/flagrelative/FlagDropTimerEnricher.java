package aiplay.scanners.feature.resolver.flag.flagrelative;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vult {@link FlagDto#dropReturnRemainingSec} per tick door state-edges in
 * {@link FlagDto#status} te volgen tegen de server-klok ({@code MapInfoDto.elapsedTime}).
 *
 * <p>UE1's {@code CTFFlag.state Dropped} doet {@code SetTimer(25.0, false)} -- na 25
 * seconden roept de engine {@code Timer()} -> {@code SendHome()}. Deze enricher leidt
 * dat in Java af zonder de UC-mod te wijzigen: bij overgang van een niet-DROPPED status
 * naar DROPPED slaan we de elapsedTime op; in vervolgticks publiceren we
 * {@code remaining = max(0, 25 - (now - dropStart))}.</p>
 *
 * <p><b>Cold-start gedrag:</b> als de eerste tick die we zien al DROPPED is (Java-proces
 * gestart of nieuwe sessie middenin een drop), is de werkelijke dropStart onbekend. We
 * laten remaining dan 0.0 -- de feature is "uit" voor die specifieke drop-cyclus en
 * wordt weer correct vanaf de volgende drop. Het alternatief (gokken op halfway / nu
 * = dropStart) zou een onjuist signaal aan de policy geven.</p>
 *
 * <p><b>Pain-zone insta-return:</b> {@code CTFFlag.state Dropped.TakeDamage} en
 * {@code ZoneChange} roepen {@code Timer()} direct aan in pain-zones (lava). De status
 * flipt dan binnen 1-2 ticks naar HOME en remaining gaat naar 0. Geen aparte
 * detectiecode nodig.</p>
 *
 * <p>State per session (live) of lokaal per batch (CSV-writer). Per-team prevStatus +
 * dropStart-array. {@link FlagStatusDto#UNKNOWN} ticks worden overgeslagen zodat een
 * losse missing-data tick een lopende timer niet reset.</p>
 */
public class FlagDropTimerEnricher implements TrainingFeatureEnricher {

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        SessionState state = new SessionState();
        for (GameStateDto f : frames) processFrame(f, state);
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        SessionState state = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        for (GameStateDto f : frames) processFrame(f, state);
    }

    private void processFrame(GameStateDto f, SessionState state) {
        if (f == null || f.mapInfo == null) return;
        double now = f.mapInfo.elapsedTime;
        processFlag(f.redFlag, 0, state, now);
        processFlag(f.blueFlag, 1, state, now);
    }

    private void processFlag(FlagDto flag, int idx, SessionState state, double now) {
        if (flag == null) return;
        FlagStatusDto curr = flag.status;
        if (curr == null || curr == FlagStatusDto.UNKNOWN) {
            applyRemaining(flag, idx, state, now);
            return;
        }

        FlagStatusDto prev = state.prevStatus[idx];
        if (curr == FlagStatusDto.DROPPED) {
            if (prev != null && prev != FlagStatusDto.DROPPED) {
                state.dropStart[idx] = now;
            }
        } else {
            state.dropStart[idx] = Double.NaN;
        }
        state.prevStatus[idx] = curr;
        applyRemaining(flag, idx, state, now);
    }

    private static void applyRemaining(FlagDto flag, int idx, SessionState state, double now) {
        double start = state.dropStart[idx];
        if (Double.isNaN(start)) {
            flag.dropReturnRemainingSec = 0.0;
            return;
        }
        double autoReturn = GlobalConfigRepository.shared().gameplay().flagDropAutoReturnSeconds();
        double remaining = autoReturn - (now - start);
        if (remaining < 0.0) remaining = 0.0;
        if (remaining > autoReturn) remaining = autoReturn;
        flag.dropReturnRemainingSec = remaining;
    }

    private static final class SessionState {
        final FlagStatusDto[] prevStatus = new FlagStatusDto[2];
        final double[] dropStart = {Double.NaN, Double.NaN};
    }
}
