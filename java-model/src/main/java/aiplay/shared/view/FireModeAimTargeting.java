package aiplay.shared.view;

import aiplay.dto.CoordinatesDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.util.NormalizationUtils;

/**
 * Fire-mode-aware aim target math shared by features and rewards.
 *
 * <p>Primary flak chunks are treated as direct projectiles. Secondary flak slug is a
 * {@code PHYS_Falling} projectile with {@code Velocity.Z += 200}, so its useful pitch is an
 * elevated ballistic solution rather than the direct line to the enemy.</p>
 */
public final class FireModeAimTargeting {

    public static final String INSTAGIB_WEAPON_CLASS = "Botpack.SuperShockRifle";
    public static final String FLAK_WEAPON_CLASS = "Botpack.UT_FlakCannon";

    // Class strings use the exact casing UScript's string(W.Class) emits (follows the
    // .uc filename, not the declaration). Several stock Botpack weapons are lowercase
    // (enforcer, minigun2, ripper, ut_biorifle). Kept in lock-step with WeaponClassNameTable.
    private static final String SHOCK_RIFLE_CLASS = "Botpack.ShockRifle";
    /** RL override-subclass die bots in de shock-arena dragen (zie WeaponClassNameTable). */
    private static final String RL_SHOCK_RIFLE_CLASS = "NeuralNetWebserver.RLShockRifle";
    private static final String MINIGUN_CLASS = "Botpack.minigun2";
    private static final String SNIPER_RIFLE_CLASS = "Botpack.SniperRifle";
    private static final String ENFORCER_CLASS = "Botpack.enforcer";
    private static final String IMPACT_HAMMER_CLASS = "Botpack.ImpactHammer";
    private static final String TRANSLOCATOR_CLASS = "Botpack.Translocator";
    private static final String EIGHTBALL_CLASS = "Botpack.UT_Eightball";
    /** RL override-subclass dat bots in arena-modes dragen (zie WeaponClassNameTable). */
    private static final String RL_EIGHTBALL_CLASS = "NeuralNetWebserver.RLEightball";
    private static final String BIO_RIFLE_CLASS = "Botpack.ut_biorifle";
    private static final String RIPPER_CLASS = "Botpack.ripper";
    private static final String PULSE_GUN_CLASS = "Botpack.PulseGun";

    private static final double ROCKET_PROJECTILE_SPEED_UU = 900.0;
    private static final double BIO_PROJECTILE_SPEED_UU = 1500.0;
    private static final double RIPPER_BLADE_SPEED_UU = 1100.0;
    private static final double VELOCITY_NORM_SCALE = 1000.0;
    private static final double TAU = 2.0 * Math.PI;

    /** UT99 ZoneInfo default gravity. */
    private static final double DEFAULT_GRAVITY_Z_UU = -950.0;
    /** Botpack.flakslug.PostBeginPlay adds this after applying Vector(Rotation) * Speed. */
    private static final double FLAK_SECONDARY_Z_BOOST_UU = 200.0;

    private FireModeAimTargeting() {}

    /** True voor het stock Eightball of de RL override-subclass. */
    public static boolean isEightballClass(String weaponClass) {
        return EIGHTBALL_CLASS.equals(weaponClass) || RL_EIGHTBALL_CLASS.equals(weaponClass);
    }

    /** True voor de stock ShockRifle of de RL override-subclass (niet de instagib SuperShockRifle). */
    public static boolean isShockRifleClass(String weaponClass) {
        return SHOCK_RIFLE_CLASS.equals(weaponClass) || RL_SHOCK_RIFLE_CLASS.equals(weaponClass);
    }

