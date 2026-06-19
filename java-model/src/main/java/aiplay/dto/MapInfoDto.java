package aiplay.dto;

public class MapInfoDto {
    public String mapName;
    public String levelTitle;
    public String gameName;
    public String gameClass;
    public double timeLimit;
    public String gameType;
    public String redScore;
    public String blueScore;

    public double remainingTime;
    public double elapsedTime;

    public boolean bGameEnded;

    public MapInfoDto deepCopy() {
        MapInfoDto copy = new MapInfoDto();
        copy.mapName = this.mapName;
        copy.levelTitle = this.levelTitle;
        copy.gameName = this.gameName;
        copy.gameClass = this.gameClass;
        copy.timeLimit = this.timeLimit;
        copy.gameType = this.gameType;
        copy.redScore = this.redScore;
        copy.blueScore = this.blueScore;
        copy.remainingTime = this.remainingTime;
        copy.elapsedTime = this.elapsedTime;
        copy.bGameEnded = this.bGameEnded;
        return copy;
    }

}
