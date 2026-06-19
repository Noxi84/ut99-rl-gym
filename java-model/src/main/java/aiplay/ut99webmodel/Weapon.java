package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * This model should be in the same format as the json output from ut99 neuralnet webserver http://127.0.0.1:8080/utneuralnet/
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Weapon {
    public String WeaponClass;
    public String AmmoAmount;
    public String MaxAmmo;
    public String AltDamageType;
    public String FireOffSet;
    public String FiringSpeed;
    public String MaxTargetRange;
    public String MyDamageType;
    public String PickupAmmoCount;
    public String bInstantHit;
    public String bAltInstantHit;
    public String bCanThrow;
    public String bChangeWeapon;
    public String bLockedOn;
    public String bWeaponStay;
    public String bWeaponUp;
    public SubWeapon SubWeapon;
    /** Enforcer.bIsDual — dual-wield (stock SpawnCopy flip, geen aparte class). */
    public String bIsDual;
    /** Sniper-scope actief (Pawn.FOVAngle &lt; DefaultFOV * 0.8). */
    public String bSniping;
    /** UT_Eightball alt-fire grenade-mode. */
    public String bGrenadeMode;
    /** UT_Eightball bTightWad — altFire was ingedrukt tijdens primary-load
     *  → bij firing wordt de rocket-straal gefocust (één punt) i.p.v.
     *  horizontaal spread. */
    public String bTightWad;
    /** UT_Eightball loadcount 0..6. */
    public String MultiCount;
    /** UT_BioRifle alt-fire ChargeSize 0..255. */
    public String ChargeAmount;
}
