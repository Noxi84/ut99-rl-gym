class RLCTFGame extends CTFGame;

// ── Multi-bot configuration (parsed from URL parameters in InitGame) ──
// Up to 16 RLBots per server (array size). Java compacts the roster into two
// CSV params (?Apr= appearance table + ?RLBots=name|team|aprIdx,...) so even a
// full 16-bot roster stays under UT99's ~1024-char InitGame URL limit.
var int RLBotCount;
var int RLBotsSpawned;
var string RLBotNames[16];
var int    RLBotTeams[16];

// Per-bot appearance, resolved from the ?Apr= table via each bot's aprIdx.
// Empty string = keep RLBot.uc default look (TMale1 mesh + CommandoSkins).
var string RLBotMeshClasses[16];  // e.g. "TMale1Bot", "TFemale2Bot"
var string RLBotSkins[16];        // e.g. "CommandoSkins.cmdo"
var string RLBotFaces[16];        // e.g. "CommandoSkins.Blake"
var string RLBotVoices[16];       // e.g. "BotPack.VoiceMaleTwo"

// Deduplicated appearance table parsed from ?Apr= (cls|skin|face|voice per
// entry, comma-separated). Bots reference entries by index in ?RLBots=.
var string AprMesh[16];
var string AprSkin[16];
var string AprFace[16];
var string AprVoice[16];
var int    AprCount;

// Registry of spawned RLBots by index (matches RLBotNames/RLBotTeams ordering).
// Used by RLUdpCommandReceiver to dispatch per-bot commands by byte-index.
var RLBot  RLBots[16];

// UDP command receiver (binary protocol, replaces HTTP POST hot path).
var int    RLUdpPort;
var RLUdpCommandReceiver UdpReceiver;

// UDP state sender (binary protocol, replaces HTTP GET state pull).
var int    RLStateUdpPort;
var RLUdpStateSender StateSender;

// ── Dynamic team balancing: park/restore RLBots when humans join/leave ──
var int  RLBotParked[16];         // 1 = this bot slot is currently parked (removed from game)
var bool bInitialSpawnComplete;   // true after all initial bots have been spawned
// Saved appearance for parked bots is no longer needed: ApplyAppearance() re-runs
// from URL-config on every (re)spawn, so there's nothing to preserve across park.

// Periodic GC to free orphaned UWeb Request/Response objects
var float GCCountdown;
var float GCIntervalSeconds;

// ── Damage attribution hook ────────────────────────────────────────────
// Forward every damage event to the StateSender so it can record per-victim
// instigator slot. Pawn.TakeDamage funnels all damage (RL-bots, stock UT99
// bots, humans, world damage, splash) through Level.Game.ReduceDamage, so
// this is the single chokepoint for outgoing-damage attribution.
function int ReduceDamage(int Damage, name DamageType, pawn injured, pawn instigatedBy)
{
    local int reduced;
    reduced = Super.ReduceDamage(Damage, DamageType, injured, instigatedBy);
    if (reduced > 0 && StateSender != None && injured != None)
        StateSender.RecordDamage(injured, instigatedBy, reduced, DamageType);
    return reduced;
}

// ── Flag-event attribution hook ────────────────────────────────────────
// CTFGame.ScoreFlag is invoked for both captures (Scorer.Team != flag.Team)
// and returns (Scorer.Team == flag.Team). Beide takken pushen we naar de
// StateSender voor de KPI-counters die DeltaGate movement consumeert
// (Plan B: 1*taken + 7*captured + 3*returned aggregaat).
function ScoreFlag(Pawn Scorer, CTFFlag theFlag)
{
    Super.ScoreFlag(Scorer, theFlag);
    if (Scorer == None || theFlag == None || StateSender == None) return;
    if (Scorer.PlayerReplicationInfo == None) return;
    if (Scorer.PlayerReplicationInfo.Team == theFlag.Team)
        StateSender.RecordFlagReturn(Scorer, theFlag);
    else
        StateSender.RecordFlagCapture(Scorer, theFlag);
}

