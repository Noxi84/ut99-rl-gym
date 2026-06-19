package aiplay.scanners.feature.resolver.flag.flagrelative;

import aiplay.runtime.config.CoordinatesConverter;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagLosDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.jsontodtoconverters.CollisionsConverter;
import aiplay.ut99webmodel.Flag;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.Player;

public class FlagRelativeFeatureJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    private final CoordinatesConverter coordinatesConverter = new CoordinatesConverter();
    private final CollisionsConverter collisionsConverter = new CollisionsConverter();

    @Override
    public Integer priority() {
        return 0;
    }

    @Override
    public GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        if (gs.Flags == null) {
            return dto;
        }
        if (dto.flagRelativeResolved) {
            return dto;
        }
        if (dto.redFlag == null) dto.redFlag = new FlagDto();
        if (dto.blueFlag == null) dto.blueFlag = new FlagDto();

        Flag redFlag = null;
        Flag blueFlag = null;
        for (Flag f : gs.Flags) {
            int team = parseIntSafe(f.Team);
            if (team == 0 && redFlag == null) redFlag = f;
            else if (team == 1 && blueFlag == null) blueFlag = f;
        }

        // All homeFlag/enemyFlag features need both flags populated.
        // Populate everything unconditionally so the value resolver can map home/enemy by team.

        if (redFlag != null) {
            dto.redFlag.team = parseIntSafe(redFlag.Team);
            dto.redFlag.status = parseStatus(redFlag.Status);
            dto.redFlag.location = coordinatesConverter.convert(redFlag.Location);
            dto.redFlag.baseLocation = coordinatesConverter.convert(redFlag.HomeBaseLocation);
            dto.redFlag.bHome = toBool(redFlag.bHome);
            dto.redFlag.hasHolder = toBool(redFlag.HasHolder);
            dto.redFlag.holderName = (redFlag.HolderName != null) ? redFlag.HolderName : "";
            dto.redFlag.lastReturnInstigatorSlot = parseIntSafe(redFlag.LastReturnInstigatorSlot);
            dto.redFlag.collisions = collisionsConverter.convert(redFlag.Collisions);
        }

        if (blueFlag != null) {
            dto.blueFlag.team = parseIntSafe(blueFlag.Team);
            dto.blueFlag.status = parseStatus(blueFlag.Status);
            dto.blueFlag.location = coordinatesConverter.convert(blueFlag.Location);
            dto.blueFlag.baseLocation = coordinatesConverter.convert(blueFlag.HomeBaseLocation);
            dto.blueFlag.bHome = toBool(blueFlag.bHome);
            dto.blueFlag.hasHolder = toBool(blueFlag.HasHolder);
            dto.blueFlag.holderName = (blueFlag.HolderName != null) ? blueFlag.HolderName : "";
            dto.blueFlag.lastReturnInstigatorSlot = parseIntSafe(blueFlag.LastReturnInstigatorSlot);
            dto.blueFlag.collisions = collisionsConverter.convert(blueFlag.Collisions);
        }

        // Line-of-sight rays from playerPawn to each flag (7-ray cone per flag)
        if (gs.Players != null) {
            String aiName = PlayerIdentityContext.effectivePlayerName();
            Player pawn = findPlayer(gs, aiName);
            if (pawn != null && pawn.FlagLineOfSight != null) {
                dto.redFlag.los = FlagLosDto.parse(pawn.FlagLineOfSight.RedFlag);
                dto.blueFlag.los = FlagLosDto.parse(pawn.FlagLineOfSight.BlueFlag);
            }
        }

        dto.flagRelativeResolved = true;
        return dto;
    }

    @Override
    public GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto) {
        return enrichAll(sessionId, gs, dto);
    }

    private static Player findPlayer(GameState gs, String aiName) {
        for (Player p : gs.Players) {
            if (p != null && p.Name != null && p.Name.equalsIgnoreCase(aiName)) {
                return p;
            }
        }
        return null;
    }

    private static int parseIntSafe(String s) {
        if (s == null) {
            return -1;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        int start = (trimmed.charAt(0) == '-' || trimmed.charAt(0) == '+') ? 1 : 0;
        boolean integerLike = start < trimmed.length();
        for (int i = start; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < '0' || ch > '9') {
                integerLike = false;
                break;
            }
        }
        if (integerLike) {
            try {
                return Integer.parseInt(trimmed);
            } catch (Exception ignore) {
                return -1;
            }
        }
        try {
            return (int) Math.round(Double.parseDouble(trimmed));
        } catch (Exception ignore) {
            return -1;
        }
    }

    private static boolean toBool(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static FlagStatusDto parseStatus(String s) {
        if (s == null) return FlagStatusDto.UNKNOWN;
        return switch (s.trim().toLowerCase()) {
            case "home" -> FlagStatusDto.HOME;
            case "carried" -> FlagStatusDto.CARRIED;
            case "dropped" -> FlagStatusDto.DROPPED;
            default -> FlagStatusDto.UNKNOWN;
        };
    }

}
