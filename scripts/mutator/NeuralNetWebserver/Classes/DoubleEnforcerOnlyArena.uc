//=============================================================================
// DoubleEnforcerOnlyArena — arena mutator voor dual-wield enforcer gameplay.
//
// Botpack.DoubleEnforcer is een Enforcer-subclass die door UT99's pickup-flow
// automatisch wordt geïnstantieerd zodra een speler een tweede Enforcer pakt.
// Wij dwingen die state af in ModifyPlayer() door direct na de standaard
// inventory-toekenning een tweede Enforcer toe te kennen.
//
// Pickup-vervanging gebeurt op de basis Enforcer-class (zoals EnforcerOnly),
// niet op DoubleEnforcer — anders zou de speler bij eerste pickup al dual zijn
// zonder dat de upgrade-trigger doorlopen wordt.
//=============================================================================

class DoubleEnforcerOnlyArena expands Botpack.Arena;

function ModifyPlayer(Pawn Other)
{
	local Inventory Inv;
	local bool bHasEnforcer;

	Super.ModifyPlayer(Other);

	// Tel hoeveel Enforcers de speler al heeft na default-inventory.
	bHasEnforcer = false;
	for (Inv = Other.Inventory; Inv != None; Inv = Inv.Inventory)
	{
		if (Inv.IsA('Enforcer'))
		{
			bHasEnforcer = true;
			break;
		}
	}

	// Trigger de dual-upgrade door een tweede Enforcer toe te kennen.
	// Botpack.Enforcer.SpawnCopy() detecteert de tweede pickup en
	// promoveert de bestaande naar DoubleEnforcer.
	if (bHasEnforcer && DeathMatchPlus(Level.Game) != None)
		DeathMatchPlus(Level.Game).GiveWeapon(Other, "Botpack.Enforcer");
}

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
