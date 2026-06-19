// Binary UDP state sender for RL bots.
// Replaces the HTTP GET game-state pull with a push-based transport.
//
// Frame format (multi-packet, each UDP packet max 255 bytes):
//   Header (8 bytes):
//     [0]    magic = 0xBB
//     [1]    frameType (0=full)
//     [2-3]  frameId uint16 LE          (increments per frame, wraps)
//     [4-5]  payloadLen uint16 LE       (bytes after header in THIS packet)
//     [6]    packetIdx                   (0-based within the frame)
//     [7]    packetCount                 (total packets in this frame)
//   Payload: TLV sections split across packets (no section alignment required —
//   Java re-concatenates payloads by packetIdx before parsing).
//     tag(1) + len uint16 LE(2) + data
//     Tags: 0x01 MapInfo, 0x02 Flag, 0x03 Player, 0x04 Projectile, 0x05 Pickup, 0x06 Mover
//
// Stage 2c scope: full feature parity with HTTP JSON — MapInfo + Flags + Players
// (basic + collisions + visibility + FlagLoS + weapon + inventory) + Projectiles +
// Pickups (live state — bHidden + remaining respawn timer, voor pickup-awareness
// features in het joint rl_pawn model).
class RLUdpStateSender extends IpDrv.UdpLink;

const HEADER_SIZE    = 8;
const PACKET_PAYLOAD = 247;   // 255 - HEADER_SIZE
const SCRATCH_SIZE   = 8192;
const MAX_PACKETS    = 32;
const MAX_SLOTS      = 32;
const MAX_PICKUPS    = 256;   // CTF-Orbital heeft 124 pickups; 256 = ruim genoeg
const MAX_MOVERS     = 64;

// Raycast probe for all collision queries (matches NeuralNetWebserver.uc)
const PROBE_MAX_DIST       = 1200;
const PROBE_CAPSULE_MARGIN = 3;
const FLOOR_PROBE_DIST     = 160;
const FLOOR_MAX_DROP       = 1600;
const STEP_PROBE_TOP       = 96;    // floor-elevation up-trace start above feet (measures jumpable step-ups)
const LOW_RAY_HEIGHT       = 8;     // foot-height horizontal rays for low-obstacle detection

var RLCTFGame GameRef;
var IpAddr TargetAddr;
var int TargetPort;
var int FrameId;
var int SendCount;

// Rate-limit sender to ~60 Hz so we don't overrun the kernel send buffer.
var float SendIntervalSec;
var float TimeAccum;

// Scratch buffer for the full frame's payload; split into packets on send.
var byte Scratch[8192];
var int  ScratchLen;

// Per-frame slot→pawn mapping. Slots 0..RLBotCount-1 = RLBots (matches
// RLBots[]), then other non-spectator pawns in PawnList order, up to MAX_SLOTS.
// Used to encode visibility as a bitmask in each Player section.
var Pawn SlotToPawn[32];
var int  SlotCount;

// ── Per-victim damage event tracking ──────────────────────────────────
// Filled by RecordDamage() (called from RLCTFGame.ReduceDamage for ANY pawn
// taking damage), drained by WriteDamageEvent() for the matching slot. One
// event reported per state frame; multiple TakeDamage calls in the same
// frame collapse to "last hit wins" (matches UT99's own kill attribution).
// (UnrealScript verbiedt bool-arrays — bSelfInflicted gebruikt byte 0/1.)
var int  LastDmgAmount[32];
var name LastDmgType[32];
var int  LastDmgInstigatorSlot[32];   // -1 = unknown / world / self
var byte bLastDmgSelfInflicted[32];   // 0 = no, 1 = yes

// ── Per-flag return-instigator tracking ───────────────────────────────
// Filled by RecordFlagReturn() (called from RLCTFGame.ScoreFlag on the
// own-team-touch branch), drained by WriteSingleFlag() for the matching
// flag. -1 = no recent return / auto-return (no scorer attributable).
// Indexed by flag team (0=red, 1=blue).
var int LastFlagReturnInstigatorSlot[2];

// ── Per-slot per-match KPI counters (Plan A/B/D2) ─────────────────────
// Monotonisch oplopende totals per spelerslot; gelezen door Java side per
// state-frame en verstuurd in WriteSinglePlayer. Worden NIET gereset op
// match-end; Python player_scores_eval.py detecteert reset via score-drop
// en wist de eigen rolling window bij negatieve delta.
//
//  - Frags:           kills (ScoreKill hook in RLCTFGame, suicide niet meegerekend)
//  - FlagsTaken:      enemy-flag pickup events (HasFlag rising-edge)
//  - FlagsCaptured:   enemy-flag-naar-eigen-base events (ScoreFlag capture branch)
//  - FlagsReturned:   eigen-flag-touch events (ScoreFlag own-team branch)
//  - Shots:           fire/altFire onsets (rising-edge) — proxy voor shot-attempts
//  - ShotsOnTarget:   shots waar view-direction-dot enemy-eye-vector > AIM_DOT_THRESHOLD
var int Frags[32];
var int FlagsTaken[32];
var int FlagsCaptured[32];
var int FlagsReturned[32];
var int Shots[32];
var int ShotsOnTarget[32];
// Cumulatieve damage in HP per match. DamageDealtTotal: outgoing damage waar
// instigator == this slot (excl. self-damage). DamageTakenTotal: incoming damage
// waar deze slot de victim is (incl. self-damage). Beide gevuld door
// RecordDamage hook (zelfde call als per-frame damage event), maar als running
// total i.p.v. last-event. Voedt de "combat_score" KPI in DeltaGate.
var int DamageDealtTotal[32];
var int DamageTakenTotal[32];

// Edge-detectie state voor de counters (vorige tick waarden).
var byte PrevFire[32];
var byte PrevAltFire[32];
var byte PrevHadFlag[32];

// ── Pickup tracking ───────────────────────────────────────────────────
// Pickup-actors blijven bestaan bij respawn (bHidden flippt, geen destroy),
// dus we kunnen ze als stabiele identity-keys gebruiken. PickupTakenAt logt
// Level.TimeSeconds op de falling-edge van available (bHidden 0→1), zodat we
// remaining = RespawnTime - elapsed kunnen versturen — UC heeft geen built-in
// "remaining respawn time" accessor (RespawnTime is class-default + Timer()).
// ── Mover tracking ────────────────────────────────────────────────
var Mover MoverRefs[64];
var int   MoverCount;

var Pickup PickupRefs[256];
var float PickupTakenAt[256];
var byte  PickupPrevHidden[256];
var int   PickupCount;

// Aim-quality drempel: cos(theta). 0.95 ≈ 18° marge tussen view-direction
// en eye-to-enemy-eye richting bij fire-onset → "on target". Eenvoudige
// 3D dot zonder lead-correctie; symmetrisch toepasbaar op RL en UT99 bots
// zodat de KPI een echte vergelijkende baseline levert.
const AIM_DOT_THRESHOLD = 0.95;

function bool Initialize(RLCTFGame Game, int Port)
{
    GameRef = Game;
    TargetPort = Port;

    LinkMode = MODE_Binary;

    LastFlagReturnInstigatorSlot[0] = -1;
    LastFlagReturnInstigatorSlot[1] = -1;

    if (BindPort(0, true) == 0)
    {
        Log("RLUdpStateSender: BindPort(0) FAILED");
        return false;
    }

    // 127.0.0.1
    TargetAddr.Addr = (127 * 16777216) + 1;
    TargetAddr.Port = Port;

    Log("RLUdpStateSender: ready, will push to 127.0.0.1:" $ Port
        $ " @ " $ int(1.0 / SendIntervalSec) $ "Hz");
    return true;
}

