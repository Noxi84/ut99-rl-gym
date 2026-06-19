//=============================================================================
// RipperOnlyArena — arena mutator for ripper-only gameplay.
// Extends Botpack.Arena directly (geen Botpack.RipperArena beschikbaar) en
// configureert DefaultWeapon + Weapon/Ammo replacement op Ripper + BladeHopper.
// Strippt health/shield/armor/invisibility/udamage pickups zoals FlakOnlyArena.
//=============================================================================

class RipperOnlyArena expands Botpack.Arena;

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
	WeaponName=Ripper
	AmmoName=BladeHopper
	WeaponString="Botpack.Ripper"
	AmmoString="Botpack.BladeHopper"
	DefaultWeapon=Class'Botpack.Ripper'
}