// ── Disable sudden-death overtime (RL training cadence) ───────────────
// Stock CTFGame.SetEndCams returns false on tied team scores at TimeLimit-hit,
// which makes Engine/GameInfo.EndGame() flip bOverTime=true and keep the match
// running until someone scores. For RL training we need every match to end on
// the TimeLimit clock so gate-eval windows and ServerTravel("?Restart") cadence
// stay predictable; ties just count as draws.
//
// Override: call Super.SetEndCams first (lets the parent do its winner-camera
// setup when there IS a clear winner). If it returns false (tie), replicate
// the winner-path pawn cleanup so the match ENDS like a normal winner-match:
// EndTime + RestartWait drives DeathMatchPlus.Timer() to RestartGame(); pawns
// go to 'GameEnded' state so they stop firing/moving cleanly (instead of being
// stuck mid-tick on a server with bGameEnded=true). No sudden-death overtime.
function bool SetEndCams(string Reason)
{
    local Pawn P;
    local PlayerPawn Player;

    if (Super.SetEndCams(Reason))
        return true;

    GameReplicationInfo.GameEndedComments = "Tied Match - Time Limit Reached";
    EndTime = Level.TimeSeconds + 3.0;
    for (P = Level.PawnList; P != None; P = P.nextPawn)
    {
        P.GotoState('GameEnded');
        Player = PlayerPawn(P);
        if (Player != None)
        {
            Player.bBehindView = true;
            Player.ClientGameEnded();
        }
    }
    Log("RLCTFGame: tied teams at TimeLimit -- forcing draw end (no overtime)");
    return true;
}

// ── Frag attribution hook (Plan A — shooting DeltaGate KPI) ────────────
// Stock UT99 telt frags impliciet via PlayerReplicationInfo.Score (+1 kill,
// -1 suicide, +7 cap, etc.). Voor pure-kills KPI heeft DeltaGate een aparte
// counter nodig zonder cap/return-noise. ScoreKill wordt door
// DeathMatchPlus.Killed() aangeroepen voor élk frag-event game-wide; we
// forwarden killer/victim naar de StateSender per-slot Frags[]-counter.
// Suicides (Killer == Other) worden door RecordKill genegeerd.
function ScoreKill(Pawn Killer, Pawn Other)
{
    Super.ScoreKill(Killer, Other);
    if (StateSender != None)
        StateSender.RecordKill(Killer, Other);
}

// Start match immediately without waiting for a human player.
// After initial spawning completes, return false so the game doesn't
// auto-spawn stock bots — we manage bot count via CheckTeamBalance.
function bool NeedPlayers()
{
    if (bInitialSpawnComplete)
        return false;
    if (NumBots >= MinPlayers)
    {
        bInitialSpawnComplete = true;
        Log("RLCTFGame: initial spawn complete (NumBots=" $ NumBots $ " MinPlayers=" $ MinPlayers $ ")");
        return false;
    }
    return true;
}

// ── Bot respawn delay (RL training throughput) ─────────────────────────
// Stock DeathMatchPlus.SpawnWait returns `NumBots * FRand()` — random 0..N
// seconden per respawn. Bij 6–8 RLBots betekent dat 3–4s gemiddelde
// "dead-time" waarin de bot geen experience produceert. Voor RL training
// is dat een directe sample-rate-tax. We willen near-instant respawn met
// minimale jitter (genoeg om gelijktijdige spawn-cascades na splash-deaths
// te spreiden, niet genoeg om throughput te kosten).
//
// 0.3 * FRand() = 0–300 ms random, gemiddeld 150 ms. Bovenop de baseline
// `Sleep(0.25 + ...)` in RLBot.Dying:Begin geeft dat ~0.25–0.55 s total
// respawn-wait vs. ~0.25–6.25 s stock. ServerReStartPlayer voor humans
// (bForceRespawn-pad) loopt via een andere code-path en is hier irrelevant.
function float SpawnWait(bot B)
{
    return 0.3 * FRand();
}