event Tick(float DeltaTime)
{
    TimeAccum += DeltaTime;
    if (TimeAccum >= SendIntervalSec)
    {
        TimeAccum = 0;
        SendFrame();
    }
}

function SendFrame()
{
    if (GameRef == None)
        return;

    BuildSlotMapping();

    ScratchLen = 0;
    WriteMapInfo();
    WriteFlags();
    WritePlayers();
    WriteProjectiles();
    WritePickups();
    WriteMovers();
    SplitAndSend();

    FrameId = (FrameId + 1) & 65535;
}

function BuildSlotMapping()
{
    local Pawn P;
    local int i;

    SlotCount = 0;
    for (i = 0; i < GameRef.RLBotCount; i++)
    {
        SlotToPawn[i] = GameRef.RLBots[i];
        SlotCount = i + 1;
    }
    for (P = GameRef.Level.PawnList; P != None; P = P.nextPawn)
    {
        if (Spectator(P) != None) continue;
        if (RLBot(P) != None) continue;
        if (P.PlayerReplicationInfo == None) continue;
        if (SlotCount >= MAX_SLOTS) break;
        SlotToPawn[SlotCount] = P;
        SlotCount++;
    }
    // Clear remaining slots
    for (i = SlotCount; i < MAX_SLOTS; i++)
        SlotToPawn[i] = None;
}

function int LookupSlot(Pawn P)
{
    local int i;
    if (P == None) return 255;
    for (i = 0; i < SlotCount; i++)
        if (SlotToPawn[i] == P) return i;
    return 255;
}

// ────────────────────────────────────────────────────────────────────
//  Packet splitter + sender
// ────────────────────────────────────────────────────────────────────

function SplitAndSend()
{
    local int totalPackets;
    local int i, j, off, pLen;
    local byte P[255];

    totalPackets = (ScratchLen + PACKET_PAYLOAD - 1) / PACKET_PAYLOAD;
    if (totalPackets < 1) totalPackets = 1;
    if (totalPackets > MAX_PACKETS) totalPackets = MAX_PACKETS;

    for (i = 0; i < totalPackets; i++)
    {
        off = i * PACKET_PAYLOAD;
        pLen = ScratchLen - off;
        if (pLen > PACKET_PAYLOAD) pLen = PACKET_PAYLOAD;
        if (pLen < 0) pLen = 0;

        P[0] = 187;                       // 0xBB
        P[1] = 0;
        P[2] = FrameId & 255;
        P[3] = (FrameId >> 8) & 255;
        P[4] = pLen & 255;
        P[5] = (pLen >> 8) & 255;
        P[6] = i & 255;
        P[7] = totalPackets & 255;

        for (j = 0; j < pLen; j++)
            P[HEADER_SIZE + j] = Scratch[off + j];

        SendBinary(TargetAddr, HEADER_SIZE + pLen, P);
        // UdpLink.SendBinary returns false on localhost even when the packet
        // is delivered; Java receive count is the authoritative signal.
    }

    SendCount++;
    if ((SendCount % 600) == 1)
    {
        Log("RLUdpStateSender: frames=" $ SendCount
            $ " lastPayload=" $ ScratchLen $ " packets=" $ totalPackets
            $ " slots=" $ SlotCount);
    }
}

// ────────────────────────────────────────────────────────────────────
//  Scratch-buffer write helpers
// ────────────────────────────────────────────────────────────────────

function WriteByte(int v)
{
    if (ScratchLen < SCRATCH_SIZE)
    {
        Scratch[ScratchLen] = v & 255;
        ScratchLen++;
    }
}

function WriteInt16(int v)
{
    WriteByte(v & 255);
    WriteByte((v >> 8) & 255);
}

function WriteInt32(int v)
{
    WriteByte(v & 255);
    WriteByte((v >> 8) & 255);
    WriteByte((v >> 16) & 255);
    WriteByte((v >> 24) & 255);
}

function int BeginSection(int tag)
{
    local int lenOff;
    WriteByte(tag);
    lenOff = ScratchLen;
    WriteByte(0);
    WriteByte(0);
    return lenOff;
}

function EndSection(int lenOff)
{
    local int dataLen;
    dataLen = ScratchLen - (lenOff + 2);
    Scratch[lenOff]     = dataLen & 255;
    Scratch[lenOff + 1] = (dataLen >> 8) & 255;
}

// ────────────────────────────────────────────────────────────────────
//  Section writers
// ────────────────────────────────────────────────────────────────────

// MapInfo (20 bytes data).
function WriteMapInfo()
{
    local CTFReplicationInfo RI;
    local int redScore, blueScore;
    local int timeDil;
    local int nameHash;
    local int lenOff;

    lenOff = BeginSection(1);

    RI = CTFReplicationInfo(GameRef.GameReplicationInfo);
    if (RI != None)
    {
        redScore  = int(RI.Teams[0].Score);
        blueScore = int(RI.Teams[1].Score);
    }
    timeDil = int(GameRef.Level.TimeDilation * 100);
    nameHash = FNV1aHash(string(GameRef.Level));

    WriteInt16(redScore);
    WriteInt16(blueScore);
    WriteInt16(GameRef.RemainingTime);
    WriteInt16(GameRef.ElapsedTime);
    WriteByte(timeDil);
    WriteByte(int(GameRef.bHardCoreMode));
    WriteByte(int(GameRef.bMegaSpeed));
    // Was reserved padding; now carries bGameEnded for the trainer-side
    // match-aligned DualKPIDeltaGate (MatchEndLogger detects false→true).
    WriteByte(int(GameRef.bGameEnded));
    WriteInt32(nameHash);
    WriteInt32(0);

    EndSection(lenOff);
}

function WriteFlags()
{
    local CTFReplicationInfo RI;
    local int i;

    RI = CTFReplicationInfo(GameRef.GameReplicationInfo);
    if (RI == None) return;

    for (i = 0; i < 2; i++)
        if (RI.FlagList[i] != None)
            WriteSingleFlag(RI.FlagList[i]);
}

function WriteSingleFlag(CTFFlag F)
{
    local vector loc, baseLoc;
    local int status;
    local int holderIdx;
    local int lenOff;
    local int returnInstSlot;

    lenOff = BeginSection(2);

    if (F.HomeBase != None)
        baseLoc = F.HomeBase.Location;

    if (F.bHome)               { loc = baseLoc;          status = 0; }
    else if (F.Holder != None) { loc = F.Holder.Location; status = 1; }
    else                       { loc = F.Location;       status = 2; }

    holderIdx = LookupSlot(F.Holder);

    // Drained on every flag write — one return event reports exactly once.
    if (F.Team == 0 || F.Team == 1)
        returnInstSlot = LastFlagReturnInstigatorSlot[F.Team];
    else
        returnInstSlot = -1;

    WriteByte(F.Team);
    WriteByte(status);
    WriteInt32(int(loc.X * 10.0));
    WriteInt32(int(loc.Y * 10.0));
    WriteInt32(int(loc.Z * 10.0));
    WriteInt32(int(baseLoc.X * 10.0));
    WriteInt32(int(baseLoc.Y * 10.0));
    WriteInt32(int(baseLoc.Z * 10.0));
    WriteByte(holderIdx);
    WriteByte(returnInstSlot & 255);   // signed byte: -1 → 0xFF, decoded as (byte) on Java side
    WriteByte(0); WriteByte(0);

    if (F.Team == 0 || F.Team == 1)
        LastFlagReturnInstigatorSlot[F.Team] = -1;

    EndSection(lenOff);
}

