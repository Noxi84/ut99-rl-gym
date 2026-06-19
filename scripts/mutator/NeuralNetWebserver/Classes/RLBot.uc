// Headless RL bot: extends TMale1Bot for proper mesh/skin/collision/animation.
// All AI is disabled — movement is driven entirely by Java via HTTP POST.
class RLBot extends TMale1Bot;

// ── Action intent (set by webservice POST, read by state code) ──
var bool bRLForward, bRLBack, bRLLeft, bRLRight;
var bool bRLJump, bRLDuck, bRLFire, bRLAltFire;
var int  RLTargetYaw, RLTargetPitch;
var int  RLMoveYaw;  // World-space movement heading (decoupled from view)

// Dodge: 0=none, 1=forward, 2=back, 3=left, 4=right
var int  RLDodgeAction;
var float DodgeCooldownTimer;
// True while in dodge sequence (air + landing cooldown) — suppresses duck/movement
var bool bDodging;

// Track key hold durations (seconds) – exposed via webservice GET for Java features
var float HoldForwardSec, HoldBackSec, HoldLeftSec, HoldRightSec;
var float HoldJumpSec, HoldDuckSec;

// Fire edge detection: track previous fire state to call Weapon.Fire() only on rising edge
var bool bWasFiring, bWasAltFiring;

// Animation state: true when running animation is active (driven by Tick, not state code)
var bool bPlayingRunAnim;