// Override SpawnBot: first N bots are RLBots, rest are stock AI.
function Bot SpawnBot(out NavigationPoint StartSpot)
{
    local RLBot NewRL;

    if (RLBotsSpawned < RLBotCount)
    {
        StartSpot = FindPlayerStart(None, 255);
        if (StartSpot == None)
        {
            Log("RLCTFGame: no start spot for RLBot " $ RLBotsSpawned);
            return None;
        }

        NewRL = Spawn(class'RLBot',,, StartSpot.Location, StartSpot.Rotation);
        if (NewRL == None)
        {
            Log("RLCTFGame: failed to spawn RLBot " $ RLBotsSpawned);
            return None;
        }

        NewRL.PlayerReplicationInfo.PlayerID   = CurrentID++;
        NewRL.PlayerReplicationInfo.PlayerName = RLBotNames[RLBotsSpawned];
        NewRL.PlayerReplicationInfo.bIsABot    = true;
        NewRL.PlayerReplicationInfo.Team       = RLBotTeams[RLBotsSpawned];
        NewRL.ViewRotation = StartSpot.Rotation;
        AddDefaultInventory(NewRL);
        NumBots++;
        NewRL.AirControl = AirControl;

        ApplyAppearance(NewRL, RLBotsSpawned);

        Log("RLCTFGame: RLBot[" $ RLBotsSpawned $ "] '" $ RLBotNames[RLBotsSpawned] $ "' spawned on team " $ RLBotTeams[RLBotsSpawned]);
        RLBots[RLBotsSpawned] = NewRL;
        RLBotsSpawned++;

        NewRL.GotoState('RLControlled');
        return NewRL;
    }

    // All subsequent bots: stock AI via parent class
    return Super.SpawnBot(StartSpot);
}

// Override AddBot to force every RLBot onto its configured team AFTER parent balancing.
function bool AddBot()
{
    local bool result;
    local Pawn P;
    local RLBot RL;
    local int i;

    result = Super.AddBot();

    // Force each RLBot to its configured team
    for (P = Level.PawnList; P != None; P = P.nextPawn)
    {
        if (P.IsA('RLBot'))
        {
            RL = RLBot(P);
            for (i = 0; i < RLBotsSpawned; i++)
            {
                if (RL.PlayerReplicationInfo.PlayerName == RLBotNames[i])
                {
                    if (RL.PlayerReplicationInfo.Team != RLBotTeams[i])
                    {
                        ChangeTeam(RL, RLBotTeams[i]);
                        ApplyAppearance(RL, i);
                        Log("RLCTFGame: forced '" $ RLBotNames[i] $ "' to team " $ RLBotTeams[i]);
                    }
                    break;
                }
            }
        }
    }

    return result;
}

// ── Dynamic team balancing ──────────────────────────────────────────────
// When a human player joins, park one RLBot from their team.
// When a human player leaves, restore a parked RLBot to that team.
// Desired players per team is derived from the configured RLBot roster.

event PostLogin(playerpawn NewPlayer)
{
    Super.PostLogin(NewPlayer);
    // Ignore RLBots (our managed bots) and spectators
    if (NewPlayer.IsA('RLBot'))
        return;
    if (Spectator(NewPlayer) != None)
        return;
    Log("RLCTFGame: human player '" $ NewPlayer.PlayerReplicationInfo.PlayerName $ "' joined team " $ NewPlayer.PlayerReplicationInfo.Team);
    CheckTeamBalance();
}

function Logout(pawn Exiting)
{
    local bool wasHuman;
    wasHuman = !Exiting.IsA('RLBot') && (Spectator(Exiting) == None)
               && Exiting.PlayerReplicationInfo != None;
    Super.Logout(Exiting);
    // Pawn may still be in PawnList during Logout — defer balance check to Timer
    if (wasHuman)
        Log("RLCTFGame: human player '" $ Exiting.PlayerReplicationInfo.PlayerName $ "' left");
}

// Count how many RLBots are configured for a given team (desired per team).
function int CountConfiguredForTeam(int Team)
{
    local int i, count;
    count = 0;
    for (i = 0; i < RLBotCount; i++)
    {
        if (RLBotTeams[i] == Team)
            count++;
    }
    return count;
}

// Count human (non-bot, non-spectator) players on a team.
function int CountHumansOnTeam(int Team)
{
    local Pawn P;
    local int count;
    count = 0;
    for (P = Level.PawnList; P != None; P = P.nextPawn)
    {
        if (P.PlayerReplicationInfo != None
            && !P.PlayerReplicationInfo.bIsABot
            && Spectator(P) == None
            && P.PlayerReplicationInfo.Team == Team)
            count++;
    }
    return count;
}

// Count active (non-parked) RLBots currently alive on a team.
function int CountActiveRLBotsOnTeam(int Team)
{
    local Pawn P;
    local int count;
    count = 0;
    for (P = Level.PawnList; P != None; P = P.nextPawn)
    {
        if (P.IsA('RLBot') && P.PlayerReplicationInfo != None
            && P.PlayerReplicationInfo.Team == Team)
            count++;
    }
    return count;
}

