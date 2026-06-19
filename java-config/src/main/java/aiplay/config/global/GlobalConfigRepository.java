package aiplay.config.global;

import aiplay.config.PropertyReaderUtils;
import aiplay.config.global.command.CommandControllerConfig;
import aiplay.config.global.command.CommandControllerGeneralConfig;
import aiplay.config.global.command.PitchConfig;
import aiplay.config.global.command.YawHeadingConfig;
import aiplay.config.global.shooting.FireKind;
import aiplay.config.global.shooting.ShootingConfig;
import aiplay.config.global.shooting.WeaponFireModeConfig;
import aiplay.config.global.shooting.WeaponFireProfile;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class GlobalConfigRepository {

  private static final GlobalConfigRepository SHARED = new GlobalConfigRepository();

  public static GlobalConfigRepository shared() {
    return SHARED;
  }

  private final AtomicReference<CachedSlices> cache = new AtomicReference<>(null);

  public RecordingConfig recording() { return slices().recording; }
  public CommandControllerConfig commandController() { return slices().commandController; }
  public MissionConfig mission() { return slices().mission; }
  public WeaponPlannerConfig weaponPlanner() { return slices().weaponPlanner; }
  public LoggingConfig logging() { return slices().logging; }
  public FilesConfig files() { return slices().files; }
  public PlayerConfig player() { return slices().player; }
  public Ut99ServerConfig server() { return slices().server; }
  public ViewConfig view() { return slices().view; }
  public GameplayConfig gameplay() { return slices().gameplay; }
  public DebugConfig debug() { return slices().debug; }
  public ShootingConfig shooting() { return slices().shooting; }
  public EngagementConfig engagement() { return slices().engagement; }
  public TacticalConfig tactical() { return slices().tactical; }
  public InferenceBatchingConfig inferenceBatching() { return slices().inferenceBatching; }
  public AimTargetConfig aimTarget() { return slices().aimTarget; }
  public PickupTypeRegistry pickupTypes() { return slices().pickupTypes; }
  public AmmoDeadlockGuardConfig ammoDeadlockGuard() { return slices().ammoDeadlockGuard; }
  public List<BotConfig> bots() { return slices().bots; }

  private CachedSlices slices() {
    CachedSlices c = cache.get();
    if (c != null) return c;
    c = load();
    cache.set(c);
    return c;
  }

  private static CachedSlices load() {
    return new CachedSlices(
        loadRecording(),
        loadCommandController(),
        loadMission(),
        loadWeaponPlanner(),
        loadLogging(),
        loadFiles(),
        loadPlayer(),
        loadServer(),
        loadView(),
        loadGameplay(),
        loadDebug(),
        loadShooting(),
        loadEngagement(),
        loadTactical(),
        loadInferenceBatching(),
        loadAimTarget(),
        loadPickupTypes(),
        loadAmmoDeadlockGuard(),
        loadBots()
    );
  }

  // ===== Ammo Deadlock Guard =====

  private static AmmoDeadlockGuardConfig loadAmmoDeadlockGuard() {
    JsonNode n = PropertyReaderUtils.getSubtree("/ammo_deadlock_guard");
    if (n == null || n.isMissingNode()) {
      throw new IllegalStateException(
          "ammo-deadlock-guard.json: missing — expected at resources/config/ammo-deadlock-guard.json "
              + "(detecteert all-RLBots-zonder-ammo deadlocks en forceert één suicide voor respawn).");
    }
    return new AmmoDeadlockGuardConfig(
        requireBoolean(n, "enabled"),
        requireDouble(n, "threshold_seconds")
    );
  }

  // ===== Pickup Types =====

  private static PickupTypeRegistry loadPickupTypes() {
    JsonNode n = PropertyReaderUtils.getSubtree("/pickup_types");
    if (n == null || n.isMissingNode()) {
      throw new IllegalStateException(
          "pickup-types.json: missing — expected at resources/config/pickup-types.json "
              + "(single source of truth for canonical pickup classes + respawn timings)");
    }
    return PickupTypeRegistry.fromJson(n);
  }

  // ===== Recording =====

  private static RecordingConfig loadRecording() {
    JsonNode rec = PropertyReaderUtils.getSubtree("/runtime/recording");
    if (rec == null) {
      throw new IllegalStateException("runtime.json: missing required section 'recording'");
    }
    JsonNode keys = rec.path("keys");
    return new RecordingConfig(
        requireString(rec, "player_name"),
        new RecordingKeysConfig(
            requireString(keys, "jump"),
            requireString(keys, "duck"),
            requireString(keys, "move_forward"),
            requireString(keys, "move_backward"),
            requireString(keys, "move_left"),
            requireString(keys, "move_right"),
            requireString(keys, "fire"),
            requireString(keys, "altfire")
        )
    );
  }

  // ===== Command Controller =====

  private static CommandControllerConfig loadCommandController() {
    JsonNode cc = PropertyReaderUtils.getSubtree("/runtime/command_controller");
    if (cc == null) {
      throw new IllegalStateException("runtime.json: missing required section 'command_controller'");
    }
    return new CommandControllerConfig(
        loadCCGeneral(cc.path("general")),
        loadYawHeading(cc.path("yaw_heading")),
        loadPitch(cc.path("pitch"))
    );
  }

  private static CommandControllerGeneralConfig loadCCGeneral(JsonNode n) {
    return new CommandControllerGeneralConfig(
        requireInt(n, "controller_fps"),
        requireInt(n, "min_movement_dwell_ms"),
        requireInt(n, "dedupe_threshold_ut"),
        requireInt(n, "view_turn_intent_max_age_ms"),
        requireDouble(n, "collision_wall_threshold_norm"),
        requireInt(n, "jump_cooldown_ms"),
        requireInt(n, "duck_cooldown_ms")
    );
  }

  private static YawHeadingConfig loadYawHeading(JsonNode n) {
    return new YawHeadingConfig(
        requireInt(n, "continuous_max_step"),
        requireDouble(n, "dead_zone_rad")
    );
  }

  private static PitchConfig loadPitch(JsonNode n) {
    return new PitchConfig(
        requireInt(n, "continuous_max_step"),
        requireInt(n, "min_signed"),
        requireInt(n, "max_signed"),
        requireDouble(n, "center_decay_rate"),
        requireDouble(n, "dead_zone_rad")
    );
  }

  // ===== Mission/Skill =====

  private static MissionConfig loadMission() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/mission");
    if (n == null) {
      throw new IllegalStateException("runtime.json: missing required section 'mission'");
    }
    return new MissionConfig(
        requireInt(n, "mission_annotator_fps"),
        requireInt(n, "mission_min_dwell_ms"),
        requireDouble(n, "anti_stuck_speed_norm_threshold"),
        requireDouble(n, "anti_stuck_forward_collision_threshold_norm"),
        requireDouble(n, "anti_stuck_forward_diag_collision_threshold_norm"),
        requireInt(n, "anti_stuck_trigger_ms"),
        requireInt(n, "anti_stuck_recovery_ms")
    );
  }

  private static WeaponPlannerConfig loadWeaponPlanner() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/weapon_planner");
    if (n == null) {
      throw new IllegalStateException("runtime.json: missing required section 'weapon_planner'");
    }
    int fps = requireInt(n, "fps");
    if (fps <= 0) {
      throw new IllegalStateException(
          "runtime.json: weapon_planner.fps must be > 0 (got " + fps + ")");
    }
    int dwellMs = requireInt(n, "dwell_ms");
    if (dwellMs < 0) {
      throw new IllegalStateException(
          "runtime.json: weapon_planner.dwell_ms must be >= 0 (got " + dwellMs + ")");
    }
    return new WeaponPlannerConfig(fps, dwellMs);
  }

  // ===== Logging =====

  private static LoggingConfig loadLogging() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/logging");
    if (n == null) {
      throw new IllegalStateException("runtime.json: missing required section 'logging'");
    }
    return new LoggingConfig(
        requireBoolean(n, "enabled"),
        requireString(n, "level"),
        requireInt(n, "max_bytes"),
        requireInt(n, "max_files")
    );
  }

  // ===== Files =====

  private static FilesConfig loadFiles() {
    JsonNode n = PropertyReaderUtils.getSubtree("/files");
    if (n == null) {
      throw new IllegalStateException("files.json: missing");
    }
    return new FilesConfig(
        requireString(n, "sessions_dir"),
        requireString(n, "recordings_dir")
    );
  }

  // ===== Player =====

  private static PlayerConfig loadPlayer() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/ut99_player");
    if (n == null || n.isMissingNode()) {
      throw new IllegalStateException("runtime.json: missing required section 'ut99_player'");
    }
    return new PlayerConfig(
        requireString(n, "name"),
        requireInt(n, "team"),
        parseRole(n, "runtime.json: ut99_player")
    );
  }

  // ===== Server =====

  private static Ut99ServerConfig loadServer() {
    JsonNode srv = PropertyReaderUtils.getSubtree("/runtime/ut99_server");
    if (srv == null) {
      throw new IllegalStateException("runtime.json: missing required section 'ut99_server'");
    }
    JsonNode ut99 = PropertyReaderUtils.getSubtree("/runtime/ut99");
    if (ut99 == null) {
      throw new IllegalStateException("runtime.json: missing required section 'ut99'");
    }
    return new Ut99ServerConfig(
        requireString(srv, "ut_neuralnet_server"),
        requireInt(srv, "uweb_listen_port"),
        requireInt(srv, "port"),
        requireInt(srv, "min_players"),
        requireString(ut99, "install_root")
    );
  }

  // ===== View =====

  private static ViewConfig loadView() {
    JsonNode vr = PropertyReaderUtils.getSubtree("/runtime/viewrotation");
    if (vr == null) {
      throw new IllegalStateException("runtime.json: missing required section 'viewrotation'");
    }
    JsonNode rt = PropertyReaderUtils.getSubtree("/runtime");
    if (rt == null) {
      throw new IllegalStateException("runtime.json: missing root");
    }
    return new ViewConfig(
        requireDouble(vr, "max_viewrotation_x"),
        requireString(rt, "unreal_tournament_window_name"),
        requireInt(vr, "pitch_clamp")
    );
  }

  // ===== Gameplay =====

  private static final java.util.Set<String> WEAPON_PROFILES = java.util.Set.of(
      "instagib", "enforcer", "doubleenforcer", "shock", "biorifle", "sniper",
      "flak", "rocket", "ripper", "minigun", "pulse", "all", "stock"
  );

  private static GameplayConfig loadGameplay() {
    JsonNode root = PropertyReaderUtils.getSubtree("");
    if (root == null) root = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    JsonNode wpNode = root.get("weapon_profile");
    if (wpNode == null || wpNode.isNull() || wpNode.asText().isBlank()) {
      throw new IllegalStateException("gameplay.json: missing required field 'weapon_profile' (expected one of " + WEAPON_PROFILES + ")");
    }
    String weaponProfile = wpNode.asText();
    if (!WEAPON_PROFILES.contains(weaponProfile)) {
      throw new IllegalStateException("gameplay.json: unknown weapon_profile='" + weaponProfile + "' (expected one of " + WEAPON_PROFILES + ")");
    }
    int matchTimeMinutes = requireInt(root, "match_time_minutes");
    if (matchTimeMinutes <= 0) {
      throw new IllegalStateException(
          "gameplay.json: 'match_time_minutes' must be > 0 (got " + matchTimeMinutes + ")");
    }
    double flagDropAutoReturnSeconds = requireDouble(root, "flag_drop_auto_return_seconds");
    if (flagDropAutoReturnSeconds <= 0.0) {
      throw new IllegalStateException(
          "gameplay.json: 'flag_drop_auto_return_seconds' must be > 0 (got "
              + flagDropAutoReturnSeconds + ")");
    }
    return new GameplayConfig(
        requireDouble(root, "near_dist_norm"),
        requireString(root, "mapName"),
        weaponProfile,
        matchTimeMinutes,
        flagDropAutoReturnSeconds
    );
  }

  // ===== Debug =====

  private static DebugConfig loadDebug() {
    JsonNode root = PropertyReaderUtils.getSubtree("");
    if (root == null) {
      throw new IllegalStateException("gameplay.json: missing root");
    }
    return new DebugConfig(
        parseStringList(root.path("debug_features")),
        requireBoolean(root, "debug_sanity_enabled"),
        requireInt(root, "debug_log_every_n"),
        requireLong(root, "debug_log_min_interval_ms"),
        requireBoolean(root, "debug_log_only_on_change"),
        requireDouble(root, "debug_log_change_epsilon"),
        requireInt(root, "debug_log_max_lines_per_feature")
    );
  }

  private static java.util.List<String> parseStringList(JsonNode n) {
    if (n == null || n.isMissingNode() || !n.isArray()) return java.util.List.of();
    return java.util.Collections.unmodifiableList(
        java.util.stream.StreamSupport.stream(n.spliterator(), false)
            .map(JsonNode::asText)
            .collect(java.util.stream.Collectors.toList()));
  }

  // ===== Engagement =====

  private static EngagementConfig loadEngagement() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/engagement");
    if (n == null) {
      throw new IllegalStateException("runtime.json: missing required section 'engagement'");
    }
    return new EngagementConfig(
        requireInt(n, "engagement_min_dwell_ms"),
        requireInt(n, "attention_target_min_dwell_ms"),
        requireInt(n, "commit_shot_hold_ms"),
        requireInt(n, "visible_grace_ms"),
        requireDouble(n, "commit_shot_distance_threshold")
    );
  }

  // ===== Helpers (strict — no silent defaults; CLAUDE.md no-fallback rule) =====

  private static int requireInt(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isNumber()) {
      throw new IllegalStateException("Missing required config int: " + field);
    }
    return n.asInt();
  }

  private static long requireLong(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isNumber()) {
      throw new IllegalStateException("Missing required config long: " + field);
    }
    return n.asLong();
  }

  private static double requireDouble(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isNumber()) {
      throw new IllegalStateException("Missing required config double: " + field);
    }
    return n.asDouble();
  }

  private static boolean requireBoolean(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isBoolean()) {
      throw new IllegalStateException("Missing required config boolean: " + field);
    }
    return n.asBoolean();
  }

  private static String requireString(JsonNode parent, String field) {
    JsonNode n = (parent != null) ? parent.path(field) : null;
    if (n == null || !n.isTextual()) {
      throw new IllegalStateException("Missing required config string: " + field);
    }
    return n.asText();
  }

  // ===== Shooting =====

  private static ShootingConfig loadShooting() {
    JsonNode weapons = PropertyReaderUtils.getSubtree("/runtime/shooting/weapons");
    if (weapons == null || weapons.isMissingNode()) {
      throw new IllegalStateException("runtime.json: missing required section 'shooting.weapons'");
    }
    Map<String, WeaponFireProfile> profiles = new HashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = weapons.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String friendlyName = e.getKey();
      JsonNode profileNode = e.getValue();
      String weaponClass = profileNode.path("class").asText();
      if (weaponClass.isBlank()) {
        throw new IllegalStateException("runtime.json: shooting.weapons." + friendlyName + ".class is required");
      }
      WeaponFireModeConfig primary = loadFireMode(profileNode, friendlyName, "primary");
      WeaponFireModeConfig secondary = profileNode.has("secondary") && !profileNode.get("secondary").isNull()
          ? loadFireMode(profileNode, friendlyName, "secondary")
          : null;
      profiles.put(weaponClass, new WeaponFireProfile(weaponClass, primary, secondary));
    }
    return new ShootingConfig(Map.copyOf(profiles));
  }

  private static WeaponFireModeConfig loadFireMode(JsonNode profileNode, String friendly, String mode) {
    JsonNode n = profileNode.path(mode);
    if (n.isMissingNode()) {
      throw new IllegalStateException("runtime.json: shooting.weapons." + friendly + "." + mode + " is required");
    }
    int fireMs = n.path("fire_duration_ms").asInt(-1);
    int cdMs = n.path("cooldown_ms").asInt(-1);
    if (fireMs < 0 || cdMs < 0) {
      throw new IllegalStateException("runtime.json: shooting.weapons." + friendly + "." + mode
          + " requires both fire_duration_ms and cooldown_ms");
    }
    FireKind kind = FireKind.EDGE;
    JsonNode kindNode = n.get("kind");
    if (kindNode != null && !kindNode.isNull()) {
      String raw = kindNode.asText("").trim().toLowerCase();
      switch (raw) {
        case "edge" -> kind = FireKind.EDGE;
        case "hold" -> kind = FireKind.HOLD;
        default -> throw new IllegalStateException("runtime.json: shooting.weapons." + friendly + "." + mode
            + ".kind must be 'edge' or 'hold' (got '" + raw + "')");
      }
    }
    return new WeaponFireModeConfig(fireMs, cdMs, kind);
  }

  // ===== Bots =====

  private static List<BotConfig> loadBots() {
    List<BotConfig> bots = new ArrayList<>();

    // AI bots: always enabled (presence in ai_bots = joins server), per-model enabled config
    JsonNode aiBots = PropertyReaderUtils.getSubtree("/ai_bots");
    if (aiBots != null && aiBots.isArray()) {
      for (JsonNode entry : aiBots) {
        String name = entry.get("name").asText();
        int team = entry.get("team").asInt();
        boolean active = entry.get("active").asBoolean();

        String role = parseRole(entry, "AI bot '" + name + "'");
        BotAppearanceConfig appearance = parseAppearance(entry, "AI bot '" + name + "'");

        JsonNode modelsNode = entry.get("models");
        if (modelsNode == null || !modelsNode.isObject()) {
          throw new IllegalStateException("AI bot '" + name + "' missing required 'models' object");
        }
        Map<String, BotModelConfig> models = new LinkedHashMap<>();
        var it = modelsNode.fields();
        while (it.hasNext()) {
          var field = it.next();
          String modelKey = field.getKey();
          JsonNode modelNode = field.getValue();
          boolean modelEnabled = modelNode.get("enabled").asBoolean();
          JsonNode snapshotNode = modelNode.get("snapshot");
          if (snapshotNode == null) {
            throw new IllegalStateException(
                "AI bot '" + name + "' model '" + modelKey
                + "' missing required 'snapshot' field "
                + "(use \"current\" for live, or \"<model_key>/<counter>\" for a champion)");
          }
          String snapshot = snapshotNode.asText();
          models.put(modelKey, new BotModelConfig(modelEnabled, snapshot));
        }

        String preferredWeapon = requireString(entry, "preferred_weapon");
        if (!WeaponCatalog.isValidToken(preferredWeapon)) {
          throw new IllegalStateException("AI bot '" + name + "' has unknown preferred_weapon='"
              + preferredWeapon + "' (expected one of " + WeaponCatalog.tokens() + ")");
        }

        bots.add(new BotConfig(true, active, name, team, role, "rl", appearance, null, Map.copyOf(models), preferredWeapon));
      }
    }

    // UT99 stock bots: explicit enabled flag, no per-model config and no
    // role (they don't run through the RL pipeline). Use a stub role so
    // downstream lookups remain total functions.
    JsonNode ut99Bots = PropertyReaderUtils.getSubtree("/ut99_bots");
    if (ut99Bots != null && ut99Bots.isArray()) {
      for (JsonNode entry : ut99Bots) {
        boolean enabled = entry.get("enabled").asBoolean();
        String name = entry.get("name").asText();
        int team = entry.get("team").asInt();

        BotAppearanceConfig appearance = parseAppearance(entry, "UT99 bot '" + name + "'");

        JsonNode uc = entry.get("ut99_config");
        Ut99BotConfig ut99Config = new Ut99BotConfig(
            uc.get("jumpy").asInt(),
            uc.get("favorite_weapon").asText(),
            uc.get("camping").asDouble(),
            uc.get("strafing_ability").asDouble(),
            uc.get("combat_style").asDouble(),
            uc.get("alertness").asDouble(),
            uc.get("accuracy").asDouble(),
            uc.get("skill").asDouble()
        );

        bots.add(new BotConfig(enabled, true, name, team, "ut99_stock", "ut99", appearance, ut99Config, null, null));
      }
    }

    if (bots.isEmpty()) {
      throw new IllegalStateException("No bots configured in ai_bots or ut99_bots");
    }

    return List.copyOf(bots);
  }

  /**
   * Strict appearance parser: every bot must declare a complete {@code appearance}
   * block with {@code class}, {@code skin}, {@code face} and {@code voice}.
   * No fallback — missing/blank field crashes config load.
   */
  private static BotAppearanceConfig parseAppearance(JsonNode node, String contextLabel) {
    JsonNode app = node.get("appearance");
    if (app == null || !app.isObject()) {
      throw new IllegalStateException(
          contextLabel + ": missing required 'appearance' object "
              + "(fields: class, skin, face, voice — see docs/config/json/gameplay.md).");
    }
    return new BotAppearanceConfig(
        requireText(app, "class", contextLabel + ".appearance"),
        requireText(app, "skin", contextLabel + ".appearance"),
        requireText(app, "face", contextLabel + ".appearance"),
        requireText(app, "voice", contextLabel + ".appearance")
    );
  }

  private static String requireText(JsonNode parent, String field, String contextLabel) {
    JsonNode v = parent.get(field);
    if (v == null || !v.isTextual() || v.asText().isBlank()) {
      throw new IllegalStateException(
          contextLabel + ": missing required text field '" + field + "'");
    }
    return v.asText().trim();
  }

  /**
   * Strict role parser: every bot/player config must declare a single
   * {@code role} string. Multi-role lists are rejected — one bot owns one
   * tactical role for the duration of a match, and the model learns to swap
   * behaviour from observable game state (teammate carrying flag, etc).
   * Missing or non-textual {@code role} crashes hard — no fallback.
   */
  private static String parseRole(JsonNode node, String contextLabel) {
    JsonNode value = node.path("role");
    if (value.isArray() || value.isObject()) {
      throw new IllegalStateException(
          contextLabel + ": 'role' must be a single string (e.g. \"role\": \"Attack\"). "
              + "Multi-role configurations are not supported — assign exactly one tactical role per bot.");
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalStateException(
          contextLabel + ": missing required 'role' string field "
              + "(one of: Attack, Cover, Defend, DeathMatch).");
    }
    return value.asText().trim();
  }

  // ===== Tactical =====

  private static TacticalConfig loadTactical() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/tactical");
    if (n == null || n.isMissingNode()) {
      throw new IllegalStateException("Missing /runtime/tactical section in config");
    }
    return new TacticalConfig(
        intRequired(n, "tactical_min_dwell_ms"),
        requireDouble(n, "carrier_line_margin_norm"),
        intRequired(n, "carrier_grace_ms")
    );
  }

  // ===== Inference Batching =====

  private static InferenceBatchingConfig loadInferenceBatching() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/inference/batching");
    if (n == null || n.isMissingNode()) {
      throw new IllegalStateException("Missing /runtime/inference/batching section in config");
    }
    return new InferenceBatchingConfig(
        requireBoolean(n, "enabled"),
        intRequired(n, "max_batch_size"),
        intRequired(n, "submit_timeout_ms")
    );
  }

  private static AimTargetConfig loadAimTarget() {
    JsonNode n = PropertyReaderUtils.getSubtree("/runtime/aim_target");
    if (n == null || n.isMissingNode()) {
      throw new IllegalStateException("Missing /runtime/aim_target section in config");
    }
    return new AimTargetConfig(
        intRequired(n, "unseen_before_switch_ms"),
        intRequired(n, "unseen_force_switch_ms"),
        doubleRequired(n, "switch_distance_ratio"),
        intRequired(n, "min_commit_ms")
    );
  }

  private static double doubleRequired(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isMissingNode()) {
      throw new IllegalStateException("Missing required config field: " + field);
    }
    return v.asDouble();
  }

  private static int intRequired(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isMissingNode()) {
      throw new IllegalStateException("Missing required config field: " + field);
    }
    return v.asInt();
  }

  private record CachedSlices(
      RecordingConfig recording,
      CommandControllerConfig commandController,
      MissionConfig mission,
      WeaponPlannerConfig weaponPlanner,
      LoggingConfig logging,
      FilesConfig files,
      PlayerConfig player,
      Ut99ServerConfig server,
      ViewConfig view,
      GameplayConfig gameplay,
      DebugConfig debug,
      ShootingConfig shooting,
      EngagementConfig engagement,
      TacticalConfig tactical,
      InferenceBatchingConfig inferenceBatching,
      AimTargetConfig aimTarget,
      PickupTypeRegistry pickupTypes,
      AmmoDeadlockGuardConfig ammoDeadlockGuard,
      List<BotConfig> bots
  ) {}
}
