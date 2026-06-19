package aiplay.scanners.feature.resolver.mover;

import aiplay.dto.GameStateDto;
import aiplay.dto.MoverDto;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.MoverEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link GameState#Movers} (webmodel, from UDP) into
 * {@link GameStateDto#movers} (dto layer). Egocentric spatial features
 * are computed later by {@link MoverEnricher}.
 *
 * <p>Priority 10: same as PlayerPawnBasicFeatureJsonToDtoConverter, so
 * movers are available when MoverEnricher (priority 11) runs.
 */
public class MoverJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    @Override
    public Integer priority() {
        return 10;
    }

    @Override
    public GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto) {
        return dto;
    }

    @Override
    public GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        if (gs == null || gs.Movers == null || gs.Movers.isEmpty()) {
            dto.movers = List.of();
            return dto;
        }
        List<MoverDto> out = new ArrayList<>(gs.Movers.size());
        for (MoverEntry src : gs.Movers) {
            MoverDto m = new MoverDto();
            m.nameHash = parseHexHash(src.NameHash);
            double[] loc = parseLocation(src.Location);
            m.locX = loc[0];
            m.locY = loc[1];
            m.locZ = loc[2];
            m.keyNum = src.KeyNum;
            m.prevKeyNum = src.PrevKeyNum;
            m.numKeys = src.NumKeys;
            m.opening = src.Opening;
            m.delaying = src.Delaying;
            m.moveProgress = src.MoveProgress;
            out.add(m);
        }
        dto.movers = out;
        return dto;
    }

    private static int parseHexHash(String hex) {
        if (hex == null || hex.isEmpty()) return 0;
        return (int) Long.parseLong(hex, 16);
    }

    private static double[] parseLocation(String loc) {
        if (loc == null || loc.isEmpty()) return new double[]{0, 0, 0};
        String[] parts = loc.split(",");
        if (parts.length < 3) return new double[]{0, 0, 0};
        return new double[]{
            Double.parseDouble(parts[0]),
            Double.parseDouble(parts[1]),
            Double.parseDouble(parts[2])
        };
    }
}