// Park one RLBot from the given team: destroy it and mark its slot as parked.
function ParkOneBot(int Team)
{
    local Pawn P;
    local RLBot RL;
    local int i;

    for (P = Level.PawnList; P != None; P = P.nextPawn)
    {
        if (P.IsA('RLBot'))
        {
            RL = RLBot(P);
            if (RL.PlayerReplicationInfo.Team == Team)
            {
                for (i = 0; i < RLBotCount; i++)
                {
                    if (RLBotNames[i] == RL.PlayerReplicationInfo.PlayerName)
                    {
                        RLBotParked[i] = 1;
                        RLBots[i] = None;
                        Log("RLCTFGame: parking '" $ RLBotNames[i] $ "' from team " $ Team);
                        Level.Game.DiscardInventory(RL);
                        RL.Destroy();
                        NumBots--;
                        return;
                    }
                }
            }
        }
    }
}

// Restore one parked RLBot to the given team. Returns true on success.
function bool RestoreOneBot(int Team)
{
    local int i;
    local RLBot NewRL;
    local NavigationPoint StartSpot;

    for (i = 0; i < RLBotCount; i++)
    {
        if (RLBotTeams[i] == Team && RLBotParked[i] == 1)
        {
            StartSpot = FindPlayerStart(None, 255);
            if (StartSpot == None)
            {
                Log("RLCTFGame: no start spot for restoring '" $ RLBotNames[i] $ "'");
                return false;
            }

            NewRL = Spawn(class'RLBot',,, StartSpot.Location, StartSpot.Rotation);
            if (NewRL == None)
            {
                Log("RLCTFGame: failed to spawn restored '" $ RLBotNames[i] $ "'");
                return false;
            }

            NewRL.PlayerReplicationInfo.PlayerID   = CurrentID++;
            NewRL.PlayerReplicationInfo.PlayerName = RLBotNames[i];
            NewRL.PlayerReplicationInfo.bIsABot    = true;
            NewRL.PlayerReplicationInfo.Team       = RLBotTeams[i];
            NewRL.ViewRotation = StartSpot.Rotation;
            AddDefaultInventory(NewRL);
            NumBots++;
            NewRL.AirControl = AirControl;

            ApplyAppearance(NewRL, i);

            RLBotParked[i] = 0;
            RLBots[i] = NewRL;
            Log("RLCTFGame: restored '" $ RLBotNames[i] $ "' to team " $ RLBotTeams[i]);

            NewRL.GotoState('RLControlled');
            return true;
        }
    }

    return false;
}

// Main balance logic: for each team, compare (humans + active bots) with desired count.
function CheckTeamBalance()
{
    local int team, desired, humans, activeBots;

    if (!bInitialSpawnComplete)
        return;

    for (team = 0; team < 2; team++)
    {
        desired = CountConfiguredForTeam(team);
        humans = CountHumansOnTeam(team);
        activeBots = CountActiveRLBotsOnTeam(team);

        // Too many players on this team — park RLBots
        while (humans + activeBots > desired && activeBots > 0)
        {
            ParkOneBot(team);
            activeBots--;
        }

        // Too few players on this team — restore parked RLBots
        while (humans + activeBots < desired)
        {
            if (!RestoreOneBot(team))
                break;
            activeBots++;
        }
    }
}

// Extract the idx-th field (0-based) from s, split on separator `sep`. Returns
// "" if the index is out of range. UE1 UnrealScript heeft geen ingebouwde split
// — we doen het handmatig met InStr/Mid/Left.
static function string NthItem(string s, int idx, string sep)
{
    local int pos, i;
    for (i = 0; i < idx; i++)
    {
        pos = InStr(s, sep);
        if (pos < 0) return "";
        s = Mid(s, pos + 1);
    }
    pos = InStr(s, sep);
    if (pos < 0) return s;
    return Left(s, pos);
}

// Count the number of `sep`-separated items in s. Empty string = 0.
static function int CountItems(string s, string sep)
{
    local int n, pos;
    if (s == "") return 0;
    n = 1;
    pos = InStr(s, sep);
    while (pos >= 0)
    {
        n++;
        s = Mid(s, pos + 1);
        pos = InStr(s, sep);
    }
    return n;
}