// Record a flag-return event. Called from RLCTFGame.ScoreFlag when a pawn
// touches its own team's flag (Scorer.Team == theFlag.Team), which in
// stock CTF resets the flag to home. Auto-returns (timeout) bypass
// ScoreFlag entirely — those leave the slot at -1 so no bot earns credit.
function RecordFlagReturn(Pawn Scorer, CTFFlag theFlag)
{
    local int instSlot;
    if (theFlag == None || Scorer == None) return;
    if (theFlag.Team != 0 && theFlag.Team != 1) return;
    instSlot = LookupSlot(Scorer);
    if (instSlot >= MAX_SLOTS) instSlot = -1;
    LastFlagReturnInstigatorSlot[theFlag.Team] = instSlot;

    // Plan B counter: per-speler returns voor movement-DeltaGate KPI.
    if (instSlot >= 0 && instSlot < MAX_SLOTS)
        FlagsReturned[instSlot] = FlagsReturned[instSlot] + 1;
}

// Plan A counter: ScoreKill hook in RLCTFGame stuurt killer/victim hierheen.
// Suicides (Killer == Other) tellen niet — die zijn al verdisconteerd in
// PlayerReplicationInfo.Score (-1) en zouden frag-rate vervuilen.
function RecordKill(Pawn Killer, Pawn Other)
{
    local int killerSlot;
    if (Killer == None || Other == None) return;
    if (Killer == Other) return;
    killerSlot = LookupSlot(Killer);
    if (killerSlot >= MAX_SLOTS) return;
    Frags[killerSlot] = Frags[killerSlot] + 1;
}

// Plan B counter: ScoreFlag capture-branch (Scorer.Team != theFlag.Team)
// stuurt naar hier. Captures lopen via stock CTFGame.ScoreFlag → +7 op
// PlayerReplicationInfo.Score; we tellen het hier expliciet voor de
// movement-DeltaGate flag-event aggregaat.
function RecordFlagCapture(Pawn Scorer, CTFFlag theFlag)
{
    local int scorerSlot;
    if (Scorer == None || theFlag == None) return;
    scorerSlot = LookupSlot(Scorer);
    if (scorerSlot >= MAX_SLOTS) return;
    FlagsCaptured[scorerSlot] = FlagsCaptured[scorerSlot] + 1;
}

// Per-tick edge-detector voor shot/flag-pickup events op SlotToPawn[slot].
// Aangeroepen vanuit WriteSinglePlayer omdat we daar P en viewRot al hebben.
// Update: Shots, ShotsOnTarget, FlagsTaken counters; refresh PrevFire/Alt/HadFlag.
function UpdateEdgeCounters(int slot, Pawn P, rotator viewRot)
{
    local byte fireNow, altFireNow, hadFlagNow;
    local Pawn nearestEnemy;
    local vector dirView, dirToEnemy, eyeFrom, eyeTo;
    local float aimDot;
    local int eyeOffsetTo;

    if (P == None || P.PlayerReplicationInfo == None) return;

    if (P.bFire != 0)    fireNow = 1;    else fireNow = 0;
    if (P.bAltFire != 0) altFireNow = 1; else altFireNow = 0;
    if (P.PlayerReplicationInfo.HasFlag != None) hadFlagNow = 1; else hadFlagNow = 0;

    // Shot-onset = rising edge van bFire OR bAltFire (geünificeerd: één event
    // per "trigger pull" ongeacht primary/alt). Aim-score wordt op datzelfde
    // moment berekend met de actuele viewRot vs nearest enemy eye-position.
    if ((fireNow != 0 && PrevFire[slot] == 0)
        || (altFireNow != 0 && PrevAltFire[slot] == 0))
    {
        Shots[slot] = Shots[slot] + 1;

        nearestEnemy = FindNearestEnemy(P);
        if (nearestEnemy != None)
        {
            eyeFrom = P.Location;
            eyeFrom.Z = eyeFrom.Z + P.BaseEyeHeight;
            eyeTo = nearestEnemy.Location;
            eyeOffsetTo = nearestEnemy.BaseEyeHeight;
            if (eyeOffsetTo == 0) eyeOffsetTo = 38;
            eyeTo.Z = eyeTo.Z + eyeOffsetTo;

            dirView = Vector(viewRot);
            dirToEnemy = Normal(eyeTo - eyeFrom);
            aimDot = dirView Dot dirToEnemy;

            if (aimDot > AIM_DOT_THRESHOLD)
                ShotsOnTarget[slot] = ShotsOnTarget[slot] + 1;
        }
    }
    PrevFire[slot] = fireNow;
    PrevAltFire[slot] = altFireNow;

    // Flag-taken = HasFlag rising-edge. In stock CTF betekent HasFlag != None
    // dat de speler de ENEMY flag draagt (eigen flag kan niet worden opgepakt).
    if (hadFlagNow != 0 && PrevHadFlag[slot] == 0)
        FlagsTaken[slot] = FlagsTaken[slot] + 1;
    PrevHadFlag[slot] = hadFlagNow;
}

// Eenvoudige nearest-enemy lookup voor aim-quality benadering. Filtert dood,
// spectators, zelfde-team. Returnt None als er geen enemy beschikbaar is —
// caller telt dan ShotsOnTarget niet (Shots wel — schot in een lege match).
// (Parameter heet Shooter, niet Self — Self is een gereserveerd UC-keyword.)
function Pawn FindNearestEnemy(Pawn Shooter)
{
    local Pawn P, best;
    local float dist, bestDist;

    if (Shooter == None || Shooter.PlayerReplicationInfo == None) return None;
    bestDist = 99999999.0;
    best = None;
    for (P = Shooter.Level.PawnList; P != None; P = P.nextPawn)
    {
        if (P == Shooter) continue;
        if (P.Health <= 0) continue;
        if (P.PlayerReplicationInfo == None) continue;
        if (Spectator(P) != None) continue;
        if (P.PlayerReplicationInfo.Team == Shooter.PlayerReplicationInfo.Team) continue;
        dist = VSize(P.Location - Shooter.Location);
        if (dist < bestDist)
        {
            bestDist = dist;
            best = P;
        }
    }
    return best;
}

// ────────────────────────────────────────────────────────────────────
//  Players
// ────────────────────────────────────────────────────────────────────

function WritePlayers()
{
    local int i;
    for (i = 0; i < SlotCount; i++)
        if (SlotToPawn[i] != None)
            WriteSinglePlayer(i, SlotToPawn[i]);
}

