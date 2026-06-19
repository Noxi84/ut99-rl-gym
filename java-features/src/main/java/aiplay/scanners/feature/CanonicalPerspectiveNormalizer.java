package aiplay.scanners.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms features from a non-canonical team perspective (red, team=0) to
 * the canonical perspective (blue, team=1) that the model was trained on.
 *
 * <p>The transform applies a 180° rotation of the coordinate system, exploiting
 * the rotational symmetry of CTF-AndAction. This makes the model "see" the
 * world as if it were playing on blue team, regardless of the actual team.
 *
 * <p>Actions (forward/left/right/yaw turn class) are relative and need no transform.
 *
 * <p><b>Pickup features zijn egocentric en hebben GEEN transformatie nodig:</b>
 * {@code pickup_<sem>_<slot>_rel_sin/_rel_cos} = sin/cos van (bearing − bot.yaw).
 * Onder een 180° XY-rotatie wordt bearing → bearing+180° én bot.yaw → bot.yaw+180°,
 * dus het verschil — en dus rel_sin/_rel_cos — blijft ongewijzigd. Dist/z-delta/
 * available zijn schaal-invariant. Daarom staan deze niet in {@link #buildNegateList()}.
 *
 * <p>Stateless and thread-safe. One instance per model, reused across ticks.
 */
public final class CanonicalPerspectiveNormalizer {

    /** The team the model was trained on. Features are normalized to this perspective. */
    private static final int CANONICAL_TEAM = 1;

    // --- Pre-computed index arrays (filled once at construction) ---

    /** Indices of features that must be negated (multiplied by -1). */
    private final int[] negateIndices;

    /** Pairs [a, b] of feature indices to swap. Each pair is swapped bidirectionally. */
    private final int[][] swapPairs;

    /** Index of viewRotationX_sin (negated for +180° rotation). */
    private final int vrxSinIdx;

    /** Index of viewRotationX_cos (negated for +180° rotation). */
    private final int vrxCosIdx;

    /** Index of team_norm (overridden to 1.0). */
    private final int teamNormIdx;

    /** Whether this normalizer has any work to do (false if model has none of the transformable features). */
    private final boolean hasWork;

    public CanonicalPerspectiveNormalizer(List<String> featureOrder) {
        Map<String, Integer> idx = new HashMap<>(featureOrder.size() * 2);
        for (int i = 0; i < featureOrder.size(); i++) {
            idx.put(featureOrder.get(i), i);
        }

        // --- Features to negate (absolute spatial + world-cartesian collision) ---
        String[] negateNames = buildNegateList();
        int[] negBuf = new int[negateNames.length];
        int negCount = 0;
        for (String name : negateNames) {
            Integer i = idx.get(name);
            if (i != null) negBuf[negCount++] = i;
        }
        negateIndices = new int[negCount];
        System.arraycopy(negBuf, 0, negateIndices, 0, negCount);

        // --- Feature pairs to swap ---
        String[][] swapNames = buildSwapList();
        int[][] swpBuf = new int[swapNames.length][2];
        int swpCount = 0;
        for (String[] pair : swapNames) {
            Integer a = idx.get(pair[0]);
            Integer b = idx.get(pair[1]);
            if (a != null && b != null) {
                swpBuf[swpCount][0] = a;
                swpBuf[swpCount][1] = b;
                swpCount++;
            }
        }
        swapPairs = new int[swpCount][2];
        for (int i = 0; i < swpCount; i++) {
            swapPairs[i][0] = swpBuf[i][0];
            swapPairs[i][1] = swpBuf[i][1];
        }

        // --- ViewRotation sin/cos indices ---
        vrxSinIdx = idx.getOrDefault("self_viewRotationX_sin", -1);
        vrxCosIdx = idx.getOrDefault("self_viewRotationX_cos", -1);

        // --- team_norm index ---
        teamNormIdx = idx.getOrDefault("self_team_norm", -1);

        hasWork = negCount > 0 || swpCount > 0 || vrxSinIdx >= 0 || teamNormIdx >= 0;
    }

    /**
     * Returns true if the given team requires perspective normalization.
     *
     * Normalization is only safe on maps that are rotationally symmetric around origin
     * (the 180° transform would otherwise map bot state into invalid world coordinates).
     * On non-symmetric maps this returns false for all teams, which means a red-team bot
     * receives raw features — the model was trained on blue-perspective, so gameplay may
     * be sub-optimal until a map-specific retrain is done.
     */
    public static boolean needsNormalization(int botTeam) {
        if (botTeam == CANONICAL_TEAM) return false;
        if (!aiplay.runtime.config.ActiveMapConfigResolver
                .resolve(aiplay.runtime.context.MapKey.active()).symmetric()) {
            warnAsymmetricMapOnce();
            return false;
        }
        return true;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean ASYMMETRIC_WARN_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void warnAsymmetricMapOnce() {
        if (ASYMMETRIC_WARN_LOGGED.compareAndSet(false, true)) {
            System.err.println(
                "[CanonicalPerspectiveNormalizer] WARNING: active map is not symmetric (see resources/config/maps/<map>.json). "
                + "Red-team bots will receive raw features instead of the 180° canonical transform. "
                + "Model was trained on blue-perspective data; expect degraded performance on red team until retrained.");
        }
    }

    /**
     * Normalizes a single frame's feature array in-place.
     * Only call this when {@link #needsNormalization(int)} returns true.
     */
    public void normalize(float[] features) {
        if (!hasWork) return;

        // 1. Negate absolute spatial + world-cartesian features
        for (int i : negateIndices) {
            features[i] = -features[i];
        }

        // 2. Swap collision pairs (yaw-relative ring 180° + world-axis mirror)
        for (int[] pair : swapPairs) {
            float tmp = features[pair[0]];
            features[pair[0]] = features[pair[1]];
            features[pair[1]] = tmp;
        }

        // 3. ViewRotation sin/cos: sin(θ+π) = -sin(θ), cos(θ+π) = -cos(θ)
        if (vrxSinIdx >= 0) features[vrxSinIdx] = -features[vrxSinIdx];
        if (vrxCosIdx >= 0) features[vrxCosIdx] = -features[vrxCosIdx];

        // 4. Override team_norm to canonical team
        if (teamNormIdx >= 0) features[teamNormIdx] = 1.0f;
    }

    // ---- Static feature name lists ----

    private static String[] buildNegateList() {
        // Absolute spatial features that flip sign under 180° map rotation
        // + all 32 world-cartesian collision features (cos/sin of θ+180° = -cos/sin of θ)
        // + enemy dodge direction (world-space absolute: sin(θ+π) = -sin(θ))
        return new String[]{
            // Absolute position
            "self_locationX_norm", "self_locationY_norm",
            // Absolute velocity
            "self_velocityX_norm", "self_velocityY_norm",
            // World-cartesian collision (16 rays × cos + sin = 32 features)
            "self_fwdCollision_world_cos",         "self_fwdCollision_world_sin",
            "self_fwdRight30Collision_world_cos",  "self_fwdRight30Collision_world_sin",
            "self_fwdRight45Collision_world_cos",  "self_fwdRight45Collision_world_sin",
            "self_fwdRight60Collision_world_cos",  "self_fwdRight60Collision_world_sin",
            "self_rightCollision_world_cos",       "self_rightCollision_world_sin",
            "self_backRight60Collision_world_cos", "self_backRight60Collision_world_sin",
            "self_backRight45Collision_world_cos", "self_backRight45Collision_world_sin",
            "self_backRight30Collision_world_cos", "self_backRight30Collision_world_sin",
            "self_backCollision_world_cos",        "self_backCollision_world_sin",
            "self_backLeft30Collision_world_cos",  "self_backLeft30Collision_world_sin",
            "self_backLeft45Collision_world_cos",  "self_backLeft45Collision_world_sin",
            "self_backLeft60Collision_world_cos",  "self_backLeft60Collision_world_sin",
            "self_leftCollision_world_cos",        "self_leftCollision_world_sin",
            "self_fwdLeft60Collision_world_cos",   "self_fwdLeft60Collision_world_sin",
            "self_fwdLeft45Collision_world_cos",   "self_fwdLeft45Collision_world_sin",
            "self_fwdLeft30Collision_world_cos",   "self_fwdLeft30Collision_world_sin",
            // Enemy dodge direction (world-space absolute, per slot)
            "enemy0_dodgeDir_sin", "enemy0_dodgeDir_cos",
            "enemy1_dodgeDir_sin", "enemy1_dodgeDir_cos",
            "enemy2_dodgeDir_sin", "enemy2_dodgeDir_cos",
        };
    }

    private static String[][] buildSwapList() {
        // Pairs of features that swap under 180° rotation.
        return new String[][]{
            // Yaw-relative collision: 180° ring permutation (8 pairs)
            {"self_fwdCollision_norm",         "self_backCollision_norm"},
            {"self_fwdRight30Collision_norm",  "self_backLeft30Collision_norm"},
            {"self_fwdRight45Collision_norm",  "self_backLeft45Collision_norm"},
            {"self_fwdRight60Collision_norm",  "self_backLeft60Collision_norm"},
            {"self_rightCollision_norm",       "self_leftCollision_norm"},
            {"self_backRight60Collision_norm", "self_fwdLeft60Collision_norm"},
            {"self_backRight45Collision_norm", "self_fwdLeft45Collision_norm"},
            {"self_backRight30Collision_norm", "self_fwdLeft30Collision_norm"},

            // World-axis collision: +X↔−X, +Y↔−Y permutation (8 pairs)
            {"self_posXCollision_norm",      "self_negXCollision_norm"},
            {"self_posYCollision_norm",      "self_negYCollision_norm"},
            {"self_posXPosY30Collision_norm", "self_negXNegY30Collision_norm"},
            {"self_posXPosY45Collision_norm", "self_negXNegY45Collision_norm"},
            {"self_posXPosY60Collision_norm", "self_negXNegY60Collision_norm"},
            {"self_negXPosY30Collision_norm", "self_posXNegY30Collision_norm"},
            {"self_negXPosY45Collision_norm", "self_posXNegY45Collision_norm"},
            {"self_negXPosY60Collision_norm", "self_posXNegY60Collision_norm"},

            // (Removed) enemy yaw-relative collision swap pairs. These were incorrect:
            // enemy_i_fwdCollision_norm is a raytrace in enemy_i's own body frame. Under
            // the world-flip the enemy rotates with the world, its body frame rotates
            // along, and the raytrace value stays identical — swapping fwd↔back
            // corrupted the signal. With Necto pooling the point is moot anyway
            // (mean+max pool is permutation-invariant across slots).
        };
    }
}