// ── RLControlled: the only state this bot ever runs ──
auto state RLControlled
{
    function BeginState()
    {
        SetMovementPhysics();
        bIsPlayer = true;
        // Clear any Enemy set by inherited Bot AI before we took control
        Enemy = None;
        bPlayingRunAnim = false;
    }

    // Compute a short-range destination from the movement heading.
    // RLMoveYaw is the complete world-space movement direction computed by Java
    // (view yaw + relative movement offset). We just move along it.
    function vector ComputeDestination()
    {
        local vector MoveDir;
        local rotator MoveRot;

        // Underwater (PHYS_Swimming), jump/duck count as ascend/descend intent even
        // without a WASD key, so the bot can surface while holding still horizontally.
        if (!bRLForward && !bRLBack && !bRLLeft && !bRLRight
            && !(Physics == PHYS_Swimming && (bRLJump || bRLDuck)))
            return Location;

        if (Physics == PHYS_Swimming)
        {
            // 3D swim steering. Horizontal heading from RLMoveYaw (as on land);
            // vertical from view-pitch (look up + swim forward -> ascend, look down ->
            // dive) plus an explicit jump=surface / duck=dive impulse. Mirrors stock
            // UT99 swimming (PlayerMove projects intent along ViewRotation + aUp),
            // which the RLBot MoveTo loop otherwise bypasses -- MoveDir.Z used to be
            // flattened to 0, so the bot could never reach the surface and drowned.
            MoveRot.Yaw   = RLMoveYaw;
            MoveRot.Pitch = RLTargetPitch;
            MoveRot.Roll  = 0;
            MoveDir = vector(MoveRot);
            if (bRLJump) MoveDir.Z += 1.0;
            if (bRLDuck) MoveDir.Z -= 1.0;
            // Pure vertical intent (no WASD): strip horizontal drift so the bot rises
            // or sinks in place instead of being pushed by the stale move-heading.
            if (!bRLForward && !bRLBack && !bRLLeft && !bRLRight)
            {
                MoveDir.X = 0;
                MoveDir.Y = 0;
            }
            MoveDir = Normal(MoveDir);
            return Location + MoveDir * 200;
        }

        // Land: horizontal-only. moveYaw already encodes the full world-space direction.
        MoveRot.Yaw = RLMoveYaw;
        MoveRot.Pitch = 0;
        MoveRot.Roll = 0;

        MoveDir = vector(MoveRot);
        MoveDir.Z = 0;
        MoveDir = Normal(MoveDir);

        return Location + MoveDir * 200;
    }

    // Apply non-movement actions (view rotation, fire, jump, duck, dodge)
    function ApplyActions(float DeltaTime)
    {
        local rotator NewRot;

        // ── ViewRotation ──
        NewRot.Yaw   = RLTargetYaw;
        NewRot.Pitch = RLTargetPitch;
        NewRot.Roll  = 0;
        ViewRotation = NewRot;
        SetRotation(NewRot);

        // ── Fire ──
        // Set bFire/bAltFire flags (read by Weapon state machine for auto-refire).
        // Also call Weapon.Fire()/AltFire() directly to trigger the initial shot,
        // since Bot AI states (which normally call FireWeapon()) are not active.
        bFire    = int(bRLFire);
        bAltFire = int(bRLAltFire);
        // Suppress the direct fire call while a weapon switch is in flight (PendingWeapon
        // set): the outgoing weapon is in its PutDown animation and the new one is being
        // brought up. Firing mid-switch would no-op or interfere with BringUp. Resumes
        // automatically once ChangedWeapon clears PendingWeapon.
        if (Weapon != None && PendingWeapon == None)
        {
            if (bRLFire && !bWasFiring)
                Weapon.Fire(1.0);
            else if (bRLAltFire && !bWasAltFiring && bFire == 0)
                Weapon.AltFire(1.0);
        }
        bWasFiring    = bRLFire;
        bWasAltFiring = bRLAltFire;

        // ── Duck (suppress during dodge sequence to prevent slide) ──
        if (!bDodging)
            bDuck = int(bRLDuck);

        // ── Jump ──
        if (bRLJump && !bDodging && Physics == PHYS_Walking && bDuck == 0)
        {
            Velocity.Z = JumpZ;
            if (Base != Level && Base != None)
                Velocity.Z += Base.Velocity.Z;
            SetPhysics(PHYS_Falling);
            PlaySound(JumpSound, SLOT_Talk, 1.5, true, 1200, 1.0);
            PlayInAir();
        }

        // ── Dodge ──
        if (RLDodgeAction > 0 && !bDodging && DodgeDir == DODGE_None
            && Physics == PHYS_Walking && bDuck == 0)
        {
            PerformRLDodge(RLDodgeAction);
            RLDodgeAction = 0;
            bDodging = true;
            // Interrupt current MoveTo and switch to dodging sublabel
            GotoState('RLControlled', 'Dodging');
        }

        // DodgeDir cooldown: DONE → NONE after ~0.35s
        if (DodgeDir == DODGE_Done)
        {
            DodgeCooldownTimer -= DeltaTime;
            if (DodgeCooldownTimer < -0.35)
            {
                DodgeDir = DODGE_None;
                DodgeCooldownTimer = 0;
            }
        }

        // ── Hold duration tracking ──
        if (bRLForward) HoldForwardSec += DeltaTime; else HoldForwardSec = 0;
        if (bRLBack)    HoldBackSec    += DeltaTime; else HoldBackSec    = 0;
        if (bRLLeft)    HoldLeftSec    += DeltaTime; else HoldLeftSec    = 0;
        if (bRLRight)   HoldRightSec   += DeltaTime; else HoldRightSec  = 0;
        if (bRLJump)    HoldJumpSec    += DeltaTime; else HoldJumpSec   = 0;
        if (bRLDuck)    HoldDuckSec    += DeltaTime; else HoldDuckSec   = 0;
    }

    event Tick(float DeltaTime)
    {
        local vector HVel;
        local float Speed;

        ApplyActions(DeltaTime);

        // Velocity-driven animation: prevents slide when transitioning idle↔moving.
        // Only switch to running when pawn actually has horizontal velocity,
        // only switch to waiting when velocity drops AND no movement intent remains.
        if (Health > 0 && Physics == PHYS_Walking && !bDodging)
        {
            HVel = Velocity;
            HVel.Z = 0;
            Speed = VSize(HVel);

            if (Speed > 15 && !bPlayingRunAnim)
            {
                PlayRunning();
                bPlayingRunAnim = true;
            }
            else if (Speed <= 15 && !bRLForward && !bRLBack && !bRLLeft && !bRLRight && bPlayingRunAnim)
            {
                PlayWaiting();
                bPlayingRunAnim = false;
            }
        }
        else if (Physics == PHYS_Falling)
        {
            bPlayingRunAnim = false;
        }
    }

Begin:
    SetMovementPhysics();
Moving:
    Destination = ComputeDestination();
    if (VSize(Destination - Location) > 5)
    {
        MoveTo(Destination);
    }
    else
    {
        Acceleration = vect(0,0,0);
        Sleep(0.03);
    }
    goto 'Moving';

// Dodge sublabel: only suppress conflicting actions while airborne.
// As soon as we land, immediately resume normal movement so dodges chain
// smoothly into continuous running instead of freezing in a landing state.
Dodging:
    bDuck = 0;  // force un-duck during dodge
    // Only wait for the airborne phase to finish.
    while (Physics == PHYS_Falling)
        Sleep(0.02);
    bDodging = false;
    // Resume normal movement immediately after touchdown.
    goto 'Moving';
}