// Convenience wrapper: idx-th pipe-separated field.
static function string SplitPipe(string s, int idx)
{
    return NthItem(s, idx, "|");
}

// Parse URL parameters for multi-bot configuration. Two CSV params keep the
// URL under UT99's ~1024-char InitGame limit even at a full 16-bot roster:
//   ?Apr=cls|skin|face|voice,cls|skin|face|voice,...   (deduped appearances)
//   ?RLBots=name|team|aprIdx,name|team|aprIdx,...        (one field per bot)
// Earlier per-bot ?RLBotNName/Team/Apr keys overflowed at ~5 bots: the trailing
// bots AND the UDP ports got truncated, so late bots never spawned and the
// command receiver/state sender were never created (bots stood still, no
// PLAYER_SCORES). The Java side now emits the UDP ports before the roster too.
event InitGame(string Options, out string Error)
{
    local string InOpt;
    local string aprEntry, botEntry;
    local int i, aprIdx;

    Super.InitGame(Options, Error);

    InOpt = ParseOption(Options, "MinPlayers");
    if (InOpt != "")
        MinPlayers = int(InOpt);

    // ── Appearance table: ?Apr=cls|skin|face|voice,cls|skin|face|voice,... ──
    // Deduplicated by the Java side; bots reference entries by index in ?RLBots=.
    // Interning shared appearances keeps the URL under UT99's ~1024-char limit.
    InOpt = ParseOption(Options, "Apr");
    AprCount = 0;
    if (InOpt != "")
    {
        AprCount = Min(CountItems(InOpt, ","), 16);
        for (i = 0; i < AprCount; i++)
        {
            aprEntry    = NthItem(InOpt, i, ",");
            AprMesh[i]  = SplitPipe(aprEntry, 0);
            AprSkin[i]  = SplitPipe(aprEntry, 1);
            AprFace[i]  = SplitPipe(aprEntry, 2);
            AprVoice[i] = SplitPipe(aprEntry, 3);
        }
    }

    // ── Bot roster: ?RLBots=name|team|aprIdx,name|team|aprIdx,... ───────────
    // RLBotCount is derived from the roster length (no separate ?RLBotCount=).
    InOpt = ParseOption(Options, "RLBots");
    if (InOpt != "")
    {
        RLBotCount = Min(CountItems(InOpt, ","), 16);
        for (i = 0; i < RLBotCount; i++)
        {
            botEntry      = NthItem(InOpt, i, ",");
            RLBotNames[i] = SplitPipe(botEntry, 0);
            RLBotTeams[i] = int(SplitPipe(botEntry, 1));
            aprIdx        = int(SplitPipe(botEntry, 2));
            if (aprIdx >= 0 && aprIdx < AprCount)
            {
                RLBotMeshClasses[i] = AprMesh[aprIdx];
                RLBotSkins[i]       = AprSkin[aprIdx];
                RLBotFaces[i]       = AprFace[aprIdx];
                RLBotVoices[i]      = AprVoice[aprIdx];
            }
        }
    }

    InOpt = ParseOption(Options, "RLUdpPort");
    if (InOpt != "")
        RLUdpPort = int(InOpt);

    InOpt = ParseOption(Options, "RLStateUdpPort");
    if (InOpt != "")
        RLStateUdpPort = int(InOpt);

    // Disable stock team balancing — we force configured teams and handle
    // human park/restore with CheckTeamBalance().
    bPlayersBalanceTeams = false;
    bBalanceTeams = false;

    // ── Weapons-stay UIT (normaal CTF weapon-pickup gedrag) ────────────────
    // bMultiWeaponStay default True (DeathMatchPlus) + dedicated server ->
    // TournamentWeapon.SetWeaponStay() zet bWeaponStay=true op ELK gespawnd
    // wapen (Weapon.PostBeginPlay). Een gedropt wapen behoudt dat (DropFrom
    // reset het niet), en TournamentWeapon.HandlePickupQuery blokkeert dan
    // oppakken/ammo-overdracht zodra de oppakker dat wapen al heeft. Bij
    // weapon_profile=all heeft elke bot via PickupStripOnly.GiveFullArsenal
    // ALLE wapens -> élk gedropt wapen blijft onneembaar liggen, en map-wapens
    // geven geen ammo. False = stock CTF: gedropte + map-wapens weer oppakbaar
    // (ammo tot max), map-wapens respawnen na pickup. Hier gezet (vóór bots/
    // arsenaal spawnen) zodat hun wapens bWeaponStay=false lezen.
    bMultiWeaponStay = false;

    Log("RLCTFGame: InitGame RLBotCount=" $ RLBotCount $ " MinPlayers=" $ MinPlayers $ " stockTeamBalance=false RLUdpPort=" $ RLUdpPort $ " RLStateUdpPort=" $ RLStateUdpPort);
    for (i = 0; i < RLBotCount; i++)
    {
        Log("RLCTFGame:   RLBot[" $ i $ "] name=" $ RLBotNames[i]
            $ " team=" $ RLBotTeams[i]
            $ " class=" $ RLBotMeshClasses[i]
            $ " skin=" $ RLBotSkins[i]
            $ " face=" $ RLBotFaces[i]
            $ " voice=" $ RLBotVoices[i]);
    }
}

