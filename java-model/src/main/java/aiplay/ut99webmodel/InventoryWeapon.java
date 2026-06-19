package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InventoryWeapon {
    public String WeaponClass;
    public String AmmoAmount;
    public String MaxAmmo;
}