// Override PlayerPawn.AnimEnd: during walking, Tick() drives animation.
// Without this, PlayerPawn.AnimEnd fires every LoopAnim cycle and can switch
// to PlayWaiting() when velocity is momentarily low between MoveTo() calls.
function AnimEnd()
{
    if (Physics == PHYS_Walking && Health > 0)
        return;
    Super.AnimEnd();
}

static function GetMultiSkin(Actor SkinActor, out string SkinName, out string FaceName)
{
    local RLBot R;
    local string ShortSkinName, FullSkinName, ShortFaceName, FullFaceName;

    R = RLBot(SkinActor);
    if (R == None)
    {
        class'Botpack.TMale1Bot'.static.GetMultiSkin(SkinActor, SkinName, FaceName);
        return;
    }

    FullSkinName = String(SkinActor.Multiskins[R.FixedSkin]);
    ShortSkinName = SkinActor.GetItemName(FullSkinName);

    FullFaceName = String(SkinActor.Multiskins[R.FaceSkin]);
    ShortFaceName = SkinActor.GetItemName(FullFaceName);

    SkinName = Left(FullSkinName, Len(FullSkinName) - Len(ShortSkinName)) $ Left(ShortSkinName, 4);
    FaceName = Left(FullFaceName, Len(FullFaceName) - Len(ShortFaceName)) $ Mid(ShortFaceName, 5);
}

static function SetMultiSkin(Actor SkinActor, string SkinName, string FaceName, byte TeamNum)
{
    local RLBot R;
    local string FacePackage, SkinItem, FaceItem, SkinPackage;

    R = RLBot(SkinActor);
    if (R == None)
    {
        class'Botpack.TMale1Bot'.static.SetMultiSkin(SkinActor, SkinName, FaceName, TeamNum);
        return;
    }

    SkinItem = SkinActor.GetItemName(SkinName);
    FaceItem = SkinActor.GetItemName(FaceName);
    FacePackage = Left(FaceName, Len(FaceName) - Len(FaceItem));
    SkinPackage = Left(SkinName, Len(SkinName) - Len(SkinItem));

    if (SkinPackage == "")
    {
        SkinPackage = R.DefaultPackage;
        SkinName = SkinPackage $ SkinName;
    }
    if (FacePackage == "")
    {
        FacePackage = R.DefaultPackage;
        FaceName = FacePackage $ FaceName;
    }

    if (!SetSkinElement(SkinActor, R.FixedSkin, SkinName $ string(R.FixedSkin + 1), R.DefaultSkinName $ string(R.FixedSkin + 1)))
    {
        SkinName = R.DefaultSkinName;
        FaceName = "";
    }

    SetSkinElement(SkinActor, R.FaceSkin, FacePackage $ SkinItem $ string(R.FaceSkin + 1) $ FaceItem, SkinName $ string(R.FaceSkin + 1));

    if (TeamNum != 255)
    {
        SetSkinElement(SkinActor, R.TeamSkin1, SkinName $ string(R.TeamSkin1 + 1) $ "T_" $ string(TeamNum), SkinName $ string(R.TeamSkin1 + 1));
        SetSkinElement(SkinActor, R.TeamSkin2, SkinName $ string(R.TeamSkin2 + 1) $ "T_" $ string(TeamNum), SkinName $ string(R.TeamSkin2 + 1));
    }
    else
    {
        SetSkinElement(SkinActor, R.TeamSkin1, SkinName $ string(R.TeamSkin1 + 1), "");
        SetSkinElement(SkinActor, R.TeamSkin2, SkinName $ string(R.TeamSkin2 + 1), "");
    }

    if (R.PlayerReplicationInfo != None)
    {
        if (FaceName != "")
            R.PlayerReplicationInfo.TalkTexture = Texture(DynamicLoadObject(FacePackage $ SkinItem $ "5" $ FaceItem, class'Texture'));
        else
            R.PlayerReplicationInfo.TalkTexture = None;
    }
}

