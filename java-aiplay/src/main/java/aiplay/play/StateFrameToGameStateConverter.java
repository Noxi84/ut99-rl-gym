package aiplay.play;

import aiplay.config.global.GlobalConfigRepository;
import aiplay.play.udpstate.model.InventoryItem;
import aiplay.play.udpstate.model.MoverState;
import aiplay.play.udpstate.model.Pickup;
import aiplay.play.udpstate.model.Projectile;
import aiplay.play.udpstate.model.StateFrame;
import aiplay.ut99webmodel.Collisions;
import aiplay.ut99webmodel.Flag;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.InventoryWeapon;
import aiplay.ut99webmodel.MapInfo;
import aiplay.ut99webmodel.Player;
import aiplay.ut99webmodel.MoverEntry;
import aiplay.ut99webmodel.PickupEntry;
import aiplay.ut99webmodel.PlayerVisibilityEntry;
import aiplay.ut99webmodel.ProjectileEntry;
import aiplay.ut99webmodel.Weapon;
import java.util.ArrayList;

/**
 * Converts a UDP-received {@link StateFrame} into the same {@link GameState}
 * JSON-bound model that the HTTP path produces, so the downstream converter
 * chain (PlayerPawnBasicFeatureJsonToDtoConverter etc) continues to work
 * unchanged.
 *
 * <p>The source wire model lives in {@code aiplay.play.udpstate.model}; five of
 * its type names ({@code MapInfo}, {@code Flag}, {@code Player}, {@code Weapon},
 * {@code Collisions}) collide with the {@code ut99webmodel} target types, so the
 * source side is referenced by fully-qualified name where they overlap.
 *
 * <p>String formatting keeps the legacy parser shape:
 *   vector  → comma-separated decimal x,y,z
 *   rotator → "pitch,yaw,roll" (integers)
 *   bool    → "True" / "False" (except {@code bDuck/bFire/bAltFire} which are "0"/"1")
 *   numeric → decimal string, no thousands separator.
 */
public final class StateFrameToGameStateConverter {

    private StateFrameToGameStateConverter() {}

    public static GameState convert(StateFrame frame) {
        if (frame == null) return null;
        GameState gs = new GameState();
        gs.MapInfo = buildMapInfo(frame.mapInfo());
        gs.Flags = buildFlags(frame);
        gs.Players = buildPlayers(frame);
        gs.Projectiles = buildProjectiles(frame);
        gs.Pickups = buildPickups(frame);
        gs.Movers = buildMovers(frame);
        gs.timestampMillis = System.currentTimeMillis();
        return gs;
    }

    // ───────────────── MapInfo ─────────────────

    private static MapInfo buildMapInfo(aiplay.play.udpstate.model.MapInfo src) {
        MapInfo mi = new MapInfo();
        if (src == null) return mi;
        mi.MapName       = "";   // UScript has the hash; the DTO converters don't use the name for features
        mi.LevelTitle    = "";
        mi.GameName      = "Capture the Flag";
        mi.GameClass     = "NeuralNetWebserver.RLCTFGame";
        mi.TimeLimit     = Integer.toString(GlobalConfigRepository.shared().gameplay().matchTimeMinutes());
        mi.GameType      = "CTF";
        mi.RedScore      = Integer.toString(src.redScore());
        mi.BlueScore     = Integer.toString(src.blueScore());
        mi.RemainingTime = Integer.toString(src.remainingTime());
        mi.ElapsedTime   = Integer.toString(src.elapsedTime());
        mi.bGameEnded    = src.gameEnded() ? "True" : "False";
        return mi;
    }

    // ───────────────── Flags ─────────────────

