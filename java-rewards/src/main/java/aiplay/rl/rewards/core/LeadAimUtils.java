package aiplay.rl.rewards.core;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.view.FireModeAimTargeting;

/**
 * Lead-aim hulpfuncties voor reward-componenten.
 *
 * <p>Bot-perspectief: voor projectile-wapens (flak shells, rockets, ...) moet de
 * crosshair vóór de bewegende enemy mikken zodat het projectiel en de enemy
 * elkaar ontmoeten op het impact-tijdstip. Hitscan-wapens (SuperShockRifle in
 * instagib) hebben hier niets aan — de straal arriveert direct.</p>
 *
 * <p>Centraliseren in deze utility houdt {@link aiplay.rl.rewards.aim.ViewAlignmentReward} compact en
 * voorkomt drift tussen reward computeViewAlignment en computeAcquisition.</p>
 */
public final class LeadAimUtils {

    /** Marker voor hitscan: geen lead-correctie nodig. */
    public static final String INSTAGIB_WEAPON_CLASS = "Botpack.SuperShockRifle";
    /** Flak Cannon (per-mode speed via config — primary chunks vs secondary slug). */
    public static final String FLAK_WEAPON_CLASS = "Botpack.UT_FlakCannon";

    /** Velocity normalisatie schaal in PlayerPawnBasicFeatureJsonToDtoConverter. */
    private static final double VELOCITY_NORM_SCALE = 1000.0;

    private LeadAimUtils() {}

    /**
     * Resolveer welke enemy DTO gebruikt moet worden voor lead-correctie.
     * Returned null wanneer attention target geen enemy is, of wanneer de
     * resolved enemy onbruikbaar is (dood / geen positie).
     */
    public static PlayerDto resolveLeadEnemy(GameStateDto frame) {
        return resolveLeadEnemy(frame, -1);
    }

    /**
     * Phase 2 overload: when {@code modelTargetIndex} is in [0, enemies.length),
     * the shooting model's chosen target overrides the engagement-rule target.
     * Falls back to engagement-aware resolution when the model index is out of
     * range, points at a dead/missing enemy, or is the sentinel -1 (absent).
     */
    public static PlayerDto resolveLeadEnemy(GameStateDto frame, int modelTargetIndex) {
        if (frame == null) return null;
        if (modelTargetIndex >= 0 && frame.enemies != null
                && modelTargetIndex < frame.enemies.length) {
            PlayerDto modelChoice = frame.enemies[modelTargetIndex];
            if (modelChoice != null && modelChoice.health > 0 && modelChoice.location != null) {
                return modelChoice;
            }
            // model picked a dead/missing slot — fall through to engagement target
            // rather than returning null (avoids dropping the reward signal entirely)
        }
        if (frame.annotatedAttentionTarget == null) return null;
        // Unified aim-target read: AimTargetSelector resolves all ENEMY_* attention types into
        // frame.annotatedAimEnemy (via AimTargetEnricher, priority 5, after engagement enricher). LeadAim must
        // use the same source as BearingSelector + LookaheadHeadingCalculator + ViewTargeting
        // so reward-target, model-input bearing, and heading-reference are aligned.
        PlayerDto enemy = switch (frame.annotatedAttentionTarget) {
            case ENEMY_PLAYER, ENEMY_CARRIER, ENEMY_NEAREST_TO_HOME_FLAG, ENEMY_NEAREST_TO_ATTACKER,
                 ENEMY_THREAT_TO_SELF ->
                (frame.annotatedAimEnemy != null) ? frame.annotatedAimEnemy : firstEnemy(frame);
            default -> null;
        };
        if (enemy == null || enemy.health <= 0 || enemy.location == null) return null;
        return enemy;
    }

    /**
     * Projectielspeed voor het huidig gedragen wapen + fire-mode, of
     * {@link Double#POSITIVE_INFINITY} voor hitscan / onbekend (geen lead-correctie).
     *
     * @param weaponClass current weapon class string
     * @param flakPrimaryUu UTChunk MaxSpeed (2700 UU/s) — zie {@code Botpack/UTChunk.uc}
     * @param flakSecondaryUu FlakSlug speed (1200 UU/s) — zie {@code Botpack/flakslug.uc}
     * @param altFire true als de evaluatie voor secondary-fire is (flak slug); false voor primary
     */
    public static double projectileSpeedForWeapon(String weaponClass,
                                                  double flakPrimaryUu, double flakSecondaryUu,
                                                  boolean altFire) {
        return FireModeAimTargeting.projectileSpeedForWeapon(
            weaponClass, flakPrimaryUu, flakSecondaryUu, altFire);
    }

