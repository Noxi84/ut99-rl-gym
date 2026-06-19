package aiplay.scanners.feature.resolver.team;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.ITrainingFeature;
import aiplay.scanners.feature.TrainingFeatureComponent;

import java.util.List;
import java.util.Set;

/**
 * Team-aggregate features (Fase 1 — CTDE-precursor, L1).
 *
 * <p>Levert vijf team-niveau aggregaten die de bot extra context geven over de
 * collectieve team-staat naast de bestaande per-slot {@code teammate{N}_*}
 * features. De aggregaten zitten in {@code s_self} en worden daarmee
 * automatisch door de bestaande 5-head {@code MultiHeadSACCritic} via de
 * shared trunk geconsumeerd — geen architecturale wijziging nodig.
 *
 * <ul>
 *   <li>{@code team_meanHealth_norm} — gemiddelde health / 100 over alle
 *       levende team-leden (zelf inclusief). 0.0 bij wipe.</li>
 *   <li>{@code team_meanDepth_norm} — gemiddelde diepte in enemy half langs
 *       de home→enemy axis (0 = midfield, 1 = aan enemy base), over alle
 *       levende team-leden. Signaleert "team is offensief" vs "team trekt
 *       terug". Hergebruikt dezelfde projectie als
 *       {@link aiplay.scanners.feature.resolver.role.RoleContextFeatureComponent#enemyDepthInOwnHalf}
 *       maar dan vanuit eigen perspectief.</li>
 *   <li>{@code team_aliveCount_norm} — levende team-leden / team-size.
 *       Bij 5v5: 1.0 = volledig team in leven, 0.0 = wipe.</li>
 *   <li>{@code team_captureProgress_norm} — projectie van eigen team's
 *       enemyFlag-carrier op de home→enemy axis (0.0 = bij home, 1.0 = bij
 *       enemy base). Sentinel 0.5 wanneer geen team-lid de enemyFlag draagt.
 *       Self-computed uit carrier-positie i.p.v. {@code annotatedCarrierProgress}
 *       want die annotation wordt alleen gezet door de runtime
 *       MissionAnnotator pipeline en blijft 0.0 (Java field default)
 *       tijdens CSV-writing van JSON-recordings.</li>
 *   <li>{@code team_droppedCount_norm} — aantal vlaggen met status DROPPED
 *       / 2. Signaleert flag-chaos (beide vlaggen op grond) of partiële
 *       chaos (één gedropt).</li>
 * </ul>
 *
 * Single-frame group: deze aggregaten veranderen langzaam (health-decay,
 * positie-evolutie) of sparsam (dropped-events, capture-progress sprongen)
 * — temporele context heeft geen toegevoegde waarde voor de policy.
 */
@TrainingFeatureComponent(priority = 10)
public class TeamAggregateFeatureComponent implements ITrainingFeature {

    private static final Set<String> FEATURE_IDS = Set.of(
        "team_meanHealth_norm",
        "team_meanDepth_norm",
        "team_aliveCount_norm",
        "team_captureProgress_norm",
        "team_droppedCount_norm"
    );

    @Override
    public Set<String> getFeatureIds() {
        return FEATURE_IDS;
    }

    @Override
    public Float resolveFeatureValueForRealTimePlay(
            String sessionId,
            String modelKey,
            String featureId,
            GameStateDto frame) {
        return resolve(featureId, frame);
    }

    @Override
    public Float resolveCsvWriterFeatureValue(
            String modelKey,
            String sessionId,
            String featureId,
            List<GameStateDto> gameStates,
            GameStateDto current) {
        return resolve(featureId, current);
    }

    private static Float resolve(String featureId, GameStateDto frame) {
        if (frame == null) return 0.0f;
        return switch (featureId) {
            case "team_meanHealth_norm" -> meanHealthNorm(frame);
            case "team_meanDepth_norm" -> meanDepthNorm(frame);
            case "team_aliveCount_norm" -> aliveCountNorm(frame);
            case "team_captureProgress_norm" -> captureProgressNorm(frame);
            case "team_droppedCount_norm" -> droppedCountNorm(frame);
            default -> 0.0f;
        };
    }

    private static float meanHealthNorm(GameStateDto frame) {
        PlayerDto self = frame.playerPawn;
        int total = 0;
        int alive = 0;
        if (isAlive(self)) {
            total += clampHealth(self.health);
            alive++;
        }
        if (frame.teammates != null) {
            for (PlayerDto t : frame.teammates) {
                if (isAlive(t)) {
                    total += clampHealth(t.health);
                    alive++;
                }
            }
        }
        if (alive == 0) return 0.0f;
        return (float) total / (alive * 100.0f);
    }