// RLBot is a TMale1Bot subclass for control-code reuse, but RLCTFGame can
// swap it to a female mesh. Use female-safe death/hit animations for those
// meshes so the spawned carcass does not inherit a missing male anim sequence.
function PlayDying(name DamageType, vector HitLoc)
{
    local carcass carc;

    if (!bIsFemale)
    {
        Super.PlayDying(DamageType, HitLoc);
        return;
    }

    BaseEyeHeight = 0.7 * CollisionHeight;
    PlayDyingSound();

    if (DamageType == 'Suicided')
    {
        PlayAnim('Dead3',, 0.1);
        return;
    }

    if ((DamageType == 'Decapitated') && !Level.Game.bVeryLowGore)
    {
        PlayDecap();
        return;
    }

    if (FRand() < 0.15)
    {
        PlayAnim('Dead7',, 0.1);
        return;
    }

    if ((Velocity.Z > 250) && (FRand() < 0.75))
    {
        if ((HitLoc.Z < Location.Z) && !Level.Game.bVeryLowGore && (FRand() < 0.6))
        {
            PlayAnim('Dead5',, 0.05);
            if (Level.NetMode != NM_Client)
            {
                carc = Spawn(class'Botpack.UT_FemaleFoot',,, Location - CollisionHeight * vect(0,0,0.5));
                if (carc != None)
                {
                    carc.Initfor(self);
                    carc.Velocity = Velocity + VSize(Velocity) * VRand();
                    carc.Velocity.Z = FMax(carc.Velocity.Z, Velocity.Z);
                }
            }
        }
        else
            PlayAnim('Dead2',, 0.1);
        return;
    }

    if ((Health > -10) && ((DamageType == 'shot') || (DamageType == 'zapped')))
    {
        PlayAnim('Dead9',, 0.1);
        return;
    }

    if ((HitLoc.Z - Location.Z > 0.7 * CollisionHeight) && !Level.Game.bVeryLowGore)
    {
        if (FRand() < 0.5)
            PlayDecap();
        else
            PlayAnim('Dead3',, 0.1);
        return;
    }

    if (FRand() < 0.5)
        PlayAnim('Dead4',, 0.1);
    else
        PlayAnim('Dead1',, 0.1);
}

function PlayDecap()
{
    local carcass carc;

    if (!bIsFemale)
    {
        Super.PlayDecap();
        return;
    }

    PlayAnim('Dead6',, 0.1);
    if (Level.NetMode != NM_Client)
    {
        carc = Spawn(class'Botpack.UT_HeadFemale',,, Location + CollisionHeight * vect(0,0,0.8), Rotation + rot(3000,0,16384));
        if (carc != None)
        {
            carc.Initfor(self);
            carc.Velocity = Velocity + VSize(Velocity) * VRand();
            carc.Velocity.Z = FMax(carc.Velocity.Z, Velocity.Z);
        }
    }
}

function PlayGutHit(float tweentime)
{
    if (!bIsFemale)
    {
        Super.PlayGutHit(tweentime);
        return;
    }

    if ((AnimSequence == 'GutHit') || (AnimSequence == 'Dead2'))
    {
        if (FRand() < 0.5)
            TweenAnim('LeftHit', tweentime);
        else
            TweenAnim('RightHit', tweentime);
    }
    else if (FRand() < 0.6)
        TweenAnim('GutHit', tweentime);
    else
        TweenAnim('Dead2', tweentime);
}

