package aiplay.dto;

public class CollisionsDto {

    // yaw-relative
    public int fwdCollision;
    public int backCollision;
    public int leftCollision;
    public int rightCollision;

    public double fwdCollision_norm;
    public double backCollision_norm;
    public double leftCollision_norm;
    public double rightCollision_norm;

    // world-axis
    public int posXCollision;
    public int negXCollision;
    public int posYCollision;
    public int negYCollision;

    public double posXCollision_norm;
    public double negXCollision_norm;
    public double posYCollision_norm;
    public double negYCollision_norm;

    // diagonal yaw-relative
    public int fwdRight30Collision;
    public int fwdRight45Collision;
    public int fwdRight60Collision;
    public int backRight60Collision;
    public int backRight45Collision;
    public int backRight30Collision;
    public int backLeft30Collision;
    public int backLeft45Collision;
    public int backLeft60Collision;
    public int fwdLeft60Collision;
    public int fwdLeft45Collision;
    public int fwdLeft30Collision;

    public double fwdRight30Collision_norm;
    public double fwdRight45Collision_norm;
    public double fwdRight60Collision_norm;
    public double backRight60Collision_norm;
    public double backRight45Collision_norm;
    public double backRight30Collision_norm;
    public double backLeft30Collision_norm;
    public double backLeft45Collision_norm;
    public double backLeft60Collision_norm;
    public double fwdLeft60Collision_norm;
    public double fwdLeft45Collision_norm;
    public double fwdLeft30Collision_norm;

    // diagonal world-axis
    public int posXPosY30Collision;
    public int posXPosY45Collision;
    public int posXPosY60Collision;
    public int negXPosY60Collision;
    public int negXPosY45Collision;
    public int negXPosY30Collision;
    public int negXNegY30Collision;
    public int negXNegY45Collision;
    public int negXNegY60Collision;
    public int posXNegY60Collision;
    public int posXNegY45Collision;
    public int posXNegY30Collision;

    public double posXPosY30Collision_norm;
    public double posXPosY45Collision_norm;
    public double posXPosY60Collision_norm;
    public double negXPosY60Collision_norm;
    public double negXPosY45Collision_norm;
    public double negXPosY30Collision_norm;
    public double negXNegY30Collision_norm;
    public double negXNegY45Collision_norm;
    public double negXNegY60Collision_norm;
    public double posXNegY60Collision_norm;
    public double posXNegY45Collision_norm;
    public double posXNegY30Collision_norm;

    // yaw-relative floor-elevation fan. Raw signed delta in uu (negative = drop, positive =
    // step-up). _norm is signed tanh(delta/64): negative = drop, positive = jumpable step-up,
    // saturated ±1 = void/wall.
    public int fwdFloorDelta;
    public int fwdRightFloorDelta;
    public int rightFloorDelta;
    public int backRightFloorDelta;
    public int backFloorDelta;
    public int backLeftFloorDelta;
    public int leftFloorDelta;
    public int fwdLeftFloorDelta;

    public double fwdFloorDelta_norm;
    public double fwdRightFloorDelta_norm;
    public double rightFloorDelta_norm;
    public double backRightFloorDelta_norm;
    public double backFloorDelta_norm;
    public double backLeftFloorDelta_norm;
    public double leftFloorDelta_norm;
    public double fwdLeftFloorDelta_norm;

    // yaw-relative foot-height horizontal rays (same 8 directions as the floor fan). Raw
    // distance in uu; _norm = distance/maxDist clamped [0,1]. Catch low obstacles the
    // chest-height collision fan passes over.
    public int fwdLowCollision;
    public int fwdRightLowCollision;
    public int rightLowCollision;
    public int backRightLowCollision;
    public int backLowCollision;
    public int backLeftLowCollision;
    public int leftLowCollision;
    public int fwdLeftLowCollision;

    public double fwdLowCollision_norm;
    public double fwdRightLowCollision_norm;
    public double rightLowCollision_norm;
    public double backRightLowCollision_norm;
    public double backLowCollision_norm;
    public double backLeftLowCollision_norm;
    public double leftLowCollision_norm;
    public double fwdLeftLowCollision_norm;

    // Egocentric vertical probes — strictly down/up from the pawn capsule.
    // floorBelow: 0 = grounded, max = mid-air over deep void.
    // ceilingAbove: 0 = head against ceiling, max = open sky.
    public int floorBelow;
    public int ceilingAbove;
    public double floorBelow_norm;
    public double ceilingAbove_norm;

