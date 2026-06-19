//=============================================================================
// MinigunOnlyArena — arena mutator for minigun-only gameplay.
// Extends Botpack.MinigunArena (which replaces all weapons + ammo with
// Minigun2 + MiniAmmo) and additionally removes health, shield, armor,
// invisibility and udamage pickups. Players spawn with 100 HP but cannot heal
// or power up. Mirrors FlakOnlyArena.
//=============================================================================

class MinigunOnlyArena expands Botpack.MinigunArena;

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
