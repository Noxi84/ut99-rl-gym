package aiplay.ut99webmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * This model should be in the same format as the json output from ut99 neuralnet webserver http://127.0.0.1:8080/utneuralnet/
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Player {
    public String Name;
    public String Location;
    public String BaseEyeHeight;
    public String ViewRotation;
    public String Health;
    /** Totaal armor-charge (UC: som van Inventory.Charge waar ArmorAbsorption > 0). */
    public String Armor;
    public String Team;
    public String Score;
    public String Deaths;
    public String HasFlag;
    public String OldLocation;
    public String bDuck;
    public String bFeigningDeath;
    public String bFire;
    public String bAltFire;
    public String Velocity;
    public String Acceleration;
    public String DodgeState;

    /**
     * UT99 physics state. Integer encoded as string. Mapping (from UScript):
     * 0=PHYS_None, 1=PHYS_Walking, 2=PHYS_Falling, 3=PHYS_Swimming, 4=PHYS_Flying,
     * 5=PHYS_Rotating, 6=PHYS_Projectile, 7=PHYS_Interpolating, 8=PHYS_MovingBrush,
     * 9=PHYS_Spider, 10=PHYS_Trailer, 11=PHYS_Ladder, 12=PHYS_RootMotion.
     * Pawn states relevant for AI: Walking, Falling, Swimming.
     */
    public String Physics;
    public Weapon Weapon;
    public List<InventoryWeapon> Inventory;

    public Collisions Collisions;
    public List<PlayerVisibilityEntry> Visibility;

    public String bIsSpectator;
    public String bIsABot;
    public String bIsRLControlled;
    public String bWaitingPlayer;

    /** UC slot index (0..MAX_SLOTS-1) — stable identity across frames; "-1" if not assigned. */
    public String Slot;

    public FlagLineOfSight FlagLineOfSight;

    // Damage-event block: present on the frame a TakeDamage fires, absent on others.
    // Used to distinguish self-inflicted flak/grenade damage from enemy damage in the
    // reward system.
    public String LastDamageAmount;          // "0" when no event this frame
    public String LastDamageType;            // UnrealScript damage name (e.g. "shredded")
    public String LastDamageSelfInflicted;   // "True" / "False"
    public String LastDamageInstigatorSlot;  // "-1" if unknown

    // KPI counters (Plan A/B/D2): monotonisch oplopende totals per match.
    // Strings voor consistentie met de overige webmodel-velden; geparsed door
    // *FeatureJsonToDtoConverter naar int op PlayerDto. Default "0" wanneer niet
    // beschikbaar (legacy .u zonder counters).
    public String Frags;            // ScoreKill events (suicides excluded)
    public String FlagsTaken;       // HasFlag rising-edge per pawn
    public String FlagsCaptured;    // ScoreFlag capture-branch per scorer
    public String FlagsReturned;    // ScoreFlag own-team-touch per scorer
    public String Shots;            // Fire/altFire onsets (rising-edge)
    public String ShotsOnTarget;    // Subset van Shots waar aim_dot > AIM_DOT_THRESHOLD
    public String DamageDealtTotal; // HP geslagen door deze speler (excl. self)
    public String DamageTakenTotal; // HP geleden door deze speler (incl. self)

    /** Translocator-disc: true wanneer er een TranslocatorTarget-actor in flight is. */
    public String bDiscPresent;
    /** "x,y,z" wereld-coordinaten van de actieve disc; "0,0,0" wanneer geen disc. */
    public String DiscLocation;

    /** "True"/"False": hoofd volledig onder water (HeadRegion in een water-zone) → ademt af / verdrinkt. */
    public String bHeadUnderwater;
    /** "0".."1": resterende adem (PainTime/UnderWaterTime); 1.0 = volle longen / boven water. */
    public String BreathRemaining;
}
