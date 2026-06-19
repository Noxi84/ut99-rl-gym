package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Tracked {
    public String damage;
    public String exploWallOut;
    public String explosionDecal;
    public String maxSpeed;
    public String momentumTransfer;
    public String myDamageType;
    public String speed;
}