function WriteSinglePlayer(int slot, Pawn P)
{
    local int lenOff;
    local int actionFlags;
    local string name;
    local int i;
    local int score, deaths, armor;
    local int hf, hb, hl, hr, hj, hd;
    local RLBot RB;
    local rotator viewRot;
    local int waterFlags;
    local float breathNorm;

    lenOff = BeginSection(3);

    RB = RLBot(P);

    actionFlags = 0;
    if (P.bDuck != 0) actionFlags = actionFlags | 1;
    if (P.bFire != 0) actionFlags = actionFlags | 2;
    if (P.bAltFire != 0) actionFlags = actionFlags | 4;
    if (P.PlayerReplicationInfo.HasFlag != None) actionFlags = actionFlags | 8;
    if (P.PlayerReplicationInfo.bIsSpectator) actionFlags = actionFlags | 16;
    if (P.PlayerReplicationInfo.bIsABot)      actionFlags = actionFlags | 32;
    if (P.PlayerReplicationInfo.bWaitingPlayer) actionFlags = actionFlags | 64;
    // Bit 128: this player is one of OUR RL-controlled bots (RLBot subclass).
    // bIsABot is true for both UT99 native bots AND our RL bots since RLBot
    // extends TMale1Bot. The RLBot cast distinguishes them — only bots driven
    // by Java policy networks evaluate true here.
    if (RB != None) actionFlags = actionFlags | 128;

    // Water state: bit 0 = head fully submerged (HeadRegion in a water zone →
    // breath drains, drowning ticks). Body-in-water (Region.Zone.bWaterZone) is
    // already exposed through P.Physics == PHYS_Swimming, so we don't duplicate it.
    waterFlags = 0;
    if (P.HeadRegion.Zone.bWaterZone) waterFlags = waterFlags | 1;

    // Remaining breath, normalised to [0,1]. PainTime counts down from
    // UnderWaterTime while the head is submerged; at 0 the engine applies a
    // drowning tick and resets it. Above water (or no configured breath) → full.
    if (P.HeadRegion.Zone.bWaterZone && P.UnderWaterTime > 0.0)
        breathNorm = FClamp(P.PainTime / P.UnderWaterTime, 0.0, 1.0);
    else
        breathNorm = 1.0;

    score  = int(P.PlayerReplicationInfo.Score);
    deaths = int(P.PlayerReplicationInfo.Deaths);
    armor  = GetTotalArmorOf(P);

    if (RB != None)
    {
        hf = int(RB.HoldForwardSec * 10);
        hb = int(RB.HoldBackSec * 10);
        hl = int(RB.HoldLeftSec * 10);
        hr = int(RB.HoldRightSec * 10);
        hj = int(RB.HoldJumpSec * 10);
        hd = int(RB.HoldDuckSec * 10);
    }

    WriteByte(slot);
    WriteByte(int(P.PlayerReplicationInfo.Team));
    WriteByte(int(P.Physics));
    WriteByte(int(P.DodgeDir));
    WriteByte(actionFlags);
    WriteByte(waterFlags);
    WriteInt16(P.Health);
    WriteInt16(score);
    WriteInt16(deaths);
    WriteInt16(armor);
    WriteInt32(int(P.Location.X * 10.0));
    WriteInt32(int(P.Location.Y * 10.0));
    WriteInt32(int(P.Location.Z * 10.0));
    WriteInt32(int(P.OldLocation.X * 10.0));
    WriteInt32(int(P.OldLocation.Y * 10.0));
    WriteInt32(int(P.OldLocation.Z * 10.0));
    WriteInt16(int(P.Velocity.X * 10.0));
    WriteInt16(int(P.Velocity.Y * 10.0));
    WriteInt16(int(P.Velocity.Z * 10.0));
    WriteInt16(int(P.Acceleration.X * 10.0));
    WriteInt16(int(P.Acceleration.Y * 10.0));
    WriteInt16(int(P.Acceleration.Z * 10.0));

    // ViewRotation: RLBots expose Java-driven target (engine resets P.ViewRotation).
    if (RB != None)
    {
        WriteInt16(RB.RLTargetPitch);
        WriteInt16(RB.RLTargetYaw);
        viewRot.Pitch = RB.RLTargetPitch;
        viewRot.Yaw   = RB.RLTargetYaw;
        viewRot.Roll  = 0;
    }
    else
    {
        WriteInt16(P.ViewRotation.Pitch);
        WriteInt16(P.ViewRotation.Yaw);
        viewRot = P.ViewRotation;
    }

    WriteByte(int(P.BaseEyeHeight));
    WriteInt16(int(P.GroundSpeed));
    WriteInt16(int(P.AirSpeed));
    WriteInt16(int(P.JumpZ));
    WriteByte(int(P.AirControl * 100));
    WriteByte(hf); WriteByte(hb); WriteByte(hl);
    WriteByte(hr); WriteByte(hj); WriteByte(hd);

    name = P.PlayerReplicationInfo.PlayerName;
    WriteInt32(FNV1aHash(name));
    WriteByte(Len(name) & 255);
    for (i = 0; i < Len(name); i++)
        WriteByte(Asc(Mid(name, i, 1)));

    // ── Stage 2c additions ────────────────────────────────────────
    WriteWeapon(P);
    WriteInventory(P);
    WriteVisibility(P);
    WriteFlagLoS(P);
    WriteCollisions(P, viewRot);
    WriteDamageEvent(slot);

    // ── KPI counters (Plan A/B/D2) ────────────────────────────────
    // Edge-detector + aim-quality update wordt hier per state-frame uitgevoerd
    // (op SendIntervalSec interval). Daarna direct de monotonisch oplopende
    // totals in het frame schrijven — Java parser leest 6× int16 LE achteraan
    // de player section, met bounds-check voor backward-compat.
    UpdateEdgeCounters(slot, P, viewRot);
    WriteInt16(Frags[slot]);
    WriteInt16(FlagsTaken[slot]);
    WriteInt16(FlagsCaptured[slot]);
    WriteInt16(FlagsReturned[slot]);
    WriteInt16(Shots[slot]);
    WriteInt16(ShotsOnTarget[slot]);

    // Damage cumulatives (Int32 want HP-totalen per match kunnen makkelijk
    // boven 65k komen — een 60-min match met 10 frags/min × 80HP = 48000,
    // dichtbij Int16 max van 65535).
    WriteInt32(DamageDealtTotal[slot]);
    WriteInt32(DamageTakenTotal[slot]);

    // Translocator-disc tracking (16 bytes): bDiscPresent uint8 + padding×3
    // + discX/Y/Z int32 (×10). UT99 spawnt 1 TranslocatorTarget-actor per
    // throw en destroyt 'm op teleport / pickup / out-of-bounds → één
    // foreach AllActors() volstaat. Java side tracked time-since-throw via
    // rising-edge van bDiscPresent.
    WriteTranslocatorDisc(P);

    // Remaining breath (uint8, x255). Trailer field — Java reads it bounds-checked
    // so a newer parser tolerates an older .u that does not emit it.
    WriteByte(int(breathNorm * 255.0));

    EndSection(lenOff);
}

// ────────────────────────────────────────────────────────────────────
//  Translocator-disc — embedded in Player section
// ────────────────────────────────────────────────────────────────────

// Disc block (16 bytes): bDiscPresent uint8, padding×3, discX/Y/Z int32 (×10).
// Wanneer geen disc actief is: alle nullen.
function WriteTranslocatorDisc(Pawn P)
{
    local TranslocatorTarget T;
    local int hasDisc;
    local vector loc;

    hasDisc = 0;
    loc.X = 0; loc.Y = 0; loc.Z = 0;

    if (P != None)
    {
        foreach P.AllActors(class'TranslocatorTarget', T)
        {
            if (T == None) continue;
            if (T.Owner != P) continue;
            hasDisc = 1;
            loc = T.Location;
            break;
        }
    }

    WriteByte(hasDisc);
    WriteByte(0); WriteByte(0); WriteByte(0);
    WriteInt32(int(loc.X * 10.0));
    WriteInt32(int(loc.Y * 10.0));
    WriteInt32(int(loc.Z * 10.0));
}

