package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * This model should be in the same format as the json output from ut99 neuralnet webserver http://127.0.0.1:8080/utneuralnet/
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapInfo {
    public String MapName;
    public String LevelTitle;
    public String GameName;
    public String GameClass;
    public String TimeLimit;
    public String GameType;
    public String RedScore;
    public String BlueScore;
    public String RemainingTime;
    public String ElapsedTime;
    public String bGameEnded;
}
