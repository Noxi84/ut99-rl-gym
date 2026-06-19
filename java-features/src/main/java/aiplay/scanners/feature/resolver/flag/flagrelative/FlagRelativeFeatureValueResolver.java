package aiplay.scanners.feature.resolver.flag.flagrelative;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;

public class FlagRelativeFeatureValueResolver implements TrainingFeatureValueResolver {

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        FlagDto homeFlag = resolveHomeFlag(f);
        FlagDto enemyFlag = resolveEnemyFlag(f);
        PlayerRelationDto homeFlagRel = resolveHomeFlagRel(f);
        PlayerRelationDto enemyFlagRel = resolveEnemyFlagRel(f);
        PlayerRelationDto homeBaseRel = resolveHomeBaseRel(f);
        PlayerRelationDto enemyBaseRel = resolveEnemyBaseRel(f);

        switch (featureId) {
            // --- Home flag relative projection ---
            case "homeFlag_relSin":
                return (homeFlagRel != null) ? (float) homeFlagRel.relSin : 0.0f;
            case "homeFlag_relCos":
                return (homeFlagRel != null) ? (float) homeFlagRel.relCos : 0.0f;

            // --- Enemy flag relative projection ---
            case "enemyFlag_relSin":
                return (enemyFlagRel != null) ? (float) enemyFlagRel.relSin : 0.0f;
            case "enemyFlag_relCos":
                return (enemyFlagRel != null) ? (float) enemyFlagRel.relCos : 0.0f;

            // --- Home base relative projection (fixed base location, not moving flag) ---
            case "homeBase_relSin":
                return (homeBaseRel != null) ? (float) homeBaseRel.relSin : 0.0f;
            case "homeBase_relCos":
                return (homeBaseRel != null) ? (float) homeBaseRel.relCos : 0.0f;
            case "homeBaseDistance_norm":
                return (homeBaseRel != null) ? (float) homeBaseRel.distance_norm : 0.0f;

            // --- Enemy base relative projection (fixed base location) ---
            case "enemyBase_relSin":
                return (enemyBaseRel != null) ? (float) enemyBaseRel.relSin : 0.0f;
            case "enemyBase_relCos":
                return (enemyBaseRel != null) ? (float) enemyBaseRel.relCos : 0.0f;
            case "enemyBaseDistance_norm":
                return (enemyBaseRel != null) ? (float) enemyBaseRel.distance_norm : 0.0f;

            // --- Home flag status ---
            case "homeFlagHasHolder":
                return (homeFlag != null && homeFlag.hasHolder) ? 1.0f : 0.0f;

            // --- Enemy flag status ---
            case "enemyFlagHasHolder":
                return (enemyFlag != null && enemyFlag.hasHolder) ? 1.0f : 0.0f;

            // --- Auto-return timer (egocentric mapping) ---
            case "homeFlag_dropReturnRemaining_norm":  return normalizeDropReturn(homeFlag);
            case "enemyFlag_dropReturnRemaining_norm": return normalizeDropReturn(enemyFlag);
        }
        return null;
    }

    private static Float normalizeDropReturn(FlagDto flag) {
        if (flag == null) return 0.0f;
        double autoReturn = GlobalConfigRepository.shared().gameplay().flagDropAutoReturnSeconds();
        double r = flag.dropReturnRemainingSec / autoReturn;
        if (r < 0.0) r = 0.0;
        if (r > 1.0) r = 1.0;
        return (float) r;
    }

    private FlagDto resolveHomeFlag(GameStateDto f) {
        if (f == null || f.playerPawn == null) return null;
        return (f.playerPawn.team == 0) ? f.redFlag : f.blueFlag;
    }

    private FlagDto resolveEnemyFlag(GameStateDto f) {
        if (f == null || f.playerPawn == null) return null;
        return (f.playerPawn.team == 0) ? f.blueFlag : f.redFlag;
    }

    private PlayerRelationDto resolveHomeFlagRel(GameStateDto f) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        return (f.playerPawn.team == 0) ? f.playerPawn.enrichments.redFlagRel : f.playerPawn.enrichments.blueFlagRel;
    }

    private PlayerRelationDto resolveHomeBaseRel(GameStateDto f) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        return f.playerPawn.enrichments.homeBaseRel;
    }

    private PlayerRelationDto resolveEnemyBaseRel(GameStateDto f) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        return f.playerPawn.enrichments.enemyBaseRel;
    }

    private PlayerRelationDto resolveEnemyFlagRel(GameStateDto f) {
        if (f == null || f.playerPawn == null || f.playerPawn.enrichments == null) return null;
        return (f.playerPawn.team == 0) ? f.playerPawn.enrichments.blueFlagRel : f.playerPawn.enrichments.redFlagRel;
    }
}
