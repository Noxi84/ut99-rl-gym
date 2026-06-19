//=============================================================================
// RLShockRifle — ShockRifle voor RL bots. Eén fix vs stock Botpack/ShockRifle:
// een state-loze AltFire() override.
//
// Stock AltFire (ShockRifle.uc:55-92) heeft een "make sure won't blow self up"-
// blok voor Bots:
//     if ( Owner.IsA('Bot') ) {
//         if ( Pawn(Owner).Enemy != None ) HitActor = Trace(...);
//         else                             HitActor = self;
//         if ( HitActor != None ) { Global.Fire(Value); return; }   // <- PRIMARY i.p.v. bal
//     }
// Onze RLBot houdt per design Enemy=None (RLBot.uc:35) -> HitActor=self -> de
// alt-fire wordt STIL omgezet naar een primary beam. Gevolg: de bot kan FYSIEK
// geen ShockProj (shockball) spawnen -> de shock-combo is onmogelijk.
//
// Override verwijdert het Bot/Enemy-blok (en het TacticalMove/SpecialFire-blok,
// dat puur Bot-AI is en voor RLBot+Enemy=None nooit triggert): AltFire spawnt nu
// onvoorwaardelijk een shockball zolang er ammo is. Aim loopt via
// ProjectileFire -> RLBot.AdjustAim (RLTargetYaw/Pitch).
//
// De rest blijft stock: primary TraceFire valt in de non-bBotSpecialMove tak en
// gebruikt AdjustAim correct; de combo-detonatie (ProcessTraceHit -> ShockProj.
// SuperExplosion zodra de beam de bal raakt) is pure engine-fysica -- geen
// ComboMove-AI nodig (die state wordt nooit bereikt want Finish() vereist
// Enemy!=None). ShockProj zelf heeft geen aim-hijackende Tick (anders dan
// StarterBolt/PBolt), dus geen RLShockProj-variant nodig.
//=============================================================================
class RLShockRifle extends ShockRifle;

function AltFire( float Value )
{
	if ( Owner == None )
		return;

	if ( AmmoType.UseAmmo(1) )
	{
		GotoState('AltFiring');
		bCanClientFire = true;
		Pawn(Owner).PlayRecoil(FiringSpeed);
		bPointing = True;
		ProjectileFire(AltProjectileClass, AltProjectileSpeed, bAltWarnTarget);
		ClientAltFire(Value);
	}
}

defaultproperties
{
}
