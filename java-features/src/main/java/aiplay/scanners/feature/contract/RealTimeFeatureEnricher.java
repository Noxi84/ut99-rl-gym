package aiplay.scanners.feature.contract;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.TrainingFeatureService;

import java.util.List;

/**
 * Hot-path SPI voor real-time feature-enrichment. Door tegen deze interface te
 * hangen i.p.v. {@link TrainingFeatureService} weten runtime-componenten niets
 * van de concrete implementatie — voorbereiding op een latere extractie van
 * {@code aiplay.scanners.feature.*} naar een aparte module.
 */
public interface RealTimeFeatureEnricher {

    void enrichIncrementalForRealTimePlay(String sessionId, String modelKey, List<GameStateDto> frames);

    static RealTimeFeatureEnricher shared() {
        return TrainingFeatureService.shared();
    }
}
