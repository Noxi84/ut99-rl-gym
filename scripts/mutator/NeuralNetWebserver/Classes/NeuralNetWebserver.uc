class NeuralNetWebserver expands WebApplication;

/* Usage:
This is a web application which creates a REST webserver at http://server.ip.address/utneuralnet
It returns the current game status in json format.

[UWeb.WebServer]
Applications[0]="NeuralNetWebServer.NeuralNetWebServer"
ApplicationPaths[0]="/utneuralnet"
bEnabled=True

http://server.ip.address/utneuralnet
*/

event Query(WebRequest Request, WebResponse Response) {
    local string logMessage;

    // POST = command intent for RLBot
    if (Request.RequestType == Request_POST) {
        HandleRLCommand(Request, Response);
        return;
    }

    // GET = game state
    logMessage $= createMapInfo();
    logMessage $= createFlags();
    logMessage $= createPlayers();
    logMessage $= createProjectiles();

    Response.HTTPResponse("HTTP/1.1 200 OK");
    Response.HTTPHeader("Content-Type: application/json");
    Response.HTTPHeader("Connection: Close");
    Response.HTTPHeader("");
    Response.Connection.SendText("{" $ logMessage $ "}");
    return;
}

function HandleRLCommand(WebRequest Request, WebResponse Response)
{
    local int BotCount;
    local int i;
    local string Prefix;
    local string BotName;

    // Batch mode: b0_bot=MrBlue&b0_fwd=1&...&b1_bot=MrRed&b1_fwd=0&...&bc=2
    // Single mode (backward compat): bot=MrBlue&fwd=1&...
    BotCount = int(Request.GetVariable("bc", "0"));

    if (BotCount > 0)
    {
        // Batch mode: apply commands for each bot in one request
        for (i = 0; i < BotCount; i++)
        {
            Prefix = "b" $ i $ "_";
            BotName = Request.GetVariable(Prefix $ "bot", "");
            ApplyBotCommand(Request, Prefix, BotName);
        }
        Response.SendText("{\"status\":\"ok\"}");
    }
    else
    {
        // Single bot mode (backward compat)
        BotName = Request.GetVariable("bot", "");
        ApplyBotCommand(Request, "", BotName);
        Response.SendText("{\"status\":\"ok\"}");
    }
}

function ApplyBotCommand(WebRequest Request, string Prefix, string BotName)
{
    local RLBot Bot;
    local Pawn P;

    Bot = None;
    for (P = Level.PawnList; P != None; P = P.nextPawn)
    {
        if (P.IsA('RLBot'))
        {
            if (BotName == "" || P.PlayerReplicationInfo.PlayerName == BotName)
            {
                Bot = RLBot(P);
                break;
            }
        }
    }

    if (Bot == None)
        return;

    Bot.bRLForward  = (Request.GetVariable(Prefix $ "fwd", "0")     == "1");
    Bot.bRLBack     = (Request.GetVariable(Prefix $ "back", "0")    == "1");
    Bot.bRLLeft     = (Request.GetVariable(Prefix $ "left", "0")    == "1");
    Bot.bRLRight    = (Request.GetVariable(Prefix $ "right", "0")   == "1");
    Bot.bRLJump     = (Request.GetVariable(Prefix $ "jump", "0")    == "1");
    Bot.bRLDuck     = (Request.GetVariable(Prefix $ "duck", "0")    == "1");
    Bot.bRLFire     = (Request.GetVariable(Prefix $ "fire", "0")    == "1");
    Bot.bRLAltFire  = (Request.GetVariable(Prefix $ "altfire", "0") == "1");
    Bot.RLTargetYaw   = int(Request.GetVariable(Prefix $ "yaw", "0"));
    Bot.RLTargetPitch = int(Request.GetVariable(Prefix $ "pitch", "0"));
    Bot.RLMoveYaw     = int(Request.GetVariable(Prefix $ "moveYaw", "0"));
    Bot.RLDodgeAction = int(Request.GetVariable(Prefix $ "dodge", "0"));
}

function string createMapInfo() {
    local string msg;
    local CTFReplicationInfo RI;
    local int redScore, blueScore;

    RI = CTFReplicationInfo(Level.Game.GameReplicationInfo);
    if (RI != None) {
        redScore  = int(RI.Teams[0].Score);
        blueScore = int(RI.Teams[1].Score);
    }

    msg $= createJsonHeaderStart("MapInfo", false);
        msg $= createJsonField("MapName", String(Level), true);
        msg $= createJsonField("LevelTitle", Level.Title, true);
        msg $= createJsonField("GameName", Level.Game.GameReplicationInfo.GameName, true);
        msg $= createJsonField("GameClass", String(Level.Game.Class), true);
        msg $= createJsonField("TimeLimit", string(DeathMatchPlus(Level.Game).TimeLimit), true);
        msg $= createJsonField("GameType", "CTF", true);
        msg $= createJsonField("RedScore", string(redScore), true);
        msg $= createJsonField("BlueScore", string(blueScore), true);
        msg $= createJsonField("RemainingTime", string(DeathMatchPlus(Level.Game).RemainingTime), true);
        msg $= createJsonField("ElapsedTime", string(DeathMatchPlus(Level.Game).ElapsedTime), true);
        msg $= createJsonField("TimeDilation", string(Level.TimeDilation), true);
        msg $= createJsonField("bHardCoreMode", string(DeathMatchPlus(Level.Game).bHardCoreMode), true);
        msg $= createJsonField("bMegaSpeed", string(DeathMatchPlus(Level.Game).bMegaSpeed), true);
        msg $= createJsonField("bGameEnded", string(Level.Game.bGameEnded), false);
    msg $= createJsonHeaderEnd(true, false);
    return msg;
}

function string createFlags() {
    local string msg;
    local CTFReplicationInfo RI;
    local int i, maxTeams;
    local bool needComma;

    RI = CTFReplicationInfo(Level.Game.GameReplicationInfo);
    if (RI == None) {
        return createJsonHeaderStart("Flags", true) $ createJsonHeaderEnd(true, true);
    }

    maxTeams = 2; // UT99 CTF
    msg $= createJsonHeaderStart("Flags", true);
    for (i = 0; i < maxTeams; i++) {
        if (RI.FlagList[i] != None) {
            msg $= "{" $ createSingleFlag(RI.FlagList[i]) $ "}";
            needComma = (i < maxTeams - 1);
            if (needComma) msg $= ",";
        }
    }
    msg $= createJsonHeaderEnd(true, true);
    return msg;
}

