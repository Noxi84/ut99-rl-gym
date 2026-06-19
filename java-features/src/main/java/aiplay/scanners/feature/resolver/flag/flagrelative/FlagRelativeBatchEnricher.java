package aiplay.scanners.feature.resolver.flag.flagrelative;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.FlagStatusDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerRelationDto;
import aiplay.scanners.feature.TrainingFeatureEnricher;
import aiplay.util.NormalizationUtils;

import java.util.List;

public class FlagRelativeBatchEnricher implements TrainingFeatureEnricher {

    private static final double DIST_TAU = 600.0;

    @Override
    public void enrichBatch(List<GameStateDto> frames) {
        if (frames == null) return;
        for (GameStateDto f : frames) enrichFrame(f);
    }

    @Override
    public void enrichIncremental(String sessionId, List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) return;
        for (GameStateDto f : frames) enrichFrame(f);
    }

    private void enrichFrame(GameStateDto f) {
        if (f == null) return;
        PlayerDto self = f.playerPawn;
        if (self == null || self.location == null || self.viewRotation == null) return;

        final int viewX = self.viewRotation.x & 0xFFFF;

        // Enemy flag bearing: null when the bot carries it (bearing to yourself is meaningless and makes the bot stuck in corners).
        FlagDto enemyFlag = (self.team == 0) ? f.blueFlag : f.redFlag;
        boolean carryingEnemyFlag = self.hasFlag && enemyFlag != null
                && enemyFlag.hasHolder && enemyFlag.status == FlagStatusDto.CARRIED;

        CoordinatesDto redTgt = (f.redFlag != null) ? f.redFlag.location : null;
        CoordinatesDto blueTgt = (f.blueFlag != null) ? f.blueFlag.location : null;

        if (carryingEnemyFlag && self.team == 1) redTgt = null;   // blue bot carries red flag
        if (carryingEnemyFlag && self.team == 0) blueTgt = null;  // red bot carries blue flag

        self.enrichments.redFlagRel = (redTgt != null)
                ? buildRelation(self.location, viewX, redTgt)
                : null;

        self.enrichments.blueFlagRel = (blueTgt != null)
                ? buildRelation(self.location, viewX, blueTgt)
                : null;

        // Home base: fixed base location of own flag (not the moving flag position)
        FlagDto ownFlag = (self.team == 0) ? f.redFlag : f.blueFlag;
        CoordinatesDto homeBaseTgt = (ownFlag != null) ? ownFlag.baseLocation : null;
        self.enrichments.homeBaseRel = (homeBaseTgt != null)
                ? buildRelation(self.location, viewX, homeBaseTgt)
                : null;

        // Enemy base: fixed base location of enemy flag
        FlagDto oppFlag = (self.team == 0) ? f.blueFlag : f.redFlag;
        CoordinatesDto enemyBaseTgt = (oppFlag != null) ? oppFlag.baseLocation : null;
        self.enrichments.enemyBaseRel = (enemyBaseTgt != null)
                ? buildRelation(self.location, viewX, enemyBaseTgt)
                : null;
    }

    private static PlayerRelationDto buildRelation(CoordinatesDto src, int viewYawX, CoordinatesDto tgt) {
        PlayerRelationDto dto = new PlayerRelationDto();

        final double dx = tgt.x - src.x;
        final double dy = tgt.y - src.y;
        final double dz = tgt.z - src.z;

        final double dist2D = Math.hypot(dx, dy);
        final double dist3D = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double soft2D = NormalizationUtils.softDistance01(dist2D, DIST_TAU);

        double[] sc = NormalizationUtils.relativeAngleSinCos(viewYawX, src.x, src.y, tgt.x, tgt.y);
        double sin = sc[0], cos = sc[1];

        double[] scStab = NormalizationUtils.stabilizeSinCosNear(sin, cos, soft2D, true);
        sin = scStab[0];
        cos = scStab[1];

        double[] fr = NormalizationUtils.forwardRightDistNorm(sin, cos, soft2D);

        dto.relSin = sin;
        dto.relCos = cos;
        dto.forwardDist_norm = fr[0];
        dto.rightDist_norm = fr[1];
        dto.relDistanceLog = Math.log1p(dist3D);
        dto.distance_norm = NormalizationUtils.normalizeDistance3D(dist3D);

        return dto;
    }
}
