package aiplay.play.udpstate.model;

import java.util.List;

/**
 * Full per-player state (TLV tag 0x03). Immutable; {@code inventory} is
 * defensively copied. Component order matches the byte layout written by
 * {@code RLUdpStateSender.uc:WriteSinglePlayer()}.
 */
public record Player(
        int slot, int team, int physics, int dodgeState, int actionFlags,
        int health, int score, int deaths, int armor,
        double locX, double locY, double locZ,
        double oldLocX, double oldLocY, double oldLocZ,
        double velX, double velY, double velZ,
        double accX, double accY, double accZ,
        int viewPitch, int viewYaw,
        int baseEyeHeight,
        int groundSpeed, int airSpeed, int jumpZ,
        float airControl,
        float holdForward, float holdBack, float holdLeft,
        float holdRight, float holdJump, float holdDuck,
        int nameHash, String name,
        Weapon weapon, List<InventoryItem> inventory,
        int visibilityMask,
        float[] flagLoS,            // 14 ratios, indices 0..6 = red flag rays, 7..13 = blue
        Collisions collisions,
        // Damage-event block: populated on the frame a TakeDamage fires, cleared
        // on subsequent frames. Used by the reward system to distinguish
        // self-inflicted splash/grenade damage from enemy fire.
        boolean damageEventPresent,
        boolean damageEventSelfInflicted,
        int damageEventAmount,
        int damageEventTypeHash,
        int damageEventInstigatorSlot,   // -1 if unknown
        // KPI counters (Plan A/B/D2): monotonisch oplopende totals per match.
        // Worden door player_scores_eval.py gebruikt voor delta-eval per kpi-keuze
        // (frags / flag_score / aim_accuracy). Reset op match-end → Python parser
        // detecteert dit via score-drop (analoog aan score-window logic).
        int frags,
        int flagsTaken,
        int flagsCaptured,
        int flagsReturned,
        int shots,
        int shotsOnTarget,
        // Cumulatieve damage in HP per match. damageDealtTotal: outgoing damage
        // door deze speler (excl. self-damage). damageTakenTotal: incoming damage
        // op deze speler (incl. self). Voedt combat_score KPI in DeltaGate.
        int damageDealtTotal,
        int damageTakenTotal,
        // Translocator-disc: per speler maximaal 1 actieve disc (UT99 destroyt
        // vorige bij nieuwe throw). discPresent=false → coords zijn nullen.
        boolean discPresent,
        double discLocX, double discLocY, double discLocZ,
        // Water/submersion state. headUnderwater = HeadRegion in a water zone
        // (fully submerged → drowning). breathRemaining = PainTime/UnderWaterTime
        // in [0,1], 1.0 = full lungs. On-wire: headUnderwater rides in the player
        // flags byte (offset 5), breathRemaining is a trailer uint8.
        boolean headUnderwater, float breathRemaining) {

    public Player {
        inventory = List.copyOf(inventory);
    }

    public boolean isDuck()         { return (actionFlags & 1)   != 0; }
    public boolean isFire()         { return (actionFlags & 2)   != 0; }
    public boolean isAltFire()      { return (actionFlags & 4)   != 0; }
    public boolean hasFlag()        { return (actionFlags & 8)   != 0; }
    public boolean isSpectator()    { return (actionFlags & 16)  != 0; }
    public boolean isBot()          { return (actionFlags & 32)  != 0; }
    public boolean isWaiting()      { return (actionFlags & 64)  != 0; }
    public boolean isRLControlled() { return (actionFlags & 128) != 0; }
    public boolean seesSlot(int s)  { return (visibilityMask & (1 << s)) != 0; }
}