function string createSingleFlag(CTFFlag F) {
    local string msg, holderName, statusText;
    local vector curLoc, baseLoc;
    local int teamIdx;

    teamIdx = F.Team;
    if (F.HomeBase != None)
        baseLoc = F.HomeBase.Location;
    else
        baseLoc = vect(0,0,0);

    if (F.bHome) {
        curLoc = baseLoc;
        statusText = "home";
    } else if (F.Holder != None) {
        curLoc = F.Holder.Location;
        if (F.Holder.PlayerReplicationInfo != None)
            holderName = F.Holder.PlayerReplicationInfo.PlayerName;
        else
            holderName = "UnknownHolder";
        statusText = "carried";
    } else {
        curLoc = F.Location;
        statusText = "dropped";
    }

    msg $= createJsonField("Team", string(teamIdx), true);
    msg $= createJsonField("Status", statusText, true);
    msg $= createJsonField("Location", string(curLoc), true);
    msg $= createJsonField("HomeBaseLocation", string(baseLoc), true);
    msg $= createJsonField("bHome", string(F.bHome), true);
    msg $= createJsonField("HasHolder", string(F.Holder != None), true);
    msg $= createJsonField("HolderName", holderName, true);
    msg $= CreateFlagCollisions(F, false);
    return msg;
}

function string createPlayers() {
    local string msg;
    local Pawn P;
    local int totalPlayers;
    local int playerCounter;
    local bool withComma;

    playerCounter = 0;

    for ( P=Level.PawnList; P!=None; P=P.nextPawn ) {
        if (Spectator(P) == None) {
            totalPlayers = totalPlayers + 1;
        }
    }

    msg $= createJsonHeaderStart("Players", true);
    for ( P=Level.PawnList; P!=None; P=P.nextPawn ) {
        if (Spectator(P) == None) {
            msg $= "{" $ createSinglePlayer(P, withComma) $ "}";
            playerCounter = playerCounter + 1;
            withComma = playerCounter < totalPlayers;
            if (withComma) {
                msg $= ",";
            }
        }
    }

    msg $= createJsonHeaderEnd(true, true);
    return msg;
}

function string createSinglePlayer(Pawn P, bool withComma) {
    local string msg;
    local int teamIdx;

    msg $= createJsonField("Name", P.PlayerReplicationInfo.PlayerName, true);
    msg $= createJsonField("Location", string(P.Location), true);
    msg $= createJsonField("BaseEyeHeight", string(P.BaseEyeHeight), true);
    // ViewRotation: use RLTargetPitch and RLTargetYaw from RLBot instead of engine's ViewRotation.
    // UT99 engine resets bot ViewRotation every tick (both pitch AND yaw go to 0).
    if (RLBot(P) != None)
        msg $= createJsonField("ViewRotation", RLBot(P).RLTargetPitch $ "," $ RLBot(P).RLTargetYaw $ "," $ 0, true);
    else
        msg $= createJsonField("ViewRotation", string(P.ViewRotation), true);
    msg $= createJsonField("Health", string(P.Health), true);
    // PRI.Team is a byte (0/1 in UT99 CTF, 255=None). Emit a stable int index.
    teamIdx = -1;
    if (P != None && P.PlayerReplicationInfo != None) {
        teamIdx = int(P.PlayerReplicationInfo.Team);
    }
    msg $= createJsonField("Team", string(teamIdx), true);
    msg $= createJsonField("Score", string(P.PlayerReplicationInfo.Score), true);
    msg $= createJsonField("HasFlag", string(P.PlayerReplicationInfo.HasFlag), true);
    msg $= createJsonField("OldLocation", string(P.OldLocation), true);
    msg $= createJsonField("bDuck", string(P.bDuck), true);
    msg $= createJsonField("bFire", string(P.bFire), true);
    msg $= createJsonField("bAltFire", string(P.bAltFire), true);
    msg $= createJsonField("Velocity", string(P.Velocity), true);
    msg $= createJsonField("Acceleration", string(P.Acceleration), true);
    msg $= createJsonField("Physics", string(int(P.Physics)), true);
    msg $= createJsonField("DodgeState", string(int(P.DodgeDir)), true);
    msg $= createJsonField("Deaths", string(int(P.PlayerReplicationInfo.Deaths)), true);
    msg $= createJsonField("Armor", string(GetTotalArmor(P)), true);
    msg $= createJsonField("bIsSpectator", string(P.PlayerReplicationInfo.bIsSpectator), true);
    msg $= createJsonField("bIsABot", string(P.PlayerReplicationInfo.bIsABot), true);
    msg $= createJsonField("bIsRLControlled", string(P.IsA('RLBot')), true);
    msg $= createJsonField("bWaitingPlayer", string(P.PlayerReplicationInfo.bWaitingPlayer), true);
    msg $= createJsonField("GroundSpeed", string(P.GroundSpeed), true);
    msg $= createJsonField("AirSpeed", string(P.AirSpeed), true);
    msg $= createJsonField("JumpZ", string(P.JumpZ), true);
    msg $= createJsonField("AirControl", string(P.AirControl), true);

    // Expose hold durations for RLBot (Java features pipeline needs these)
    if (P.IsA('RLBot'))
    {
        msg $= createJsonField("HoldForwardSec", string(RLBot(P).HoldForwardSec), true);
        msg $= createJsonField("HoldBackSec",    string(RLBot(P).HoldBackSec), true);
        msg $= createJsonField("HoldLeftSec",    string(RLBot(P).HoldLeftSec), true);
        msg $= createJsonField("HoldRightSec",   string(RLBot(P).HoldRightSec), true);
        msg $= createJsonField("HoldJumpSec",    string(RLBot(P).HoldJumpSec), true);
        msg $= createJsonField("HoldDuckSec",    string(RLBot(P).HoldDuckSec), true);
    }

    msg $= CreatePlayerCollisions(P, true);
    msg $= CreatePlayerVisibility(P, true);
    msg $= CreateFlagLineOfSight(P, true);
    msg $= createSinglePlayerWeapon(P, true);
    msg $= createInventory(P, false);
    return msg;
}

