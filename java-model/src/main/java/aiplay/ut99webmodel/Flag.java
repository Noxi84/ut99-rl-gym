package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Flag {

    public String Team;
    public String Status;            // "home" | "carried" | "dropped"
    public String Location;          // "x,y,z"
    public String HomeBaseLocation;  // "x,y,z"
    public String bHome;             // "True"/"False"
    public String HasHolder;         // "True"/"False"
    public String HolderName;        // "" of spelernaam

    /** UC slot van de speler die deze vlag het laatst returnde (CTFGame.ScoreFlag own-team
     *  branch). "-1" voor auto-returns of geen recente return. Drained per state-frame. */
    public String LastReturnInstigatorSlot;

    public Collisions Collisions;
}