// Record a damage event for the given victim. Called from RLCTFGame.ReduceDamage
// for every pawn that takes damage (RL-bot, stock UT99 bot, human, world).
// Stored per-slot until consumed by WriteDamageEvent on the next state frame.
// Multiple events on the same victim within one frame collapse to last-wins.
function RecordDamage(Pawn injured, Pawn instigator, int amount, name dmgType)
{
    local int victimSlot, instSlot;

    victimSlot = LookupSlot(injured);
    if (victimSlot >= MAX_SLOTS) return;  // 255 = not in slot mapping

    LastDmgAmount[victimSlot] = amount;
    LastDmgType[victimSlot] = dmgType;
    if (instigator == injured)
        bLastDmgSelfInflicted[victimSlot] = 1;
    else
        bLastDmgSelfInflicted[victimSlot] = 0;

    if (instigator == None || instigator == injured)
    {
        LastDmgInstigatorSlot[victimSlot] = -1;
    }
    else
    {
        instSlot = LookupSlot(instigator);
        if (instSlot >= MAX_SLOTS)
            LastDmgInstigatorSlot[victimSlot] = -1;
        else
            LastDmgInstigatorSlot[victimSlot] = instSlot;
    }

    // Cumulatieve damage counters voor combat_score KPI:
    //  - DamageTakenTotal: alle damage die de victim incasseert (incl. self).
    //  - DamageDealtTotal: alleen wanneer instigator een geldige andere slot is
    //    (geen world-damage, geen self-damage).
    if (amount > 0)
    {
        DamageTakenTotal[victimSlot] = DamageTakenTotal[victimSlot] + amount;
        if (instigator != None && instigator != injured)
        {
            instSlot = LookupSlot(instigator);
            if (instSlot < MAX_SLOTS)
                DamageDealtTotal[instSlot] = DamageDealtTotal[instSlot] + amount;
        }
    }
}

// Damage event block (8 bytes) — last damage this player took since previous state frame:
//   flags uint8 (bit0 = has_damage, bit1 = self_inflicted)
//   amount uint16 LE
//   typeHash uint32 LE (FNV-1a of UnrealScript damage name, e.g. 'shredded', 'exploded')
//   instigatorSlot int8 (signed: -1 = unknown, 0..SlotCount-1 = matches SlotToPawn)
//
// Block is filled for ALL pawns (not just RL-bots) since RLCTFGame.ReduceDamage
// captures every damage event game-wide. Per-slot fields are consumed (reset)
// here so one event reports exactly once.
function WriteDamageEvent(int slot)
{
    local int flags, amount, typeHash, instSlot;

    flags = 0;
    amount = LastDmgAmount[slot];
    typeHash = 0;
    instSlot = LastDmgInstigatorSlot[slot];

    if (amount > 0)
    {
        flags = flags | 1;
        if (bLastDmgSelfInflicted[slot] != 0) flags = flags | 2;
        typeHash = FNV1aHash(string(LastDmgType[slot]));
    }

    // Consume the event — next frame starts clean unless new RecordDamage fires.
    LastDmgAmount[slot] = 0;
    LastDmgType[slot] = '';
    LastDmgInstigatorSlot[slot] = -1;
    bLastDmgSelfInflicted[slot] = 0;

    WriteByte(flags);
    WriteInt16(amount);
    WriteInt32(typeHash);
    WriteByte(instSlot & 255);  // signed byte (-1 → 0xFF); Java decodes as (byte).
}

function int GetTotalArmorOf(Pawn P)
{
    local Inventory Inv;
    local int total;
    total = 0;
    for (Inv = P.Inventory; Inv != None; Inv = Inv.Inventory)
        if (Inv.ArmorAbsorption > 0 && Inv.Charge > 0)
            total += Inv.Charge;
    return total;
}

// Weapon block (32 bytes):
//   classHash uint32, ammo uint16, maxAmmo uint16,
//   altDamageHash uint32,
//   fireOffsetX int16, fireOffsetY int16, fireOffsetZ int16,    (×10)
//   firingSpeed int16 (×100),
//   maxTargetRange int16 (×0.1 — compresses 16000 → 1600),
//   myDamageHash uint32,
//   pickupAmmo uint16,
//   weaponFlags uint8 (bit0..6 = bInstantHit bAltInstantHit bCanThrow
//                     bChangeWeapon bLockedOn bWeaponStay bWeaponUp),
//   weaponFlags2 uint8 (bit0 = bIsDual    — Enforcer dual-wield (stock SpawnCopy
//                                            flipt bIsDual=true; geen aparte
//                                            DoubleEnforcer-class)
//                       bit1 = bSniping   — Pawn.FOVAngle < DefaultFOV*0.8
//                                            (scope-zoom active)
//                       bit2 = bGrenadeMode — UT_Eightball.bGrenadeMode (rocket
//                                            launcher in grenade-fire mode)),
//   multiCount uint8   — UT_Eightball.MultiCount (0..6 loaded rockets/grenades),
//   chargeAmount uint8 — UT_BioRifle.ChargeSize  (0..255 alt-fire glob charge;
//                                                 stock max ~10 maar veld is int).
function WriteWeapon(Pawn P)
{
    local Weapon W;
    local int ammo, maxAmmo;
    local int weaponFlags;
    local int weaponFlags2;
    local int multiCount;
    local int chargeAmount;
    local int classHash, altHash, myHash;
    local Enforcer Enf;
    local UT_Eightball EB;
    local UT_BioRifle Bio;
    local PlayerPawn PP;
    local float fov, defaultFov;

    W = P.Weapon;
    if (W != None)
    {
        classHash = FNV1aHash(string(W.Class));
        if (W.AmmoType != None) { ammo = W.AmmoType.AmmoAmount; maxAmmo = W.AmmoType.MaxAmmo; }
        altHash = FNV1aHash(string(W.AltDamageType));
        myHash  = FNV1aHash(string(W.MyDamageType));
        if (W.bInstantHit)    weaponFlags = weaponFlags | 1;
        if (W.bAltInstantHit) weaponFlags = weaponFlags | 2;
        if (W.bCanThrow)      weaponFlags = weaponFlags | 4;
        if (W.bChangeWeapon)  weaponFlags = weaponFlags | 8;
        if (W.bLockedOn)      weaponFlags = weaponFlags | 16;
        if (W.bWeaponStay)    weaponFlags = weaponFlags | 32;
        if (W.bWeaponUp)      weaponFlags = weaponFlags | 64;

        // ── Weapon-specifieke state via class-cast ────────────────────
        // Cast naar concrete Botpack-klasse; faalt None bij andere wapens
        // (UScript resolveert klassennaam via geladen packages — Botpack
        // is auto-loaded, dus unqualified form werkt; zie
        // NeuralNetWebserver.uc:355 voor identiek patroon).
        // Botpack.Enforcer dual-wield detectie: stock UT99 heeft GEEN bIsDual veld
        // (zoals NeuralNetWebserver.uc lijn 357-364 toont, de echte velden zijn
        // slaveEnforcer / bIsSlave / bBringingUp / DoubleName). Dual = bot heeft
        // beide enforcers: actieve weapon heeft slaveEnforcer-pointer naar de
        // tweede, OF deze actor IS de slaaf van een primary.
        Enf = Enforcer(W);
        if (Enf != None && (Enf.slaveEnforcer != None || Enf.bIsSlave))
            weaponFlags2 = weaponFlags2 | 1;

        // UT_Eightball: stock UT99 heeft RocketsLoaded (0..6) en bFireLoad
        // (true tijdens alt-fire grenade-load — geen aparte bGrenadeMode-vlag).
        // Geverifieerd via `strings Botpack.u` op 2026-05-15.
        EB = UT_Eightball(W);
        if (EB != None)
        {
            if (EB.bFireLoad) weaponFlags2 = weaponFlags2 | 4;
            // bTightWad = bot hield altFire ingedrukt tijdens primary-load
            // → bij firing levert dit een gerichte rocket-straal (focused)
            // i.p.v. horizontale spread. Critical state-bit voor rocket-
            // launcher strategie: het model moet kunnen observeren of de
            // huidige multi-load tight-wad of spread mode is. Bit 8 in
            // weaponFlags2 — opvolger van bit 4 (bGrenadeMode).
            if (EB.bTightWad) weaponFlags2 = weaponFlags2 | 8;
            multiCount = EB.RocketsLoaded;
            if (multiCount < 0)   multiCount = 0;
            if (multiCount > 255) multiCount = 255;
        }

        Bio = UT_BioRifle(W);
        if (Bio != None)
        {
            chargeAmount = Bio.ChargeSize;
            if (chargeAmount < 0)   chargeAmount = 0;
            if (chargeAmount > 255) chargeAmount = 255;
        }
    }

    // Sniper-scope detectie via FOV-zoom: stock SniperRifle alt-fire
    // klemt FOVAngle naar ~20°. FOVAngle + DefaultFOV zitten op PlayerPawn
    // (niet op Pawn — UT99 bots zonder PlayerPawn-cast krijgen geen scope-bit).
    // Threshold = 80% van DefaultFOV vangt zoom-in en overshoot.
    PP = PlayerPawn(P);
    if (PP != None)
    {
        defaultFov = PP.DefaultFOV;
        if (defaultFov <= 0.0) defaultFov = 90.0;
        fov = PP.FOVAngle;
        if (fov > 0.0 && fov < defaultFov * 0.8)
            weaponFlags2 = weaponFlags2 | 2;
    }

    WriteInt32(classHash);
    WriteInt16(ammo);
    WriteInt16(maxAmmo);
    WriteInt32(altHash);
    if (W != None)
    {
        WriteInt16(int(W.FireOffSet.X * 10.0));
        WriteInt16(int(W.FireOffSet.Y * 10.0));
        WriteInt16(int(W.FireOffSet.Z * 10.0));
        WriteInt16(int(W.FiringSpeed * 100));
        WriteInt16(int(W.MaxTargetRange * 0.1));
        WriteInt32(myHash);
        WriteInt16(W.PickupAmmoCount);
    }
    else
    {
        WriteInt16(0); WriteInt16(0); WriteInt16(0);
        WriteInt16(0); WriteInt16(0);
        WriteInt32(0);
        WriteInt16(0);
    }
    WriteByte(weaponFlags);
    WriteByte(weaponFlags2);
    WriteByte(multiCount);
    WriteByte(chargeAmount);
}

