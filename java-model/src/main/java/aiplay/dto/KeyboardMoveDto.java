package aiplay.dto;

public class KeyboardMoveDto {

    public String key;
    public boolean value;
    public float value_norm;

    public KeyboardMoveDto deepCopy() {
        KeyboardMoveDto copy = new KeyboardMoveDto();
        copy.key = this.key;
        copy.value = this.value;
        copy.value_norm = this.value_norm;
        return copy;
    }
}
