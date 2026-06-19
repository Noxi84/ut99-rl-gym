package aiplay.dto;

/**
 * Runtime mover state (lifts, doors, platforms), one entry per UC Mover actor.
 * Combined with static map data from {@code resources/config/maps/<map>.json#movers[]}
 * by MoverEnricher to produce egocentric spatial features.
 */
public class MoverDto {
    /** FNV-1a hash of the mover's Name property — stable identity per map. */
    public int nameHash;
    public double locX;
    public double locY;
    public double locZ;
    public int keyNum;
    public int prevKeyNum;
    public int numKeys;
    public boolean opening;
    public boolean delaying;
    /** PhysAlpha 0.0–1.0: interpolation progress between keyframes. */
    public double moveProgress;

    // Static map data (populated by MoverEnricher from map JSON)
    /** Key positions from map JSON. null if no static match found. */
    public double[][] keyPositions;
    /** Platform AABB min in local coords. null if no static match found. */
    public double[] platformBoundsMin;
    /** Platform AABB max in local coords. null if no static match found. */
    public double[] platformBoundsMax;
    /** Seconds between keyframes. */
    public double moveTime;
    /** Seconds to stay open before returning (timed movers). */
    public double stayOpenTime;

    // Egocentric spatial relations (populated by MoverEnricher)
    public double relSin;
    public double relCos;
    public double distanceNorm;
    public double forwardDistNorm;
    public double rightDistNorm;
    public double zOffsetNorm;
    public boolean onPlatform;
    public double destZOffsetNorm;
    public double destDistanceNorm;
    public double timeToArriveNorm;
    public double travelRangeNorm;

    public MoverDto deepCopy() {
        MoverDto c = new MoverDto();
        c.nameHash = this.nameHash;
        c.locX = this.locX;
        c.locY = this.locY;
        c.locZ = this.locZ;
        c.keyNum = this.keyNum;
        c.prevKeyNum = this.prevKeyNum;
        c.numKeys = this.numKeys;
        c.opening = this.opening;
        c.delaying = this.delaying;
        c.moveProgress = this.moveProgress;
        c.keyPositions = this.keyPositions;
        c.platformBoundsMin = this.platformBoundsMin;
        c.platformBoundsMax = this.platformBoundsMax;
        c.moveTime = this.moveTime;
        c.stayOpenTime = this.stayOpenTime;
        c.relSin = this.relSin;
        c.relCos = this.relCos;
        c.distanceNorm = this.distanceNorm;
        c.forwardDistNorm = this.forwardDistNorm;
        c.rightDistNorm = this.rightDistNorm;
        c.zOffsetNorm = this.zOffsetNorm;
        c.onPlatform = this.onPlatform;
        c.destZOffsetNorm = this.destZOffsetNorm;
        c.destDistanceNorm = this.destDistanceNorm;
        c.timeToArriveNorm = this.timeToArriveNorm;
        c.travelRangeNorm = this.travelRangeNorm;
        return c;
    }
}