// Inventory block:
//   count uint8,
//   items × 8 bytes { classHash uint32, ammo uint16, maxAmmo uint16 }
function WriteInventory(Pawn P)
{
    local Inventory Inv;
    local Weapon W;
    local int count;
    local int countOff;

    countOff = ScratchLen;
    WriteByte(0);   // placeholder

    for (Inv = P.Inventory; Inv != None; Inv = Inv.Inventory)
    {
        if (Inv.IsA('Weapon'))
        {
            if (count >= 16) break;   // cap at 16 weapons
            W = Weapon(Inv);
            WriteInt32(FNV1aHash(string(W.Class)));
            if (W.AmmoType != None)
            {
                WriteInt16(W.AmmoType.AmmoAmount);
                WriteInt16(W.AmmoType.MaxAmmo);
            }
            else
            {
                WriteInt16(0); WriteInt16(0);
            }
            count++;
        }
    }

    Scratch[countOff] = count & 255;
}

// Visibility bitmask uint32: bit N = "slot N (from SlotToPawn) is visible
// from P's eye". Bit for self is always 0.
function WriteVisibility(Pawn P)
{
    local int i;
    local int mask;
    local vector srcEye, tgtEye;
    local Pawn Q;

    srcEye = P.Location;
    srcEye.Z += P.BaseEyeHeight;

    for (i = 0; i < SlotCount; i++)
    {
        Q = SlotToPawn[i];
        if (Q == None || Q == P) continue;
        tgtEye = Q.Location;
        tgtEye.Z += Q.BaseEyeHeight;
        if (P.FastTrace(tgtEye, srcEye))
            mask = mask | (1 << i);
    }
    WriteInt32(mask);
}

// FlagLoS: 7 rays (center, ±15°, ±30°, ±45°) for each of the 2 flags,
// as uint8 ratios ×255. Total 14 bytes.
function WriteFlagLoS(Pawn P)
{
    local CTFReplicationInfo RI;
    local vector srcEye;
    local int i;

    srcEye = P.Location;
    srcEye.Z += P.BaseEyeHeight;

    RI = CTFReplicationInfo(GameRef.GameReplicationInfo);

    for (i = 0; i < 2; i++)
    {
        if (RI != None && RI.FlagList[i] != None)
            TraceFlagLosRays7(P, srcEye, RI.FlagList[i].Location);
        else
            WriteZero7();
    }
}

function WriteZero7()
{
    local int i;
    for (i = 0; i < 7; i++) WriteByte(0);
}

function TraceFlagLosRays7(Pawn P, vector SrcEye, vector FlagLoc)
{
    local vector Dir, OffDir;
    local float Dist;
    local rotator DirRot;
    local float ratios[7];
    local int i;

    Dir = FlagLoc - SrcEye;
    Dist = VSize(Dir);
    if (Dist < 1.0)
    {
        for (i = 0; i < 7; i++) WriteByte(255);
        return;
    }

    DirRot = rotator(Dir);
    DirRot.Pitch = 0;
    DirRot.Roll = 0;

    ratios[0] = LosTraceRatio(P, SrcEye, vector(DirRot), Dist);
    OffDir = YawDirection(DirRot, -2731);
    ratios[1] = LosTraceRatio(P, SrcEye, OffDir, Dist);
    OffDir = YawDirection(DirRot, 2731);
    ratios[2] = LosTraceRatio(P, SrcEye, OffDir, Dist);
    OffDir = YawDirection(DirRot, -5461);
    ratios[3] = LosTraceRatio(P, SrcEye, OffDir, Dist);
    OffDir = YawDirection(DirRot, 5461);
    ratios[4] = LosTraceRatio(P, SrcEye, OffDir, Dist);
    OffDir = YawDirection(DirRot, -8192);
    ratios[5] = LosTraceRatio(P, SrcEye, OffDir, Dist);
    OffDir = YawDirection(DirRot, 8192);
    ratios[6] = LosTraceRatio(P, SrcEye, OffDir, Dist);

    for (i = 0; i < 7; i++)
        WriteByte(int(ratios[i] * 255));
}

function float LosTraceRatio(Pawn P, vector SrcEye, vector Dir, float MaxDist)
{
    local vector E, HitLoc, HitNorm;
    local Actor HitActor;

    E = SrcEye + Normal(Dir) * MaxDist;
    HitActor = P.Trace(HitLoc, HitNorm, E, SrcEye, false);
    if (HitActor != None)
        return FClamp(VSize(HitLoc - SrcEye) / MaxDist, 0.0, 1.0);
    return 1.0;
}

