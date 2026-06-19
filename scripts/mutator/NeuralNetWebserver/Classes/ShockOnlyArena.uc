//=============================================================================
// ShockOnlyArena — arena mutator for shock-only gameplay.
// Extends Botpack.ShockArena (which replaces all weapons + ammo with ShockRifle
// + ShockCore) and additionally removes health, shield, armor, invisibility
// and udamage pickups. Players spawn with 100 HP but cannot heal or power up.
// Mirrors FlakOnlyArena — keeps the RL combat-reward signal pickup-free.
//=============================================================================

class ShockOnlyArena expands Botpack.ShockArena;

function bool CheckReplacement(Actor Other, out byte bSuperRelevant)
{
	if ( Other.IsA('TournamentHealth') || Other.IsA('UT_Shieldbelt')
		|| Other.IsA('Armor2') || Other.IsA('ThighPads')
		|| Other.IsA('UT_Invisibility') || Other.IsA('UDamage') )
		return false;

	return Super.CheckReplacement( Other, bSuperRelevant );
}

// Override DefaultWeapon naar RLShockRifle (zonder de Bot/Enemy=None AltFire-
// redirect die de shockball-spawn -- en daarmee de shock-combo -- blokkeert).
// WeaponName/WeaponString sturen Arena.CheckReplacement om alle ShockRifle-
// spawns op de map te vervangen door RLShockRifle ipv stock ShockRifle.
defaultproperties
{
    WeaponName=RLShockRifle
    WeaponString="NeuralNetWebserver.RLShockRifle"
    DefaultWeapon=class'RLShockRifle'
}