function string createProjectiles() {
    local string msg;
    local RLCTFGame Game;
    local RLNeuralNetHelper Helper;

    msg $= "\"Projectiles\":[";
    Game = RLCTFGame(Level.Game);
    if (Game != None) {
        // RL training path — gametype already has the helper.
        msg $= Game.GetProjectilesJson();
    } else {
        // Recording / non-RL path: gametype is Botpack.CTFGame (or similar).
        // Find or lazy-spawn an RLNeuralNetHelper to do the AllActors iteration
        // that a WebApplication cannot perform directly.
        Helper = findOrSpawnProjectileHelper();
        if (Helper != None) {
            msg $= Helper.GetProjectilesJson();
        }
    }
    msg $= "]";
    return msg;
}

function RLNeuralNetHelper findOrSpawnProjectileHelper() {
    local RLNeuralNetHelper H;

    if (Level == None) return None;
    foreach Level.AllActors(class'RLNeuralNetHelper', H) {
        return H;
    }
    return Level.Spawn(class'RLNeuralNetHelper');
}

function string createSinglePlayerWeapon(Pawn P, bool withComma) {
    local string msg;

    // Avoid "Accessed None 'Weapon'" spam (and expensive log overhead) when a pawn has no weapon yet.
    if (P == None || P.Weapon == None) {
        msg $= createJsonHeaderStart("Weapon", false);
        msg $= createJsonHeaderEnd(withComma, false);
        return msg;
    }

    msg $= createJsonHeaderStart("Weapon", false);
        msg $= createJsonField("WeaponClass", string(P.Weapon.Class), true);
        if (P.Weapon.AmmoType != None) {
            msg $= createJsonField("AmmoAmount", string(P.Weapon.AmmoType.AmmoAmount), true);
            msg $= createJsonField("MaxAmmo", string(P.Weapon.AmmoType.MaxAmmo), true);
        } else {
            msg $= createJsonField("AmmoAmount", "0", true);
            msg $= createJsonField("MaxAmmo", "0", true);
        }
        msg $= createJsonField("AltDamageType",string(P.Weapon.AltDamageType), true);
        msg $= createJsonField("FireOffSet", string(P.Weapon.FireOffSet), true);
        msg $= createJsonField("FiringSpeed", string(P.Weapon.FiringSpeed), true);
        msg $= createJsonField("MaxTargetRange", string(P.Weapon.MaxTargetRange), true);
        msg $= createJsonField("MyDamageType", string(P.Weapon.MyDamageType), true);
        msg $= createJsonField("PickupAmmoCount", string(P.Weapon.PickupAmmoCount), true);
        msg $= createJsonField("bInstantHit", string(P.Weapon.bInstantHit), true);
        msg $= createJsonField("bAltInstantHit", string(P.Weapon.bAltInstantHit), true);
        msg $= createJsonField("bCanThrow", string(P.Weapon.bCanThrow), true);
        msg $= createJsonField("bChangeWeapon", string(P.Weapon.bChangeWeapon), true);
        msg $= createJsonField("bLockedOn", string(P.Weapon.bLockedOn), true);
        msg $= createJsonField("bWeaponStay", string(P.Weapon.bWeaponStay), true);
        msg $= createJsonField("bWeaponUp", string(P.Weapon.bWeaponUp), true);

        msg $= createJsonHeaderStart("SubWeapon", false);
            if (TournamentWeapon(P.Weapon) != None) {
                msg $= createSinglePlayerTournamentWeapon(TournamentWeapon(P.Weapon), false);
            }
        msg $= createJsonHeaderEnd(false, false);
    msg $= createJsonHeaderEnd(withComma, false);

    return msg;
}