// Apply per-bot appearance: mesh-swap (if class != default RLBot mesh) + skin/face/voice.
// Called from SpawnBot (initial spawn) and RestoreOneBot (post-park respawn).
// Empty strings = skip that field (keep current).
function ApplyAppearance(RLBot Bot, int Idx)
{
    local class<Bot> AppearanceCls;
    local class<Bot> SkinDispatchCls;
    local class<ChallengeVoicePack> VoiceCls;

    if (Bot == None) return;

    // 1. Mesh swap. Load the Botpack bot class and copy its render/death defaults
    //    so the bot behaves like that character (TFemale2Bot vs TMale1Bot, etc.).
    if (RLBotMeshClasses[Idx] != "")
    {
        AppearanceCls = class<Bot>(
            DynamicLoadObject("Botpack." $ RLBotMeshClasses[Idx], class'Class'));
        if (AppearanceCls != None && AppearanceCls.default.Mesh != None)
        {
            Bot.Mesh = AppearanceCls.default.Mesh;
            Bot.DrawScale = AppearanceCls.default.DrawScale;
            Bot.SetCollisionSize(
                AppearanceCls.default.CollisionRadius,
                AppearanceCls.default.CollisionHeight);
            Bot.CarcassType = AppearanceCls.default.CarcassType;
            Bot.BaseEyeHeight = AppearanceCls.default.BaseEyeHeight;
            Bot.EyeHeight = Bot.BaseEyeHeight;
            Bot.bIsFemale = AppearanceCls.default.bIsFemale;
            Bot.FixedSkin = AppearanceCls.default.FixedSkin;
            Bot.FaceSkin = AppearanceCls.default.FaceSkin;
            Bot.TeamSkin1 = AppearanceCls.default.TeamSkin1;
            Bot.TeamSkin2 = AppearanceCls.default.TeamSkin2;
            Bot.DefaultSkinName = AppearanceCls.default.DefaultSkinName;
            Bot.DefaultPackage = AppearanceCls.default.DefaultPackage;
            Bot.drown = AppearanceCls.default.drown;
            Bot.breathagain = AppearanceCls.default.breathagain;
            Bot.HitSound1 = AppearanceCls.default.HitSound1;
            Bot.HitSound2 = AppearanceCls.default.HitSound2;
            Bot.HitSound3 = AppearanceCls.default.HitSound3;
            Bot.HitSound4 = AppearanceCls.default.HitSound4;
            Bot.Deaths[0] = AppearanceCls.default.Deaths[0];
            Bot.Deaths[1] = AppearanceCls.default.Deaths[1];
            Bot.Deaths[2] = AppearanceCls.default.Deaths[2];
            Bot.Deaths[3] = AppearanceCls.default.Deaths[3];
            Bot.Deaths[4] = AppearanceCls.default.Deaths[4];
            Bot.Deaths[5] = AppearanceCls.default.Deaths[5];
            Bot.GaspSound = AppearanceCls.default.GaspSound;
            Bot.UWHit1 = AppearanceCls.default.UWHit1;
            Bot.UWHit2 = AppearanceCls.default.UWHit2;
            Bot.LandGrunt = AppearanceCls.default.LandGrunt;
            Bot.JumpSound = AppearanceCls.default.JumpSound;
            Bot.StatusDoll = AppearanceCls.default.StatusDoll;
            Bot.StatusBelt = AppearanceCls.default.StatusBelt;
            Bot.VoicePackMetaClass = AppearanceCls.default.VoicePackMetaClass;
        }
        else
        {
            Log("RLCTFGame: ApplyAppearance['" $ RLBotNames[Idx] $ "'] failed to load class Botpack." $ RLBotMeshClasses[Idx]);
        }
    }

    // 2. Skin + face via SetMultiSkin. CRUCIAL: dispatch the static call on the
    //    mesh-specific bot class (TMale1Bot/TFemale2Bot/…). Each Botpack mesh
    //    uses different FixedSkin/FaceSkin/TeamSkin slots. A wrong dispatch
    //    writes valid textures into the wrong slots, which looks like broken
    //    colors or placeholder patches in game.
    if (RLBotSkins[Idx] != "" && RLBotFaces[Idx] != "")
    {
        SkinDispatchCls = AppearanceCls;
        if (SkinDispatchCls == None)
            SkinDispatchCls = class'Botpack.TMale1Bot';  // RLBot's superclass — same 0/1/2/3 layout
        SkinDispatchCls.static.SetMultiSkin(
            Bot, RLBotSkins[Idx], RLBotFaces[Idx], Bot.PlayerReplicationInfo.Team);
    }

    // 3. Voice pack (taunt class).
    if (RLBotVoices[Idx] != "")
    {
        VoiceCls = class<ChallengeVoicePack>(DynamicLoadObject(RLBotVoices[Idx], class'Class'));
        if (VoiceCls != None)
            Bot.PlayerReplicationInfo.VoiceType = VoiceCls;
        else
            Log("RLCTFGame: ApplyAppearance['" $ RLBotNames[Idx] $ "'] failed to load voice " $ RLBotVoices[Idx]);
    }
}

