package aiplay.dto;

public class InventoryItemDto {
    public String weaponClass;
    public int ammoAmount;
    public int maxAmmo;

    public InventoryItemDto deepCopy() {
        InventoryItemDto c = new InventoryItemDto();
        c.weaponClass = this.weaponClass;
        c.ammoAmount = this.ammoAmount;
        c.maxAmmo = this.maxAmmo;
        return c;
    }
}
