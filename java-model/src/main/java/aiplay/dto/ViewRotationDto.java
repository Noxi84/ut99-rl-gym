package aiplay.dto;

public class ViewRotationDto {

  /**
   * UT raw rotation units (wrap): meestal 0..65535/65536
   */
  public int x;
  public int y;

  /**
   * Normalized features already used
   */
  public double x_sin;
  public double x_cos;
  public double y_norm;

  public ViewRotationDto deepCopy() {
    ViewRotationDto v = new ViewRotationDto();
    v.x = this.x;
    v.y = this.y;
    v.x_sin = this.x_sin;
    v.x_cos = this.x_cos;
    v.y_norm = this.y_norm;

    return v;
  }
}