// Force match to start immediately (no countdown, no ready-up).
// Start periodic GC timer to prevent UWeb Request/Response object leak.
function PostBeginPlay()
{
    local Weapon W;

    Super.PostBeginPlay();
    bRequireReady = false;
    bNetReady     = false;

    // Corrigeer map-placed wapens die hun PostBeginPlay (-> SetWeaponStay) al
    // doorliepen vóór InitGame bMultiWeaponStay=false zette: zonder dit houden
    // zij bWeaponStay=true en blijven onneembaar / geven geen ammo. Respawn
    // (Sleeping->Pickup) spawnt geen nieuwe actor, dus deze waarde blijft staan.
    ForEach AllActors(class'Weapon', W)
        W.bWeaponStay = false;

    StartMatch();
    // Run garbage collection every 5 minutes to free orphaned WebRequest/WebResponse objects.
    // UWeb creates new(None) objects per HTTP request that are only freed by GC,
    // but GC never runs automatically on a headless server (no level transitions).
    // NOTE: parent class (DeathMatchPlus) also uses Timer() for bot spawning/game management.
    // We piggyback on its timer and track GC interval ourselves.
    GCCountdown = GCIntervalSeconds;

    // Spawn binary UDP command receiver (replaces HTTP POST hot path).
    // HTTP POST endpoint remains available for debugging via curl.
    if (RLUdpPort > 0)
    {
        UdpReceiver = Spawn(class'RLUdpCommandReceiver');
        if (UdpReceiver != None)
        {
            if (!UdpReceiver.Initialize(self, RLUdpPort))
            {
                Log("RLCTFGame: UdpReceiver.Initialize failed on port " $ RLUdpPort);
                UdpReceiver.Destroy();
                UdpReceiver = None;
            }
        }
        else
        {
            Log("RLCTFGame: failed to spawn RLUdpCommandReceiver");
        }
    }
    else
    {
        Log("RLCTFGame: RLUdpPort=0 - UDP command receiver disabled (HTTP POST only)");
    }

    // Spawn binary UDP state sender (push-based state transport).
    // HTTP GET endpoint remains available for debugging via curl.
    if (RLStateUdpPort > 0)
    {
        StateSender = Spawn(class'RLUdpStateSender');
        if (StateSender != None)
        {
            if (!StateSender.Initialize(self, RLStateUdpPort))
            {
                Log("RLCTFGame: StateSender.Initialize failed on port " $ RLStateUdpPort);
                StateSender.Destroy();
                StateSender = None;
            }
        }
        else
        {
            Log("RLCTFGame: failed to spawn RLUdpStateSender");
        }
    }
    else
    {
        Log("RLCTFGame: RLStateUdpPort=0 - UDP state sender disabled (HTTP GET only)");
    }
}