// Collisions block:
//   maxDist int16, capsuleMargin uint8, reserved uint8,
//   distances × 32 uint16 (matches the 32-direction fan in NeuralNetWebserver.uc),
//   floorProbeDist uint16, floorMaxDrop uint16,
//   floorDelta × 8 int16  (SIGNED: +step-up / -drop; fwd, fwdRight, right, backRight, back, backLeft, left, fwdLeft),
//   lowDistances × 8 uint16 (foot-height horizontal rays; same 8 directions)
function WriteCollisions(Pawn P, rotator R)
{
    local rotator W;
    W.Yaw = 0; W.Pitch = 0; W.Roll = 0;

    WriteInt16(PROBE_MAX_DIST);
    WriteByte(PROBE_CAPSULE_MARGIN);
    WriteByte(0);

    // yaw cardinals (fwd/back/left/right)
    WriteInt16(TraceDistance(P, YawDirection(R, 0)));
    WriteInt16(TraceDistance(P, YawDirection(R, 32768)));
    WriteInt16(TraceDistance(P, YawDirection(R, 49152)));
    WriteInt16(TraceDistance(P, YawDirection(R, 16384)));

    // world axis (posX/negX/posY/negY)
    WriteInt16(TraceDistance(P, WorldPosX()));
    WriteInt16(TraceDistance(P, WorldNegX()));
    WriteInt16(TraceDistance(P, WorldPosY()));
    WriteInt16(TraceDistance(P, WorldNegY()));

    // yaw diagonals (12 directions matching the JSON order)
    WriteInt16(TraceDistance(P, YawDirection(R, 5461)));    // fwdRight30
    WriteInt16(TraceDistance(P, YawDirection(R, 8192)));    // fwdRight45
    WriteInt16(TraceDistance(P, YawDirection(R, 10923)));   // fwdRight60
    WriteInt16(TraceDistance(P, YawDirection(R, 21845)));   // backRight60
    WriteInt16(TraceDistance(P, YawDirection(R, 24576)));   // backRight45
    WriteInt16(TraceDistance(P, YawDirection(R, 27307)));   // backRight30
    WriteInt16(TraceDistance(P, YawDirection(R, 38229)));   // backLeft30
    WriteInt16(TraceDistance(P, YawDirection(R, 40960)));   // backLeft45
    WriteInt16(TraceDistance(P, YawDirection(R, 43691)));   // backLeft60
    WriteInt16(TraceDistance(P, YawDirection(R, 54613)));   // fwdLeft60
    WriteInt16(TraceDistance(P, YawDirection(R, 57344)));   // fwdLeft45
    WriteInt16(TraceDistance(P, YawDirection(R, 60075)));   // fwdLeft30

    // world axis diagonals (12 directions)
    WriteInt16(TraceDistance(P, YawDirection(W, 5461)));
    WriteInt16(TraceDistance(P, YawDirection(W, 8192)));
    WriteInt16(TraceDistance(P, YawDirection(W, 10923)));
    WriteInt16(TraceDistance(P, YawDirection(W, 21845)));
    WriteInt16(TraceDistance(P, YawDirection(W, 24576)));
    WriteInt16(TraceDistance(P, YawDirection(W, 27307)));
    WriteInt16(TraceDistance(P, YawDirection(W, 38229)));
    WriteInt16(TraceDistance(P, YawDirection(W, 40960)));
    WriteInt16(TraceDistance(P, YawDirection(W, 43691)));
    WriteInt16(TraceDistance(P, YawDirection(W, 54613)));
    WriteInt16(TraceDistance(P, YawDirection(W, 57344)));
    WriteInt16(TraceDistance(P, YawDirection(W, 60075)));

    // Floor-elevation fan (signed). Horizontal TraceDistance treats both void AND low
    // steps as clear space; these expose how the floor ahead rises/falls without using
    // NavigationPoint paths. Negative = drop, positive = step-up (jumpable threshold),
    // saturated high = wall.
    WriteInt16(FLOOR_PROBE_DIST);
    WriteInt16(FLOOR_MAX_DROP);
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 0)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 8192)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 16384)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 24576)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 32768)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 40960)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 49152)));
    WriteInt16(TraceFloorElevation(P, YawDirection(R, 57344)));

    // Foot-height horizontal rays (same 8 directions). A low obstacle blocks these while
    // the chest-height fan above passes over it — the contrast lets the model tell a
    // jumpable ledge/crate from a full wall.
    WriteInt16(TraceLowDistance(P, YawDirection(R, 0)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 8192)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 16384)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 24576)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 32768)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 40960)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 49152)));
    WriteInt16(TraceLowDistance(P, YawDirection(R, 57344)));
}

function int TraceDistance(Actor A, vector Dir)
{
    local vector S, E, nDir, HitLoc, HitNorm;
    local Actor HitActor;

    nDir = Normal(Dir);
    S   = A.Location;
    S.Z = S.Z + A.CollisionHeight * 0.5;
    S   = S + nDir * (A.CollisionRadius + PROBE_CAPSULE_MARGIN);
    E = S + nDir * float(PROBE_MAX_DIST);
    HitActor = A.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor != None)
        return int(VSize(HitLoc - S));
    return PROBE_MAX_DIST;
}

function int TraceFloorElevation(Pawn P, vector Dir)
{
    local vector S, E, nDir, HitLoc, HitNorm;
    local Actor HitActor;
    local float FootZ;

    nDir = Normal(Dir);
    nDir.Z = 0;
    nDir = Normal(nDir);

    // Down-trace from above foot level so step-ups (floor higher than feet) are caught,
    // not just drops. The start clears the jumpable band; obstacles taller than that
    // saturate and are disambiguated from drops by the foot-height low rays.
    FootZ = P.Location.Z - P.CollisionHeight;
    S = P.Location + nDir * float(FLOOR_PROBE_DIST + P.CollisionRadius);
    S.Z = FootZ + float(STEP_PROBE_TOP);

    E = S;
    E.Z = FootZ - float(FLOOR_MAX_DROP);

    HitActor = P.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor == None)
        return -FLOOR_MAX_DROP;  // no floor within range -> deep void (drop)

    // Signed: positive = floor ahead higher than feet (step-up), negative = drop.
    return int(FClamp(HitLoc.Z - FootZ, -float(FLOOR_MAX_DROP), float(STEP_PROBE_TOP)));
}

// Horizontal ray at foot height. The chest-height TraceDistance fan passes over a low
// obstacle; this catches it, so the model can tell a jumpable ledge from a full wall.
function int TraceLowDistance(Actor A, vector Dir)
{
    local vector S, E, nDir, HitLoc, HitNorm;
    local Actor HitActor;
    local float FootZ;

    nDir = Normal(Dir);
    nDir.Z = 0;
    nDir = Normal(nDir);

    FootZ = A.Location.Z - A.CollisionHeight;
    S = A.Location;
    S.Z = FootZ + float(LOW_RAY_HEIGHT);
    S = S + nDir * (A.CollisionRadius + PROBE_CAPSULE_MARGIN);
    E = S + nDir * float(PROBE_MAX_DIST);
    HitActor = A.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor != None)
        return int(VSize(HitLoc - S));
    return PROBE_MAX_DIST;
}

function vector YawDirection(rotator R, int YawOffset)
{
    local rotator D;
    D.Yaw = R.Yaw + YawOffset;
    D.Pitch = 0;
    D.Roll = 0;
    return Vector(D);
}

function vector WorldPosX() { local vector v; v.X =  1; v.Y =  0; v.Z = 0; return v; }
function vector WorldNegX() { local vector v; v.X = -1; v.Y =  0; v.Z = 0; return v; }
function vector WorldPosY() { local vector v; v.X =  0; v.Y =  1; v.Z = 0; return v; }
function vector WorldNegY() { local vector v; v.X =  0; v.Y = -1; v.Z = 0; return v; }

// ────────────────────────────────────────────────────────────────────
//  Projectiles (top-level TLV, tag 0x04)
// ────────────────────────────────────────────────────────────────────