    public CollisionsDto deepCopy() {
        CollisionsDto c = new CollisionsDto();
        c.fwdCollision = this.fwdCollision;
        c.backCollision = this.backCollision;
        c.leftCollision = this.leftCollision;
        c.rightCollision = this.rightCollision;

        c.fwdCollision_norm = this.fwdCollision_norm;
        c.backCollision_norm = this.backCollision_norm;
        c.leftCollision_norm = this.leftCollision_norm;
        c.rightCollision_norm = this.rightCollision_norm;

        c.posXCollision = this.posXCollision;
        c.negXCollision = this.negXCollision;
        c.posYCollision = this.posYCollision;
        c.negYCollision = this.negYCollision;

        c.posXCollision_norm = this.posXCollision_norm;

        c.negXCollision_norm = this.negXCollision_norm;

        c.posYCollision_norm = this.posYCollision_norm;

        c.negYCollision_norm = this.negYCollision_norm;

        c.fwdRight30Collision = this.fwdRight30Collision;
        c.fwdRight45Collision = this.fwdRight45Collision;
        c.fwdRight60Collision = this.fwdRight60Collision;
        c.backRight60Collision = this.backRight60Collision;
        c.backRight45Collision = this.backRight45Collision;
        c.backRight30Collision = this.backRight30Collision;
        c.backLeft30Collision = this.backLeft30Collision;
        c.backLeft45Collision = this.backLeft45Collision;
        c.backLeft60Collision = this.backLeft60Collision;
        c.fwdLeft60Collision = this.fwdLeft60Collision;
        c.fwdLeft45Collision = this.fwdLeft45Collision;
        c.fwdLeft30Collision = this.fwdLeft30Collision;

        c.fwdRight30Collision_norm = this.fwdRight30Collision_norm;
        c.fwdRight45Collision_norm = this.fwdRight45Collision_norm;
        c.fwdRight60Collision_norm = this.fwdRight60Collision_norm;
        c.backRight60Collision_norm = this.backRight60Collision_norm;
        c.backRight45Collision_norm = this.backRight45Collision_norm;
        c.backRight30Collision_norm = this.backRight30Collision_norm;
        c.backLeft30Collision_norm = this.backLeft30Collision_norm;
        c.backLeft45Collision_norm = this.backLeft45Collision_norm;
        c.backLeft60Collision_norm = this.backLeft60Collision_norm;
        c.fwdLeft60Collision_norm = this.fwdLeft60Collision_norm;
        c.fwdLeft45Collision_norm = this.fwdLeft45Collision_norm;
        c.fwdLeft30Collision_norm = this.fwdLeft30Collision_norm;

        c.posXPosY30Collision = this.posXPosY30Collision;
        c.posXPosY45Collision = this.posXPosY45Collision;
        c.posXPosY60Collision = this.posXPosY60Collision;
        c.negXPosY60Collision = this.negXPosY60Collision;
        c.negXPosY45Collision = this.negXPosY45Collision;
        c.negXPosY30Collision = this.negXPosY30Collision;
        c.negXNegY30Collision = this.negXNegY30Collision;
        c.negXNegY45Collision = this.negXNegY45Collision;
        c.negXNegY60Collision = this.negXNegY60Collision;
        c.posXNegY60Collision = this.posXNegY60Collision;
        c.posXNegY45Collision = this.posXNegY45Collision;
        c.posXNegY30Collision = this.posXNegY30Collision;

        c.posXPosY30Collision_norm = this.posXPosY30Collision_norm;
        c.posXPosY45Collision_norm = this.posXPosY45Collision_norm;
        c.posXPosY60Collision_norm = this.posXPosY60Collision_norm;
        c.negXPosY60Collision_norm = this.negXPosY60Collision_norm;
        c.negXPosY45Collision_norm = this.negXPosY45Collision_norm;
        c.negXPosY30Collision_norm = this.negXPosY30Collision_norm;
        c.negXNegY30Collision_norm = this.negXNegY30Collision_norm;
        c.negXNegY45Collision_norm = this.negXNegY45Collision_norm;
        c.negXNegY60Collision_norm = this.negXNegY60Collision_norm;
        c.posXNegY60Collision_norm = this.posXNegY60Collision_norm;
        c.posXNegY45Collision_norm = this.posXNegY45Collision_norm;
        c.posXNegY30Collision_norm = this.posXNegY30Collision_norm;

        c.fwdFloorDelta = this.fwdFloorDelta;
        c.fwdRightFloorDelta = this.fwdRightFloorDelta;
        c.rightFloorDelta = this.rightFloorDelta;
        c.backRightFloorDelta = this.backRightFloorDelta;
        c.backFloorDelta = this.backFloorDelta;
        c.backLeftFloorDelta = this.backLeftFloorDelta;
        c.leftFloorDelta = this.leftFloorDelta;
        c.fwdLeftFloorDelta = this.fwdLeftFloorDelta;

        c.fwdFloorDelta_norm = this.fwdFloorDelta_norm;
        c.fwdRightFloorDelta_norm = this.fwdRightFloorDelta_norm;
        c.rightFloorDelta_norm = this.rightFloorDelta_norm;
        c.backRightFloorDelta_norm = this.backRightFloorDelta_norm;
        c.backFloorDelta_norm = this.backFloorDelta_norm;
        c.backLeftFloorDelta_norm = this.backLeftFloorDelta_norm;
        c.leftFloorDelta_norm = this.leftFloorDelta_norm;
        c.fwdLeftFloorDelta_norm = this.fwdLeftFloorDelta_norm;

        c.fwdLowCollision = this.fwdLowCollision;
        c.fwdRightLowCollision = this.fwdRightLowCollision;
        c.rightLowCollision = this.rightLowCollision;
        c.backRightLowCollision = this.backRightLowCollision;
        c.backLowCollision = this.backLowCollision;
        c.backLeftLowCollision = this.backLeftLowCollision;
        c.leftLowCollision = this.leftLowCollision;
        c.fwdLeftLowCollision = this.fwdLeftLowCollision;

        c.fwdLowCollision_norm = this.fwdLowCollision_norm;
        c.fwdRightLowCollision_norm = this.fwdRightLowCollision_norm;
        c.rightLowCollision_norm = this.rightLowCollision_norm;
        c.backRightLowCollision_norm = this.backRightLowCollision_norm;
        c.backLowCollision_norm = this.backLowCollision_norm;
        c.backLeftLowCollision_norm = this.backLeftLowCollision_norm;
        c.leftLowCollision_norm = this.leftLowCollision_norm;
        c.fwdLeftLowCollision_norm = this.fwdLeftLowCollision_norm;

        c.floorBelow = this.floorBelow;
        c.ceilingAbove = this.ceilingAbove;
        c.floorBelow_norm = this.floorBelow_norm;
        c.ceilingAbove_norm = this.ceilingAbove_norm;

        return c;
    }
}