function string createSinglePlayerTournamentWeapon(TournamentWeapon W, bool withComma) {
    local string msg;

    msg $= createJsonHeaderStart("TournamentWeapon", false);

        msg $= createJsonField("bCanClientFire", string(W.bCanClientFire), true);

        if (Enforcer(W) != None) {
            msg $= createJsonHeaderStart("Enforcer", false);
                msg $= createJsonField("AltAccuracy", string(Enforcer(W).AltAccuracy), true);
                msg $= createJsonField("DoubleName", Enforcer(W).DoubleName, true);
                msg $= createJsonField("DoubleSwitchPriority", string(Enforcer(W).DoubleSwitchPriority), true);
                msg $= createJsonField("slaveEnforcer", string(Enforcer(W).slaveEnforcer), true);
                msg $= createJsonField("bBringingUp", string(Enforcer(W).bBringingUp), true);
                msg $= createJsonField("bIsSlave", string(Enforcer(W).bIsSlave), true);
                msg $= createJsonField("bSetup", string(Enforcer(W).bSetup), true);
                msg $= createJsonField("hitDamage", string(Enforcer(W).hitDamage), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (ImpactHammer(W) != None) {
            msg $= createJsonHeaderStart("ImpactHammer", false);
                msg $= createJsonField("count", string(ImpactHammer(W).count), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (Minigun2(W) != None) {
            msg $= createJsonHeaderStart("Minigun2", false);
                msg $= createJsonField("count", string(Minigun2(W).count), true);
                msg $= createJsonField("lastShellSpawn", string(Minigun2(W).lastShellSpawn), true);
                msg $= createJsonField("bFiredShot", string(Minigun2(W).bFiredShot), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (PulseGun(W) != None) {
            msg $= createJsonHeaderStart("PulseGun", false);
                msg $= createJsonField("count", string(PulseGun(W).count), true);
                msg $= createJsonField("plasmaBeam", string(PulseGun(W).plasmaBeam), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (Ripper(W) != None) {
            msg $= createJsonHeaderStart("Ripper", false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (ShockRifle(W) != None) {
            msg $= createJsonHeaderStart("ShockRifle", false);
                msg $= createJsonField("hitDamage", string(ShockRifle(W).hitDamage), true);
                msg $= createJsonField("tapTime", string(ShockRifle(W).tapTime), true);
                msg $= createJsonHeaderStart("tracked", false);
                    msg $= createJsonField("damage",  string(ShockRifle(W).tracked), true);
                    msg $= createJsonField("exploWallOut", "", true);
                    msg $= createJsonField("explosionDecal", "", true);
                    msg $= createJsonField("maxSpeed", "", true);
                    msg $= createJsonField("momentumTransfer", "", true);
                    msg $= createJsonField("myDamageType", "", true);
                    msg $= createJsonField("speed", "", false);
                msg $= createJsonHeaderEnd(true, false);
                msg $= createJsonField("bBotSpecialMove", "", false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (SniperRifle(W) != None) {
            msg $= createJsonHeaderStart("SniperRifle", false);
                msg $= createJsonField("numFire", string(SniperRifle(W).NumFire), true);
                msg $= createJsonField("ownerLocation", string(SniperRifle(W).OwnerLocation), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (Translocator(W) != None) {
            msg $= createJsonHeaderStart("Translocator", false);
                msg $= createJsonField("desiredTarget", string(Translocator(W).desiredTarget), true);
                msg $= createJsonField("fireDelay", string(Translocator(W).fireDelay), true);
                msg $= createJsonField("maxTossForce", string(Translocator(W).maxTossForce), true);
                msg $= createJsonField("previousWeapon", string(Translocator(W).previousWeapon), true);
                msg $= createJsonHeaderStart("tTarget", false);
                    msg $= createJsonField("desiredTarget", string(Translocator(W).tTarget), true);
                    msg $= createJsonField("disruptionThreshold", "", true);
                    msg $= createJsonField("disruptor", "", true);
                        msg $= createJsonHeaderStart("master", false);
                            msg $= createJsonField("desiredTarget", "", true);
                            msg $= createJsonField("fireDelay", "", true);
                            msg $= createJsonField("maxTossForce", "", true);
                            msg $= createJsonField("previousWeapon", "", true);
                            msg $= createJsonField("tTarget", "", true);
                            msg $= createJsonField("bTargetOut", "", false);
                        msg $= createJsonHeaderEnd(true, false);
                    msg $= createJsonField("realLocation", "", true);
                    msg $= createJsonField("spawnTime", "", true);
                    msg $= createJsonField("bTempDamage", "", false);
                msg $= createJsonHeaderEnd(true, false);
                msg $= createJsonField("bTempDamage", "", false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (UT_BioRifle(W) != None) {
            msg $= createJsonHeaderStart("UT_BioRifle", false);
                msg $= createJsonField("count", string(UT_BioRifle(W).count), true);
                msg $= createJsonField("bBurst", string(UT_BioRifle(W).bBurst), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (UT_Eightball(W) != None) {
            msg $= createJsonHeaderStart("UT_Eightball", false);
                msg $= createJsonField("clientRocketsLoaded", string(UT_Eightball(W).clientRocketsLoaded), true);
                msg $= createJsonField("oldTarget", string(UT_Eightball(W).oldTarget), true);
                msg $= createJsonField("bPendingLock", string(UT_Eightball(W).bPendingLock), true);
                msg $= createJsonField("bTightWad", string(UT_Eightball(W).bTightWad), false);
            msg $= createJsonHeaderEnd(false, false);
        }

        if (UT_FlakCannon(W) != None) {
            msg $= createJsonHeaderStart("UT_FlakCannon", false);
            msg $= createJsonHeaderEnd(false, false);
        }

    msg $= createJsonHeaderEnd(withComma, false);
    return msg;
}

function string createInventory(Pawn P, bool withComma)
{
    local string msg;
    local Inventory Inv;
    local Weapon W;
    local bool first;

    first = true;
    msg $= createJsonHeaderStart("Inventory", true);
    for (Inv = P.Inventory; Inv != None; Inv = Inv.Inventory) {
        if (Inv.IsA('Weapon')) {
            W = Weapon(Inv);
            if (!first) msg $= ",";
            msg $= "{";
            msg $= createJsonField("WeaponClass", string(W.Class), true);
            if (W.AmmoType != None) {
                msg $= createJsonField("AmmoAmount", string(W.AmmoType.AmmoAmount), true);
                msg $= createJsonField("MaxAmmo", string(W.AmmoType.MaxAmmo), false);
            } else {
                msg $= createJsonField("AmmoAmount", "0", true);
                msg $= createJsonField("MaxAmmo", "0", false);
            }
            msg $= "}";
            first = false;
        }
    }
    msg $= createJsonHeaderEnd(withComma, true);
    return msg;
}

function int GetTotalArmor(Pawn P)
{
    local Inventory Inv;
    local int total;

    total = 0;
    for (Inv = P.Inventory; Inv != None; Inv = Inv.Inventory) {
        if (Inv.ArmorAbsorption > 0 && Inv.Charge > 0) {
            total += Inv.Charge;
        }
    }
    return total;
}

function string CreatePlayerVisibility(Pawn Source, bool withComma)
{
    local Pawn P;
    local string msg;
    local bool first;
    local vector srcEye, tgtEye;
    local bool bVis;

    srcEye = Source.Location;
    srcEye.Z += Source.BaseEyeHeight;

    first = true;
    msg $= createJsonHeaderStart("Visibility", true);
    for (P = Level.PawnList; P != None; P = P.nextPawn) {
        if (P != Source && Spectator(P) == None && P.PlayerReplicationInfo != None) {
            tgtEye = P.Location;
            tgtEye.Z += P.BaseEyeHeight;
            bVis = Source.FastTrace(tgtEye, srcEye);
            if (!first) msg $= ",";
            msg $= "{";
            msg $= createJsonField("Name", P.PlayerReplicationInfo.PlayerName, true);
            msg $= createJsonField("bVisible", string(bVis), false);
            msg $= "}";
            first = false;
        }
    }
    msg $= createJsonHeaderEnd(withComma, true);
    return msg;
}

// Traces a ray from srcEye in direction Dir for MaxDist.
// Returns the ratio (0..1) of how far the ray gets before hitting geometry.
// 1.0 = ray reaches full distance (clear), 0.3 = blocked at 30% of distance.
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

// Fires 7 rays in a horizontal cone from srcEye toward flagLoc:
// center (0°), ±15°, ±30°, ±45°.
// Returns ratios as a comma-separated string: "center,left15,right15,left30,right30,left45,right45"
function string TraceFlagLosRays(Pawn P, vector SrcEye, vector FlagLoc)
{
    local string result;
    local vector Dir;
    local float Dist;
    local rotator DirRot;
    local vector OffDir;

    Dir = FlagLoc - SrcEye;
    Dist = VSize(Dir);
    if (Dist < 1.0)
        return "1.00,1.00,1.00,1.00,1.00,1.00,1.00";

    DirRot = rotator(Dir);
    DirRot.Pitch = 0;
    DirRot.Roll = 0;

    // center (0°)
    result = string(LosTraceRatio(P, SrcEye, vector(DirRot), Dist));

    // left 15° (yaw -2731)
    OffDir = YawDirection(DirRot, -2731);
    result $= "," $ string(LosTraceRatio(P, SrcEye, OffDir, Dist));
    // right 15° (yaw +2731)
    OffDir = YawDirection(DirRot, 2731);
    result $= "," $ string(LosTraceRatio(P, SrcEye, OffDir, Dist));

    // left 30° (yaw -5461)
    OffDir = YawDirection(DirRot, -5461);
    result $= "," $ string(LosTraceRatio(P, SrcEye, OffDir, Dist));
    // right 30° (yaw +5461)
    OffDir = YawDirection(DirRot, 5461);
    result $= "," $ string(LosTraceRatio(P, SrcEye, OffDir, Dist));

    // left 45° (yaw -8192)
    OffDir = YawDirection(DirRot, -8192);
    result $= "," $ string(LosTraceRatio(P, SrcEye, OffDir, Dist));
    // right 45° (yaw +8192)
    OffDir = YawDirection(DirRot, 8192);
    result $= "," $ string(LosTraceRatio(P, SrcEye, OffDir, Dist));

    return result;
}

function string CreateFlagLineOfSight(Pawn P, bool withComma)
{
    local string msg;
    local CTFReplicationInfo RI;
    local vector srcEye;

    msg $= createJsonHeaderStart("FlagLineOfSight", false);

    RI = CTFReplicationInfo(Level.Game.GameReplicationInfo);
    if (RI != None && P != None)
    {
        srcEye = P.Location;
        srcEye.Z += P.BaseEyeHeight;

        if (RI.FlagList[0] != None)
            msg $= createJsonField("RedFlag", TraceFlagLosRays(P, srcEye, RI.FlagList[0].Location), true);
        else
            msg $= createJsonField("RedFlag", "0.00,0.00,0.00,0.00,0.00,0.00,0.00", true);

        if (RI.FlagList[1] != None)
            msg $= createJsonField("BlueFlag", TraceFlagLosRays(P, srcEye, RI.FlagList[1].Location), false);
        else
            msg $= createJsonField("BlueFlag", "0.00,0.00,0.00,0.00,0.00,0.00,0.00", false);
    }
    else
    {
        msg $= createJsonField("RedFlag", "0.00,0.00,0.00,0.00,0.00,0.00,0.00", true);
        msg $= createJsonField("BlueFlag", "0.00,0.00,0.00,0.00,0.00,0.00,0.00", false);
    }

    msg $= createJsonHeaderEnd(withComma, false);
    return msg;
}

function string createJsonHeaderStart(string key, bool withArray) {
    local string msg;
    msg = "\"" $ key $ "\": ";
    if (withArray) {
        msg $= "[";
    } else {
        msg $= "{";
    }
    return msg;
}

function string createJsonHeaderEnd(bool appendComma, bool withArray) {
    local string msg;

    if (withArray) {
        msg $= "]";
    } else {
        msg $= "}";
    }
    if (appendComma) {
        msg $= ",";
    }
    return msg;
}

function string createJsonField(string key, string value, bool appendComma) {
    local string msg;
    msg = "\"" $ key $ "\": " $ "\"" $ value $ "\"";
    if (appendComma) {
        msg $= ",";
    }
    return msg;
}

// -------------------------
// Collisions: robust + stabiel in hoeken
// UT99 levert RAW distances (uu) + metadata (maxDist etc).
// Normalisatie gebeurt in Java.
// -------------------------

function rotator YawOnly(rotator R)
{
    R.Pitch = 0;
    R.Roll  = 0;
    return R;
}

function vector YawForward(rotator R) { return Vector(YawOnly(R)); }
function vector YawRight  (rotator R) { return Vector(YawOnly(R) + rot(0,16384,0)); }

function vector YawDirection(rotator R, int YawOffset)
{
    local rotator DirRot;
    DirRot = YawOnly(R);
    DirRot.Yaw = DirRot.Yaw + YawOffset;
    return Vector(DirRot);
}

// Single Trace() call per direction — replaces the old FastTrace binary search
// (1 native call instead of 20-44 per direction)
function int TraceDistance(Actor A, vector Dir, int MaxDist, float CapsuleMargin)
{
    local vector S, E, nDir, HitLoc, HitNorm;
    local Actor HitActor;

    nDir = Normal(Dir);

    S   = A.Location;
    S.Z = S.Z + A.CollisionHeight * 0.5;
    S   = S + nDir * (A.CollisionRadius + CapsuleMargin);

    E = S + nDir * float(MaxDist);

    HitActor = A.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor != None)
        return int(VSize(HitLoc - S));
    return MaxDist;
}

// Signed floor-elevation fan. Down-trace from above foot level (StepTop) so step-ups are
// caught, not just drops. Negative = drop, positive = step-up (jumpable threshold),
// saturated high = wall. Mirrors RLUdpStateSender.TraceFloorElevation.
function int TraceFloorElevation(Pawn P, vector Dir, int ProbeDist, int MaxDrop, int StepTop)
{
    local vector S, E, nDir, HitLoc, HitNorm;
    local Actor HitActor;
    local float FootZ;

    nDir = Normal(Dir);
    nDir.Z = 0;
    nDir = Normal(nDir);

    FootZ = P.Location.Z - P.CollisionHeight;
    S = P.Location + nDir * float(ProbeDist + P.CollisionRadius);
    S.Z = FootZ + float(StepTop);

    E = S;
    E.Z = FootZ - float(MaxDrop);

    HitActor = P.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor == None)
        return -MaxDrop;  // no floor within range -> deep void (drop)

    return int(FClamp(HitLoc.Z - FootZ, -float(MaxDrop), float(StepTop)));
}

// Horizontal ray at foot height (RayHeight above the feet). The chest-height TraceDistance
// fan passes over a low obstacle; this catches it. Mirrors RLUdpStateSender.TraceLowDistance.
function int TraceLowDistance(Actor A, vector Dir, int MaxDist, float CapsuleMargin, int RayHeight)
{
    local vector S, E, nDir, HitLoc, HitNorm;
    local Actor HitActor;
    local float FootZ;

    nDir = Normal(Dir);
    nDir.Z = 0;
    nDir = Normal(nDir);

    FootZ = A.Location.Z - A.CollisionHeight;
    S = A.Location;
    S.Z = FootZ + float(RayHeight);
    S = S + nDir * (A.CollisionRadius + CapsuleMargin);
    E = S + nDir * float(MaxDist);
    HitActor = A.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor != None)
        return int(VSize(HitLoc - S));
    return MaxDist;
}

// Trace strictly downward from the pawn's foot position. Returns the distance
// (UU) from foot to the first floor hit, clamped to MaxDist. Used for the
// egocentric self_floorBelow_norm feature — map-onafhankelijke vertical context
// (mid-air vs grounded, lift platform height, drop depth).
function int TraceFloorBelow(Pawn P, int MaxDist)
{
    local vector S, E, HitLoc, HitNorm;
    local Actor HitActor;
    local float FootZ;

    FootZ = P.Location.Z - P.CollisionHeight;
    S = P.Location;
    S.Z = FootZ;

    E = S;
    E.Z = FootZ - float(MaxDist);

    HitActor = P.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor == None)
        return MaxDist;

    return int(FClamp(FootZ - HitLoc.Z, 0.0, float(MaxDist)));
}

// Trace strictly upward from the pawn's head position. Returns the distance
// (UU) from head to the first ceiling hit, clamped to MaxDist. Used for the
// egocentric self_ceilingAbove_norm feature — headroom signaal voor jumps en
// rocket-jump viability.
function int TraceCeilingAbove(Pawn P, int MaxDist)
{
    local vector S, E, HitLoc, HitNorm;
    local Actor HitActor;
    local float HeadZ;

    HeadZ = P.Location.Z + P.CollisionHeight;
    S = P.Location;
    S.Z = HeadZ;

    E = S;
    E.Z = HeadZ + float(MaxDist);

    HitActor = P.Trace(HitLoc, HitNorm, E, S, false);
    if (HitActor == None)
        return MaxDist;

    return int(FClamp(HitLoc.Z - HeadZ, 0.0, float(MaxDist)));
}

function vector WorldPosX() { local vector v; v.X = 1; v.Y = 0; v.Z = 0; return v; }
function vector WorldNegX() { local vector v; v.X = -1; v.Y = 0; v.Z = 0; return v; }
function vector WorldPosY() { local vector v; v.X = 0; v.Y = 1; v.Z = 0; return v; }
function vector WorldNegY() { local vector v; v.X = 0; v.Y = -1; v.Z = 0; return v; }

function string CreatePlayerCollisions(Pawn P, bool withComma)
{
    local string Msg;
    local rotator R;
    local int MaxDist;
    local float CapsuleMargin;
    local int dF, dB, dL, dR;
    local int dPosX, dNegX, dPosY, dNegY;
    local int dFR30, dFR45, dFR60;
    local int dBR60, dBR45, dBR30;
    local int dBL30, dBL45, dBL60;
    local int dFL60, dFL45, dFL30;
    local rotator W;
    local int wXY30, wXY45, wXY60;
    local int wNXY60, wNXY45, wNXY30;
    local int wNXNY30, wNXNY45, wNXNY60;
    local int wXNY60, wXNY45, wXNY30;
    local int FloorProbeDist, FloorMaxDrop, StepProbeTop, LowRayHeight;
    local int floorF, floorFR, floorR, floorBR;
    local int floorB, floorBL, floorL, floorFL;
    local int lowF, lowFR, lowR, lowBR;
    local int lowB, lowBL, lowL, lowFL;
    local int VerticalMaxDist;
    local int floorBelow, ceilingAbove;

    // Use RLTargetYaw for collision raycasts (engine resets P.ViewRotation to 0 for bots)
    if (RLBot(P) != None)
    {
        R.Yaw = RLBot(P).RLTargetYaw;
        R.Pitch = RLBot(P).RLTargetPitch;
        R.Roll = 0;
    }
    else
        R = P.ViewRotation;

    MaxDist       = 1200;
    CapsuleMargin = 3.0;
    FloorProbeDist = 160;
    FloorMaxDrop = 1600;
    StepProbeTop = 96;
    LowRayHeight = 8;

    // yaw-relative cardinals (0=fwd, 16384=right, 32768=back, 49152=left)
    dF = TraceDistance(P, YawDirection(R, 0),     MaxDist, CapsuleMargin);
    dB = TraceDistance(P, YawDirection(R, 32768), MaxDist, CapsuleMargin);
    dL = TraceDistance(P, YawDirection(R, 49152), MaxDist, CapsuleMargin);
    dR = TraceDistance(P, YawDirection(R, 16384), MaxDist, CapsuleMargin);

    // world-axis
    dPosX = TraceDistance(P, WorldPosX(), MaxDist, CapsuleMargin);
    dNegX = TraceDistance(P, WorldNegX(), MaxDist, CapsuleMargin);
    dPosY = TraceDistance(P, WorldPosY(), MaxDist, CapsuleMargin);
    dNegY = TraceDistance(P, WorldNegY(), MaxDist, CapsuleMargin);

    // yaw-relative diagonals (30deg=5461, 45deg=8192, 60deg=10923 rot units)
    dFR30 = TraceDistance(P, YawDirection(R, 5461),  MaxDist, CapsuleMargin);
    dFR45 = TraceDistance(P, YawDirection(R, 8192),  MaxDist, CapsuleMargin);
    dFR60 = TraceDistance(P, YawDirection(R, 10923), MaxDist, CapsuleMargin);
    dBR60 = TraceDistance(P, YawDirection(R, 21845), MaxDist, CapsuleMargin);
    dBR45 = TraceDistance(P, YawDirection(R, 24576), MaxDist, CapsuleMargin);
    dBR30 = TraceDistance(P, YawDirection(R, 27307), MaxDist, CapsuleMargin);
    dBL30 = TraceDistance(P, YawDirection(R, 38229), MaxDist, CapsuleMargin);
    dBL45 = TraceDistance(P, YawDirection(R, 40960), MaxDist, CapsuleMargin);
    dBL60 = TraceDistance(P, YawDirection(R, 43691), MaxDist, CapsuleMargin);
    dFL60 = TraceDistance(P, YawDirection(R, 54613), MaxDist, CapsuleMargin);
    dFL45 = TraceDistance(P, YawDirection(R, 57344), MaxDist, CapsuleMargin);
    dFL30 = TraceDistance(P, YawDirection(R, 60075), MaxDist, CapsuleMargin);

    // world-axis diagonals (W = zero rotator, so Yaw 0 = +X direction)
    W.Yaw = 0; W.Pitch = 0; W.Roll = 0;
    wXY30  = TraceDistance(P, YawDirection(W, 5461),  MaxDist, CapsuleMargin);
    wXY45  = TraceDistance(P, YawDirection(W, 8192),  MaxDist, CapsuleMargin);
    wXY60  = TraceDistance(P, YawDirection(W, 10923), MaxDist, CapsuleMargin);
    wNXY60 = TraceDistance(P, YawDirection(W, 21845), MaxDist, CapsuleMargin);
    wNXY45 = TraceDistance(P, YawDirection(W, 24576), MaxDist, CapsuleMargin);
    wNXY30 = TraceDistance(P, YawDirection(W, 27307), MaxDist, CapsuleMargin);
    wNXNY30 = TraceDistance(P, YawDirection(W, 38229), MaxDist, CapsuleMargin);
    wNXNY45 = TraceDistance(P, YawDirection(W, 40960), MaxDist, CapsuleMargin);
    wNXNY60 = TraceDistance(P, YawDirection(W, 43691), MaxDist, CapsuleMargin);
    wXNY60 = TraceDistance(P, YawDirection(W, 54613), MaxDist, CapsuleMargin);
    wXNY45 = TraceDistance(P, YawDirection(W, 57344), MaxDist, CapsuleMargin);
    wXNY30 = TraceDistance(P, YawDirection(W, 60075), MaxDist, CapsuleMargin);

    // Vertical egocentric probes — strictly down / up from the pawn capsule.
    // Map-onafhankelijke "ben ik mid-air / hoeveel headroom heb ik" signaal.
    VerticalMaxDist = 2048;
    floorBelow   = TraceFloorBelow(P, VerticalMaxDist);
    ceilingAbove = TraceCeilingAbove(P, VerticalMaxDist);

    // Floor-elevation fan: sample the floor in the 8 movement sectors. Horizontal collision
    // rays see empty air AND low steps as "clear"; these expose drops (negative) and
    // jumpable step-ups (positive).
    floorF  = TraceFloorElevation(P, YawDirection(R, 0),     FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorFR = TraceFloorElevation(P, YawDirection(R, 8192),  FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorR  = TraceFloorElevation(P, YawDirection(R, 16384), FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorBR = TraceFloorElevation(P, YawDirection(R, 24576), FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorB  = TraceFloorElevation(P, YawDirection(R, 32768), FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorBL = TraceFloorElevation(P, YawDirection(R, 40960), FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorL  = TraceFloorElevation(P, YawDirection(R, 49152), FloorProbeDist, FloorMaxDrop, StepProbeTop);
    floorFL = TraceFloorElevation(P, YawDirection(R, 57344), FloorProbeDist, FloorMaxDrop, StepProbeTop);

    // Foot-height horizontal rays (same 8 sectors). Low obstacles block these while the
    // chest-height fan passes over them.
    lowF  = TraceLowDistance(P, YawDirection(R, 0),     MaxDist, CapsuleMargin, LowRayHeight);
    lowFR = TraceLowDistance(P, YawDirection(R, 8192),  MaxDist, CapsuleMargin, LowRayHeight);
    lowR  = TraceLowDistance(P, YawDirection(R, 16384), MaxDist, CapsuleMargin, LowRayHeight);
    lowBR = TraceLowDistance(P, YawDirection(R, 24576), MaxDist, CapsuleMargin, LowRayHeight);
    lowB  = TraceLowDistance(P, YawDirection(R, 32768), MaxDist, CapsuleMargin, LowRayHeight);
    lowBL = TraceLowDistance(P, YawDirection(R, 40960), MaxDist, CapsuleMargin, LowRayHeight);
    lowL  = TraceLowDistance(P, YawDirection(R, 49152), MaxDist, CapsuleMargin, LowRayHeight);
    lowFL = TraceLowDistance(P, YawDirection(R, 57344), MaxDist, CapsuleMargin, LowRayHeight);

    Msg $= createJsonHeaderStart("Collisions", false);
        Msg $= createJsonField("maxDist", string(MaxDist), true);
        Msg $= createJsonField("stepCoarse", "0", true);
        Msg $= createJsonField("capsuleMargin", string(CapsuleMargin), true);
        Msg $= createJsonField("immediateProbeUu", "0", true);

        // cardinals (existing field names — unchanged)
        Msg $= createJsonField("fwd_collision",   string(dF), true);
        Msg $= createJsonField("back_collision",  string(dB), true);
        Msg $= createJsonField("left_collision",  string(dL), true);
        Msg $= createJsonField("right_collision", string(dR), true);

        Msg $= createJsonField("posX_collision", string(dPosX), true);
        Msg $= createJsonField("negX_collision", string(dNegX), true);
        Msg $= createJsonField("posY_collision", string(dPosY), true);
        Msg $= createJsonField("negY_collision", string(dNegY), true);

        // diagonals (new)
        Msg $= createJsonField("fwdRight30_collision",  string(dFR30), true);
        Msg $= createJsonField("fwdRight45_collision",  string(dFR45), true);
        Msg $= createJsonField("fwdRight60_collision",  string(dFR60), true);
        Msg $= createJsonField("backRight60_collision", string(dBR60), true);
        Msg $= createJsonField("backRight45_collision", string(dBR45), true);
        Msg $= createJsonField("backRight30_collision", string(dBR30), true);
        Msg $= createJsonField("backLeft30_collision",  string(dBL30), true);
        Msg $= createJsonField("backLeft45_collision",  string(dBL45), true);
        Msg $= createJsonField("backLeft60_collision",  string(dBL60), true);
        Msg $= createJsonField("fwdLeft60_collision",   string(dFL60), true);
        Msg $= createJsonField("fwdLeft45_collision",   string(dFL45), true);
        Msg $= createJsonField("fwdLeft30_collision",   string(dFL30), true);

        // world-axis diagonals (new)
        Msg $= createJsonField("posXPosY30_collision",  string(wXY30), true);
        Msg $= createJsonField("posXPosY45_collision",  string(wXY45), true);
        Msg $= createJsonField("posXPosY60_collision",  string(wXY60), true);
        Msg $= createJsonField("negXPosY60_collision",  string(wNXY60), true);
        Msg $= createJsonField("negXPosY45_collision",  string(wNXY45), true);
        Msg $= createJsonField("negXPosY30_collision",  string(wNXY30), true);
        Msg $= createJsonField("negXNegY30_collision",  string(wNXNY30), true);
        Msg $= createJsonField("negXNegY45_collision",  string(wNXNY45), true);
        Msg $= createJsonField("negXNegY60_collision",  string(wNXNY60), true);
        Msg $= createJsonField("posXNegY60_collision",  string(wXNY60), true);
        Msg $= createJsonField("posXNegY45_collision",  string(wXNY45), true);
        Msg $= createJsonField("posXNegY30_collision",  string(wXNY30), true);

        Msg $= createJsonField("floorProbeDist", string(FloorProbeDist), true);
        Msg $= createJsonField("floorMaxDrop", string(FloorMaxDrop), true);
        Msg $= createJsonField("fwdFloorDelta", string(floorF), true);
        Msg $= createJsonField("fwdRightFloorDelta", string(floorFR), true);
        Msg $= createJsonField("rightFloorDelta", string(floorR), true);
        Msg $= createJsonField("backRightFloorDelta", string(floorBR), true);
        Msg $= createJsonField("backFloorDelta", string(floorB), true);
        Msg $= createJsonField("backLeftFloorDelta", string(floorBL), true);
        Msg $= createJsonField("leftFloorDelta", string(floorL), true);
        Msg $= createJsonField("fwdLeftFloorDelta", string(floorFL), true);

        // Foot-height horizontal rays (low-obstacle detection)
        Msg $= createJsonField("fwdLowCollision", string(lowF), true);
        Msg $= createJsonField("fwdRightLowCollision", string(lowFR), true);
        Msg $= createJsonField("rightLowCollision", string(lowR), true);
        Msg $= createJsonField("backRightLowCollision", string(lowBR), true);
        Msg $= createJsonField("backLowCollision", string(lowB), true);
        Msg $= createJsonField("backLeftLowCollision", string(lowBL), true);
        Msg $= createJsonField("leftLowCollision", string(lowL), true);
        Msg $= createJsonField("fwdLeftLowCollision", string(lowFL), true);

        // Vertical egocentric probes
        Msg $= createJsonField("verticalMaxDist", string(VerticalMaxDist), true);
        Msg $= createJsonField("floorBelow", string(floorBelow), true);
        Msg $= createJsonField("ceilingAbove", string(ceilingAbove), false);
    Msg $= createJsonHeaderEnd(withComma, false);

    return Msg;
}

function string CreateFlagCollisions(CTFFlag F, bool withComma)
{
    local string Msg;
    local rotator R;
    local int MaxDist;
    local float CapsuleMargin;
    local int dF, dB, dL, dR;
    local int dPosX, dNegX, dPosY, dNegY;

    R     = F.Rotation;
    MaxDist       = 1200;
    CapsuleMargin = 3.0;

    // yaw-relative
    dF = TraceDistance(F, YawDirection(R, 0),     MaxDist, CapsuleMargin);
    dB = TraceDistance(F, YawDirection(R, 32768), MaxDist, CapsuleMargin);
    dL = TraceDistance(F, YawDirection(R, 49152), MaxDist, CapsuleMargin);
    dR = TraceDistance(F, YawDirection(R, 16384), MaxDist, CapsuleMargin);

    // world-axis
    dPosX = TraceDistance(F, WorldPosX(), MaxDist, CapsuleMargin);
    dNegX = TraceDistance(F, WorldNegX(), MaxDist, CapsuleMargin);
    dPosY = TraceDistance(F, WorldPosY(), MaxDist, CapsuleMargin);
    dNegY = TraceDistance(F, WorldNegY(), MaxDist, CapsuleMargin);

    Msg $= createJsonHeaderStart("Collisions", false);
        Msg $= createJsonField("maxDist", string(MaxDist), true);
        Msg $= createJsonField("stepCoarse", "0", true);
        Msg $= createJsonField("capsuleMargin", string(CapsuleMargin), true);
        Msg $= createJsonField("immediateProbeUu", "0", true);

        Msg $= createJsonField("fwd_collision",   string(dF), true);
        Msg $= createJsonField("back_collision",  string(dB), true);
        Msg $= createJsonField("left_collision",  string(dL), true);
        Msg $= createJsonField("right_collision", string(dR), true);

        Msg $= createJsonField("posX_collision", string(dPosX), true);
        Msg $= createJsonField("negX_collision", string(dNegX), true);
        Msg $= createJsonField("posY_collision", string(dPosY), true);
        Msg $= createJsonField("negY_collision", string(dNegY), false);
    Msg $= createJsonHeaderEnd(withComma, false);

    return Msg;
}

defaultproperties {
}