    private static java.util.List<Flag> buildFlags(StateFrame frame) {
        java.util.List<Flag> out = new ArrayList<>(frame.flags().size());
        for (aiplay.play.udpstate.model.Flag src : frame.flags()) {
            Flag dst = new Flag();
            dst.Team = Integer.toString(src.team());
            dst.Status = statusToString(src.status());
            dst.Location         = vec(src.locX(), src.locY(), src.locZ());
            dst.HomeBaseLocation = vec(src.baseX(), src.baseY(), src.baseZ());
            dst.bHome            = bool01True(src.status() == 0);
            dst.HasHolder        = bool01True(src.status() == 1);
            dst.HolderName       = lookupHolderName(frame, src.holderSlot());
            dst.LastReturnInstigatorSlot = Integer.toString(src.lastReturnInstigatorSlot());
            dst.Collisions = null;  // Flag collisions not emitted over UDP yet
            out.add(dst);
        }
        return out;
    }

    private static String statusToString(int status) {
        return switch (status) {
            case 0 -> "home";
            case 1 -> "carried";
            case 2 -> "dropped";
            default -> "home";
        };
    }

    private static String lookupHolderName(StateFrame frame, int slot) {
        if (slot == 255) return "";
        for (aiplay.play.udpstate.model.Player p : frame.players()) {
            if (p.slot() == slot) return p.name();
        }
        return "";
    }

    // ───────────────── Players ─────────────────

    private static java.util.List<Player> buildPlayers(StateFrame frame) {
        java.util.List<Player> out = new ArrayList<>(frame.players().size());
        for (aiplay.play.udpstate.model.Player src : frame.players()) {
            Player dst = new Player();
            dst.Name          = src.name();
            dst.Location      = vec(src.locX(), src.locY(), src.locZ());
            dst.BaseEyeHeight = Integer.toString(src.baseEyeHeight());
            dst.ViewRotation  = src.viewPitch() + "," + src.viewYaw() + ",0";
            dst.Health        = Integer.toString(src.health());
            dst.Armor         = Integer.toString(src.armor());
            dst.Team          = Integer.toString(src.team());
            dst.Score         = Integer.toString(src.score());
            dst.Deaths        = Integer.toString(src.deaths());
            dst.HasFlag       = src.hasFlag() ? "CTFFlag" : "None";
            dst.OldLocation   = vec(src.oldLocX(), src.oldLocY(), src.oldLocZ());
            dst.bDuck         = bool01Digit(src.isDuck());
            dst.bFire         = bool01Digit(src.isFire());
            dst.bAltFire      = bool01Digit(src.isAltFire());
            dst.bFeigningDeath = "False";
            dst.Velocity      = vec(src.velX(), src.velY(), src.velZ());
            dst.Acceleration  = vec(src.accX(), src.accY(), src.accZ());
            dst.DodgeState    = Integer.toString(src.dodgeState());
            dst.Physics       = Integer.toString(src.physics());

            dst.bIsSpectator  = boolTrueFalse(src.isSpectator());
            dst.bIsABot       = boolTrueFalse(src.isBot());
            dst.bIsRLControlled = boolTrueFalse(src.isRLControlled());
            dst.bWaitingPlayer = boolTrueFalse(src.isWaiting());
            dst.Slot          = Integer.toString(src.slot());

            dst.Weapon     = buildWeapon(src.weapon());
            dst.Inventory  = buildInventory(src.inventory());
            dst.Visibility = buildVisibility(frame, src);
            dst.Collisions = buildCollisions(src.collisions());
            dst.FlagLineOfSight = buildFlagLoS(src.flagLoS());
            dst.LastDamageAmount         = Integer.toString(src.damageEventPresent() ? src.damageEventAmount() : 0);
            dst.LastDamageType           = src.damageEventPresent() ? WeaponClassNameTable.lookup(src.damageEventTypeHash()) : "";
            dst.LastDamageSelfInflicted  = boolTrueFalse(src.damageEventSelfInflicted());
            dst.LastDamageInstigatorSlot = Integer.toString(src.damageEventInstigatorSlot());
            dst.Frags          = Integer.toString(src.frags());
            dst.FlagsTaken     = Integer.toString(src.flagsTaken());
            dst.FlagsCaptured  = Integer.toString(src.flagsCaptured());
            dst.FlagsReturned  = Integer.toString(src.flagsReturned());
            dst.Shots          = Integer.toString(src.shots());
            dst.ShotsOnTarget  = Integer.toString(src.shotsOnTarget());
            dst.DamageDealtTotal = Integer.toString(src.damageDealtTotal());
            dst.DamageTakenTotal = Integer.toString(src.damageTakenTotal());
            dst.bDiscPresent     = boolTrueFalse(src.discPresent());
            dst.DiscLocation     = vec(src.discLocX(), src.discLocY(), src.discLocZ());
            dst.bHeadUnderwater  = boolTrueFalse(src.headUnderwater());
            dst.BreathRemaining  = Float.toString(src.breathRemaining());
            out.add(dst);
        }
        return out;
    }