    private static float meanDepthNorm(GameStateDto frame) {
        PlayerDto self = frame.playerPawn;
        if (self == null) return 0.0f;
        FlagDto ownFlag = (self.team == 0) ? frame.redFlag : frame.blueFlag;
        FlagDto enemyFlag = (self.team == 0) ? frame.blueFlag : frame.redFlag;
        if (ownFlag == null || enemyFlag == null
                || ownFlag.baseLocation == null || enemyFlag.baseLocation == null) {
            return 0.0f;
        }
        CoordinatesDto home = ownFlag.baseLocation;
        CoordinatesDto enemyBase = enemyFlag.baseLocation;
        double axisX = enemyBase.x - home.x;
        double axisY = enemyBase.y - home.y;
        double axisLenSq = axisX * axisX + axisY * axisY;
        if (axisLenSq < 1e-9) return 0.0f;

        double depthSum = 0.0;
        int count = 0;
        if (isAlive(self) && self.location != null) {
            depthSum += depthInEnemyHalf(self.location, home, axisX, axisY, axisLenSq);
            count++;
        }
        if (frame.teammates != null) {
            for (PlayerDto t : frame.teammates) {
                if (isAlive(t) && t.location != null) {
                    depthSum += depthInEnemyHalf(t.location, home, axisX, axisY, axisLenSq);
                    count++;
                }
            }
        }
        if (count == 0) return 0.0f;
        return (float) (depthSum / count);
    }

    private static double depthInEnemyHalf(
            CoordinatesDto pos, CoordinatesDto home,
            double axisX, double axisY, double axisLenSq) {
        // t = 0 at home base, 1 at enemy base. Depth = max(0, min(1, t)).
        double t = ((pos.x - home.x) * axisX + (pos.y - home.y) * axisY) / axisLenSq;
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t;
    }

    private static float aliveCountNorm(GameStateDto frame) {
        int alive = isAlive(frame.playerPawn) ? 1 : 0;
        int teamSize = 1;
        if (frame.teammates != null) {
            for (PlayerDto t : frame.teammates) {
                if (t == null) continue;
                teamSize++;
                if (isAlive(t)) alive++;
            }
        }
        if (teamSize == 0) return 0.0f;
        return (float) alive / (float) teamSize;
    }

    private static float captureProgressNorm(GameStateDto frame) {
        PlayerDto self = frame.playerPawn;
        if (self == null) return 0.5f;
        FlagDto ownFlag = (self.team == 0) ? frame.redFlag : frame.blueFlag;
        FlagDto enemyFlag = (self.team == 0) ? frame.blueFlag : frame.redFlag;
        if (ownFlag == null || enemyFlag == null
                || ownFlag.baseLocation == null || enemyFlag.baseLocation == null) {
            return 0.5f;
        }
        CoordinatesDto carrierPos = findTeamCarrierPosition(frame);
        if (carrierPos == null) {
            return 0.5f;
        }
        CoordinatesDto home = ownFlag.baseLocation;
        CoordinatesDto enemyBase = enemyFlag.baseLocation;
        double axisX = enemyBase.x - home.x;
        double axisY = enemyBase.y - home.y;
        double axisLenSq = axisX * axisX + axisY * axisY;
        if (axisLenSq < 1e-9) return 0.5f;
        double t = ((carrierPos.x - home.x) * axisX + (carrierPos.y - home.y) * axisY) / axisLenSq;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        return (float) t;
    }

    private static CoordinatesDto findTeamCarrierPosition(GameStateDto frame) {
        PlayerDto self = frame.playerPawn;
        if (self != null && self.hasFlag && self.location != null) {
            return self.location;
        }
        if (frame.teammates != null) {
            for (PlayerDto t : frame.teammates) {
                if (t != null && t.hasFlag && t.location != null) {
                    return t.location;
                }
            }
        }
        return null;
    }

    private static float droppedCountNorm(GameStateDto frame) {
        int dropped = 0;
        if (isDropped(frame.redFlag)) dropped++;
        if (isDropped(frame.blueFlag)) dropped++;
        return dropped / 2.0f;
    }

    private static boolean isAlive(PlayerDto p) {
        return p != null && p.health > 0;
    }

    private static int clampHealth(int h) {
        // Clamp boost-health (max 199) op 100 voor consistentie met
        // PlayerDtoFeatureResolver.health_norm = min(1.0, health/100).
        if (h < 0) return 0;
        if (h > 100) return 100;
        return h;
    }

    private static boolean isDropped(FlagDto flag) {
        return flag != null && flag.status == FlagStatusDto.DROPPED;
    }
}
