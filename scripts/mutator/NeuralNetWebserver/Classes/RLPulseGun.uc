//=============================================================================
// RLPulseGun — pulse gun voor RL bots. Twee fixes vs stock Botpack/PulseGun:
//
// 1) AltProjectileClass = RLStarterBolt: vermijdt StarterBolt.Tick's hijacking
//    van Instigator.ViewRotation (zie RLStarterBolt.uc voor details).
//
// 2) state AltFiring.Tick: stock check (lijn 413-419) killt de beam direct
//    voor Bots wanneer Enemy=None of LastSeenTime>5s. Onze RLBot heeft per
//    design Enemy=None (zie RLBot.uc:35). Override behoudt alleen de bAltFire-
//    en ammo-checks zodat de beam blijft branden zolang RL bAltFire vasthoudt.
//
// Alle overige PulseGun mechanics blijven intact (primary fire, animations,
// damage, weapon switching).
//=============================================================================
class RLPulseGun extends PulseGun;

state AltFiring
{
    ignores AnimEnd;

    function Tick(float DeltaTime)
    {
        local Pawn P;

        P = Pawn(Owner);
        if ( P == None )
        {
            GotoState('Pickup');
            return;
        }
        // RL: stop alleen wanneer policy bAltFire loslaat. Geen Enemy/Bot-AI
        // dependency — RL bestuurt zelf wanneer de beam moet stoppen.
        if ( P.bAltFire == 0 )
        {
            Finish();
            return;
        }

        Count += DeltaTime;
        if ( Count > 0.24 )
        {
            if ( Owner.IsA('PlayerPawn') )
                PlayerPawn(Owner).ClientInstantFlash( InstFlash, InstFog );
            if ( Affector != None )
                Affector.FireEffect();
            Count -= 0.24;
            if ( !AmmoType.UseAmmo(1) )
                Finish();
        }
    }

    function EndState()
    {
        AmbientGlow = 0;
        AmbientSound = None;
        if ( PlasmaBeam != None )
        {
            PlasmaBeam.Destroy();
            PlasmaBeam = None;
        }
        Super.EndState();
    }

Begin:
    AmbientGlow = 200;
    FinishAnim();
    LoopAnim( 'boltloop');
}

defaultproperties
{
    AltProjectileClass=class'RLStarterBolt'
}
