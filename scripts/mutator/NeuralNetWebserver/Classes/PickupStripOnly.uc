//=============================================================================
// PickupStripOnly — pickup-strip mutator without weapon replacement.
// For weapon_profile="all": keeps the full UT99 weapon arsenal on the map but
// removes health, shield, armor, invisibility and udamage pickups so the RL
// combat-reward signal stays free of pickup-bias (analogous to FlakOnlyArena,
// but without forcing a single weapon class).
//
// Additionally hands every player — RL bots, joining humans and stock UT99 bots —
// the full standard arsenal + full ammo on each (re)spawn via ModifyPlayer, so
// weapon_profile="all" means "everyone carries everything" symmetrically. RL bots
// need it because their policy was trained in single-weapon arenas and never
// navigates to weapon pickups (without it they'd keep only their default enforcer /
// impact hammer / translocator and the Java weapon-planner could never activate
// their preferred_weapon); a joining human would otherwise spawn with that same
// stock default while the bots had the full set. ModifyPlayer is called by
// GameInfo.AddDefaultInventory on every spawn, including via RLBot.RLDoRestart.
// Redeemer + instagib SuperShockRifle are intentionally excluded — they are
// specials, not part of the standard loadout.
//
// The arena profiles (shock/flak/sniper/…) already apply to everyone via the
// engine (GameInfo.AddDefaultInventory -> BaseMutator.MutatedDefaultWeapon gives the
// arena weapon to every pawn); "all" is the only profile that needed this fix.
//=============================================================================

class PickupStripOnly expands Mutator;

function bool CheckReplacement(Actor Other, out byte bSuperRelevant)
{
	if ( Other.IsA('TournamentHealth') || Other.IsA('UT_Shieldbelt')
		|| Other.IsA('Armor2') || Other.IsA('ThighPads')
		|| Other.IsA('UT_Invisibility') || Other.IsA('UDamage') )
		return false;

	return Super.CheckReplacement( Other, bSuperRelevant );
}

function ModifyPlayer(Pawn Other)
{
	// Give the full arsenal to every real player, not just RL bots: a joining
	// human must get the same loadout under weapon_profile="all". Spectators
	// never reach here (GameInfo.AddDefaultInventory returns before it calls
	// BaseMutator.ModifyPlayer for them), but we guard defensively.
	if (Other != None && Spectator(Other) == None)
		GiveFullArsenal(Other);

	Super.ModifyPlayer(Other);
}

function GiveFullArsenal(Pawn P)
{
	// Shock / rocket / pulse use the RL override subclasses (same ones the single-weapon
	// arena mutators install via DefaultWeapon). The stock versions silently break for an
	// RLBot because it keeps Enemy=None: stock ShockRifle.AltFire redirects to a primary
	// beam (no shockball/combo), stock UT_Eightball forces bFire/bAltFire (breaks multi-
	// load), stock PulseGun hijacks the beam aim. The other weapons have no such issue
	// (their Enemy==None branches are AI-aim heuristics the RLBot.AdjustAim override
	// bypasses), so they stay stock — mirroring the arena mutators exactly.
	GiveArsenalWeapon(P, class'Botpack.UT_FlakCannon');
	GiveArsenalWeapon(P, class'RLShockRifle');
	GiveArsenalWeapon(P, class'RLEightball');
	GiveArsenalWeapon(P, class'Botpack.Minigun2');
	GiveArsenalWeapon(P, class'RLPulseGun');
	GiveArsenalWeapon(P, class'Botpack.SniperRifle');
	GiveArsenalWeapon(P, class'Botpack.Ripper');
	GiveArsenalWeapon(P, class'Botpack.UT_BioRifle');
	GiveArsenalWeapon(P, class'Botpack.Enforcer');
	GiveArsenalWeapon(P, class'Botpack.Translocator');
	GiveArsenalWeapon(P, class'Botpack.ImpactHammer');
}

// Give one weapon to the pawn (skip if already carried), then top its ammo to max
// so a freshly-spawned player is never one fire-burst away from falling back to the
// impact hammer. Mirrors the stock DeathMatchPlus.GiveWeapon spawn sequence.
function GiveArsenalWeapon(Pawn P, class<Weapon> WClass)
{
	local Weapon W;

	W = Weapon(P.FindInventoryType(WClass));
	if (W == None)
	{
		W = Spawn(WClass,,, P.Location);
		if (W == None)
			return;
		W.Instigator = P;
		W.BecomeItem();
		P.AddInventory(W);
		W.GiveAmmo(P);
		W.SetSwitchPriority(P);
		W.WeaponSet(P);
		// Mirror DeathMatchPlus.GiveWeapon's pawn split: a human (PlayerPawn) gets
		// its first-person weapon hand set; a bot is non-PlayerPawn so the weapon
		// parks in 'Idle' (no BringUp). Either way the currently-held default weapon
		// stays active — WeaponSet only switches on a higher SwitchPriority.
		if (PlayerPawn(P) != None)
			W.SetHand(PlayerPawn(P).Handedness);
		else
			W.GotoState('Idle');
	}

	if (W.AmmoType != None)
		W.AmmoType.AmmoAmount = W.AmmoType.MaxAmmo;
}

defaultproperties
{
}
