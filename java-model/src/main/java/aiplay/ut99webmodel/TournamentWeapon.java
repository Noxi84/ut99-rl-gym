package aiplay.ut99webmodel;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TournamentWeapon {
    public String bCanClientFire;
    public ShockRifle ShockRifle;
}
