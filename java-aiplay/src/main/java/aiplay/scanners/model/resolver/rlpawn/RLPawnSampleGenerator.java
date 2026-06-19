package aiplay.scanners.model.resolver.rlpawn;

import aiplay.scanners.model.sample.AugmentedTrainingSample;
import aiplay.scanners.model.sample.TrainingSample;
import aiplay.scanners.model.sample.TrainingSampleGenerator;

import java.util.Collections;
import java.util.Iterator;

public class RLPawnSampleGenerator implements TrainingSampleGenerator {

    @Override
    public Iterator<AugmentedTrainingSample> generateSamples(TrainingSample baseSample) {
        return Collections.singletonList(AugmentedTrainingSample.identity(baseSample)).iterator();
    }
}
