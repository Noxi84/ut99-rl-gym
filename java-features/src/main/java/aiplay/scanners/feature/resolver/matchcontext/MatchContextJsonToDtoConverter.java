package aiplay.scanners.feature.resolver.matchcontext;

import aiplay.runtime.config.MapSpawnPointsResolver;
import aiplay.dto.GameStateDto;
import aiplay.runtime.context.MapKey;
import aiplay.dto.MapInfoDto;
import aiplay.dto.SpawnPointDto;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.ut99webmodel.GameState;

import java.util.List;

/**
 * Populeert {@link GameStateDto#mapInfo} en {@link GameStateDto#spawnPoints} vanuit de
 * JSON-source {@link GameState}. Tijdens realtime UDP-play wordt {@code mapInfo} al door
 * {@code StateFrameToGameStateConverter} gevuld; deze converter dekt de CSV-writer en
 * JSON-recording paden waar {@code TrainingFeatureService.createGameStateDtoFromJsonSession}
 * elke geregistreerde converter aanroept.
 */
public class MatchContextJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    @Override
    public Integer priority() {
        return 0;
    }

    @Override
    public GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        if (dto.mapInfo == null) {
            dto.mapInfo = new MapInfoDto();
        }

        dto.timestampMillis = gs.timestampMillis;

        if (gs.MapInfo != null) {
            dto.mapInfo.mapName = gs.MapInfo.MapName;
            dto.mapInfo.levelTitle = gs.MapInfo.LevelTitle;
            dto.mapInfo.gameName = gs.MapInfo.GameName;
            dto.mapInfo.gameClass = gs.MapInfo.GameClass;
            dto.mapInfo.gameType = gs.MapInfo.GameType;
            dto.mapInfo.timeLimit = parseDoubleSafe(gs.MapInfo.TimeLimit);
            dto.mapInfo.remainingTime = parseDoubleSafe(gs.MapInfo.RemainingTime);
            dto.mapInfo.elapsedTime = parseDoubleSafe(gs.MapInfo.ElapsedTime);
            dto.mapInfo.bGameEnded = parseBoolSafe(gs.MapInfo.bGameEnded);
            dto.mapInfo.redScore = gs.MapInfo.RedScore;
            dto.mapInfo.blueScore = gs.MapInfo.BlueScore;
        }

        if (dto.spawnPoints == null) {
            List<SpawnPointDto> configured = MapSpawnPointsResolver.resolve(MapKey.fromFrame(dto));
            dto.spawnPoints = configured.toArray(new SpawnPointDto[0]);
        }

        return dto;
    }

    @Override
    public GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto) {
        return enrichAll(sessionId, gs, dto);
    }

    private static double parseDoubleSafe(String s) {
        if (s == null) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (Exception ignore) {
            return 0.0;
        }
    }

    private static boolean parseBoolSafe(String s) {
        if (s == null) return false;
        return "True".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
    }
}
