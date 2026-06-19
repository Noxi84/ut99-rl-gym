package aiplay.rl.rewards.catalog.json;

import aiplay.config.PropertyReaderUtils;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.rl.rewards.aim.enemyspawnattention.EnemySpawnAttentionParams;
import aiplay.rl.rewards.aim.pitch.PitchParams;
import aiplay.rl.rewards.aim.viewalignment.ViewAlignmentParams;
import aiplay.rl.rewards.aim.viewsmoothness.ViewSmoothnessParams;
import aiplay.rl.rewards.catalog.EndgameUrgencyParams;
import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardCatalog;
import aiplay.rl.rewards.catalog.RewardId;
import aiplay.rl.rewards.catalog.RewardMetadata;
import aiplay.rl.rewards.catalog.RewardModule;
import aiplay.rl.rewards.catalog.RewardModules;
import aiplay.rl.rewards.catalog.RewardParseSupport;
import aiplay.rl.rewards.combat.ammoconsumptionpenalty.AmmoConsumptionPenaltyParams;
import aiplay.rl.rewards.combat.combatevent.CombatEventParams;
import aiplay.rl.rewards.combat.damagedelta.DamageDeltaParams;
import aiplay.rl.rewards.combat.fireholdingpenalty.FireHoldingPenaltyParams;
import aiplay.rl.rewards.combat.primaryfireaim.PrimaryFireAimParams;
import aiplay.rl.rewards.combat.projectileaim.ProjectileAimParams;
import aiplay.rl.rewards.combat.shockcomboaim.ShockComboAimParams;
import aiplay.rl.rewards.combat.shockcomboclick.ShockComboClickParams;
import aiplay.rl.rewards.combat.shockcombocurriculum.ShockComboCurriculumParams;
import aiplay.rl.rewards.combat.shockcomboevent.ShockComboEventParams;
import aiplay.rl.rewards.movement.enemyspacing.EnemySpacingParams;
import aiplay.rl.rewards.movement.facing.FacingParams;
import aiplay.rl.rewards.movement.flakavoidance.FlakAvoidanceParams;
import aiplay.rl.rewards.movement.movementaction.MovementActionParams;
import aiplay.rl.rewards.movement.speed.SpeedParams;
import aiplay.rl.rewards.objective.flagcarrierkill.FlagCarrierKillParams;
import aiplay.rl.rewards.objective.flagevent.FlagEventParams;
import aiplay.rl.rewards.objective.objectiveprogress.ObjectiveProgressParams;
import aiplay.rl.rewards.objective.pickupevent.PickupEventParams;
import aiplay.rl.rewards.objective.scoregainrate.ScoreGainRateParams;
import aiplay.rl.rewards.team.coverescort.CoverEscortParams;
import aiplay.rl.rewards.team.defenderpresence.DefenderPresenceParams;
import aiplay.rl.rewards.team.teamassist.TeamAssistParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Resolveert {@link RewardCatalog} direct uit de {@code rewards.json}-structuur.
 *
 * <p>Verwacht structuur per model:
 *
 * <pre>
 * {
 *   "match_duration": 60000.0,
 *   "projectile_speed_flak_primary_uu": 2700.0,
 *   "projectile_speed_flak_secondary_uu": 1200.0,
 *   "weapon_profiles": {
 *     "shock": { "name": "Shock", "rewards": { "shock_combo_event": { "weights": { "event_bonus": 1.5 } } } }
 *   },
 *   "rewardgroups": {
 *     "default": {
 *       "name": "Default",
 *       "rewards": {
 *         "flag_event":   { "description": "...", "kind": "sparse", "owner": "movement", "weights": {...} },
 *         "facing":       { "description": "...", "kind": "dense",  "owner": "movement", "weight": 0.05 },
 *         ...
 *       }
 *     },
 *     "rewardgroup0": { "name": "Attack", "rewards": { "facing": { "weight": 0.10 } } }
 *   }
 * }
 * </pre>
 *
 * <p>Resolutie-orde per reward-id (meest-specifiek wint, recursieve deep-merge):
 *
 * <ol>
 *   <li>{@code weapon_profiles[<actief wapen>].rewards} — wapen-specifieke combat-shaping
 *       (optioneel per wapen; geselecteerd door {@code gameplay.json#weapon_profile})</li>
 *   <li>{@code rewardgroups[<role>].rewards} — rol-specifieke overrides</li>
 *   <li>{@code rewardgroups.default.rewards} — complete basis-set</li>
 * </ol>
 *
 * <p>Merge is recursief: een override op {@code combat_event.weights.frag} laat alle andere
 * combat-event velden uit default intact. Alleen reward-keys die in de default voorkomen worden
 * geparsed; rewards.json moet alle reward-blocks bevatten in {@code rewardgroups.default.rewards}.
 *
 * <p>De per-reward parsing zelf zit niet langer in deze klasse: elke {@link RewardModule}
 * (geregistreerd in {@link RewardModules}) parset z'n eigen block tot een typed {@code *Params}
 * naast z'n {@code *Reward}-klasse. Deze klasse is nog enkel de orchestrator: rewardgroup-merge,
 * de top-level {@code endgame_urgency}-knoppen, en de typed-accessor façade over een
 * {@link EnumMap}. {@code endgame_urgency} valt buiten {@code rewardgroups} en buiten
 * {@link RewardModules} — het is geen {@link RewardBlock} en wordt hier top-level geparsed.
 */
public final class JsonRewardCatalog implements RewardCatalog {

  private final Map<RewardId, RewardBlock> blocks;
  private final EndgameUrgencyParams endgameUrgency;

  public static RewardCatalog from(String modelKey, String selector) {
    if (modelKey == null || modelKey.isBlank()) {
      throw new IllegalArgumentException("modelKey must not be blank");
    }
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException(
          "JsonRewardCatalog requires a non-blank rewardgroup selector (role)");
    }
    JsonNode rewardsCfg = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/rewards");
    if (rewardsCfg == null || !rewardsCfg.isObject()) {
      throw new IllegalStateException("rewards.json missing for model: " + modelKey);
    }
    String weaponProfile = GlobalConfigRepository.shared().gameplay().weaponProfile();
    JsonNode merged = mergeRewards(modelKey, rewardsCfg, selector, weaponProfile);
    EndgameUrgencyParams endgame = parseEndgameUrgency(modelKey, rewardsCfg);
    return new JsonRewardCatalog(modelKey, merged, endgame);
  }

  private JsonRewardCatalog(
      String modelKey, JsonNode rewards, EndgameUrgencyParams endgameUrgency) {
    RewardParseSupport support = new RewardParseSupport("rewards.json (" + modelKey + ")");
    Map<RewardId, RewardBlock> map = new EnumMap<>(RewardId.class);
    for (RewardModule<?> module : RewardModules.all()) {
      JsonNode block = support.requireBlock(rewards, module.id());
      map.put(module.id(), module.parse(support, block));
    }
    this.blocks = map;
    this.endgameUrgency = endgameUrgency;
  }

  // -------------------- Top-level endgame_urgency (buiten rewardgroups) --------------------

  private static EndgameUrgencyParams parseEndgameUrgency(String modelKey, JsonNode rewardsCfg) {
    String ctx = "rewards.json (" + modelKey + ")";
    JsonNode block = rewardsCfg.get("endgame_urgency");
    if (block == null || !block.isObject()) {
      throw new IllegalStateException(
          ctx + ": missing top-level object 'endgame_urgency' (required for endgame catchup)");
    }
    return new EndgameUrgencyParams(
        requireTopLevelDouble(ctx, block, "endgame_urgency.ramp_start_remaining_norm"),
        requireTopLevelDouble(ctx, block, "endgame_urgency.ramp_full_remaining_norm"));
  }

  private static double requireTopLevelDouble(String ctx, JsonNode parent, String name) {
    String leaf = name.substring(name.lastIndexOf('.') + 1);
    JsonNode value = parent.get(leaf);
    if (value == null || !value.isNumber()) {
      throw new IllegalStateException(ctx + ": " + name + " must be a number");
    }
    return value.asDouble();
  }

  // -------------------- Rewardgroup merge --------------------

  /**
   * Bouwt het deep-merged {@code rewards}-blok via drie lagen (meest-specifiek wint):
   *
   * <ol>
   *   <li>{@code weapon_profiles[<actief wapen>].rewards} — wapen-specifieke combat-shaping
   *       (combo, spacing, ammo). Optioneel: ontbreekt het profiel, dan slaat deze laag over.</li>
   *   <li>{@code rewardgroups[<role>].rewards} — rol-specifieke objective/team-shaping.</li>
   *   <li>{@code rewardgroups.default.rewards} — de complete basis-set (bron-van-waarheid).</li>
   * </ol>
   *
   * <p>Het actieve wapen komt uit {@code gameplay.json#weapon_profile} (door de caller doorgegeven).
   * Wapen wint van rol zodat de combat-shaping klopt met het vastgehouden wapen ongeacht de rol; in
   * de praktijk tunen de twee assen disjuncte reward-sets (wapen=combat, rol=objective/team). De
   * merge is recursief ({@link #deepFillMissing}): een override op {@code shock_combo_event.weights.
   * event_bonus} laat alle andere velden uit de lagere lagen intact.
   *
   * <p>Public + puur (neemt {@code rewardsCfg} + selectors als argument, geen neveneffecten) zodat
   * de merge-orde los te verifiëren is zonder geladen global config.
   */
  public static JsonNode mergeRewards(
      String modelKey, JsonNode rewardsCfg, String selector, String weaponProfile) {
    String ctx = "rewards.json (" + modelKey + ")";
    JsonNode rewardgroups = rewardsCfg.path("rewardgroups");
    if (!rewardgroups.isObject()) {
      throw new IllegalStateException(ctx + ": missing or non-object 'rewardgroups'");
    }
    JsonNode defaultGroup = rewardgroups.path("default");
    if (!defaultGroup.isObject()) {
      throw new IllegalStateException(ctx + ": rewardgroups.default must be an object");
    }
    JsonNode defaultRewards = defaultGroup.path("rewards");
    if (!defaultRewards.isObject()) {
      throw new IllegalStateException(
          ctx + ": rewardgroups.default.rewards must be an object containing all reward blocks");
    }

    ObjectNode merged = JsonNodeFactory.instance.objectNode();
    // 1) wapen-profiel overrides (meest specifiek voor combat) winnen eerst.
    JsonNode weaponRewards = findWeaponProfileRewards(ctx, rewardsCfg, weaponProfile);
    if (weaponRewards != null) {
      deepFillMissing(merged, weaponRewards);
    }
    // 2) rol-groep vult wat het wapen-profiel niet zette.
    JsonNode groupRewards = findGroupRewards(ctx, rewardgroups, selector);
    if (groupRewards != null) {
      deepFillMissing(merged, groupRewards);
    }
    // 3) default vult de rest (complete basis-set).
    deepFillMissing(merged, defaultRewards);
    return merged;
  }

  /**
   * Zoekt de {@code rewards}-sub-tree van het {@code weapon_profiles}-profiel dat matcht met het
   * actieve {@code weaponProfile}. {@code weapon_profiles} is een verplicht top-level object (mag
   * leeg zijn) — zo is de wapen-laag expliciet geconfigureerd en wordt een per ongeluk weggevallen
   * sectie een harde fout i.p.v. een silent skip. De profiel-lookup zélf is optioneel: een wapen
   * zonder eigen entry levert {@code null} → de merge valt terug op rol + default (niet elk wapen
   * heeft combat-afwijkingen; flak draait bijvoorbeeld op de default-baseline). De
   * {@code weaponProfile}-waarde is al gevalideerd tegen de toegestane set in
   * {@link GlobalConfigRepository}.
   */
  private static JsonNode findWeaponProfileRewards(
      String ctx, JsonNode rewardsCfg, String weaponProfile) {
    JsonNode weaponProfiles = rewardsCfg.path("weapon_profiles");
    if (!weaponProfiles.isObject()) {
      throw new IllegalStateException(
          ctx + ": missing or non-object 'weapon_profiles' (verplicht; gebruik {} als geen "
              + "wapen-specifieke reward-overrides nodig zijn)");
    }
    if (weaponProfile == null || weaponProfile.isBlank()) {
      throw new IllegalStateException(ctx + ": gameplay.weapon_profile is blank/null");
    }
    String normalized = normalizeSelector(weaponProfile);
    var fields = weaponProfiles.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      String key = entry.getKey();
      if (key.startsWith("_doc") || "default".equals(key)) {
        continue;
      }
      JsonNode profile = entry.getValue();
      if (!profile.isObject()) {
        continue;
      }
      String name = profile.path("name").asText("");
      if (normalizeSelector(key).equals(normalized)
          || normalizeSelector(name).equals(normalized)) {
        JsonNode rewards = profile.path("rewards");
        return rewards.isObject() ? rewards : null;
      }
    }
    return null; // geen wapen-profiel voor dit wapen → rol + default
  }

  /** Zoekt de {@code rewards}-sub-tree van de rewardgroup die de gegeven selector matcht. */
  private static JsonNode findGroupRewards(String ctx, JsonNode rewardgroups, String selector) {
    String normalized = normalizeSelector(selector);
    var fields = rewardgroups.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      String key = entry.getKey();
      if ("default".equals(key)) {
        continue;
      }
      JsonNode group = entry.getValue();
      if (!group.isObject()) {
        continue;
      }
      String name = group.path("name").asText("");
      if (normalizeSelector(key).equals(normalized) || normalizeSelector(name).equals(normalized)) {
        JsonNode rewards = group.path("rewards");
        return rewards.isObject() ? rewards : null;
      }
    }
    throw new IllegalStateException(
        ctx + ": no rewardgroup matches selector '" + selector + "'");
  }

  /**
   * Recursive merge waarbij {@code target} wint en {@code source} alleen ontbrekende velden vult.
   * Voor twee objecten op dezelfde positie wordt recursief gemerged; primitieve waarden in target
   * blijven onveranderd.
   */
  private static void deepFillMissing(ObjectNode target, JsonNode source) {
    if (source == null || !source.isObject()) {
      return;
    }
    source
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (key.startsWith("_doc")) {
                return;
              }
              JsonNode srcVal = entry.getValue();
              JsonNode tgtVal = target.get(key);
              if (tgtVal == null) {
                target.set(key, srcVal.deepCopy());
              } else if (tgtVal.isObject() && srcVal.isObject()) {
                deepFillMissing((ObjectNode) tgtVal, srcVal);
              }
            });
  }

  private static String normalizeSelector(String value) {
    if (value == null) {
      return "";
    }
    return value.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
  }

  // -------------------- RewardCatalog accessors --------------------

  /** Typed view over de {@link EnumMap}; de cast is veilig door het module-id ↔ params-type
   *  invariant en gooit anders een duidelijke {@link ClassCastException}. */
  private <P extends RewardBlock> P block(RewardId id, Class<P> type) {
    return type.cast(blocks.get(id));
  }

  @Override
  public FlagEventParams flagEvent() {
    return block(RewardId.FLAG_EVENT, FlagEventParams.class);
  }

  @Override
  public FlagCarrierKillParams flagCarrierKill() {
    return block(RewardId.FLAG_CARRIER_KILL, FlagCarrierKillParams.class);
  }

  @Override
  public CombatEventParams combatEvent() {
    return block(RewardId.COMBAT_EVENT, CombatEventParams.class);
  }

  @Override
  public ObjectiveProgressParams objectiveProgress() {
    return block(RewardId.OBJECTIVE_PROGRESS, ObjectiveProgressParams.class);
  }

  @Override
  public SpeedParams speed() {
    return block(RewardId.SPEED, SpeedParams.class);
  }

  @Override
  public FacingParams facing() {
    return block(RewardId.FACING, FacingParams.class);
  }

  @Override
  public ViewAlignmentParams viewAlignment() {
    return block(RewardId.VIEW_ALIGNMENT, ViewAlignmentParams.class);
  }

  @Override
  public PitchParams pitch() {
    return block(RewardId.PITCH, PitchParams.class);
  }

  @Override
  public EnemySpacingParams enemySpacing() {
    return block(RewardId.ENEMY_SPACING, EnemySpacingParams.class);
  }

  @Override
  public ViewSmoothnessParams viewSmoothness() {
    return block(RewardId.VIEW_SMOOTHNESS, ViewSmoothnessParams.class);
  }

  @Override
  public MovementActionParams movementAction() {
    return block(RewardId.MOVEMENT_ACTION, MovementActionParams.class);
  }

  @Override
  public DamageDeltaParams damageDelta() {
    return block(RewardId.DAMAGE_DELTA, DamageDeltaParams.class);
  }

  @Override
  public ProjectileAimParams projectileAim() {
    return block(RewardId.PROJECTILE_AIM, ProjectileAimParams.class);
  }

  @Override
  public PrimaryFireAimParams primaryFireAim() {
    return block(RewardId.PRIMARY_FIRE_AIM, PrimaryFireAimParams.class);
  }

  @Override
  public FireHoldingPenaltyParams fireHoldingPenalty() {
    return block(RewardId.FIRE_HOLDING_PENALTY, FireHoldingPenaltyParams.class);
  }

  @Override
  public AmmoConsumptionPenaltyParams ammoConsumptionPenalty() {
    return block(RewardId.AMMO_CONSUMPTION_PENALTY, AmmoConsumptionPenaltyParams.class);
  }

  @Override
  public EnemySpawnAttentionParams enemySpawnAttention() {
    return block(RewardId.ENEMY_SPAWN_ATTENTION, EnemySpawnAttentionParams.class);
  }

  @Override
  public ScoreGainRateParams scoreGainRate() {
    return block(RewardId.SCORE_GAIN_RATE, ScoreGainRateParams.class);
  }

  @Override
  public FlakAvoidanceParams flakAvoidance() {
    return block(RewardId.FLAK_AVOIDANCE, FlakAvoidanceParams.class);
  }

  @Override
  public PickupEventParams pickupEvent() {
    return block(RewardId.PICKUP_EVENT, PickupEventParams.class);
  }

  @Override
  public ShockComboEventParams shockComboEvent() {
    return block(RewardId.SHOCK_COMBO_EVENT, ShockComboEventParams.class);
  }

  @Override
  public ShockComboAimParams shockComboAim() {
    return block(RewardId.SHOCK_COMBO_AIM, ShockComboAimParams.class);
  }

  @Override
  public ShockComboClickParams shockComboClick() {
    return block(RewardId.SHOCK_COMBO_CLICK, ShockComboClickParams.class);
  }

  @Override
  public ShockComboCurriculumParams shockComboCurriculum() {
    return block(RewardId.SHOCK_COMBO_CURRICULUM_SHAPING, ShockComboCurriculumParams.class);
  }

  @Override
  public DefenderPresenceParams defenderPresence() {
    return block(RewardId.DEFENDER_PRESENCE, DefenderPresenceParams.class);
  }

  @Override
  public CoverEscortParams coverEscort() {
    return block(RewardId.COVER_ESCORT, CoverEscortParams.class);
  }

  @Override
  public TeamAssistParams teamAssist() {
    return block(RewardId.TEAM_ASSIST, TeamAssistParams.class);
  }

  @Override
  public EndgameUrgencyParams endgameUrgency() {
    return endgameUrgency;
  }

  @Override
  public RewardMetadata metadata(RewardId id) {
    return blockOf(id).metadata();
  }

  @Override
  public boolean isEnabled(RewardId id) {
    return blockOf(id).enabled();
  }

  @Override
  public Stream<RewardBlock> allBlocks() {
    return blocks.values().stream();
  }

  private RewardBlock blockOf(RewardId id) {
    RewardBlock block = blocks.get(id);
    if (block == null) {
      throw new IllegalStateException("No reward block resolved for id " + id);
    }
    return block;
  }
}
