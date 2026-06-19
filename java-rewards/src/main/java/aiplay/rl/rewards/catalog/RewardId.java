package aiplay.rl.rewards.catalog;

/**
 * Stabiele identifiers voor elke reward-component. Eén-op-één met de
 * {@link aiplay.rl.rewards.core.RewardComponent}-implementaties in {@code aiplay.rl.rewards}.
 *
 * <p>{@link #jsonKey()} is de string die in {@code rewards.json} verschijnt als key onder
 * {@code rewardgroups.<group>.rewards}. Voorbeeld:
 *
 * <pre>
 * "rewards": {
 *   "facing":  { "description": "...", "kind": "dense", "owner": "movement", "weight": 0.05 },
 *   "combat_event": { "description": "...", "kind": "sparse", "owner": "shooting", ... }
 * }
 * </pre>
 *
 * <p>Onbekende JSON-keys laten {@link #fromJsonKey(String)} crashen — geen silent skip, conform
 * project-regel "no config fallbacks".
 */
public enum RewardId {
  FLAG_EVENT,
  FLAG_CARRIER_KILL,
  COMBAT_EVENT,
  OBJECTIVE_PROGRESS,
  SPEED,
  FACING,
  VIEW_ALIGNMENT,
  PITCH,
  ENEMY_SPACING,
  VIEW_SMOOTHNESS,
  MOVEMENT_ACTION,
  DAMAGE_DELTA,
  PROJECTILE_AIM,
  PRIMARY_FIRE_AIM,
  FIRE_HOLDING_PENALTY,
  AMMO_CONSUMPTION_PENALTY,
  ENEMY_SPAWN_ATTENTION,
  SCORE_GAIN_RATE,
  FLAK_AVOIDANCE,
  PICKUP_EVENT,
  SHOCK_COMBO_EVENT,
  SHOCK_COMBO_AIM,
  SHOCK_COMBO_CLICK,
  SHOCK_COMBO_CURRICULUM_SHAPING,
  DEFENDER_PRESENCE,
  COVER_ESCORT,
  TEAM_ASSIST;

  private final String jsonKey;

  RewardId() {
    this.jsonKey = name().toLowerCase();
  }

  public String jsonKey() {
    return jsonKey;
  }

  public static RewardId fromJsonKey(String key) {
    for (RewardId id : values()) {
      if (id.jsonKey.equals(key)) {
        return id;
      }
    }
    throw new IllegalArgumentException("Unknown reward id in rewards.json: '" + key + "'");
  }
}
