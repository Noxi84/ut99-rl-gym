//=============================================================================
// BioOnlyArena — arena mutator for bio-rifle-only gameplay.
// Extends Botpack.Arena directly (geen Botpack.BioArena beschikbaar) en
// configureert DefaultWeapon + Weapon/Ammo replacement op UT_BioRifle + BioAmmo.
// Strippt health/shield/armor/invisibility/udamage pickups zoals FlakOnlyArena.
//=============================================================================

class BioOnlyArena expands Botpack.Arena;

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
	WeaponName=UT_BioRifle
	AmmoName=BioAmmo
	WeaponString="Botpack.UT_BioRifle"
	AmmoString="Botpack.BioAmmo"
	DefaultWeapon=Class'Botpack.UT_BioRifle'
}
