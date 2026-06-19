package aiplay.play.udpstate;

import aiplay.play.udpstate.model.Collisions;
import aiplay.play.udpstate.model.Flag;
import aiplay.play.udpstate.model.InventoryItem;
import aiplay.play.udpstate.model.MapInfo;
import aiplay.play.udpstate.model.MoverState;
import aiplay.play.udpstate.model.Pickup;
import aiplay.play.udpstate.model.Player;
import aiplay.play.udpstate.model.Projectile;
import aiplay.play.udpstate.model.StateFrame;
import aiplay.play.udpstate.model.Weapon;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a reassembled TLV payload into a {@link StateFrame}. Pure and
 * stateless: no socket, no thread, no ambient clock (the receive timestamp is
 * injected). Every {@code parseX} here is the exact dual of a {@code WriteX} in
 * {@code scripts/mutator/NeuralNetWebserver/Classes/RLUdpStateSender.uc} — keep
 * the byte offsets aligned when either side changes.
 *
 * <p>Backward compatibility: optional trailers (floor probes, damage event, KPI
 * counters, damage cumulatives, translocator disc) are bounds-checked so a JVM
 * running a newer parser against a legacy {@code .u} sender degrades gracefully
 * instead of overrunning the section.
 */
public final class StateFrameCodec {

    private static final int TAG_MAP_INFO   = 0x01;
    private static final int TAG_FLAG       = 0x02;
    private static final int TAG_PLAYER     = 0x03;
    private static final int TAG_PROJECTILE = 0x04;
    private static final int TAG_PICKUP     = 0x05;
    private static final int TAG_MOVER      = 0x06;

    private StateFrameCodec() {}

    /**
     * Parse a complete payload. Returns {@code null} on a malformed payload
     * (section length overrun, or no MapInfo section present); callers treat
     * {@code null} as a parse error.
     *
     * @param receivedAtNanos transport-supplied receive time ({@code System.nanoTime()})
     */
    public static StateFrame parse(int frameId, byte[] payload, long receivedAtNanos) {
        return parse(frameId, ByteBuffer.wrap(payload), receivedAtNanos);
    }

    public static StateFrame parse(int frameId, ByteBuffer payload, long receivedAtNanos) {
        ByteBuffer buf = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
        MapInfo mapInfo = null;
        List<Flag> flags = new ArrayList<>(2);
        List<Player> players = new ArrayList<>(4);
        List<Projectile> projectiles = new ArrayList<>(4);
        List<Pickup> pickups = new ArrayList<>(32);
        List<MoverState> movers = new ArrayList<>(8);
        while (buf.remaining() >= 3) {
            int tag = buf.get() & 0xFF;
            int len = buf.getShort() & 0xFFFF;
            if (len > buf.remaining()) return null;
            int sectionEnd = buf.position() + len;
            switch (tag) {
                case TAG_MAP_INFO -> mapInfo = parseMapInfo(buf);
                case TAG_FLAG -> flags.add(parseFlag(buf));
                case TAG_PLAYER -> players.add(parsePlayer(buf, len));
                case TAG_PROJECTILE -> projectiles.add(parseProjectile(buf));
                case TAG_PICKUP -> pickups.add(parsePickup(buf));
                case TAG_MOVER -> movers.add(parseMover(buf));
                default -> { /* unknown */ }
            }
            buf.position(sectionEnd);
        }
        if (mapInfo == null) return null;
        return new StateFrame(frameId, receivedAtNanos, mapInfo, flags, players, projectiles, pickups, movers);
    }

    private static MapInfo parseMapInfo(ByteBuffer buf) {
        int red = buf.getShort();
        int blue = buf.getShort();
        int remaining = buf.getShort();
        int elapsed = buf.getShort();
        float timeDil = (buf.get() & 0xFF) / 100f;
        boolean hardcore = buf.get() != 0;
        boolean mega = buf.get() != 0;
        boolean gameEnded = buf.get() != 0;   // was reserved; UC now writes int(bGameEnded)
        int hash = buf.getInt();
        buf.getInt();
        return new MapInfo(red, blue, remaining, elapsed, timeDil, hardcore, mega, gameEnded, hash);
    }