event Timer()
{
    Super.Timer();

    // Heal any command-routing registry desync first, so a frozen-but-alive
    // bot (commands dispatched to a stale/None RLBots[] slot) recovers within
    // ~1s instead of standing inert for the whole match.
    ResyncRLBotRegistry();

    // Periodic team balance check -- catches player leaves, team changes,
    // and any edge cases missed by PostLogin.
    CheckTeamBalance();

    GCCountdown -= 1.0;
    if (GCCountdown <= 0)
    {
        ConsoleCommand("obj garbage");
        GCCountdown = GCIntervalSeconds;
    }
}

// ── Self-heal the RLBots[] command-routing registry ────────────────────────
// UDP commands are dispatched by byte-index: RLUdpCommandReceiver writes each
// tick's action onto RLBots[botIdx]. SpawnBot/RestoreOneBot populate that
// array, but if a slot ever desyncs from the live pawn — a respawn/relogin
// path that bypasses them, or an auto-nulled reference after the pawn it held
// was destroyed — every command for that bot is silently dropped (receiver
// line "Bot == None → return"). The bot then freezes in place while still
// alive: its Java side keeps inferring and sending, but nothing reaches the
// pawn (Acceleration/ViewRotation/fire all stay at spawn defaults). Rebuilding
// each slot from PawnList by PlayerName makes routing robust no matter how the
// desync arose. Logs only when it actually heals a slot, so it doubles as the
// confirmation that a desync was the cause.
function ResyncRLBotRegistry()
{
    local Pawn P;
    local int i;

    for (i = 0; i < RLBotCount; i++)
    {
        if (RLBotParked[i] == 1)
            continue;  // parked slots are intentionally None — leave them
        if (RLBots[i] != None
            && RLBots[i].PlayerReplicationInfo != None
            && RLBots[i].PlayerReplicationInfo.PlayerName == RLBotNames[i])
            continue;  // slot already points at the right (named) pawn
        for (P = Level.PawnList; P != None; P = P.nextPawn)
        {
            if (P.IsA('RLBot')
                && P.PlayerReplicationInfo != None
                && P.PlayerReplicationInfo.PlayerName == RLBotNames[i])
            {
                if (RLBots[i] != RLBot(P))
                {
                    RLBots[i] = RLBot(P);
                    Log("RLCTFGame: resynced RLBots[" $ i $ "] -> '" $ RLBotNames[i]
                        $ "' (command-routing registry desync healed)");
                }
                break;
            }
        }
    }
}

// Called by NeuralNetWebserver to build projectile JSON.
// foreach AllActors() requires Actor context, which WebApplication doesn't have.
function string GetProjectilesJson()
{
    local Projectile P;
    local bool first;
    local string msg;
    local string instigatorName;
    local int instigatorTeam;

    first = true;
    foreach AllActors(class'Projectile', P) {
        if (!first) msg $= ",";
        instigatorName = "";
        instigatorTeam = -1;
        if (P.Instigator != None && P.Instigator.PlayerReplicationInfo != None) {
            instigatorName = P.Instigator.PlayerReplicationInfo.PlayerName;
            instigatorTeam = int(P.Instigator.PlayerReplicationInfo.Team);
        }
        msg $= "{";
        msg $= "\"Class\":\"" $ string(P.Class) $ "\",";
        msg $= "\"Location\":\"" $ string(P.Location) $ "\",";
        msg $= "\"Velocity\":\"" $ string(P.Velocity) $ "\",";
        msg $= "\"Speed\":\"" $ string(P.Speed) $ "\",";
        msg $= "\"Damage\":\"" $ string(P.Damage) $ "\",";
        msg $= "\"InstigatorName\":\"" $ instigatorName $ "\",";
        msg $= "\"InstigatorTeam\":\"" $ string(instigatorTeam) $ "\"";
        msg $= "}";
        first = false;
    }
    return msg;
}

defaultproperties
{
    bRequireReady=false
    RLBotCount=1
    RLBotsSpawned=0
    RLBotNames(0)="MrPython"
    RLBotTeams(0)=1
    bInitialSpawnComplete=false
    GCCountdown=300
    GCIntervalSeconds=300
}
