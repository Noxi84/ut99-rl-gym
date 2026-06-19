package aiplay.ut99webmodel;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ShockRifle {
    public String hitDamage;
    public String tapTime;
    public Tracked tracked;
    public String bBotSpecialMove;
}