    private static Weapon buildWeapon(aiplay.play.udpstate.model.Weapon src) {
        if (src == null || src.isEmpty()) return null;
        Weapon w = new Weapon();
        w.WeaponClass     = WeaponClassNameTable.lookup(src.classHash());
        w.AmmoAmount      = Integer.toString(src.ammo());
        w.MaxAmmo         = Integer.toString(src.maxAmmo());
        w.AltDamageType   = WeaponClassNameTable.lookup(src.altDamageHash());
        w.MyDamageType    = WeaponClassNameTable.lookup(src.myDamageHash());
        w.FireOffSet      = vec(src.fireOffsetX(), src.fireOffsetY(), src.fireOffsetZ());
        w.FiringSpeed     = f(src.firingSpeed());
        w.MaxTargetRange  = f(src.maxTargetRange());
        w.PickupAmmoCount = Integer.toString(src.pickupAmmo());
        w.bInstantHit     = boolTrueFalse(src.isInstantHit());
        w.bAltInstantHit  = boolTrueFalse(src.isAltInstantHit());
        w.bCanThrow       = boolTrueFalse(src.canThrow());
        w.bChangeWeapon   = boolTrueFalse(src.changeWeapon());
        w.bLockedOn       = boolTrueFalse(src.lockedOn());
        w.bWeaponStay     = boolTrueFalse(src.weaponStay());
        w.bWeaponUp       = boolTrueFalse(src.weaponUp());
        w.SubWeapon       = null;  // sub-weapon specifics not transported over UDP
        w.bIsDual         = boolTrueFalse(src.isDual());
        w.bSniping        = boolTrueFalse(src.isSniping());
        w.bGrenadeMode    = boolTrueFalse(src.isGrenadeMode());
        w.bTightWad       = boolTrueFalse(src.isTightWad());
        w.MultiCount      = Integer.toString(src.multiCount());
        w.ChargeAmount    = Integer.toString(src.chargeAmount());
        return w;
    }

    private static java.util.List<InventoryWeapon> buildInventory(
            java.util.List<InventoryItem> items) {
        java.util.List<InventoryWeapon> out = new ArrayList<>(items.size());
        for (InventoryItem src : items) {
            InventoryWeapon w = new InventoryWeapon();
            w.WeaponClass = WeaponClassNameTable.lookup(src.classHash());
            w.AmmoAmount  = Integer.toString(src.ammo());
            w.MaxAmmo     = Integer.toString(src.maxAmmo());
            out.add(w);
        }
        return out;
    }

    private static java.util.List<PlayerVisibilityEntry> buildVisibility(
            StateFrame frame, aiplay.play.udpstate.model.Player self) {
        java.util.List<PlayerVisibilityEntry> out = new ArrayList<>();
        for (aiplay.play.udpstate.model.Player other : frame.players()) {
            if (other == self) continue;
            if (other.slot() < 0 || other.slot() >= 32) continue;
            boolean visible = self.seesSlot(other.slot());
            PlayerVisibilityEntry e = new PlayerVisibilityEntry();
            e.Name = other.name();
            e.bVisible = boolTrueFalse(visible);
            out.add(e);
        }
        return out;
    }

