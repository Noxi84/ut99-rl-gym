//=============================================================================
// PulseOnlyArena — arena mutator for pulse-gun-only gameplay.
// Extends Botpack.PulseArena (which replaces all weapons + ammo with PulseGun
// + PAmmo) and additionally removes health, shield, armor, invisibility and
// udamage pickups. Players spawn with 100 HP but cannot heal or power up.
// Mirrors FlakOnlyArena.
//=============================================================================

class PulseOnlyArena expands Botpack.PulseArena;

function bool CheckReplacement(Actor Other, out byte bSuperRelevant)
{
	if ( Other.IsA('TournamentHealth') || Other.IsA('UT_Shieldbelt')
		|| Other.IsA('Armor2') || Other.IsA('ThighPads')
		|| Other.IsA('UT_Invisibility') || Other.IsA('UDamage') )
		return false;

	return Super.CheckReplacement( Other, bSuperRelevant );
}

// Override DefaultWeapon naar RLPulseGun (zonder Bot-AI aim-hijacking in
// altFire beam). WeaponName/WeaponString sturen Arena.CheckReplacement om
// alle weapon-spawns op de map te vervangen door RLPulseGun ipv stock PulseGun.
defaultproperties
{
    WeaponName=RLPulseGun
    WeaponString="NeuralNetWebserver.RLPulseGun"
    DefaultWeapon=class'RLPulseGun'
}
