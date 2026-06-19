package aiplay.play.udpstate.model;

/**
 * Active weapon block. Component order matches the byte order written by
 * {@code RLUdpStateSender.uc:WriteWeapon()} / read by {@code StateFrameCodec.parseWeapon()}.
 */
public record Weapon(
        int classHash,
        int ammo,
        int maxAmmo,
        int altDamageHash,
        double fireOffsetX, double fireOffsetY, double fireOffsetZ,
        float firingSpeed,
        float maxTargetRange,
        int myDamageHash,
        int pickupAmmo,
        int flags,
        // weaponFlags2 bit0=bIsDual, bit1=bSniping, bit2=bGrenadeMode, bit3=bTightWad.
        int flags2,
        // UT_Eightball loadcount 0..6 (0 voor andere wapens).
        int multiCount,
        // UT_BioRifle ChargeSize 0..255 (0 voor andere wapens).
        int chargeAmount) {

    public boolean isInstantHit()     { return (flags & 1)  != 0; }
    public boolean isAltInstantHit()  { return (flags & 2)  != 0; }
    public boolean canThrow()         { return (flags & 4)  != 0; }
    public boolean changeWeapon()     { return (flags & 8)  != 0; }
    public boolean lockedOn()         { return (flags & 16) != 0; }
    public boolean weaponStay()       { return (flags & 32) != 0; }
    public boolean weaponUp()         { return (flags & 64) != 0; }
    public boolean isEmpty()          { return classHash == 0; }
    public boolean isDual()           { return (flags2 & 1)  != 0; }
    public boolean isSniping()        { return (flags2 & 2)  != 0; }
    public boolean isGrenadeMode()    { return (flags2 & 4)  != 0; }
    /** UT_Eightball bTightWad: altFire was ingedrukt tijdens primary-load
     *  → bij firing wordt de rocket-straal gefocust (één punt) i.p.v.
     *  horizontaal spread. Alleen relevant voor Eightball. */
    public boolean isTightWad()       { return (flags2 & 8)  != 0; }
}
