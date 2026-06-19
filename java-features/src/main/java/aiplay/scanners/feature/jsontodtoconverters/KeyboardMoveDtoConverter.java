package aiplay.scanners.feature.jsontodtoconverters;

import aiplay.dto.KeyboardMoveDto;

public class KeyboardMoveDtoConverter {

    public KeyboardMoveDto convert(String key, String value) {
        KeyboardMoveDto keyboardMoveDto = new KeyboardMoveDto();
        keyboardMoveDto.key = key;
        keyboardMoveDto.value = isTrue(value);
        keyboardMoveDto.value_norm = keyboardMoveDto.value ? 1.0f : 0.0f;

        return keyboardMoveDto;
    }

    private boolean isTrue(String value) {
        return "True".equalsIgnoreCase(value) || "1".equalsIgnoreCase(value);
    }
}