    private static Collisions buildCollisions(aiplay.play.udpstate.model.Collisions src) {
        if (src == null) return null;
        Collisions c = new Collisions();
        c.maxDist          = Integer.toString(src.maxDist());
        c.capsuleMargin    = Integer.toString(src.capsuleMargin());
        c.stepCoarse       = "0";
        c.immediateProbeUu = "0";
        c.floorProbeDist   = Integer.toString(src.floorProbeDist());
        c.floorMaxDrop     = Integer.toString(src.floorMaxDrop());

        int[] d = src.distances();
        //  0 fwd           1 back           2 left           3 right
        //  4 posX          5 negX           6 posY           7 negY
        //  8 fwdR30        9 fwdR45        10 fwdR60
        // 11 backR60      12 backR45       13 backR30
        // 14 backL30      15 backL45       16 backL60
        // 17 fwdL60       18 fwdL45        19 fwdL30
        // 20 posXposY30   21 posXposY45    22 posXposY60
        // 23 negXposY60   24 negXposY45    25 negXposY30
        // 26 negXnegY30   27 negXnegY45    28 negXnegY60
        // 29 posXnegY60   30 posXnegY45    31 posXnegY30
        c.fwd_collision   = Integer.toString(d[0]);
        c.back_collision  = Integer.toString(d[1]);
        c.left_collision  = Integer.toString(d[2]);
        c.right_collision = Integer.toString(d[3]);
        c.posX_collision  = Integer.toString(d[4]);
        c.negX_collision  = Integer.toString(d[5]);
        c.posY_collision  = Integer.toString(d[6]);
        c.negY_collision  = Integer.toString(d[7]);
        c.fwdRight30_collision = Integer.toString(d[8]);
        c.fwdRight45_collision = Integer.toString(d[9]);
        c.fwdRight60_collision = Integer.toString(d[10]);
        c.backRight60_collision = Integer.toString(d[11]);
        c.backRight45_collision = Integer.toString(d[12]);
        c.backRight30_collision = Integer.toString(d[13]);
        c.backLeft30_collision  = Integer.toString(d[14]);
        c.backLeft45_collision  = Integer.toString(d[15]);
        c.backLeft60_collision  = Integer.toString(d[16]);
        c.fwdLeft60_collision   = Integer.toString(d[17]);
        c.fwdLeft45_collision   = Integer.toString(d[18]);
        c.fwdLeft30_collision   = Integer.toString(d[19]);
        c.posXPosY30_collision  = Integer.toString(d[20]);
        c.posXPosY45_collision  = Integer.toString(d[21]);
        c.posXPosY60_collision  = Integer.toString(d[22]);
        c.negXPosY60_collision  = Integer.toString(d[23]);
        c.negXPosY45_collision  = Integer.toString(d[24]);
        c.negXPosY30_collision  = Integer.toString(d[25]);
        c.negXNegY30_collision  = Integer.toString(d[26]);
        c.negXNegY45_collision  = Integer.toString(d[27]);
        c.negXNegY60_collision  = Integer.toString(d[28]);
        c.posXNegY60_collision  = Integer.toString(d[29]);
        c.posXNegY45_collision  = Integer.toString(d[30]);
        c.posXNegY30_collision  = Integer.toString(d[31]);

        int[] f = src.floorDelta();
        if (f != null && f.length >= 8) {
            c.fwdFloorDelta       = Integer.toString(f[0]);
            c.fwdRightFloorDelta  = Integer.toString(f[1]);
            c.rightFloorDelta     = Integer.toString(f[2]);
            c.backRightFloorDelta = Integer.toString(f[3]);
            c.backFloorDelta      = Integer.toString(f[4]);
            c.backLeftFloorDelta  = Integer.toString(f[5]);
            c.leftFloorDelta      = Integer.toString(f[6]);
            c.fwdLeftFloorDelta   = Integer.toString(f[7]);
        }
        int[] lo = src.lowDistances();
        if (lo != null && lo.length >= 8) {
            c.fwdLowCollision       = Integer.toString(lo[0]);
            c.fwdRightLowCollision  = Integer.toString(lo[1]);
            c.rightLowCollision     = Integer.toString(lo[2]);
            c.backRightLowCollision = Integer.toString(lo[3]);
            c.backLowCollision      = Integer.toString(lo[4]);
            c.backLeftLowCollision  = Integer.toString(lo[5]);
            c.leftLowCollision      = Integer.toString(lo[6]);
            c.fwdLeftLowCollision   = Integer.toString(lo[7]);
        }
        return c;
    }

