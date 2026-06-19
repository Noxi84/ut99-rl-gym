package aiplay.scanners.model.resolver.rlshared;

import aiplay.scanners.model.TrainingModelLogger;

import java.util.Set;

public class RLModelTrainingLogger implements TrainingModelLogger {
    @Override
    public Set<String> getLogFiles() {
        return Set.of("Movement", "ViewRotation");
    }
}
