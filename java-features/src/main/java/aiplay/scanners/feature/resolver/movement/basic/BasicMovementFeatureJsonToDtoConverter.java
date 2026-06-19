package aiplay.scanners.feature.resolver.movement.basic;

import aiplay.scanners.feature.jsontodtoconverters.KeyboardMoveDtoConverter;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerPawnDto;
import aiplay.rl.MovementPrimitive;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.KeyboardCaptureData;
import aiplay.ut99webmodel.Player;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.RecordingKeysConfig;

public class BasicMovementFeatureJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    private final KeyboardMoveDtoConverter keyboardMoveDtoConverter = new KeyboardMoveDtoConverter();

    @Override
    public Integer priority() {
        return 0;
    }

    @Override
    public GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        if (gs == null) {
            return dto;
        }

        // KeyboardCapture path (BC training from JSON recordings).
        KeyboardCaptureData kc = gs.KeyboardCapture;
        if (kc != null) {
            if (dto.playerPawn == null) dto.playerPawn = new PlayerDto();
            if (dto.playerPawn.playerPawn == null) dto.playerPawn.playerPawn = new PlayerPawnDto();

            // Backward compatibility: if old bWas* fields are present, convert to move* primitives
            if (kc.moveForward == null && kc.bWasForward != null) {
                convertLegacyToMovePrimitives(kc);
            }

            RecordingKeysConfig keys = GlobalConfigRepository.shared().recording().keys();
            PlayerPawnDto pp = dto.playerPawn.playerPawn;
            pp.moveIdle = keyboardMoveDtoConverter.convert(null, boolStr(kc.moveIdle));
            pp.moveForward = keyboardMoveDtoConverter.convert(keys.moveForward(), boolStr(kc.moveForward));
            pp.moveForwardLeft = keyboardMoveDtoConverter.convert(null, boolStr(kc.moveForwardLeft));
            pp.moveForwardRight = keyboardMoveDtoConverter.convert(null, boolStr(kc.moveForwardRight));
            pp.moveStrafeLeft = keyboardMoveDtoConverter.convert(keys.moveLeft(), boolStr(kc.moveStrafeLeft));
            pp.moveStrafeRight = keyboardMoveDtoConverter.convert(keys.moveRight(), boolStr(kc.moveStrafeRight));
            pp.moveBack = keyboardMoveDtoConverter.convert(keys.moveBackward(), boolStr(kc.moveBack));
            pp.moveBackLeft = keyboardMoveDtoConverter.convert(null, boolStr(kc.moveBackLeft));
            pp.moveBackRight = keyboardMoveDtoConverter.convert(null, boolStr(kc.moveBackRight));
            pp.bJump = keyboardMoveDtoConverter.convert(keys.jump(), boolStr(kc.bPressedJump));
            dto.playerPawn.bDuck = keyboardMoveDtoConverter.convert(keys.duck(), boolStr(kc.bDuck));
            return dto;
        }

        // PlayerPawn path (real-time webservice or legacy recordings without KeyboardCapture).
        // IMPORTANT: do NOT create dto.playerPawn here before finding the player.
        // AiPlayService checks dto.playerPawn == null to detect missing player (startup/reconnect).
        if (gs.Players == null) {
            return dto;
        }
        String aiName = aiplay.runtime.context.PlayerIdentityContext.effectivePlayerName();
        Player pawn = findPlayer(gs, aiName);
        if (pawn == null) {
            return dto;
        }
        if (dto.playerPawn == null) {
            dto.playerPawn = new PlayerDto();
        }
        if (dto.playerPawn.playerPawn == null) {
            dto.playerPawn.playerPawn = new PlayerPawnDto();
        }
        dto.playerPawn.bDuck = keyboardMoveDtoConverter.convert(
                GlobalConfigRepository.shared().recording().keys().duck(), nz(pawn.bDuck));
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

    /**
     * Convert legacy bWas* fields to move* primitives on the KeyboardCaptureData.
     * Called once per frame when old-format recordings are loaded.
     */
    private static void convertLegacyToMovePrimitives(KeyboardCaptureData kc) {
        boolean fwd = kc.bWasForward != null && kc.bWasForward;
        boolean back = kc.bWasBack != null && kc.bWasBack;
        boolean left = kc.bWasLeft != null && kc.bWasLeft;
        boolean right = kc.bWasRight != null && kc.bWasRight;
        MovementPrimitive p = MovementPrimitive.fromLegacyKeyStates(fwd, back, left, right);
        kc.moveIdle = (p == MovementPrimitive.IDLE);
        kc.moveForward = (p == MovementPrimitive.FORWARD);
        kc.moveForwardLeft = (p == MovementPrimitive.FORWARD_LEFT);
        kc.moveForwardRight = (p == MovementPrimitive.FORWARD_RIGHT);
        kc.moveStrafeLeft = (p == MovementPrimitive.STRAFE_LEFT);
        kc.moveStrafeRight = (p == MovementPrimitive.STRAFE_RIGHT);
        kc.moveBack = (p == MovementPrimitive.BACK);
        kc.moveBackLeft = (p == MovementPrimitive.BACK_LEFT);
        kc.moveBackRight = (p == MovementPrimitive.BACK_RIGHT);
    }

    private static String boolStr(Boolean b) {
        return b != null && b ? "True" : "False";
    }

    private static String nz(String s) {
        return s == null ? "False" : s;
    }
}