    /**
     * Pas lead-correctie toe op een target-coordinaat. Returned een NIEUWE
     * {@link CoordinatesDto} met de geprojecteerde positie waar de enemy zich
     * naar verwachting bevindt op projectile-impact-tijdstip. Returned het
     * originele target ongewijzigd (zelfde reference) voor hitscan / nul-snelheid /
     * ontbrekende velocity-info.
     *
     * @param baseTarget huidige enemy positie (vaak eye-target)
     * @param botLocation positie van de bot voor distance-berekening
     * @param enemy enemy DTO voor velocity (mag null zijn → no-op)
     * @param projectileSpeedUu projectielspeed; ∞ → no-op
     */
    public static CoordinatesDto applyLeadOrFallback(CoordinatesDto baseTarget,
                                                     CoordinatesDto botLocation,
                                                     PlayerDto enemy,
                                                     double projectileSpeedUu) {
        if (baseTarget == null) return null;
        if (botLocation == null) return baseTarget;
        if (enemy == null) return baseTarget;
        if (!Double.isFinite(projectileSpeedUu) || projectileSpeedUu <= 0.0) return baseTarget;

        // Un-normalize enemy world velocity. Bij speeds > 1000 UU/s is velocityX_norm
        // geclipt op ±1, dus we onderschatten leading bij dodges/launches. Acceptabel
        // voor MVP — kan later vervangen worden door raw velocity in PlayerDto.
        double evx = enemy.velocityX_norm * VELOCITY_NORM_SCALE;
        double evy = enemy.velocityY_norm * VELOCITY_NORM_SCALE;
        double evz = enemy.velocityZ_norm * VELOCITY_NORM_SCALE;
        double speedSq = evx * evx + evy * evy + evz * evz;
        if (speedSq < 1.0) return baseTarget; // staande enemy → geen lead

        double dx = baseTarget.x - botLocation.x;
        double dy = baseTarget.y - botLocation.y;
        double dz = baseTarget.z - botLocation.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(dist) || dist < 1.0) return baseTarget;

        double travelTime = dist / projectileSpeedUu;

        CoordinatesDto lead = new CoordinatesDto();
        lead.x = baseTarget.x + evx * travelTime;
        lead.y = baseTarget.y + evy * travelTime;
        lead.z = baseTarget.z + evz * travelTime;
        // x_norm/y_norm/z_norm blijven 0 — niet gebruikt door reward computatie.
        return lead;
    }

    /**
     * 3D aim score voor het huidig zicht-frame: cosine tussen view direction
     * (yaw + pitch) en de vector naar het lead-gecorrigeerde target. Per-mode:
     * primary en secondary fire krijgen aparte scores omdat hun projectielen
     * verschillende snelheden hebben (flak chunks 2700 UU/s vs slug 1200 UU/s).
     *
     * <p>Resolutie-volgorde: model target_index → engagement target → eerst
     * zichtbare vijand. Returned 0.0 wanneer er geen target is — dat fungeert
     * als signaal voor "vuren in het luchtledige" voor reward-componenten die
     * dit als off-target willen behandelen.
     *
     * <p>Single source of truth voor zowel event-getriggerde shot-rewards
     * (CombatEventReward) als per-tick fire-holding penalties — voorkomt drift
     * tussen de twee.
     *
     * @param altFire true voor secondary fire onset (flak slug, ~1200 UU/s lead);
     *                false voor primary (flak chunks, ~2700 UU/s lead). Voor hitscan
     *                weapons heeft de flag geen effect.
     * @param rocketPrimaryAimTargetHeightUu verticale offset (UU) op enemy.location.z voor rocket
     *                primary aim — typisch 0 (foot/origin) voor splash-friendly ground aim. Geldt
     *                alleen voor RLEightball/UT_Eightball primary; andere wapens mikken op eye.
     * @param sniperPrimaryAimTargetHeightUu verticale offset (UU) op enemy.location.z voor Sniper
     *                primary aim — head-center (~+31) zodat de aim-score op het hoofd piekt
     *                (decap-zone → 100 HP). Geldt alleen voor Botpack.SniperRifle primary; andere
     *                wapens mikken op eye.
     */
    public static double computeAimScore3D(GameStateDto frame, int modelTargetIndex,
                                           double flakPrimaryUu, double flakSecondaryUu,
                                           double rocketPrimaryAimTargetHeightUu,
                                           double sniperPrimaryAimTargetHeightUu,
                                           boolean altFire) {
        return FireModeAimTargeting.computeAimScore3D(
            frame, modelTargetIndex, flakPrimaryUu, flakSecondaryUu,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, altFire);
    }

    private static PlayerDto firstEnemy(GameStateDto frame) {
        if (frame.enemies == null) return null;
        for (PlayerDto e : frame.enemies) {
            if (e != null && e.health > 0 && e.location != null) return e;
        }
        return null;
    }

}
