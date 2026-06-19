package aiplay.dto;

public class CoordinatesDto {

    public double x;
    public double y;
    public double z;

    public double x_norm;
    public double y_norm;
    public double z_norm;

    public CoordinatesDto deepCopy() {
        CoordinatesDto c = new CoordinatesDto();
        c.x = this.x;
        c.y = this.y;
        c.z = this.z;
        c.x_norm = this.x_norm;
        c.y_norm = this.y_norm;
        c.z_norm = this.z_norm;
        return c;
    }
}
