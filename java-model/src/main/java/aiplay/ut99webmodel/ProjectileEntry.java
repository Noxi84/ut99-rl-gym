package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectileEntry {
    public String Class;
    public String Location;
    public String Velocity;
    public String Speed;
    public String Damage;
    public String InstigatorName;
    public String InstigatorTeam;
    /** UC Actor.DrawScale. Default ~1.0; charged BioGlob alt-fire scales tot ~4.0. */
    public String DrawScale;
}
