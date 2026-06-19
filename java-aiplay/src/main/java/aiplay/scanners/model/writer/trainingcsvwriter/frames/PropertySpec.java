package aiplay.scanners.model.writer.trainingcsvwriter.frames;

import aiplay.dto.GameStateDto;

import java.util.function.Predicate;

public final class PropertySpec {
    public final String name;
    public final Predicate<GameStateDto> isActive;

    public PropertySpec(String name, Predicate<GameStateDto> isActive) {
        this.name = name;
        this.isActive = isActive;
    }
}
