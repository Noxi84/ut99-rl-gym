package aiplay.dto;

import java.util.ArrayList;
import java.util.List;

public class PlayerDto {
    public String name;
    public CoordinatesDto location;
    public double baseEyeHeight;
    public ViewRotationDto viewRotation;
    public int health;
    /** Totaal armor-charge (Σ Inventory.Charge waar ArmorAbsorption > 0). */
    public int armor;
    public int team;
    public int score;
    public int deaths;
    public boolean hasFlag;
    public float hasFlag_norm;

    public CoordinatesDto oldLocation;
    public boolean bFeigningDeath;

    public KeyboardMoveDto bDuck;
    public KeyboardMoveDto bFire;
    public KeyboardMoveDto bAltFire;
    public boolean fireKilledEnemy;

    public PlayerPawnDto playerPawn;

    public DodgeState dodgeState = DodgeState.NONE;
    /** UT99 Pawn physics state (Walking/Falling/Swimming/...). Drives airborne/ground awareness. */
    public Ut99PhysicsType physics = Ut99PhysicsType.NONE;

    /** True when the head is fully submerged (HeadRegion in a water zone) — breath drains, drowning ticks. */
    public boolean headUnderwater;
    /** Remaining breath in [0,1] (PainTime/UnderWaterTime). 1.0 = full lungs / above water. */
    public float breathRemaining = 1.0f;
    /** Dodge direction tracked through ACTIVE phase. Set by DodgeDirTrackingEnricher. 0=none, 1=fwd, 2=back, 3=left, 4=right */
    public int activeDodgeDir = 0;
    public double speed;
    public float speed_norm;
    public float velocityX_norm;
    public float velocityY_norm;
    public float velocityZ_norm;
    public float forwardVelocity_norm;
    public float rightVelocity_norm;
    public float accelerationX_norm;
    public float accelerationY_norm;
    public float accelerationZ_norm;
    public float forwardAcceleration_norm;
    public float rightAcceleration_norm;
    public float forwardAccelVelocityMismatch_norm;
    public float rightAccelVelocityMismatch_norm;
    public float yawAngularVelocity_norm;
    public float pitchAngularVelocity_norm;
    public boolean velocityResolved;
    public boolean accelerationResolved;
    public boolean dodgeStateResolved;
    /** Continuous dodge cooldown: 0.0 = just dodged, 1.0 = fully recovered. Set by DodgeDirTrackingEnricher. */
    public float dodgeCooldownNorm = 1.0f;
    /** Continuous idle duration: 0.0 = currently moving / just stopped, 1.0 = idle for ≥ idle.duration_window_ms.
     *  Set by TimeSinceLastMoveTrackingEnricher. Lets the model condition on how long the player has been still. */
    public float timeSinceLastMoveNorm = 0.0f;

    public CollisionsDto collisions;
    public PlayerEnrichmentDto enrichments = new PlayerEnrichmentDto();

    public boolean bIsSpectator;
    public boolean bIsABot;
    public boolean bIsRLControlled; // True only for our RL-driven bots (RLBot UC subclass). UT99 native bots have bIsABot=true but bIsRLControlled=false.
    public boolean bWaitingPlayer;

    /** UC slot index (0..MAX_SLOTS-1) — stable identity across frames; -1 if not assigned.
     *  Used by damage attribution: comparing this against {@code lastDamageInstigatorSlot}
     *  on another player tells us whether *this* player caused the damage. */
    public int slot = -1;

    /** Weapon class name (e.g. "Botpack.SuperShockRifle" for instagib). */
    public String weaponClass;

    /** True if the current TournamentWeapon allows firing (cooldown expired). From UC bCanClientFire. */
    public boolean weaponCanFire;

    /** All weapons the player currently carries, with ammo counts. */
    public List<InventoryItemDto> inventory;

    /** Current weapon ammo (uit Weapon.AmmoType.AmmoAmount). 0 wanneer geen wapen. */
    public int weaponAmmo;

    /** Current weapon max ammo (Weapon.AmmoType.MaxAmmo). 0 wanneer geen wapen. */
    public int weaponMaxAmmo;

    /** Enforcer bIsDual flag — true = dual-wielded (één Botpack.Enforcer-actor
     *  met bIsDual=true; geen aparte DoubleEnforcer-class in stock UT99). */
    public boolean weaponIsDual;

    /** Sniper scope-zoom actief (Pawn.FOVAngle &lt; DefaultFOV × 0.8). */
    public boolean weaponSniping;

