// Binary UDP command receiver for RL bots.
// Replaces the HTTP POST hot-path for per-tick action commands (fwd/back/fire/yaw/...).
// Packet format (12 bytes, little-endian uint16):
//   [0]    magic = 0xAA
//   [1]    botIdx (0..15, index into RLCTFGame.RLBots[]; bounds-checked vs RLBotCount)
//   [2]    flags bitmask (bit0..7 = fwd,back,left,right,jump,duck,fire,altfire)
//   [3]    dodge (0..8)
//   [4-5]  yaw   uint16 (UT rotation units 0..65535)
//   [6-7]  pitch uint16
//   [8-9]  moveYaw uint16
//   [10-11] seqNum uint16 (for drop detection)
//
// Suicide command (2 bytes):
//   [0]    magic = 0xAB
//   [1]    botIdx (0..15)
// Forceert RLBots[botIdx].PerformRLSuicide() — gebruikt door de Java-side
// AmmoDeadlockGuard wanneer alle living RLBots gelijktijdig zonder ammo zitten.
//
// Select-weapon command (6 bytes):
//   [0]    magic = 0xAC
//   [1]    botIdx (0..15)
//   [2-5]  weaponClassHash int32 (little-endian, FNV-1a van de UT99 class-string)
// Forceert RLBots[botIdx].RLSelectWeaponByHash(hash) — gestuurd door de Java-side
// CommandController wanneer de weapon-planner-lane een ander wapen kiest dan het actieve.
//
// Uses manual polling via Tick() + IsDataPending() + ReadBinary(). The RMODE_Event
// path is unreliable on OldUnreal dedicated servers for MODE_Binary — packets queue
// in the kernel socket but ReceivedBinary() never fires.
class RLUdpCommandReceiver extends IpDrv.UdpLink;

var RLCTFGame GameRef;
var int ListenPort;
var int PacketCount;
var int DropCount;
var int BadMagicCount;
var int BadIndexCount;
var int SuicideCount;
var int SelectWeaponCount;

function bool Initialize(RLCTFGame Game, int Port)
{
    GameRef = Game;
    ListenPort = Port;
    LinkMode = MODE_Binary;
    ReceiveMode = RMODE_Manual;

    if (BindPort(Port, false) == 0)
    {
        Log("RLUdpCommandReceiver: BindPort(" $ Port $ ") FAILED");
        return false;
    }
    Log("RLUdpCommandReceiver: listening on UDP port " $ Port);
    return true;
}

event Tick(float DeltaTime)
{
    local IpAddr Addr;
    local byte B[255];
    local int Count;
    local int guard;

    guard = 0;
    while (IsDataPending() && guard < 64)
    {
        Count = ReadBinary(Addr, 255, B);
        if (Count <= 0)
            break;
        ProcessPacket(Count, B);
        guard++;
    }
}

function ProcessPacket(int Count, byte B[255])
{
    local int botIdx, flags, dodge;
    local int yaw, pitch, moveYaw;
    local int selectHash;
    local RLBot Bot;

    if (Count < 2)
    {
        DropCount++;
        return;
    }

    // Suicide-command (2-byte): [0xAB][botIdx]
    if (B[0] == 171)  // 0xAB magic
    {
        if (GameRef == None)
        {
            DropCount++;
            return;
        }
        botIdx = int(B[1]);
        if (botIdx < 0 || botIdx >= GameRef.RLBotCount)
        {
            BadIndexCount++;
            return;
        }
        Bot = GameRef.RLBots[botIdx];
        if (Bot == None)
        {
            return;
        }
        SuicideCount++;
        Log("RLUdpCommandReceiver: suicide command bot=" $ botIdx
            $ " name=" $ Bot.PlayerReplicationInfo.PlayerName
            $ " total=" $ SuicideCount);
        Bot.PerformRLSuicide();
        return;
    }

    // Select-weapon-command (6-byte): [0xAC][botIdx][hash int32 LE]
    // Forceert RLBots[botIdx].RLSelectWeaponByHash(hash) — gestuurd door de Java-side
    // CommandController wanneer de weapon-planner een ander wapen kiest dan het actieve.
    if (B[0] == 172)  // 0xAC magic
    {
        if (Count < 6)
        {
            DropCount++;
            return;
        }
        if (GameRef == None)
        {
            DropCount++;
            return;
        }
        botIdx = int(B[1]);
        if (botIdx < 0 || botIdx >= GameRef.RLBotCount)
        {
            BadIndexCount++;
            return;
        }
        Bot = GameRef.RLBots[botIdx];
        if (Bot == None)
        {
            return;
        }
        // Reassemble the FNV-1a int32 from little-endian bytes (mirror of WriteInt32 /
        // Java ByteBuffer LE putInt). Byte 5 << 24 sets the sign bit when needed.
        selectHash = int(B[2]) | (int(B[3]) << 8) | (int(B[4]) << 16) | (int(B[5]) << 24);
        SelectWeaponCount++;
        Bot.RLSelectWeaponByHash(selectHash);
        return;
    }

    if (Count < 12)
    {
        DropCount++;
        return;
    }

    if (B[0] != 170)  // 0xAA magic
    {
        BadMagicCount++;
        return;
    }

    botIdx = int(B[1]);
    flags = int(B[2]);
    dodge = int(B[3]);
    yaw     = int(B[4]) + int(B[5]) * 256;
    pitch   = int(B[6]) + int(B[7]) * 256;
    moveYaw = int(B[8]) + int(B[9]) * 256;

    if (GameRef == None)
    {
        DropCount++;
        return;
    }

    if (botIdx < 0 || botIdx >= GameRef.RLBotCount)
    {
        BadIndexCount++;
        return;
    }

    Bot = GameRef.RLBots[botIdx];
    if (Bot == None)
    {
        // Parked bot — silently drop
        return;
    }

    Bot.bRLForward = ((flags & 1) != 0);
    Bot.bRLBack    = ((flags & 2) != 0);
    Bot.bRLLeft    = ((flags & 4) != 0);
    Bot.bRLRight   = ((flags & 8) != 0);
    Bot.bRLJump    = ((flags & 16) != 0);
    Bot.bRLDuck    = ((flags & 32) != 0);
    Bot.bRLFire    = ((flags & 64) != 0);
    Bot.bRLAltFire = ((flags & 128) != 0);
    Bot.RLDodgeAction = dodge;
    Bot.RLTargetYaw   = yaw;
    Bot.RLTargetPitch = pitch;
    Bot.RLMoveYaw     = moveYaw;

    PacketCount++;

    // Lightweight observability: log every 1000th packet
    if ((PacketCount % 1000) == 1)
    {
        Log("RLUdpCommandReceiver: count=" $ PacketCount
            $ " drops=" $ DropCount $ " badMagic=" $ BadMagicCount
            $ " badIdx=" $ BadIndexCount);
    }
}

defaultproperties
{
    ListenPort=0
    PacketCount=0
    DropCount=0
    BadMagicCount=0
    BadIndexCount=0
    SuicideCount=0
    SelectWeaponCount=0
    LinkMode=MODE_Binary
    bAlwaysTick=True
}
