//=============================================================================
// RocketOnlyArena — arena mutator for rocket-launcher-only gameplay.
// Extends Botpack.RocketArena (which replaces all weapons + ammo with
// UT_Eightball + RocketPack) and additionally removes health, shield, armor,
// invisibility and udamage pickups. Players spawn with 100 HP but cannot heal
// or power up. Mirrors FlakOnlyArena.
//=============================================================================

class RocketOnlyArena expands Botpack.RocketArena;

function bool CheckReplacement(Actor Other, out byte bSuperRelevant)
{
	if ( Other.IsA('TournamentHealth') || Other.IsA('UT_Shieldbelt')
		|| Other.IsA('Armor2') || Other.IsA('ThighPads')
		|| Other.IsA('UT_Invisibility') || Other.IsA('UDamage') )
		return false;

	return Super.CheckReplacement( Other, bSuperRelevant );
}

// Override DefaultWeapon naar RLEightball (zonder Bot/Enemy=None forcing van
// bFire/bAltFire die multi-load breekt). WeaponName/WeaponString sturen
// Arena.CheckReplacement om alle UT_Eightball-spawns op de map te vervangen
// door RLEightball ipv stock UT_Eightball.
defaultproperties
{
    WeaponName=RLEightball
    WeaponString="NeuralNetWebserver.RLEightball"
    DefaultWeapon=class'RLEightball'
}