    /** UT_Eightball alt-fire grenade-mode (rockets → grenades). */
    public boolean weaponGrenadeMode;

    /** UT_Eightball bTightWad: altFire was ingedrukt tijdens primary-load
     *  → bij firing wordt de rocket-straal gefocust (één punt) i.p.v.
     *  horizontaal spread. Alleen relevant voor Eightball; false voor
     *  andere wapens. */
    public boolean weaponTightWad;

    /** UT_Eightball loadcount (0..6). Aantal opgeladen rockets/grenades. */
    public int weaponMultiCount;

    /** UT_BioRifle alt-fire ChargeSize (0..255, stock max ~10). */
    public int weaponChargeAmount;

    /** Fire state features (set by FireCooldownIncrementalEnricher). */
    public boolean fireActive;
    public boolean fireCooldown;
    public boolean altFireActive;
    public boolean altFireCooldown;

    /** True if the model wanted fire but was suppressed by weapon cooldown. Set by ShootingExecutorAiController. */
    public boolean fireWantedDuringCooldown;

    /** Refire-klok: genormaliseerde resterende tijd (0..1 over een 1s-horizon) tot het vastgehouden
     *  wapen weer een schot kan lossen (eigen fire/alt-cyclus + cross-mode block), gereconstrueerd
     *  uit bFire/bAltFire-flanken + WeaponFireProfile. 0 = nu schietbaar. Set by
     *  WeaponReadyIncrementalEnricher. Maakt klik-TIMING (o.a. shock-combo) observeerbaar. */
    public float weaponReadyInNorm;

    /** True if this player is visible to playerPawn via line-of-sight trace. Set by enemy converter. */
    public boolean enemyVisible;

    /** Last damage event block (populated by PlayerPawnBasicFeatureJsonToDtoConverter).
     *  Non-zero {@code lastDamageAmount} indicates a TakeDamage event fired on the frame
     *  represented by this DTO. Consumers should compare against the previous frame to
     *  detect edges. */
    public int lastDamageAmount;
    public String lastDamageType;
    public boolean lastDamageSelfInflicted;
    public int lastDamageInstigatorSlot;

    /** KPI counters (Plan A/B/D2): monotonisch oplopende totals per match.
     *  Gevuld door StateFrameToGameStateConverter vanuit binary frame; verbruikt
     *  door PlayerScoresLogger voor de DeltaGate KPI-keuzes (frags / flag-events
     *  / aim-accuracy). */
    public int frags;
    public int flagsTaken;
    public int flagsCaptured;
    public int flagsReturned;
    public int shots;
    public int shotsOnTarget;
    /** Cumulatieve outgoing damage in HP per match (excl. self-damage). */
    public int damageDealtTotal;
    /** Cumulatieve incoming damage in HP per match (incl. self). */
    public int damageTakenTotal;

    /** Translocator-disc state: true wanneer speler een TranslocatorTarget-actor
     *  uitstaan heeft (UC: foreach AllActors waar Owner == this Pawn). */
    public boolean discPresent;
    /** Wereld-coordinaten van de actieve disc; null wanneer discPresent=false. */
    public CoordinatesDto discLocation;
    /** Tijd-sinds-throw 0..1 (1 sec window). Gevuld door TranslocatorDiscTrackingEnricher
     *  via rising-edge van discPresent. */
    public float discTimeSinceThrow_norm = 1.0f;