function PlayHeadHit(float tweentime)
{
    if (!bIsFemale)
    {
        Super.PlayHeadHit(tweentime);
        return;
    }

    if ((AnimSequence == 'HeadHit') || (AnimSequence == 'Dead4'))
        TweenAnim('GutHit', tweentime);
    else if (FRand() < 0.6)
        TweenAnim('HeadHit', tweentime);
    else
        TweenAnim('Dead4', tweentime);
}

function PlayLeftHit(float tweentime)
{
    if (!bIsFemale)
    {
        Super.PlayLeftHit(tweentime);
        return;
    }

    if ((AnimSequence == 'LeftHit') || (AnimSequence == 'Dead3'))
        TweenAnim('GutHit', tweentime);
    else if (FRand() < 0.6)
        TweenAnim('LeftHit', tweentime);
    else
        TweenAnim('Dead3', tweentime);
}

function PlayRightHit(float tweentime)
{
    if (!bIsFemale)
    {
        Super.PlayRightHit(tweentime);
        return;
    }

    if (AnimSequence == 'RightHit')
        TweenAnim('GutHit', tweentime);
    else
        TweenAnim('RightHit', tweentime);
}

// ── Dodge: reproduces TournamentPlayer.PlayDodge() physics + animations ──
function PerformRLDodge(int DodgeAction)
{
    local vector X, Y, Z;

    GetAxes(ViewRotation, X, Y, Z);

    if (DodgeAction == 1)       // Forward
        Velocity = 1.5 * GroundSpeed * X + (Velocity Dot Y) * Y;
    else if (DodgeAction == 2)  // Back
        Velocity = -1.5 * GroundSpeed * X + (Velocity Dot Y) * Y;
    else if (DodgeAction == 3)  // Left
        Velocity = 1.5 * GroundSpeed * Y + (Velocity Dot X) * X;
    else if (DodgeAction == 4)  // Right
        Velocity = -1.5 * GroundSpeed * Y + (Velocity Dot X) * X;
    else
        return;

    // Velocity.Z = 210 matches TournamentPlayer (160 was too low for proper animations)
    Velocity.Z = 210;
    DodgeDir = DODGE_Active;
    DodgeCooldownTimer = 0;
    SetPhysics(PHYS_Falling);
    PlaySound(JumpSound, SLOT_Talk, 1.0, true, 800, 1.0);

    // Direction-specific animations (from TournamentPlayer.PlayDodge):
    // Forward = Flip (salto), Back = DodgeB, Left = DodgeL, Right = DodgeR
    if (DodgeAction == 1)
        PlayAnim('Flip', 1.35 * FMax(0.35, Region.Zone.ZoneGravity.Z/Region.Zone.Default.ZoneGravity.Z), 0.06);
    else if (DodgeAction == 2)
        TweenAnim('DodgeB', 0.25);
    else if (DodgeAction == 3)
        TweenAnim('DodgeL', 0.25);
    else if (DodgeAction == 4)
        TweenAnim('DodgeR', 0.25);
}

// ── Landing: same DodgeDir state transition as PlayerPawn ──
event Landed(vector HitNormal)
{
    if (DodgeDir == DODGE_Active)
    {
        DodgeDir = DODGE_Done;
        DodgeCooldownTimer = 0;
        // Keep horizontal momentum so post-dodge movement continues smoothly.
        Velocity.Z = 0;
    }
    else
    {
        DodgeDir = DODGE_None;
    }
    Super.Landed(HitNormal);
}

// ── Forced suicide entrypoint for the Java AmmoDeadlockGuard ──
// Triggered via RLUdpCommandReceiver (binary magic 0xAB packet) wanneer alle
// levende RLBots in de match gelijktijdig zonder ammo zitten. Reproduceert
// PlayerPawn.Suicide() semantiek (Died met 'Suicided' damageType) zodat onze
// eigen Died-override de cleanup en respawn-cycle verzorgt. Health<=0 guard
// voorkomt dubbele triggers tijdens de Dying state.
function PerformRLSuicide()
{
    if (Health <= 0)
        return;
    Died(None, 'Suicided', Location);
}

