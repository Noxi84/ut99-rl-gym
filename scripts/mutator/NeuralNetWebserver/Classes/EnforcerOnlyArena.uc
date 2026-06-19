//=============================================================================
// EnforcerOnlyArena — arena mutator for enforcer-only gameplay.
// Extends Botpack.Arena directly (geen Botpack.EnforcerArena beschikbaar) en
// configureert DefaultWeapon + Weapon/Ammo replacement op Enforcer + Miniammo
// (gedeeld met Minigun). Strippt daarnaast health/shield/armor/invisibility/
// udamage pickups zoals FlakOnlyArena.
//=============================================================================

class EnforcerOnlyArena expands Botpack.Arena;

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
	WeaponName=Enforcer
	AmmoName=Miniammo
	WeaponString="Botpack.Enforcer"
	AmmoString="Botpack.Miniammo"
	DefaultWeapon=Class'Botpack.Enforcer'
}
