package aiplay.dto;

/**
 * Relation van een projectile (van tegenstander) t.o.v. bot/playerPawn.
 * Gebruikt voor movement/viewrotation awareness — laat model inkomende
 * flak chunks / grenades anticiperen en ontwijken.
 *
 * <p>Alle velden zijn egocentrisch (bot-frame) of scalar → invariant onder
 * 180° team-flip, geen perspective-normalization-aanpassing vereist.
 */
public class ProjectileRelationDto {
    /** 1.0 als slot gevuld, 0.0 anders. */
    public float present;
    /** sin(bearing) t.o.v. bot view-yaw — positief = projectile rechts. */
    public double relSin;
    public double relCos;
    /** 3D-afstand bot → projectile, genormaliseerd op kaartdiagonaal. */
    public double distance_norm;
    /** Verticale bearing eye→projectile, in [-1,+1]. */
    public double pitchBearing_norm;
    /** Projectile velocity componenten in bot-frame, genormaliseerd op 2000 UT/s. */
    public double forwardVelocity_norm;
    public double rightVelocity_norm;
    /** |velocity| / 2000 UT/s. */
    public double speed_norm;
    /** Geschatte tijd tot closest approach, in [0,1] (clamped bij 2 sec flight time). */
    public double timeToImpact_norm;
    /** Closest-approach-distance (3D miss-margin op huidige baan), genormaliseerd op kaart-
     *  diagonaal via {@code NormalizationUtils.normalizeDistance3D}. Niet als feature
     *  gepubliceerd — uitsluitend voor reward-shaping (FlakAvoidanceReward). */
    public double closestApproachDistance_norm;
    /** Closest-approach-distance (3D miss-margin) van dit projectiel tot de DICHTSTBIJZIJNDE enemy
     *  op de huidige baan, genormaliseerd op kaartdiagonaal. Laag = projectiel scheert langs een enemy
     *  → offensieve kans (shock-combo-detonatie, rocket/flak-splash-timing). Analoog aan
     *  {@link #closestApproachDistance_norm} maar tegen enemies i.p.v. de bot. Vooral zinvol voor
     *  eigen projectielen; default 1.0 (= geen enemy nabij de baan) wanneer er geen enemies zijn. */
    public double enemyClosestApproach_norm = 1.0;
    /** Geschatte tijd tot het closest-approach-moment t.o.v. de dichtstbijzijnde enemy, in [0,1]
     *  (clamped bij 2 sec flight time). Geeft de combo-/splash-TIMING: wanneer scheert het projectiel
     *  langs de enemy — het moment om de shock-beam te vuren / op de splash te rekenen. Default 1.0. */
    public double enemyTimeToClosest_norm = 1.0;
    /** 1.0 als projectile class flak grenade (flakslug / UTFlakShell). */
    public float isGrenade;
    /** 1.0 als projectile class flak chunk (UTChunk1..4). */
    public float isChunk;
    /** 1.0 als Botpack.ShockProj (shock-rifle secondary blue ball). */
    public float isShockBall;
    /** 1.0 als Botpack.RocketMk2 (rocket-launcher primary rocket). */
    public float isRocket;
    /** 1.0 als Botpack.Grenade (rocket-launcher alt-fire grenade). */
    public float isRocketGrenade;
    /** 1.0 als Botpack.UT_BioGel (bio-rifle primary spray goo). Lage damage (~25). */
    public float isBioBlob;
    /** 1.0 als Botpack.BioGlob (bio-rifle charged alt-fire glob). Damage 75 × DrawScale,
     *  spawnt extra BioSplash bij wall-hit. {@link #chargeScale_norm} codeert het laad-niveau. */
    public float isBioGlob;
    /** 1.0 als Botpack.PlasmaSphere (pulse-gun primary plasma chunk). */
    public float isPulsePlasma;
    /** 1.0 als Botpack.Razor2 (ripper sawblade — primary & secondary). */
    public float isRazor;
    /** 1.0 als Botpack.WarShell (redeemer warhead missile). */
    public float isRedeemerMissile;
    /** 1.0 als Botpack.TranslocatorTarget (de gegooide disc). */
    public float isTranslocatorDisc;
    /** Statische damage uit UC Projectile.Damage, genormaliseerd op 250 HP en geclamped [0,1].
     *  Geeft het model 'hoe gevaarlijk is dit projectiel' direct — werkt voor alle wapens. */
    public double damage_norm;
    /** UC Actor.DrawScale gemapt naar [0,1] via (drawScale - 1) / 3, clamped. 0 = unscaled
     *  (regular projectile), 1 = max charge (BioGlob full charge). Voornamelijk relevant
     *  voor BioGlob; voor andere projectielen ~0.0. */
    public double chargeScale_norm;

    public ProjectileRelationDto deepCopy() {
        ProjectileRelationDto c = new ProjectileRelationDto();
        c.present = this.present;
        c.relSin = this.relSin;
        c.relCos = this.relCos;
        c.distance_norm = this.distance_norm;
        c.pitchBearing_norm = this.pitchBearing_norm;
        c.forwardVelocity_norm = this.forwardVelocity_norm;
        c.rightVelocity_norm = this.rightVelocity_norm;
        c.speed_norm = this.speed_norm;
        c.timeToImpact_norm = this.timeToImpact_norm;
        c.closestApproachDistance_norm = this.closestApproachDistance_norm;
        c.enemyClosestApproach_norm = this.enemyClosestApproach_norm;
        c.enemyTimeToClosest_norm = this.enemyTimeToClosest_norm;
        c.isGrenade = this.isGrenade;
        c.isChunk = this.isChunk;
        c.isShockBall = this.isShockBall;
        c.isRocket = this.isRocket;
        c.isRocketGrenade = this.isRocketGrenade;
        c.isBioBlob = this.isBioBlob;
        c.isBioGlob = this.isBioGlob;
        c.isPulsePlasma = this.isPulsePlasma;
        c.isRazor = this.isRazor;
        c.isRedeemerMissile = this.isRedeemerMissile;
        c.isTranslocatorDisc = this.isTranslocatorDisc;
        c.damage_norm = this.damage_norm;
        c.chargeScale_norm = this.chargeScale_norm;
        return c;
    }
}
