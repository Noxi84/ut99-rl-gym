package aiplay.dto;

public class SpawnPointDto {
    public int team;
    public CoordinatesDto location;

    public SpawnPointDto deepCopy() {
        SpawnPointDto c = new SpawnPointDto();
        c.team = this.team;
        if (this.location != null) c.location = this.location.deepCopy();
        return c;
    }
}
