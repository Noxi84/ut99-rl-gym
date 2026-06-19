//=============================================================================
// RLEightball — UT_Eightball voor RL bots. Drie fixes vs stock Botpack/UT_Eightball:
//
// 1) state AltFiring.AnimEnd: stock conditionalt na elke geladen granaat
//    `if ((PlayerPawn(Owner)==None) && ((FRand()>0.5) || (Enemy==None)))
//     Pawn(Owner).bAltFire = 0;`. Onze RLBot heeft per design Enemy=None →
//    100% kans dat bAltFire wordt geforceerd op 0 na de eerste granaat →
//    multi-load van granaten breekt. Override behoudt bAltFire zoals RL het
//    set; multi-load loopt door zolang RL bAltFire vasthoudt of tot 6/ammo op.
//
// 2) state NormalFire.Tick: stock heeft een complexe condition die bFire op 0
//    forceert bij Bot+Enemy=None (plus MoveTarget/LockedTarget/Falling checks).
//    Voor RL: alleen check bFire=0 of RocketsLoaded>5 — geen Bot-AI conditions.
//
// 3) state NormalFire.RotateRocket: stock heeft `if (FRand()>0.33)
//    Pawn(Owner).bFire = 0;` voor non-PlayerPawn → 67% kans per rocket-load
//    om te stoppen → multi-load van rockets effectief ~1.5 rockets average.
//    Override behoudt bFire zodat RL kan blijven laden tot 6.
//
// LET OP: NormalFire/AltFiring.BeginState mag NIET overriden worden. Stock
// BeginState roept zelf RotateRocket() aan; een subclass-copy met Super.BeginState()
// roept het dubbel aan -> tweede call valt in Global scope zodra de eerste
// GotoState('FireRockets') triggert -> fatale "Failed to find function RotateRocket".
//
// Aim is correct via RLBot.AdjustAim override (returnt RLTargetYaw/Pitch).
// State FireRockets.BeginState is onveranderd — gebruikt AdjustedAim correct.
//=============================================================================
class RLEightball extends UT_Eightball;

state AltFiring
{
    function Tick( float DeltaTime )
    {
        if ( (Pawn(Owner).bAltFire == 0) || (RocketsLoaded > 5) )
            GoToState('FireRockets');
    }

    function AnimEnd()
    {
        if ( bRotated )
        {
            bRotated = false;
            PlayLoading(1.1, RocketsLoaded);
        }
        else
        {
            if ( RocketsLoaded == 6 )
            {
                GotoState('FireRockets');
                return;
            }
            RocketsLoaded++;
            AmmoType.UseAmmo(1);
            // RL: geen Bot/Enemy=None forcing van bAltFire — multi-load
            // loopt door zolang RL bAltFire vasthoudt.
            bPointing = true;
            Owner.MakeNoise(0.6 * Pawn(Owner).SoundDampening);
            RotateRocket();
        }
    }

    function RotateRocket()
    {
        if (AmmoType.AmmoAmount<=0)
        {
            GotoState('FireRockets');
            return;
        }
        PlayRotating(RocketsLoaded-1);
        bRotated = true;
    }

    // BeginState NIET overriden: in een subclass chaint Super.BeginState() naar
    // UT_Eightball.AltFiring.BeginState, die zelf al RotateRocket() aanroept. Een
    // eigen copy zou RotateRocket() een TWEEDE keer aanroepen; als die eerste call
    // bij ammo<=0 GotoState('FireRockets') doet is het object de state al uit en
    // faalt de tweede lookup in Global scope ("Failed to find function RotateRocket
    // ... Global 0") -> fatale ucc-crash. Stock BeginState roept RotateRocket precies
    // 1x aan en is exact wat we willen, dus erven we 'm ongewijzigd.

Begin:
    bLockedOn = False;
}

state NormalFire
{
    function bool SplashJump()
    {
        return true;
    }

    function Tick( float DeltaTime )
    {
        // RL: alleen check bFire-release of max-load. Geen Bot-AI conditions
        // (MoveTarget/LockedTarget/Enemy=None/Falling) die anders bFire op 0
        // zouden forceren en multi-load zouden breken.
        if ( (Pawn(Owner).bFire == 0) || (RocketsLoaded > 5) )
            GoToState('FireRockets');
    }

    function AnimEnd()
    {
        if ( bRotated )
        {
            bRotated = false;
            PlayLoading(1.1, RocketsLoaded);
        }
        else
        {
            if ( RocketsLoaded == 6 )
            {
                GotoState('FireRockets');
                return;
            }
            RocketsLoaded++;
            AmmoType.UseAmmo(1);
            if (Pawn(Owner).bAltFire != 0) bTightWad = True;
            // NewTarget/LockedTarget logica overgeslagen — RL stuurt aim
            // direct via RLTargetYaw/Pitch, geen Bot AI lock-on nodig.
            bPointing = true;
            Owner.MakeNoise(0.6 * Pawn(Owner).SoundDampening);
            RotateRocket();
        }
    }

    // BeginState NIET overriden — zie toelichting in state AltFiring: een eigen copy
    // veroorzaakt een dubbele RotateRocket()-aanroep (via Super -> stock BeginState
    // plus onze eigen call) en daarmee een fatale "Failed to find function
    // RotateRocket" crash zodra de eerste call GotoState('FireRockets') triggert.

    function RotateRocket()
    {
        // RL: geen FRand()>0.33-forcing van bFire=0 voor non-PlayerPawn.
        // Multi-load loopt door zolang RL bFire vasthoudt of ammo op.
        if ( AmmoType.AmmoAmount <= 0 )
        {
            GotoState('FireRockets');
            return;
        }
        if ( AmmoType.AmmoAmount == 1 )
            Owner.PlaySound(Misc2Sound, SLOT_None, Pawn(Owner).SoundDampening);
        PlayRotating(RocketsLoaded-1);
        bRotated = true;
    }

Begin:
    Sleep(0.0);
}