    public static double computePrimaryPitchErrorNorm(GameStateDto frame, int targetIndex,
                                                      double flakPrimaryUu, double flakSecondaryUu,
                                                      double rocketPrimaryAimTargetHeightUu,
                                                      double sniperPrimaryAimTargetHeightUu) {
        return computePitchErrorNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, false);
    }

    public static double computeSecondaryPitchErrorNorm(GameStateDto frame, int targetIndex,
                                                        double flakPrimaryUu, double flakSecondaryUu,
                                                        double rocketPrimaryAimTargetHeightUu,
                                                        double sniperPrimaryAimTargetHeightUu) {
        return computePitchErrorNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, true);
    }

    public static double computeSelectedPitchErrorNorm(GameStateDto frame, int targetIndex,
                                                       double flakPrimaryUu, double flakSecondaryUu,
                                                       double rocketPrimaryAimTargetHeightUu,
                                                       double sniperPrimaryAimTargetHeightUu,
                                                       boolean fire, boolean altFire) {
        if (altFire) {
            return computeSecondaryPitchErrorNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
                rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu);
        }
        if (fire) {
            return computePrimaryPitchErrorNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
                rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu);
        }
        return 0.0;
    }

    public static double computeSelectedPitchNorm(GameStateDto frame, int targetIndex,
                                                  double flakPrimaryUu, double flakSecondaryUu,
                                                  double rocketPrimaryAimTargetHeightUu,
                                                  double sniperPrimaryAimTargetHeightUu,
                                                  boolean fire, boolean altFire) {
        if (altFire) {
            return computePitchNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
                rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, true);
        }
        if (fire) {
            return computePitchNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
                rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, false);
        }
        return Double.NaN;
    }

    /**
     * Aim score against the fire-mode-specific desired view direction. For flak secondary this
     * scores the elevated lob angle, not the direct ray to the target. For rocket primary the
     * aim target z is offset by {@code rocketPrimaryAimTargetHeightUu} (typisch 0 = enemy-foot)
     * voor splash-friendly ground-aim.
     */
    public static double computeAimScore3D(GameStateDto frame, int targetIndex,
                                           double flakPrimaryUu, double flakSecondaryUu,
                                           double rocketPrimaryAimTargetHeightUu,
                                           double sniperPrimaryAimTargetHeightUu,
                                           boolean altFire) {
        AimSolution solution = computeAimSolution(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, altFire);
        if (solution == null || frame.playerPawn == null || frame.playerPawn.viewRotation == null) {
            return 0.0;
        }

        double currentYaw = NormalizationUtils.viewRotationXToRad(frame.playerPawn.viewRotation.x);
        int signedPitch = ViewTargeting.extractSignedPitch(frame);
        double currentPitch = signedPitch * (TAU / 65536.0);

        double dot = directionDot(currentYaw, currentPitch, solution.yawRad, pitchNormToRad(solution.pitchNorm));
        return Math.max(0.0, dot);
    }

    /**
     * Cosine tussen de huidige kijkrichting van de bot (yaw uit {@code viewRotation.x} +
     * signed pitch) en de directe richting van het bot-oog naar een willekeurig wereld-punt.
     * Hitscan-recht: geen lead-correctie of ballistiek — bedoeld voor "mik-op-een-statisch-punt"
     * gevallen, zoals de eigen {@code Botpack.ShockProj} bij een shock-combo (beam = hitscan).
     *
     * <p>Zelfde yaw/pitch-conventie en {@link #directionDot} als {@link #computeAimScore3D}, zodat
     * de score consistent is met de bestaande aim-rewards. Returned 0.0 (i.p.v. negatief) wanneer
     * het punt achter de bot ligt, en bij ontbrekende state of een (bijna) samenvallend punt.
     */
    public static double aimCosineToPoint(GameStateDto frame, CoordinatesDto worldPoint) {
        if (frame == null || frame.playerPawn == null
            || frame.playerPawn.viewRotation == null
            || frame.playerPawn.location == null
            || worldPoint == null) {
            return 0.0;
        }
        double currentYaw = NormalizationUtils.viewRotationXToRad(frame.playerPawn.viewRotation.x);
        int signedPitch = ViewTargeting.extractSignedPitch(frame);
        double currentPitch = signedPitch * (TAU / 65536.0);

        double srcX = frame.playerPawn.location.x;
        double srcY = frame.playerPawn.location.y;
        double srcZ = frame.playerPawn.location.z + safeEyeHeight(frame.playerPawn);
        double dx = worldPoint.x - srcX;
        double dy = worldPoint.y - srcY;
        double dz = worldPoint.z - srcZ;
        double horizontal = Math.hypot(dx, dy);
        if (!Double.isFinite(horizontal) || horizontal < 1.0) {
            return 0.0;
        }
        double yawB = Math.atan2(dy, dx);
        double pitchB = Math.atan2(dz, horizontal);
        return Math.max(0.0, directionDot(currentYaw, currentPitch, yawB, pitchB));
    }

    /**
     * Loodrechte afstand (UU) van {@code worldPoint} tot de semi-oneindige kijk-straal vanuit het
     * bot-oog — een afstandsonafhankelijke "raakt de beam dit punt?"-maat, i.t.t. een vaste
     * cosine-drempel (waarvan de lineaire tolerantie met de afstand meegroeit). Bedoeld om een échte
     * hitscan-beam-treffer op een specifiek punt te bevestigen, zoals de eigen
     * {@code Botpack.ShockProj} bij een shock-combo (beam = hitscan-recht). Zelfde yaw/pitch-conventie
     * als {@link #aimCosineToPoint}; de kijk-richting is een eenheidsvector dus de projectie is exact.
     *
     * <p>Returned {@code Double.POSITIVE_INFINITY} wanneer het punt achter de bot ligt of bij
     * ontbrekende state, zodat een afstands-drempel die gevallen afwijst.
     */
    public static double beamMissDistanceUu(GameStateDto frame, CoordinatesDto worldPoint) {
        if (frame == null || frame.playerPawn == null
            || frame.playerPawn.viewRotation == null
            || frame.playerPawn.location == null
            || worldPoint == null) {
            return Double.POSITIVE_INFINITY;
        }
        double currentYaw = NormalizationUtils.viewRotationXToRad(frame.playerPawn.viewRotation.x);
        int signedPitch = ViewTargeting.extractSignedPitch(frame);
        double currentPitch = signedPitch * (TAU / 65536.0);

        double cosP = Math.cos(currentPitch);
        double dirX = cosP * Math.cos(currentYaw);
        double dirY = cosP * Math.sin(currentYaw);
        double dirZ = Math.sin(currentPitch);

        double srcX = frame.playerPawn.location.x;
        double srcY = frame.playerPawn.location.y;
        double srcZ = frame.playerPawn.location.z + safeEyeHeight(frame.playerPawn);
        double ox = worldPoint.x - srcX;
        double oy = worldPoint.y - srcY;
        double oz = worldPoint.z - srcZ;

        double along = ox * dirX + oy * dirY + oz * dirZ;
        if (!Double.isFinite(along) || along <= 0.0) {
            return Double.POSITIVE_INFINITY; // punt ligt achter de bot
        }
        double perpSq = (ox * ox + oy * oy + oz * oz) - along * along;
        return perpSq > 0.0 ? Math.sqrt(perpSq) : 0.0;
    }

    public static PlayerDto resolveAimEnemy(GameStateDto frame, int targetIndex) {
        if (frame == null) return null;
        if (targetIndex >= 0 && frame.enemies != null && targetIndex < frame.enemies.length) {
            PlayerDto modelChoice = frame.enemies[targetIndex];
            if (isUsableEnemy(modelChoice)) {
                return modelChoice;
            }
        }
        if (isUsableEnemy(frame.annotatedAimEnemy)) {
            return frame.annotatedAimEnemy;
        }
        if (frame.enemies != null) {
            for (PlayerDto enemy : frame.enemies) {
                if (isUsableEnemy(enemy) && enemy.enemyVisible) {
                    return enemy;
                }
            }
            for (PlayerDto enemy : frame.enemies) {
                if (isUsableEnemy(enemy)) {
                    return enemy;
                }
            }
        }
        return isUsableEnemy(frame.player1) ? frame.player1 : null;
    }

    public static double projectileSpeedForWeapon(String weaponClass,
                                                  double flakPrimaryUu, double flakSecondaryUu,
                                                  boolean altFire) {
        if (weaponClass == null) return Double.POSITIVE_INFINITY;
        if (INSTAGIB_WEAPON_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (SHOCK_RIFLE_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (MINIGUN_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (SNIPER_RIFLE_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (ENFORCER_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (PULSE_GUN_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (IMPACT_HAMMER_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (TRANSLOCATOR_CLASS.equals(weaponClass)) return Double.POSITIVE_INFINITY;
        if (FLAK_WEAPON_CLASS.equals(weaponClass)) {
            return altFire ? flakSecondaryUu : flakPrimaryUu;
        }
        if (isEightballClass(weaponClass)) return ROCKET_PROJECTILE_SPEED_UU;
        if (BIO_RIFLE_CLASS.equals(weaponClass)) return BIO_PROJECTILE_SPEED_UU;
        if (RIPPER_CLASS.equals(weaponClass)) return RIPPER_BLADE_SPEED_UU;
        return Double.POSITIVE_INFINITY;
    }

    private static double computePitchErrorNorm(GameStateDto frame, int targetIndex,
                                                double flakPrimaryUu, double flakSecondaryUu,
                                                double rocketPrimaryAimTargetHeightUu,
                                                double sniperPrimaryAimTargetHeightUu,
                                                boolean altFire) {
        double desired = computePitchNorm(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, altFire);
        if (!Double.isFinite(desired)) {
            return 0.0;
        }
        return NormalizationUtils.clampM11(desired - ViewTargeting.extractCurrentPitchNorm(frame));
    }

    private static double computePitchNorm(GameStateDto frame, int targetIndex,
                                           double flakPrimaryUu, double flakSecondaryUu,
                                           double rocketPrimaryAimTargetHeightUu,
                                           double sniperPrimaryAimTargetHeightUu,
                                           boolean altFire) {
        AimSolution solution = computeAimSolution(frame, targetIndex, flakPrimaryUu, flakSecondaryUu,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu, altFire);
        return solution != null ? solution.pitchNorm : Double.NaN;
    }

    private static AimSolution computeAimSolution(GameStateDto frame, int targetIndex,
                                                  double flakPrimaryUu, double flakSecondaryUu,
                                                  double rocketPrimaryAimTargetHeightUu,
                                                  double sniperPrimaryAimTargetHeightUu,
                                                  boolean altFire) {
        if (frame == null || frame.playerPawn == null || frame.playerPawn.location == null) {
            return null;
        }
        PlayerDto enemy = resolveAimEnemy(frame, targetIndex);
        if (!isUsableEnemy(enemy)) {
            return null;
        }

        double speed = projectileSpeedForWeapon(
            frame.playerPawn.weaponClass, flakPrimaryUu, flakSecondaryUu, altFire);
        CoordinatesDto baseTarget = aimTarget(enemy, frame.playerPawn.weaponClass, altFire,
            rocketPrimaryAimTargetHeightUu, sniperPrimaryAimTargetHeightUu);
        // GEEN combo-bal-target-switch hier (verwijderd 2026-06-05): de switch liet het aim-target
        // (en daarmee óók de primaryAimPitchError/shootIntentPitchError-FEATURES en de
        // computeAimScore3D-gates van shot_on_target/fire_holding) per frame enemy↔bal flippen
        // zodra een eigen ShockProj door het gate-venster vloog → schokkerige aim bij shock
        // (gemeten 2× RMS-yawΔ vs flak) zonder combo-winst. Combo-vorming hoort bij de
        // shock_combo_* rewards; het aim-target blijft stabiel op de enemy.
        CoordinatesDto target = applyLinearLeadOrFallback(baseTarget, frame.playerPawn.location, enemy, speed);
        if (target == null) {
            return null;
        }

        double srcX = frame.playerPawn.location.x;
        double srcY = frame.playerPawn.location.y;
        double srcZ = frame.playerPawn.location.z + safeEyeHeight(frame.playerPawn);
        double dx = target.x - srcX;
        double dy = target.y - srcY;
        double horizontal = Math.hypot(dx, dy);
        if (!Double.isFinite(horizontal) || horizontal < 1.0) {
            return null;
        }

        double yaw = Math.atan2(dy, dx);
        double dz = target.z - srcZ;
        double pitchRad;
        if (altFire && FLAK_WEAPON_CLASS.equals(frame.playerPawn.weaponClass)) {
            pitchRad = solveFlakSecondaryPitchRad(horizontal, dz, speed);
        } else {
            pitchRad = Math.atan2(dz, horizontal);
        }
        return new AimSolution(yaw, radiansToPitchNorm(pitchRad));
    }

    /**
     * Weapon-conditional aim target. Voor rocket primary (UT_Eightball / RLEightball, geen altFire)
     * verschuift de target-z naar {@code enemy.location.z + rocketPrimaryAimTargetHeightUu} — typisch
     * 0 (enemy-foot/origin) zodat rockets op de grond onder de enemy exploderen voor maximaal splash
     * effect en dodge-resistance op gelijke hoogte. Voor Sniper primary verschuift de target-z naar
     * {@code enemy.location.z + sniperPrimaryAimTargetHeightUu} — head-center (~+31), zodat de pitch-/
     * view-rewards de aim recht op het hoofd trekken (UT99's decap-zone begint op +0.62·CollisionHeight
     * ≈ +24; head-aim → 100 HP {@code Decapitated} i.p.v. 45 HP body). Alle andere wapens (en rocket /
     * sniper alt-fire — grenades met eigen ballistic-lob solver, resp. scope-zoom zonder eigen damage)
     * blijven de eye-target gebruiken.
     */
    private static CoordinatesDto aimTarget(PlayerDto enemy, String weaponClass, boolean altFire,
                                            double rocketPrimaryAimTargetHeightUu,
                                            double sniperPrimaryAimTargetHeightUu) {
        if (!isUsableEnemy(enemy)) return null;
        CoordinatesDto target = enemy.location.deepCopy();
        if (!altFire && isEightballClass(weaponClass)) {
            target.z += rocketPrimaryAimTargetHeightUu;
        } else if (!altFire && SNIPER_RIFLE_CLASS.equals(weaponClass)) {
            target.z += sniperPrimaryAimTargetHeightUu;
        } else {
            target.z += safeEyeHeight(enemy);
        }
        return target;
    }

    private static CoordinatesDto applyLinearLeadOrFallback(CoordinatesDto baseTarget,
                                                            CoordinatesDto botLocation,
                                                            PlayerDto enemy,
                                                            double projectileSpeedUu) {
        if (baseTarget == null) return null;
        if (botLocation == null || enemy == null) return baseTarget;
        if (!Double.isFinite(projectileSpeedUu) || projectileSpeedUu <= 0.0) return baseTarget;

        double evx = enemy.velocityX_norm * VELOCITY_NORM_SCALE;
        double evy = enemy.velocityY_norm * VELOCITY_NORM_SCALE;
        double evz = enemy.velocityZ_norm * VELOCITY_NORM_SCALE;
        double speedSq = evx * evx + evy * evy + evz * evz;
        if (speedSq < 1.0) return baseTarget;

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
        return lead;
    }

    private static double solveFlakSecondaryPitchRad(double horizontal, double dz, double speed) {
        if (!Double.isFinite(speed) || speed <= 1.0) {
            return Math.atan2(dz, horizontal);
        }

        double min = Math.toRadians(-60.0);
        double max = Math.toRadians(80.0);
        double bestTheta = Math.atan2(dz, horizontal);
        double bestErr = Double.POSITIVE_INFINITY;
        double prevTheta = min;
        double prevErr = ballisticVertical(prevTheta, horizontal, speed) - dz;

        for (int i = 0; i <= 280; i++) {
            double theta = min + (max - min) * i / 280.0;
            double err = ballisticVertical(theta, horizontal, speed) - dz;
            double abs = Math.abs(err);
            if (abs < bestErr) {
                bestErr = abs;
                bestTheta = theta;
            }
            if (i > 0 && crossesZero(prevErr, err)) {
                return bisectPitch(prevTheta, theta, horizontal, dz, speed);
            }
            prevTheta = theta;
            prevErr = err;
        }
        return bestTheta;
    }

    private static double bisectPitch(double lo, double hi, double horizontal, double dz, double speed) {
        double loErr = ballisticVertical(lo, horizontal, speed) - dz;
        for (int i = 0; i < 32; i++) {
            double mid = 0.5 * (lo + hi);
            double midErr = ballisticVertical(mid, horizontal, speed) - dz;
            if (crossesZero(loErr, midErr)) {
                hi = mid;
            } else {
                lo = mid;
                loErr = midErr;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double ballisticVertical(double theta, double horizontal, double speed) {
        double cos = Math.cos(theta);
        if (Math.abs(cos) < 1e-4) {
            return Double.POSITIVE_INFINITY;
        }
        double time = horizontal / (speed * cos);
        if (!Double.isFinite(time) || time < 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return horizontal * Math.tan(theta)
            + FLAK_SECONDARY_Z_BOOST_UU * time
            + 0.5 * DEFAULT_GRAVITY_Z_UU * time * time;
    }

    private static boolean crossesZero(double a, double b) {
        return (a <= 0.0 && b >= 0.0) || (a >= 0.0 && b <= 0.0);
    }

    private static double safeEyeHeight(PlayerDto player) {
        return (player != null && Double.isFinite(player.baseEyeHeight)) ? player.baseEyeHeight : 0.0;
    }

    private static boolean isUsableEnemy(PlayerDto player) {
        return player != null && player.health > 0 && player.location != null;
    }

    private static double radiansToPitchNorm(double radians) {
        if (!Double.isFinite(radians)) return 0.0;
        int ut = (int) Math.round(radians * 65536.0 / TAU);
        if (ut > 18000) ut = 18000;
        if (ut < -16384) ut = -16384;
        return NormalizationUtils.clampM11(ut / 18000.0);
    }

    private static double pitchNormToRad(double pitchNorm) {
        double signedUt = NormalizationUtils.clampM11(pitchNorm) * 18000.0;
        return signedUt * (TAU / 65536.0);
    }

    private static double directionDot(double yawA, double pitchA, double yawB, double pitchB) {
        double cosA = Math.cos(pitchA);
        double ax = cosA * Math.cos(yawA);
        double ay = cosA * Math.sin(yawA);
        double az = Math.sin(pitchA);

        double cosB = Math.cos(pitchB);
        double bx = cosB * Math.cos(yawB);
        double by = cosB * Math.sin(yawB);
        double bz = Math.sin(pitchB);

        return ax * bx + ay * by + az * bz;
    }

    private record AimSolution(double yawRad, double pitchNorm) {}
}