// Per projectile (36 bytes):
//   classHash uint32, locX/Y/Z int32 (×10), velX/Y/Z int16 (×10),
//   speed uint16, damage uint16, instigatorNameHash uint32, instigatorTeam int8, reserved×3
function WriteProjectiles()
{
    local Projectile Proj;
    local string instigatorName;
    local int instigatorTeam;
    local int lenOff;

    foreach GameRef.AllActors(class'Projectile', Proj)
    {
        lenOff = BeginSection(4);

        instigatorName = "";
        instigatorTeam = -1;
        if (Proj.Instigator != None && Proj.Instigator.PlayerReplicationInfo != None)
        {
            instigatorName = Proj.Instigator.PlayerReplicationInfo.PlayerName;
            instigatorTeam = int(Proj.Instigator.PlayerReplicationInfo.Team);
        }

        WriteInt32(FNV1aHash(string(Proj.Class)));
        WriteInt32(int(Proj.Location.X * 10.0));
        WriteInt32(int(Proj.Location.Y * 10.0));
        WriteInt32(int(Proj.Location.Z * 10.0));
        WriteInt16(int(Proj.Velocity.X * 10.0));
        WriteInt16(int(Proj.Velocity.Y * 10.0));
        WriteInt16(int(Proj.Velocity.Z * 10.0));
        WriteInt16(int(Proj.Speed));
        WriteInt16(int(Proj.Damage));
        WriteInt32(FNV1aHash(instigatorName));
        WriteByte(instigatorTeam & 255);
        // drawScale byte: UC writes Min(int(DrawScale * 64), 255). Java parses
        // /64.0 → effective range [0, 3.98]. Vooral relevant voor BioGlob (alt-fire bio
        // blob): DrawScale = 1 + 0.8*ChargeSize ∈ [1, ~4.3]; voor andere projectielen
        // is DrawScale doorgaans 1.0.
        WriteByte(int(FMin(Proj.DrawScale * 64.0, 255.0)));
        WriteByte(0); WriteByte(0);

        EndSection(lenOff);
    }
}

// ────────────────────────────────────────────────────────────────────
//  Pickups (top-level TLV, tag 0x05)
// ────────────────────────────────────────────────────────────────────

// Per pickup (19 bytes):
//   classHash uint32, locX/Y/Z int32 (×10), bHidden byte,
//   remainingRespawnCentisec uint16  (0..65535, dwz 0..655.35s — voldoende
//   voor stock 180s SuperHealth respawn).
//
// "Available" semantiek = !bHidden. Java leest bHidden door en flipt naar
// `available` in PickupDto.
//
// Identity = Pickup-actor reference. UE1 destroyt pickups niet bij respawn —
// ze togglen bHidden en gebruiken Timer() om weer beschikbaar te worden.
// Daardoor blijft de Pickup-pointer stabiel over de hele match en kunnen we
// hem als identity-key in PickupRefs[] gebruiken (O(N²) lookup is OK: stock
// CTF maps ≤ 130 pickups, 60Hz send-rate → ~1M comparisons/sec, prima voor UC).
function int FindOrAddPickupSlot(Pickup P)
{
    local int i;
    for (i = 0; i < PickupCount; i++)
    {
        if (PickupRefs[i] == P) return i;
    }
    if (PickupCount >= MAX_PICKUPS) return -1;
    PickupRefs[PickupCount] = P;
    PickupTakenAt[PickupCount] = 0.0;
    PickupPrevHidden[PickupCount] = 0;
    PickupCount++;
    return PickupCount - 1;
}

function WritePickups()
{
    local Pickup P;
    local int lenOff;
    local int slot;
    local int isHidden;
    local float remaining;
    local int remainingCs;

    foreach GameRef.AllActors(class'Pickup', P)
    {
        slot = FindOrAddPickupSlot(P);
        if (slot < 0) continue;

        if (P.bHidden) isHidden = 1; else isHidden = 0;

        // Falling-edge available (bHidden 0→1): mark "just taken" timestamp
        if (isHidden == 1 && PickupPrevHidden[slot] == 0)
        {
            PickupTakenAt[slot] = Level.TimeSeconds;
        }
        PickupPrevHidden[slot] = byte(isHidden);

        // Compute remaining respawn time. RespawnTime=0 on weird/never-respawning
        // pickups; we send 0 remaining in that case (Java treats it as
        // "respawning indefinitely" — feature will normalize via pickup-types.json).
        if (isHidden == 1 && P.RespawnTime > 0.0)
        {
            remaining = P.RespawnTime - (Level.TimeSeconds - PickupTakenAt[slot]);
            if (remaining < 0.0) remaining = 0.0;
        }
        else
        {
            remaining = 0.0;
        }
        remainingCs = int(remaining * 100.0);
        if (remainingCs < 0) remainingCs = 0;
        if (remainingCs > 65535) remainingCs = 65535;

        lenOff = BeginSection(5);
        WriteInt32(FNV1aHash(string(P.Class)));
        WriteInt32(int(P.Location.X * 10.0));
        WriteInt32(int(P.Location.Y * 10.0));
        WriteInt32(int(P.Location.Z * 10.0));
        WriteByte(byte(isHidden));
        WriteInt16(remainingCs);
        EndSection(lenOff);
    }
}

// ────────────────────────────────────────────────────────────────────
//  Movers (top-level TLV, tag 0x06)
// ────────────────────────────────────────────────────────────────────

// Per mover (25 bytes):
//   nameHash uint32 (FNV1a of actor name — stable identity per map),
//   locX/Y/Z int32 (×10), keyNum uint8, prevKeyNum uint8,
//   numKeys uint8, stateFlags uint8 (bit0=bOpening, bit1=bDelaying),
//   moveProgress uint16 (PhysAlpha × 10000, range 0..10000)
function int FindOrAddMoverSlot(Mover M)
{
    local int i;
    for (i = 0; i < MoverCount; i++)
    {
        if (MoverRefs[i] == M) return i;
    }
    if (MoverCount >= MAX_MOVERS) return -1;
    MoverRefs[MoverCount] = M;
    MoverCount++;
    return MoverCount - 1;
}

function WriteMovers()
{
    local Mover M;
    local int lenOff;
    local int slot;
    local int stateFlags;
    local int progress;

    foreach GameRef.AllActors(class'Mover', M)
    {
        slot = FindOrAddMoverSlot(M);
        if (slot < 0) continue;

        stateFlags = 0;
        if (M.bOpening)  stateFlags = stateFlags | 1;
        if (M.bDelaying) stateFlags = stateFlags | 2;

        progress = int(M.PhysAlpha * 10000.0);
        if (progress < 0) progress = 0;
        if (progress > 10000) progress = 10000;

        lenOff = BeginSection(6);
        WriteInt32(FNV1aHash(string(M.Name)));
        WriteInt32(int(M.Location.X * 10.0));
        WriteInt32(int(M.Location.Y * 10.0));
        WriteInt32(int(M.Location.Z * 10.0));
        WriteByte(M.KeyNum);
        WriteByte(M.PrevKeyNum);
        WriteByte(M.NumKeys);
        WriteByte(stateFlags);
        WriteInt16(progress);
        EndSection(lenOff);
    }
}

// ────────────────────────────────────────────────────────────────────
//  Utility
// ────────────────────────────────────────────────────────────────────

// FNV-1a 32-bit hash of a string.
function int FNV1aHash(string s)
{
    local int h;
    local int i;
    local int c;
    h = -2128831035;
    for (i = 0; i < Len(s); i++)
    {
        c = Asc(Mid(s, i, 1));
        h = h ^ c;
        h = h * 16777619;
    }
    return h;
}

defaultproperties
{
    TargetPort=0
    FrameId=0
    SendCount=0
    SendIntervalSec=0.01667
    TimeAccum=0.0
    ScratchLen=0
    SlotCount=0
    PickupCount=0
    MoverCount=0
    LinkMode=MODE_Binary
    bAlwaysTick=True
}
