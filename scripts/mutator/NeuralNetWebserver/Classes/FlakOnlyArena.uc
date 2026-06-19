//=============================================================================
// FlakOnlyArena — arena mutator for flak-only gameplay.
// Extends Botpack.FlakArena (which replaces all weapons + ammo with UT_FlakCannon
// + FlakAmmo) and additionally removes health, shield, armor, invisibility and
// udamage pickups. Players spawn with 100 HP but cannot heal or power up.
//=============================================================================

class FlakOnlyArena expands Botpack.FlakArena;

function bool CheckReplacement(Actor Other, out byte bSuperRelevant)
{
	if ( Other.IsA('TournamentHealth') || Other.IsA('UT_Shieldbelt')
		|| Other.IsA('Armor2') || Other.IsA('ThighPads')
		|| Other.IsA('UT_Invisibility') || Other.IsA('UDamage') )
		return false;

	return Super.CheckReplacement( Other, bSuperRelevant );
}

defaultproperties
{
}