// ── Override all stock Bot AI – bot does ONLY what Java tells it ──
function SetOrders(name NewOrders, Pawn OrderGiver, optional bool bNoAck) {}
function WhatToDoNext(name LikelyState, name LikelyLabel)
{
    if (Health > 0)
        GotoState('RLControlled');
}

// Suppress inherited Bot AI events that set Enemy and trigger combat behavior
event SeePlayer(actor SeenPlayer) {}
event HearNoise(float Loudness, Actor NoiseMaker) {}
event EnemyNotVisible() {}
event Bump(actor Other) {}

// ── AdjustAim: bypass Bot's AI aim-correction.
//
// Bot.AdjustAim (Botpack/Bot.uc:1260) lead-corrigeert naar Enemy/Target en valt
// terug op `return Rotation;` wanneer beide None zijn. Onze RLControlled state
// houdt Enemy=None (zie BeginState lijn 35) zodat stock Bot AI niet vuurt; gevolg
// is dat AdjustAim Rotation returnt, en Pawn.Rotation.Pitch wordt door PHYS_Walking
// op 0 geclampt → wapens die ProjectileFire gebruiken (pulse, rocket, flak, bio,
// ripper, ...) krijgen verkeerde aim.
//
// BELANGRIJK: NIET Pawn.ViewRotation gebruiken — engine reset die periodiek op 0
// voor non-PlayerPawn Pawns (zie NeuralNetWebserver.uc:767 comment). Gebruik
// RLTargetYaw/RLTargetPitch direct als authoritative aim-source.
function rotator AdjustAim(float projSpeed, vector projStart, int aimerror, bool leadTarget, bool warnTarget)
{
    local rotator R;
    R.Yaw   = RLTargetYaw;
    R.Pitch = RLTargetPitch;
    R.Roll  = 0;
    return R;
}

// Fire weapon based on bFire/bAltFire flags set by Java via HTTP POST.
// Bot.FireWeapon() normally contains AI decision-making (SwitchToBestWeapon,
// Target selection, etc.) which we bypass. We only call Weapon.Fire/AltFire
// when Java has explicitly requested it.
//
// CRITICAL: do NOT touch ViewRotation here. RL policy sets ViewRotation each
// tick in ApplyActions; resetting it = Rotation here would clamp pitch to 0
// (Walking physics) and break weapon aim (visible: pulse altFire beam altijd
// horizontaal). Stock Bot.FireWeapon includes `ViewRotation = Rotation` —
// historisch overgenomen, maar voor RL-bot is dat een aim-killer.
function FireWeapon()
{
    if (Weapon == None)
        return;
    if (Weapon.AmmoType != None && Weapon.AmmoType.AmmoAmount <= 0)
        return;

    if (bAltFire != 0)
        Weapon.AltFire(1.0);
    else if (bFire != 0)
        Weapon.Fire(1.0);
}

// ── Weapon selection (Java weapon-planner lane via RLUdpCommandReceiver 0xAC) ──
// Switch to the inventory weapon whose class FNV-1a hash matches targetHash, using the
// stock PendingWeapon/PutDown flow — identical to Pawn.SwitchToBestWeapon minus the AI
// RecommendWeapon pick. Idempotent: no-op when the target is already active or already
// the pending weapon, so the CommandController may resend safely (retry / packet loss).
// Java owns the decision (incl. next-best-with-ammo fallback) and only sends a weapon
// it has verified is in this bot's inventory; the not-found branch is a safe guard.
function RLSelectWeaponByHash(int targetHash)
{
    local Inventory Inv;
    local Weapon W, Found;

    if (Weapon != None && FNV1aHash(string(Weapon.Class)) == targetHash)
        return;  // already active
    if (PendingWeapon != None && FNV1aHash(string(PendingWeapon.Class)) == targetHash)
        return;  // switch already in flight

    Found = None;
    for (Inv = Inventory; Inv != None; Inv = Inv.Inventory)
    {
        if (Inv.IsA('Weapon'))
        {
            W = Weapon(Inv);
            if (FNV1aHash(string(W.Class)) == targetHash)
            {
                Found = W;
                break;
            }
        }
    }
    if (Found == None)
        return;  // not carried — safe no-op

    PendingWeapon = Found;
    if (Weapon == None)
        ChangedWeapon();   // nothing held → bring the new weapon up immediately
    else
        Weapon.PutDown();  // putdown → DownWeapon state → ChangedWeapon → BringUp(PendingWeapon)
}

