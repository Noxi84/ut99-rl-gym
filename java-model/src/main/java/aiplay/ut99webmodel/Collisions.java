package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents collision data for a player or flag.
 * Values are strings because UT99 JSON is emitted as strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Collisions {

    // metadata (sent by UnrealScript)
    public String maxDist;          // e.g. "1200"
    public String stepCoarse;       // e.g. "32"
    public String capsuleMargin;    // e.g. "3.0"
    public String immediateProbeUu; // e.g. "8"
    public String floorProbeDist;   // horizontal ledge-probe distance in uu
    public String floorMaxDrop;     // saturated downward probe distance in uu
    public String verticalMaxDist;  // saturation distance for floorBelow/ceilingAbove probes (uu)

    // yaw-relative raw distances (uu)
    public String fwd_collision;
    public String back_collision;
    public String left_collision;
    public String right_collision;

    // world-axis raw distances (uu)
    // +X = East, -X = West, +Y = North, -Y = South (consistent world axes)
    public String posX_collision;
    public String negX_collision;
    public String posY_collision;
    public String negY_collision;

    // diagonal yaw-relative raw distances (uu)
    public String fwdRight30_collision;
    public String fwdRight45_collision;
    public String fwdRight60_collision;
    public String backRight60_collision;
    public String backRight45_collision;
    public String backRight30_collision;
    public String backLeft30_collision;
    public String backLeft45_collision;
    public String backLeft60_collision;
    public String fwdLeft60_collision;
    public String fwdLeft45_collision;
    public String fwdLeft30_collision;

    // diagonal world-axis raw distances (uu)
    public String posXPosY30_collision;
    public String posXPosY45_collision;
    public String posXPosY60_collision;
    public String negXPosY60_collision;
    public String negXPosY45_collision;
    public String negXPosY30_collision;
    public String negXNegY30_collision;
    public String negXNegY45_collision;
    public String negXNegY60_collision;
    public String posXNegY60_collision;
    public String posXNegY45_collision;
    public String posXNegY30_collision;

    // yaw-relative floor-elevation fan (raw signed delta in uu; negative = drop, positive =
    // step-up/jumpable threshold, saturated high = wall)
    public String fwdFloorDelta;
    public String fwdRightFloorDelta;
    public String rightFloorDelta;
    public String backRightFloorDelta;
    public String backFloorDelta;
    public String backLeftFloorDelta;
    public String leftFloorDelta;
    public String fwdLeftFloorDelta;

    // yaw-relative foot-height horizontal rays (raw distance in uu; same 8 directions as the
    // floor fan). Catch low obstacles the chest-height collision fan passes over.
    public String fwdLowCollision;
    public String fwdRightLowCollision;
    public String rightLowCollision;
    public String backRightLowCollision;
    public String backLowCollision;
    public String backLeftLowCollision;
    public String leftLowCollision;
    public String fwdLeftLowCollision;

    // Egocentric vertical probes — strictly down/up from the pawn capsule.
    // floorBelow: 0 = grounded, max = mid-air over deep void.
    // ceilingAbove: 0 = head pressed against ceiling, max = open sky.
    public String floorBelow;
    public String ceilingAbove;
}
