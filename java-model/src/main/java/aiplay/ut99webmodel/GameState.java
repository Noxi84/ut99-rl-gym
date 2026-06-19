package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * This model should be in the same format as the json output from ut99 neuralnet webserver http://127.0.0.1:8080/utneuralnet/
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameState {
    public MapInfo MapInfo;
    public List<Flag> Flags;
    public List<Player> Players;
    public List<ProjectileEntry> Projectiles;
    public List<PickupEntry> Pickups;
    public List<MoverEntry> Movers;
    public KeyboardCaptureData KeyboardCapture;
    public long timestampMillis;
}