// FNV-1a (32-bit). MUST stay in lock-step with RLUdpStateSender.FNV1aHash and the Java
// WeaponClassNameTable.fnv1a: seed 0x811C9DC5 (= -2128831035), prime 16777619, one byte
// per char. Duplicated here because UnrealScript has no shared util across these
// unrelated classes (RLBot extends TMale1Bot, the sender extends UdpLink).
function int FNV1aHash(string s)
{
    local int h, i, c;
    h = -2128831035;
    for (i = 0; i < Len(s); i++)
    {
        c = Asc(Mid(s, i, 1));
        h = h ^ c;
        h = h * 16777619;
    }
    return h;
}

// ── TakeDamage: skip Bot's state-specific TakeDamage (has AI code after Died) ──
// Damage attribution itself is captured game-wide by RLCTFGame.ReduceDamage →
// RLUdpStateSender.RecordDamage; this override handles engine-level HP reduction
// without Bot's AI side-effects.
//
// MutatorTakeDamage chain MUST stay wired — Smart CTF (and any DamageMutator)
// hooks deze chain voor Cover/Seal damage-tracking en kan ActualDamage/HitLocation/
// Momentum nog aanpassen. Stock Pawn.TakeDamage roept dit aan na ReduceDamage en
// vóór de Health-aftrek (zie Engine/Pawn.uc:1437-1438) — we volgen die volgorde.
function TakeDamage(int Damage, Pawn instigatedBy, Vector hitlocation, Vector momentum, name damageType)
{
    local int actualDamage;

    if (Health <= 0)
        return;

    // Game-level damage modification (friendly fire, neutral zones, etc.)
    actualDamage = Damage;
    if (Level.Game != None)
        actualDamage = Level.Game.ReduceDamage(actualDamage, damageType, self, instigatedBy);

    if (actualDamage <= 0)
        return;

    // Mutator damage hook (Smart CTF Cover/Seal/Saved tracking + bonus pts)
    if (Level.Game.DamageMutator != None)
        Level.Game.DamageMutator.MutatorTakeDamage(actualDamage, self, instigatedBy, hitlocation, momentum, damageType);

    if (actualDamage <= 0)
        return;

    // Knockback
    if (Physics == PHYS_Walking)
        momentum.Z = FMax(momentum.Z, 0.4 * VSize(momentum));
    if (Mass > 0)
        AddVelocity(momentum / Mass);

    Health -= actualDamage;

    if (Health <= 0)
    {
        Health = Min(0, Health);
        Died(instigatedBy, damageType, hitlocation);
    }
}

// ── Died: reproduces Pawn.Died() but skips Bot-specific overrides ──
//
// PreventDeath mutator hook is verplicht — Smart CTF (en SmartDM-derivaten) houden
// daarin ALLE killer-side stats bij: Frags, FlagKills, DefKills, Seals, Covers,
// Sprees, MultiKills, SpawnKill-detection. Zonder deze call zien spectators 0
// in elke kolom behalve Deaths (engine PRI.Deaths) en Assists (gehookt via
// MutatorBroadcastLocalizedMessage op CTF cap-events). Stock Pawn.Died:1485-1489.
function Died(pawn Killer, name damageType, vector HitLocation)
{
    local pawn OtherPawn;
    local actor A;

    if (Level.Game.BaseMutator != None
        && Level.Game.BaseMutator.PreventDeath(self, Killer, damageType, HitLocation))
    {
        Health = Max(Health, 1);
        return;
    }

    if (bDeleteMe)
        return;

    // Clean up RL state
    bRLForward = false;
    bRLBack    = false;
    bRLLeft    = false;
    bRLRight   = false;
    bRLJump    = false;
    bRLDuck    = false;
    bRLFire    = false;
    bRLAltFire = false;
    bDodging   = false;
    bPlayingRunAnim = false;
    RLDodgeAction = 0;
    bFire    = 0;
    bAltFire = 0;
    bDuck    = 0;
    Enemy    = None;

    Health = Min(0, Health);

    // Notify all pawns (kill feed, AI reactions, etc.)
    for (OtherPawn = Level.PawnList; OtherPawn != None; OtherPawn = OtherPawn.nextPawn)
        OtherPawn.Killed(Killer, self, damageType);

    // Game rules: frag scoring, CTF events
    Level.Game.Killed(Killer, self, damageType);

    // Trigger any map events
    if (Event != '')
        foreach AllActors(class'Actor', A, Event)
            A.Trigger(self, Killer);

    // Drop weapons
    Level.Game.DiscardInventory(self);

    Velocity.Z *= 1.3;

    // Death animation
    PlayDying(damageType, HitLocation);

    // Go to our safe Dying state (overrides Bot.Dying which calls Destroy/BotReplicationInfo)
    GotoState('Dying');
}

