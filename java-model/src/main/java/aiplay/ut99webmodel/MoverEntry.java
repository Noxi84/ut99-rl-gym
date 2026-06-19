package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Runtime mover state, one entry per Mover actor from UC. TLV tag 0x06.
 *
 * <p>Matching to static map data ({@code resources/config/maps/<map>.json#movers[]})
 * uses {@code NameHash} → FNV1a of the actor's Name property (stable per map).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MoverEntry {
    public String NameHash;
    public String Location;
    public int KeyNum;
    public int PrevKeyNum;
    public int NumKeys;
    public boolean Opening;
    public boolean Delaying;
    public double MoveProgress;
}