    private static Flag parseFlag(ByteBuffer buf) {
        int team = buf.get() & 0xFF;
        int status = buf.get() & 0xFF;
        double lx = buf.getInt() / 10.0;
        double ly = buf.getInt() / 10.0;
        double lz = buf.getInt() / 10.0;
        double bx = buf.getInt() / 10.0;
        double by = buf.getInt() / 10.0;
        double bz = buf.getInt() / 10.0;
        int holder = buf.get() & 0xFF;
        int returnInstSlot = buf.get();   // signed: -1 (auto-return / none) → 0xFF
        buf.get(); buf.get();
        return new Flag(team, status, lx, ly, lz, bx, by, bz, holder, returnInstSlot);
    }

    private static Player parsePlayer(ByteBuffer buf, int sectionLen) {
        int startPos = buf.position();

        // stage 2b basic fields
        int slot = buf.get() & 0xFF;
        int team = buf.get() & 0xFF;
        int physics = buf.get() & 0xFF;
        int dodgeState = buf.get() & 0xFF;
        int actionFlags = buf.get() & 0xFF;
        int waterFlags = buf.get() & 0xFF;          // offset 5: was reserved padding
        boolean headUnderwater = (waterFlags & 1) != 0;
        int health = buf.getShort();
        int score = buf.getShort();
        int deaths = buf.getShort();
        int armor = buf.getShort();
        double lx = buf.getInt() / 10.0;
        double ly = buf.getInt() / 10.0;
        double lz = buf.getInt() / 10.0;
        double olx = buf.getInt() / 10.0;
        double oly = buf.getInt() / 10.0;
        double olz = buf.getInt() / 10.0;
        double vx = buf.getShort() / 10.0;
        double vy = buf.getShort() / 10.0;
        double vz = buf.getShort() / 10.0;
        double ax = buf.getShort() / 10.0;
        double ay = buf.getShort() / 10.0;
        double az = buf.getShort() / 10.0;
        int viewPitch = buf.getShort() & 0xFFFF;
        int viewYaw = buf.getShort() & 0xFFFF;
        int baseEye = buf.get() & 0xFF;
        int ground = buf.getShort() & 0xFFFF;
        int air = buf.getShort() & 0xFFFF;
        int jumpZ = buf.getShort() & 0xFFFF;
        float airCtrl = (buf.get() & 0xFF) / 100f;
        float hf = (buf.get() & 0xFF) / 10f;
        float hb = (buf.get() & 0xFF) / 10f;
        float hl = (buf.get() & 0xFF) / 10f;
        float hr = (buf.get() & 0xFF) / 10f;
        float hj = (buf.get() & 0xFF) / 10f;
        float hd = (buf.get() & 0xFF) / 10f;
        int nameHash = buf.getInt();
        int nameLen = buf.get() & 0xFF;
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String name = new String(nameBytes, StandardCharsets.US_ASCII);

        // stage 2c trailer
        Weapon weapon = parseWeapon(buf);
        List<InventoryItem> inventory = parseInventory(buf);
        int visibilityMask = buf.getInt();
        float[] flagLoS = new float[14];
        for (int i = 0; i < 14; i++) flagLoS[i] = (buf.get() & 0xFF) / 255f;
        int trailerEnd = startPos + sectionLen;
        Collisions collisions = parseCollisions(buf, trailerEnd);

        // Damage event (8 bytes): flags u8, amount u16, typeHash u32, instigatorSlot s8.
        // Tolerate older senders by bounds-checking before reading each field so
        // a JVM running a newer parser against a legacy .u doesn't explode.
        boolean dmgPresent = false;
        boolean dmgSelf = false;
        int dmgAmount = 0;
        int dmgTypeHash = 0;
        int dmgInstSlot = -1;
        if (buf.position() + 8 <= trailerEnd) {
            int dmgFlags = buf.get() & 0xFF;
            dmgPresent = (dmgFlags & 1) != 0;
            dmgSelf    = (dmgFlags & 2) != 0;
            dmgAmount  = buf.getShort() & 0xFFFF;
            dmgTypeHash = buf.getInt();
            dmgInstSlot = buf.get();  // signed byte: -1 encodes as 0xFF
        }

        // KPI counters (12 bytes, 6× u16 LE): frags, flagsTaken, flagsCaptured,
        // flagsReturned, shots, shotsOnTarget. Bounds-checked voor backward-compat
        // met een legacy .u — als deze block ontbreekt, alle counters blijven 0.
        int frags = 0, flagsTaken = 0, flagsCaptured = 0, flagsReturned = 0;
        int shots = 0, shotsOnTarget = 0;
        if (buf.position() + 12 <= trailerEnd) {
            frags          = buf.getShort() & 0xFFFF;
            flagsTaken     = buf.getShort() & 0xFFFF;
            flagsCaptured  = buf.getShort() & 0xFFFF;
            flagsReturned  = buf.getShort() & 0xFFFF;
            shots          = buf.getShort() & 0xFFFF;
            shotsOnTarget  = buf.getShort() & 0xFFFF;
        }

        // Damage cumulatives (8 bytes, 2× s32 LE): damageDealtTotal, damageTakenTotal.
        // Int32 i.p.v. u16 omdat HP-totalen per match boven 65k uit kunnen komen.
        int damageDealtTotal = 0, damageTakenTotal = 0;
        if (buf.position() + 8 <= trailerEnd) {
            damageDealtTotal = buf.getInt();
            damageTakenTotal = buf.getInt();
        }

        // Translocator-disc block (16 bytes): bDiscPresent uint8 + padding×3
        // + discX/Y/Z int32 (×10). Bounds-checked voor backward-compat met
        // legacy .u zonder disc-block.
        boolean discPresent = false;
        double discX = 0, discY = 0, discZ = 0;
        if (buf.position() + 16 <= trailerEnd) {
            discPresent = (buf.get() & 0xFF) != 0;
            buf.get(); buf.get(); buf.get();
            discX = buf.getInt() / 10.0;
            discY = buf.getInt() / 10.0;
            discZ = buf.getInt() / 10.0;
        }

        // Remaining breath (1 byte, uint8 /255): 1.0 = full lungs. Bounds-checked
        // for backward-compat with a legacy .u that doesn't emit it (defaults full).
        float breathRemaining = 1.0f;
        if (buf.position() + 1 <= trailerEnd) {
            breathRemaining = (buf.get() & 0xFF) / 255f;
        }

        // Skip any remaining bytes in this section (defensive).
        int consumed = buf.position() - startPos;
        if (consumed < sectionLen) buf.position(startPos + sectionLen);

        return new Player(slot, team, physics, dodgeState, actionFlags,
            health, score, deaths, armor,
            lx, ly, lz, olx, oly, olz, vx, vy, vz, ax, ay, az,
            viewPitch, viewYaw, baseEye, ground, air, jumpZ, airCtrl,
            hf, hb, hl, hr, hj, hd, nameHash, name,
            weapon, inventory, visibilityMask, flagLoS, collisions,
            dmgPresent, dmgSelf, dmgAmount, dmgTypeHash, dmgInstSlot,
            frags, flagsTaken, flagsCaptured, flagsReturned, shots, shotsOnTarget,
            damageDealtTotal, damageTakenTotal,
            discPresent, discX, discY, discZ,
            headUnderwater, breathRemaining);
    }

