package aiplay.scanners.feature.jsontodtoconverters;

import aiplay.dto.CollisionsDto;
import aiplay.ut99webmodel.Collisions;

public class CollisionsConverter {

    /** Tanh scale (uu) for the signed floor-elevation normalization — concentrates resolution
     *  in the jumpable / small-drop band. */
    private static final double FLOOR_DELTA_TANH_SCALE_UU = 64.0;

    public CollisionsDto convert(Collisions collisions) {
        if (collisions == null) {
            return null;
        }

        CollisionsDto dto = new CollisionsDto();

        int maxDist = parseIntSafe(collisions.maxDist, 1200); // fallback enkel als JSON ontbreekt
        if (maxDist <= 0) {
            maxDist = 1200;
        }

        // yaw-relative
        int fwd = parseIntSafe(collisions.fwd_collision, 0);
        int back = parseIntSafe(collisions.back_collision, 0);
        int left = parseIntSafe(collisions.left_collision, 0);
        int right = parseIntSafe(collisions.right_collision, 0);

        dto.fwdCollision = fwd;
        dto.backCollision = back;
        dto.leftCollision = left;
        dto.rightCollision = right;

        // world-axis (NEW)
        int posX = parseIntSafe(collisions.posX_collision, 0);
        int negX = parseIntSafe(collisions.negX_collision, 0);
        int posY = parseIntSafe(collisions.posY_collision, 0);
        int negY = parseIntSafe(collisions.negY_collision, 0);

        dto.posXCollision = posX;
        dto.negXCollision = negX;
        dto.posYCollision = posY;
        dto.negYCollision = negY;

        double denom = (double) maxDist;

        // yaw-relative norms
        dto.fwdCollision_norm = clamp01(((double) fwd) / denom);
        dto.backCollision_norm = clamp01(((double) back) / denom);
        dto.leftCollision_norm = clamp01(((double) left) / denom);
        dto.rightCollision_norm = clamp01(((double) right) / denom);

        // world-axis norms
        dto.posXCollision_norm = clamp01(((double) posX) / denom);
        dto.negXCollision_norm = clamp01(((double) negX) / denom);
        dto.posYCollision_norm = clamp01(((double) posY) / denom);
        dto.negYCollision_norm = clamp01(((double) negY) / denom);

        // diagonal yaw-relative
        int fwdRight30 = parseIntSafe(collisions.fwdRight30_collision, 0);
        int fwdRight45 = parseIntSafe(collisions.fwdRight45_collision, 0);
        int fwdRight60 = parseIntSafe(collisions.fwdRight60_collision, 0);
        int backRight60 = parseIntSafe(collisions.backRight60_collision, 0);
        int backRight45 = parseIntSafe(collisions.backRight45_collision, 0);
        int backRight30 = parseIntSafe(collisions.backRight30_collision, 0);
        int backLeft30 = parseIntSafe(collisions.backLeft30_collision, 0);
        int backLeft45 = parseIntSafe(collisions.backLeft45_collision, 0);
        int backLeft60 = parseIntSafe(collisions.backLeft60_collision, 0);
        int fwdLeft60 = parseIntSafe(collisions.fwdLeft60_collision, 0);
        int fwdLeft45 = parseIntSafe(collisions.fwdLeft45_collision, 0);
        int fwdLeft30 = parseIntSafe(collisions.fwdLeft30_collision, 0);

        dto.fwdRight30Collision = fwdRight30;
        dto.fwdRight45Collision = fwdRight45;
        dto.fwdRight60Collision = fwdRight60;
        dto.backRight60Collision = backRight60;
        dto.backRight45Collision = backRight45;
        dto.backRight30Collision = backRight30;
        dto.backLeft30Collision = backLeft30;
        dto.backLeft45Collision = backLeft45;
        dto.backLeft60Collision = backLeft60;
        dto.fwdLeft60Collision = fwdLeft60;
        dto.fwdLeft45Collision = fwdLeft45;
        dto.fwdLeft30Collision = fwdLeft30;

        dto.fwdRight30Collision_norm = clamp01(((double) fwdRight30) / denom);
        dto.fwdRight45Collision_norm = clamp01(((double) fwdRight45) / denom);
        dto.fwdRight60Collision_norm = clamp01(((double) fwdRight60) / denom);
        dto.backRight60Collision_norm = clamp01(((double) backRight60) / denom);
        dto.backRight45Collision_norm = clamp01(((double) backRight45) / denom);
        dto.backRight30Collision_norm = clamp01(((double) backRight30) / denom);
        dto.backLeft30Collision_norm = clamp01(((double) backLeft30) / denom);
        dto.backLeft45Collision_norm = clamp01(((double) backLeft45) / denom);
        dto.backLeft60Collision_norm = clamp01(((double) backLeft60) / denom);
        dto.fwdLeft60Collision_norm = clamp01(((double) fwdLeft60) / denom);
        dto.fwdLeft45Collision_norm = clamp01(((double) fwdLeft45) / denom);
        dto.fwdLeft30Collision_norm = clamp01(((double) fwdLeft30) / denom);

        // diagonal world-axis
        int posXPosY30 = parseIntSafe(collisions.posXPosY30_collision, 0);
        int posXPosY45 = parseIntSafe(collisions.posXPosY45_collision, 0);
        int posXPosY60 = parseIntSafe(collisions.posXPosY60_collision, 0);
        int negXPosY60 = parseIntSafe(collisions.negXPosY60_collision, 0);
        int negXPosY45 = parseIntSafe(collisions.negXPosY45_collision, 0);
        int negXPosY30 = parseIntSafe(collisions.negXPosY30_collision, 0);
        int negXNegY30 = parseIntSafe(collisions.negXNegY30_collision, 0);
        int negXNegY45 = parseIntSafe(collisions.negXNegY45_collision, 0);
        int negXNegY60 = parseIntSafe(collisions.negXNegY60_collision, 0);
        int posXNegY60 = parseIntSafe(collisions.posXNegY60_collision, 0);
        int posXNegY45 = parseIntSafe(collisions.posXNegY45_collision, 0);
        int posXNegY30 = parseIntSafe(collisions.posXNegY30_collision, 0);

        dto.posXPosY30Collision = posXPosY30;
        dto.posXPosY45Collision = posXPosY45;
        dto.posXPosY60Collision = posXPosY60;
        dto.negXPosY60Collision = negXPosY60;
        dto.negXPosY45Collision = negXPosY45;
        dto.negXPosY30Collision = negXPosY30;
        dto.negXNegY30Collision = negXNegY30;
        dto.negXNegY45Collision = negXNegY45;
        dto.negXNegY60Collision = negXNegY60;
        dto.posXNegY60Collision = posXNegY60;
        dto.posXNegY45Collision = posXNegY45;
        dto.posXNegY30Collision = posXNegY30;

        dto.posXPosY30Collision_norm = clamp01(((double) posXPosY30) / denom);
        dto.posXPosY45Collision_norm = clamp01(((double) posXPosY45) / denom);
        dto.posXPosY60Collision_norm = clamp01(((double) posXPosY60) / denom);
        dto.negXPosY60Collision_norm = clamp01(((double) negXPosY60) / denom);
        dto.negXPosY45Collision_norm = clamp01(((double) negXPosY45) / denom);
        dto.negXPosY30Collision_norm = clamp01(((double) negXPosY30) / denom);
        dto.negXNegY30Collision_norm = clamp01(((double) negXNegY30) / denom);
        dto.negXNegY45Collision_norm = clamp01(((double) negXNegY45) / denom);
        dto.negXNegY60Collision_norm = clamp01(((double) negXNegY60) / denom);
        dto.posXNegY60Collision_norm = clamp01(((double) posXNegY60) / denom);
        dto.posXNegY45Collision_norm = clamp01(((double) posXNegY45) / denom);
        dto.posXNegY30Collision_norm = clamp01(((double) posXNegY30) / denom);

        int fwdFloorDelta = parseIntSafe(collisions.fwdFloorDelta, 0);
        int fwdRightFloorDelta = parseIntSafe(collisions.fwdRightFloorDelta, 0);
        int rightFloorDelta = parseIntSafe(collisions.rightFloorDelta, 0);
        int backRightFloorDelta = parseIntSafe(collisions.backRightFloorDelta, 0);
        int backFloorDelta = parseIntSafe(collisions.backFloorDelta, 0);
        int backLeftFloorDelta = parseIntSafe(collisions.backLeftFloorDelta, 0);
        int leftFloorDelta = parseIntSafe(collisions.leftFloorDelta, 0);
        int fwdLeftFloorDelta = parseIntSafe(collisions.fwdLeftFloorDelta, 0);

        dto.fwdFloorDelta = fwdFloorDelta;
        dto.fwdRightFloorDelta = fwdRightFloorDelta;
        dto.rightFloorDelta = rightFloorDelta;
        dto.backRightFloorDelta = backRightFloorDelta;
        dto.backFloorDelta = backFloorDelta;
        dto.backLeftFloorDelta = backLeftFloorDelta;
        dto.leftFloorDelta = leftFloorDelta;
        dto.fwdLeftFloorDelta = fwdLeftFloorDelta;

        // Signed normalization tanh(delta / FLOOR_DELTA_TANH_SCALE_UU): fine resolution in the
        // decision band (~0-128 uu: small drops vs jumpable step-ups), saturating to ±1 for
        // void (deep drop) / wall (tall step). Negative = drop, positive = step-up.
        dto.fwdFloorDelta_norm = signedTanh(fwdFloorDelta);
        dto.fwdRightFloorDelta_norm = signedTanh(fwdRightFloorDelta);
        dto.rightFloorDelta_norm = signedTanh(rightFloorDelta);
        dto.backRightFloorDelta_norm = signedTanh(backRightFloorDelta);
        dto.backFloorDelta_norm = signedTanh(backFloorDelta);
        dto.backLeftFloorDelta_norm = signedTanh(backLeftFloorDelta);
        dto.leftFloorDelta_norm = signedTanh(leftFloorDelta);
        dto.fwdLeftFloorDelta_norm = signedTanh(fwdLeftFloorDelta);

        // Foot-height low rays — linear distance norm like the chest-height fan (/maxDist).
        int fwdLow = parseIntSafe(collisions.fwdLowCollision, maxDist);
        int fwdRightLow = parseIntSafe(collisions.fwdRightLowCollision, maxDist);
        int rightLow = parseIntSafe(collisions.rightLowCollision, maxDist);
        int backRightLow = parseIntSafe(collisions.backRightLowCollision, maxDist);
        int backLow = parseIntSafe(collisions.backLowCollision, maxDist);
        int backLeftLow = parseIntSafe(collisions.backLeftLowCollision, maxDist);
        int leftLow = parseIntSafe(collisions.leftLowCollision, maxDist);
        int fwdLeftLow = parseIntSafe(collisions.fwdLeftLowCollision, maxDist);

        dto.fwdLowCollision = fwdLow;
        dto.fwdRightLowCollision = fwdRightLow;
        dto.rightLowCollision = rightLow;
        dto.backRightLowCollision = backRightLow;
        dto.backLowCollision = backLow;
        dto.backLeftLowCollision = backLeftLow;
        dto.leftLowCollision = leftLow;
        dto.fwdLeftLowCollision = fwdLeftLow;

        dto.fwdLowCollision_norm = clamp01(((double) fwdLow) / denom);
        dto.fwdRightLowCollision_norm = clamp01(((double) fwdRightLow) / denom);
        dto.rightLowCollision_norm = clamp01(((double) rightLow) / denom);
        dto.backRightLowCollision_norm = clamp01(((double) backRightLow) / denom);
        dto.backLowCollision_norm = clamp01(((double) backLow) / denom);
        dto.backLeftLowCollision_norm = clamp01(((double) backLeftLow) / denom);
        dto.leftLowCollision_norm = clamp01(((double) leftLow) / denom);
        dto.fwdLeftLowCollision_norm = clamp01(((double) fwdLeftLow) / denom);

        // Egocentric vertical probes — strikt onder/boven de bot capsule. Lineaire
        // normalisatie met aparte denominators per richting: floor concentreert
        // signaal in lift/drop range (0-1024 UU); ceiling op headroom range
        // (0-512 UU). Past de gameplay-distributies waar deze signalen het meest
        // beslissend zijn (jump-timing, rocket-jump viability, mid-air awareness).
        int floorBelow = parseIntSafe(collisions.floorBelow, 0);
        int ceilingAbove = parseIntSafe(collisions.ceilingAbove, 0);
        dto.floorBelow = floorBelow;
        dto.ceilingAbove = ceilingAbove;
        dto.floorBelow_norm = clamp01(((double) floorBelow) / 1024.0);
        dto.ceilingAbove_norm = clamp01(((double) ceilingAbove) / 512.0);

        return dto;
    }

    private static int parseIntSafe(String s, int d) {
        if (s == null) {
            return d;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return d;
        }
        int start = (trimmed.charAt(0) == '-' || trimmed.charAt(0) == '+') ? 1 : 0;
        boolean integerLike = start < trimmed.length();
        for (int i = start; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < '0' || ch > '9') {
                integerLike = false;
                break;
            }
        }
        if (integerLike) {
            try {
                return Integer.parseInt(trimmed);
            } catch (Exception ignore) {
                return d;
            }
        }
        try {
            return (int) Math.round(Double.parseDouble(trimmed));
        } catch (Exception ignore) {
            return d;
        }
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

    /** Signed normalization for floor-elevation: tanh(uu / scale). Negative = drop, positive
     *  = step-up; saturates to ±1 for void/wall. */
    private static double signedTanh(int uu) {
        return Math.tanh(((double) uu) / FLOOR_DELTA_TANH_SCALE_UU);
    }
}