    private static aiplay.ut99webmodel.FlagLineOfSight buildFlagLoS(float[] rays) {
        if (rays == null || rays.length < 14) return null;
        aiplay.ut99webmodel.FlagLineOfSight los = new aiplay.ut99webmodel.FlagLineOfSight();
        los.RedFlag  = csv7(rays, 0);
        los.BlueFlag = csv7(rays, 7);
        return los;
    }

    // ───────────────── Projectiles ─────────────────

    private static java.util.List<ProjectileEntry> buildProjectiles(StateFrame frame) {
        java.util.Map<Integer, String> nameByHash = new java.util.HashMap<>(frame.players().size() * 2);
        for (aiplay.play.udpstate.model.Player pl : frame.players()) {
            if (pl.name() != null) nameByHash.put(pl.nameHash(), pl.name());
        }
        java.util.List<ProjectileEntry> out = new ArrayList<>(frame.projectiles().size());
        for (Projectile src : frame.projectiles()) {
            ProjectileEntry p = new ProjectileEntry();
            p.Class    = WeaponClassNameTable.lookup(src.classHash());
            p.Location = vec(src.locX(), src.locY(), src.locZ());
            p.Velocity = vec(src.velX(), src.velY(), src.velZ());
            p.Speed    = Integer.toString(src.speed());
            p.Damage   = Integer.toString(src.damage());
            p.InstigatorName = nameByHash.getOrDefault(src.instigatorNameHash(), "");
            p.InstigatorTeam = Integer.toString(src.instigatorTeam());
            p.DrawScale = Double.toString(src.drawScale());
            out.add(p);
        }
        return out;
    }

    // ───────────────── Pickups ─────────────────

    private static java.util.List<PickupEntry> buildPickups(StateFrame frame) {
        java.util.List<PickupEntry> out = new ArrayList<>(frame.pickups().size());
        for (Pickup src : frame.pickups()) {
            PickupEntry p = new PickupEntry();
            // ClassHash gaat als unsigned hex string omdat sommige FNV1a-hashes
            // negatieve int-waarden geven en we de bit-pattern willen behouden voor
            // de matching tegen pickup-types.json class-aliases.
            p.ClassHash = Integer.toHexString(src.classHash());
            p.Location = vec(src.locX(), src.locY(), src.locZ());
            p.BHidden = src.hidden() ? "1" : "0";
            p.RemainingRespawnSec = Double.toString(src.remainingRespawnSec());
            out.add(p);
        }
        return out;
    }

    // ───────────────── Movers ─────────────────

    private static java.util.List<MoverEntry> buildMovers(StateFrame frame) {
        java.util.List<MoverEntry> out = new ArrayList<>(frame.movers().size());
        for (MoverState src : frame.movers()) {
            MoverEntry m = new MoverEntry();
            m.NameHash = Integer.toHexString(src.nameHash());
            m.Location = vec(src.locX(), src.locY(), src.locZ());
            m.KeyNum = src.keyNum();
            m.PrevKeyNum = src.prevKeyNum();
            m.NumKeys = src.numKeys();
            m.Opening = src.opening();
            m.Delaying = src.delaying();
            m.MoveProgress = src.moveProgress();
            out.add(m);
        }
        return out;
    }

    // ───────────────── helpers ─────────────────

    private static String vec(double x, double y, double z) {
        return Double.toString(x) + "," + Double.toString(y) + "," + Double.toString(z);
    }

    private static String f(double v) {
        return Double.toString(v);
    }

    private static String csv7(float[] values, int offset) {
        StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < 7; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(values[offset + i]));
        }
        return sb.toString();
    }

    private static String boolTrueFalse(boolean v) { return v ? "True" : "False"; }
    private static String bool01True(boolean v)    { return v ? "True" : "False"; }
    private static String bool01Digit(boolean v)   { return v ? "1" : "0"; }
}
