package aiplay.dto;

public class ProjectileDto {
    public String projectileClass;
    public CoordinatesDto location;
    public CoordinatesDto velocity;
    public float speed;
    public float damage;
    public String instigatorName;
    public int instigatorTeam;
    public float drawScale;

    public ProjectileDto deepCopy() {
        ProjectileDto c = new ProjectileDto();
        c.projectileClass = this.projectileClass;
        if (this.location != null) c.location = this.location.deepCopy();
        if (this.velocity != null) c.velocity = this.velocity.deepCopy();
        c.speed = this.speed;
        c.damage = this.damage;
        c.instigatorName = this.instigatorName;
        c.instigatorTeam = this.instigatorTeam;
        c.drawScale = this.drawScale;
        return c;
    }
}