    public PlayerDto deepCopy() {
        PlayerDto c = new PlayerDto();
        c.name = this.name;
        c.baseEyeHeight = this.baseEyeHeight;
        c.health = this.health;
        c.armor = this.armor;
        c.team = this.team;
        c.score = this.score;
        c.deaths = this.deaths;
        c.hasFlag = this.hasFlag;
        c.hasFlag_norm = this.hasFlag_norm;
        c.bFeigningDeath = this.bFeigningDeath;
        c.fireKilledEnemy = this.fireKilledEnemy;
        c.dodgeState = this.dodgeState;
        c.physics = this.physics;
        c.headUnderwater = this.headUnderwater;
        c.breathRemaining = this.breathRemaining;
        c.activeDodgeDir = this.activeDodgeDir;
        c.speed = this.speed;
        c.speed_norm = this.speed_norm;
        c.velocityX_norm = this.velocityX_norm;
        c.velocityY_norm = this.velocityY_norm;
        c.velocityZ_norm = this.velocityZ_norm;
        c.forwardVelocity_norm = this.forwardVelocity_norm;
        c.rightVelocity_norm = this.rightVelocity_norm;
        c.accelerationX_norm = this.accelerationX_norm;
        c.accelerationY_norm = this.accelerationY_norm;
        c.accelerationZ_norm = this.accelerationZ_norm;
        c.forwardAcceleration_norm = this.forwardAcceleration_norm;
        c.rightAcceleration_norm = this.rightAcceleration_norm;
        c.forwardAccelVelocityMismatch_norm = this.forwardAccelVelocityMismatch_norm;
        c.rightAccelVelocityMismatch_norm = this.rightAccelVelocityMismatch_norm;
        c.yawAngularVelocity_norm = this.yawAngularVelocity_norm;
        c.pitchAngularVelocity_norm = this.pitchAngularVelocity_norm;
        c.velocityResolved = this.velocityResolved;
        c.accelerationResolved = this.accelerationResolved;
        c.dodgeStateResolved = this.dodgeStateResolved;
        c.dodgeCooldownNorm = this.dodgeCooldownNorm;
        c.timeSinceLastMoveNorm = this.timeSinceLastMoveNorm;

        if (this.location != null) c.location = this.location.deepCopy();
        if (this.oldLocation != null) c.oldLocation = this.oldLocation.deepCopy();
        if (this.viewRotation != null) c.viewRotation = this.viewRotation.deepCopy();
        if (this.bDuck != null) c.bDuck = this.bDuck.deepCopy();
        if (this.bFire != null) c.bFire = this.bFire.deepCopy();
        if (this.bAltFire != null) c.bAltFire = this.bAltFire.deepCopy();
        if (this.playerPawn != null) c.playerPawn = this.playerPawn.deepCopy();

        if (this.collisions != null) c.collisions = this.collisions.deepCopy();

        if (this.enrichments != null) c.enrichments = this.enrichments.deepCopy();
        c.weaponClass = this.weaponClass;
        c.weaponCanFire = this.weaponCanFire;
        if (this.inventory != null) {
            c.inventory = new ArrayList<>(this.inventory.size());
            for (InventoryItemDto item : this.inventory) {
                c.inventory.add(item.deepCopy());
            }
        }
        c.weaponAmmo = this.weaponAmmo;
        c.weaponMaxAmmo = this.weaponMaxAmmo;
        c.weaponIsDual = this.weaponIsDual;
        c.weaponSniping = this.weaponSniping;
        c.weaponGrenadeMode = this.weaponGrenadeMode;
        c.weaponTightWad = this.weaponTightWad;
        c.weaponMultiCount = this.weaponMultiCount;
        c.weaponChargeAmount = this.weaponChargeAmount;
        c.fireActive = this.fireActive;
        c.fireCooldown = this.fireCooldown;
        c.altFireActive = this.altFireActive;
        c.altFireCooldown = this.altFireCooldown;
        c.fireWantedDuringCooldown = this.fireWantedDuringCooldown;
        c.bIsSpectator = this.bIsSpectator;
        c.bIsABot = this.bIsABot;
        c.bIsRLControlled = this.bIsRLControlled;
        c.bWaitingPlayer = this.bWaitingPlayer;
        c.enemyVisible = this.enemyVisible;
        c.lastDamageAmount = this.lastDamageAmount;
        c.lastDamageType = this.lastDamageType;
        c.lastDamageSelfInflicted = this.lastDamageSelfInflicted;
        c.lastDamageInstigatorSlot = this.lastDamageInstigatorSlot;
        c.slot = this.slot;
        c.frags = this.frags;
        c.flagsTaken = this.flagsTaken;
        c.flagsCaptured = this.flagsCaptured;
        c.flagsReturned = this.flagsReturned;
        c.shots = this.shots;
        c.shotsOnTarget = this.shotsOnTarget;
        c.damageDealtTotal = this.damageDealtTotal;
        c.damageTakenTotal = this.damageTakenTotal;
        c.discPresent = this.discPresent;
        if (this.discLocation != null) c.discLocation = this.discLocation.deepCopy();
        c.discTimeSinceThrow_norm = this.discTimeSinceThrow_norm;
        return c;
    }

