//=============================================================================
// SniperOnlyArena — arena mutator for sniper-only gameplay.
// Extends Botpack.SniperArena (which replaces all weapons + ammo with
// SniperRifle + BulletBox) and additionally removes health, shield, armor,
// invisibility and udamage pickups. Players spawn with 100 HP but cannot heal
// or power up. Mirrors FlakOnlyArena.
//=============================================================================

class SniperOnlyArena expands Botpack.SniperArena;

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
