//=============================================================================
// RLStarterBolt — pulse-beam projectile zonder Bot-AI aim-hijacking.
//
// Stock Botpack/StarterBolt.uc:67-130 doet in elke Tick een Bot-AI-aim-correctie
// op basis van Instigator.Target/Enemy, en schrijft het resultaat terug naar
// Instigator.ViewRotation (lijn 123: "Instigator.ViewRotation = AimRotation;").
// Voor onze RLBot is Target/Enemy=None → fallback berekening levert
// Rotator(-AimStart) op = richting bot→wereldorigin, wat op CTF-AndAction
// (bot bij Z=-930) altijd omhoog wijst. Bovendien wordt onze RL-gestuurde
// ViewRotation overschreven.
//
// Deze override negeert Bot-AI volledig en gebruikt ViewRotation direct als
// beam-richting. RL policy stuurt aim via ViewRotation.
//=============================================================================
class RLStarterBolt extends StarterBolt;

simulated function Tick(float DeltaTime)
{
    local vector X, Y, Z, DrawOffset;
    local RLBot RLOwner;

    AnimTime += DeltaTime;
    if ( AnimTime > 0.05 )
    {
        AnimTime -= 0.05;
        SpriteFrame++;
        if ( SpriteFrame == ArrayCount(SpriteAnim) )
            SpriteFrame = 0;
        Skin = SpriteAnim[SpriteFrame];
    }

    if ( Instigator != None )
    {
        if (Role == ROLE_Authority)
        {
            // Server: bereken AimRotation uit RLTargetYaw/Pitch (engine reset
            // Pawn.ViewRotation periodiek op 0 voor non-PlayerPawn Pawns,
            // zie NeuralNetWebserver.uc:767). AimRotation is in StarterBolt's
            // replication block (Role==ROLE_Authority) → gerepliceerd naar
            // clients/spectators.
            RLOwner = RLBot(Instigator);
            if (RLOwner != None)
            {
                AimRotation.Yaw   = RLOwner.RLTargetYaw;
                AimRotation.Pitch = RLOwner.RLTargetPitch;
                AimRotation.Roll  = 0;
            }
            else
            {
                AimRotation = Instigator.ViewRotation;
            }
        }
        // Client: AimRotation komt uit replication, niet overschrijven met
        // niet-gerepliceerde RLTargetYaw/Pitch (= 0 op client).

        SetRotation(AimRotation);
        if (Instigator.Weapon != None)
            DrawOffset = Instigator.Weapon.CalcDrawOffset();

        GetAxes(AimRotation, X, Y, Z);

        if ( bCenter )
        {
            FireOffset.Z = Default.FireOffset.Z * 1.5;
            FireOffset.Y = 0;
        }
        else
        {
            FireOffset.Z = Default.FireOffset.Z;
            if ( bRight )
                FireOffset.Y = Default.FireOffset.Y;
            else
                FireOffset.Y = -1 * Default.FireOffset.Y;
        }
        SetLocation(Instigator.Location + DrawOffset + FireOffset.X * X + FireOffset.Y * Y + FireOffset.Z * Z);
    }
    else
        GetAxes(Rotation, X, Y, Z);

    CheckBeam(X, DeltaTime);
}

defaultproperties
{
}