    private static Weapon parseWeapon(ByteBuffer buf) {
        int classHash = buf.getInt();
        int ammo = buf.getShort() & 0xFFFF;
        int maxAmmo = buf.getShort() & 0xFFFF;
        int altHash = buf.getInt();
        double foX = buf.getShort() / 10.0;
        double foY = buf.getShort() / 10.0;
        double foZ = buf.getShort() / 10.0;
        float firingSpeed = buf.getShort() / 100f;
        float maxTargetRange = buf.getShort() * 10f;   // reverse of ×0.1 encode
        int myHash = buf.getInt();
        int pickupAmmo = buf.getShort() & 0xFFFF;
        int flags = buf.get() & 0xFF;
        // Voorheen padding×3; nu weaponFlags2/multiCount/chargeAmount (zie
        // RLUdpStateSender.uc WriteWeapon). Legacy .u stuurt nullen → veilig.
        int flags2 = buf.get() & 0xFF;
        int multiCount = buf.get() & 0xFF;
        int chargeAmount = buf.get() & 0xFF;
        return new Weapon(classHash, ammo, maxAmmo, altHash,
            foX, foY, foZ, firingSpeed, maxTargetRange, myHash, pickupAmmo, flags,
            flags2, multiCount, chargeAmount);
    }

    private static List<InventoryItem> parseInventory(ByteBuffer buf) {
        int count = buf.get() & 0xFF;
        List<InventoryItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int ch = buf.getInt();
            int a = buf.getShort() & 0xFFFF;
            int m = buf.getShort() & 0xFFFF;
            items.add(new InventoryItem(ch, a, m));
        }
        return items;
    }

    private static Collisions parseCollisions(ByteBuffer buf, int sectionEnd) {
        int maxDist = buf.getShort() & 0xFFFF;
        int capsuleMargin = buf.get() & 0xFF;
        buf.get();  // reserved
        int[] distances = new int[32];
        for (int i = 0; i < 32; i++) distances[i] = buf.getShort() & 0xFFFF;
        int floorProbeDist = 160;
        int floorMaxDrop = 1600;
        int[] floorDelta = new int[8];                          // SIGNED: +step-up / -drop
        int[] lowDistances = new int[8];
        for (int i = 0; i < 8; i++) lowDistances[i] = maxDist;  // default: clear

        // Trailer before the 8-byte damage block:
        //   floorProbeDist u16, floorMaxDrop u16, floorDelta×8 i16 (SIGNED), lowDistances×8 u16
        // = 36 bytes. Older senders wrote only the first 20 (unsigned floor-DROP, no low rays);
        // map their positive drop onto the signed delta's negative half. Consume a trailer only
        // when it fully fits before the damage block.
        if (buf.position() + 36 <= sectionEnd - 8) {
            floorProbeDist = buf.getShort() & 0xFFFF;
            floorMaxDrop = buf.getShort() & 0xFFFF;
            for (int i = 0; i < 8; i++) floorDelta[i] = buf.getShort();           // signed
            for (int i = 0; i < 8; i++) lowDistances[i] = buf.getShort() & 0xFFFF;
        } else if (buf.position() + 20 <= sectionEnd - 8) {
            floorProbeDist = buf.getShort() & 0xFFFF;
            floorMaxDrop = buf.getShort() & 0xFFFF;
            for (int i = 0; i < 8; i++) floorDelta[i] = -(buf.getShort() & 0xFFFF); // legacy drop → -delta
        }
        return new Collisions(maxDist, capsuleMargin, distances,
            floorProbeDist, floorMaxDrop, floorDelta, lowDistances);
    }

    private static Projectile parseProjectile(ByteBuffer buf) {
        int classHash = buf.getInt();
        double lx = buf.getInt() / 10.0;
        double ly = buf.getInt() / 10.0;
        double lz = buf.getInt() / 10.0;
        double vx = buf.getShort() / 10.0;
        double vy = buf.getShort() / 10.0;
        double vz = buf.getShort() / 10.0;
        int speed = buf.getShort() & 0xFFFF;
        int damage = buf.getShort() & 0xFFFF;
        int instHash = buf.getInt();
        int instTeam = buf.get();
        double drawScale = (buf.get() & 0xFF) / 64.0;
        buf.get(); buf.get();
        return new Projectile(classHash, lx, ly, lz, vx, vy, vz,
            speed, damage, instHash, instTeam, drawScale);
    }

    /** Matched 19-byte payload uit {@code RLUdpStateSender.uc:WritePickups()}. */
    private static Pickup parsePickup(ByteBuffer buf) {
        int classHash = buf.getInt();
        double lx = buf.getInt() / 10.0;
        double ly = buf.getInt() / 10.0;
        double lz = buf.getInt() / 10.0;
        boolean hidden = (buf.get() & 0xFF) != 0;
        int remainingCs = buf.getShort() & 0xFFFF;
        return new Pickup(classHash, lx, ly, lz, hidden, remainingCs / 100.0);
    }

    /** Matched 25-byte payload uit {@code RLUdpStateSender.uc:WriteMovers()}. */
    private static MoverState parseMover(ByteBuffer buf) {
        int nameHash = buf.getInt();
        double lx = buf.getInt() / 10.0;
        double ly = buf.getInt() / 10.0;
        double lz = buf.getInt() / 10.0;
        int keyNum = buf.get() & 0xFF;
        int prevKeyNum = buf.get() & 0xFF;
        int numKeys = buf.get() & 0xFF;
        int stateFlags = buf.get() & 0xFF;
        boolean opening = (stateFlags & 1) != 0;
        boolean delaying = (stateFlags & 2) != 0;
        int progressRaw = buf.getShort() & 0xFFFF;
        double moveProgress = progressRaw / 10000.0;
        return new MoverState(nameHash, lx, ly, lz, keyNum, prevKeyNum, numKeys,
            opening, delaying, moveProgress);
    }
}
