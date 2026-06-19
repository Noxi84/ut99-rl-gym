package aiplay.scanners.feature.resolver.enemy.enemyhasflag;

import aiplay.scanners.feature.TrainingFeatureLogger;

import java.util.Set;

public class EnemyBasicFeatureLogger implements TrainingFeatureLogger {

    @Override
    public Set<String> getLogFiles() {
        return Set.of("Enemy");
    }
}