    /**
     * Lightweight copy for enrichment: only clones objects that enrichers mutate
     * (enrichments). Shares location, oldLocation, viewRotation, collisions,
     * bAltFire by reference.
     */
    public PlayerDto shallowCopyForEnrichment() {
        PlayerDto c = new PlayerDto();
        // primitives — copied
        c.name = this.name;
        c.baseEyeHeight = this.baseEyeHeight;
        c.health = this.health;
        c.armor = this.armor;
        c.team = this.team;
        c.score = this.score;
        c.deaths = this.deaths;
        c.hasFlag = this.hasFlag;
        c.hasFlag_norm = this.hasFlag_norm;
        c.bFeigningDeath = this.bFeigningDeath;
        c.fireKilledEnemy = this.fireKilledEnemy;
        c.dodgeState = this.dodgeState;
        c.physics = this.physics;
        c.headUnderwater = this.headUnderwater;
        c.breathRemaining = this.breathRemaining;
        c.activeDodgeDir = this.activeDodgeDir;
        c.speed = this.speed;
        c.speed_norm = this.speed_norm;
        c.velocityX_norm = this.velocityX_norm;
        c.velocityY_norm = this.velocityY_norm;
        c.velocityZ_norm = this.velocityZ_norm;
        c.forwardVelocity_norm = this.forwardVelocity_norm;
        c.rightVelocity_norm = this.rightVelocity_norm;
        c.accelerationX_norm = this.accelerationX_norm;
        c.accelerationY_norm = this.accelerationY_norm;
        c.accelerationZ_norm = this.accelerationZ_norm;
        c.forwardAcceleration_norm = this.forwardAcceleration_norm;
        c.rightAcceleration_norm = this.rightAcceleration_norm;
        c.forwardAccelVelocityMismatch_norm = this.forwardAccelVelocityMismatch_norm;
        c.rightAccelVelocityMismatch_norm = this.rightAccelVelocityMismatch_norm;
        c.yawAngularVelocity_norm = this.yawAngularVelocity_norm;
        c.pitchAngularVelocity_norm = this.pitchAngularVelocity_norm;
        c.velocityResolved = this.velocityResolved;
        c.accelerationResolved = this.accelerationResolved;
        c.dodgeStateResolved = this.dodgeStateResolved;
        c.dodgeCooldownNorm = this.dodgeCooldownNorm;
        c.timeSinceLastMoveNorm = this.timeSinceLastMoveNorm;
        c.bIsSpectator = this.bIsSpectator;
        c.bIsABot = this.bIsABot;
        c.bIsRLControlled = this.bIsRLControlled;
        c.bWaitingPlayer = this.bWaitingPlayer;
        c.enemyVisible = this.enemyVisible;
        c.lastDamageAmount = this.lastDamageAmount;
        c.lastDamageType = this.lastDamageType;
        c.lastDamageSelfInflicted = this.lastDamageSelfInflicted;
        c.lastDamageInstigatorSlot = this.lastDamageInstigatorSlot;
        c.slot = this.slot;
        c.frags = this.frags;
        c.flagsTaken = this.flagsTaken;
        c.flagsCaptured = this.flagsCaptured;
        c.flagsReturned = this.flagsReturned;
        c.shots = this.shots;
        c.shotsOnTarget = this.shotsOnTarget;
        c.damageDealtTotal = this.damageDealtTotal;
        c.damageTakenTotal = this.damageTakenTotal;
        c.weaponClass = this.weaponClass;
        c.weaponAmmo = this.weaponAmmo;
        c.weaponMaxAmmo = this.weaponMaxAmmo;
        c.weaponIsDual = this.weaponIsDual;
        c.weaponSniping = this.weaponSniping;
        c.weaponGrenadeMode = this.weaponGrenadeMode;
        c.weaponTightWad = this.weaponTightWad;
        c.weaponMultiCount = this.weaponMultiCount;
        c.weaponChargeAmount = this.weaponChargeAmount;
        c.discPresent = this.discPresent;
        c.discLocation = this.discLocation;   // shared by reference (immutable per frame)
        c.discTimeSinceThrow_norm = this.discTimeSinceThrow_norm;

        // shared by reference — not mutated by enrichers
        c.location = this.location;
        c.oldLocation = this.oldLocation;
        c.viewRotation = this.viewRotation;
        c.collisions = this.collisions;
        c.bAltFire = this.bAltFire;
        c.weaponCanFire = this.weaponCanFire;
        c.inventory = this.inventory;

        if (this.bDuck != null) c.bDuck = this.bDuck.deepCopy();
        if (this.bFire != null) c.bFire = this.bFire.deepCopy();

        if (this.playerPawn != null) c.playerPawn = this.playerPawn.deepCopy();

        // new empty — enrichers (FlagRelative, EnemyRelative) will populate
        c.enrichments = new PlayerEnrichmentDto();
        return c;
    }
}