// ── Dying state: overrides Bot.Dying to prevent Destroy() and BotReplicationInfo crashes ──
// Bot.Dying.BeginState calls Destroy() when TooManyBots() (always true with a spectator).
// Bot.Dying.RestartPlayer accesses BotReplicationInfo and GotoState('Roaming').
// This version: safe cleanup, carcass, hide, respawn, back to RLControlled.
state Dying
{
    ignores SeePlayer, EnemyNotVisible, HearNoise, Died, Bump, Trigger,
            HitWall, HeadZoneChange, FootZoneChange, ZoneChange, Falling,
            WarnTarget, LongFall, SetFall, PainTimer;

    function TakeDamage(int Damage, Pawn instigatedBy, Vector hitlocation, Vector momentum, name damageType)
    {
    }

    function BeginState()
    {
        // Safe cleanup — NO Destroy(), NO TooManyBots() check
        SetTimer(0, false);
        Enemy = None;
        bFire = 0;
        bAltFire = 0;
    }

Begin:
    if (Level.Game.bGameEnded)
        GotoState('GameEnded');
    Sleep(0.2);
    if (!bHidden)
        SpawnCarcass();
TryAgain:
    if (!bHidden)
        HidePlayer();
    Sleep(0.25 + DeathMatchPlus(Level.Game).SpawnWait(self));
    // Manual respawn — bypasses DeathMatchPlus.RestartPlayer which calls
    // TooManyBots() → Destroy() on dedicated servers with a spectator/player.
    // Reproduces GameInfo.RestartPlayer logic directly.
    RLDoRestart();
    if (Health > 0)
        GotoState('RLControlled');
    else
        Goto('TryAgain');
}

// Respawn logic from GameInfo.RestartPlayer, without TooManyBots/Destroy.
function RLDoRestart()
{
    local NavigationPoint StartSpot;

    StartSpot = Level.Game.FindPlayerStart(self, 255);
    if (StartSpot == None)
        return;

    if (!SetLocation(StartSpot.Location))
        return;

    StartSpot.PlayTeleportEffect(self, true);
    SetRotation(StartSpot.Rotation);
    ViewRotation = Rotation;
    Acceleration = vect(0,0,0);
    Velocity = vect(0,0,0);
    Health = Default.Health;
    SetCollision(true, true, true);
    bHidden = false;
    DamageScaling = Default.DamageScaling;
    SoundDampening = Default.SoundDampening;
    SetPhysics(PHYS_Falling);
    Level.Game.AddDefaultInventory(self);
}

defaultproperties
{
    bRLForward=false
    bRLBack=false
    bRLLeft=false
    bRLRight=false
    bRLJump=false
    bRLDuck=false
    bRLFire=false
    bRLAltFire=false
    RLTargetYaw=0
    RLTargetPitch=0
    RLDodgeAction=0
    DodgeCooldownTimer=0
    bDodging=false
    bWasFiring=false
    bWasAltFiring=false
    bPlayingRunAnim=false
    HoldForwardSec=0
    HoldBackSec=0
    HoldLeftSec=0
    HoldRightSec=0
    HoldJumpSec=0
    HoldDuckSec=0
}
