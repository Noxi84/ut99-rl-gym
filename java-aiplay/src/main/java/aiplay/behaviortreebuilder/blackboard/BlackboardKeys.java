package aiplay.behaviortreebuilder.blackboard;

public class BlackboardKeys {

  public static final String SESSION_ID = "SESSION_ID";

  public static final String GAMESTATE_BUS = "GAMESTATE_BUS";
  public static final String RUNNING_ATOMIC_BOOLEAN = "RUNNING_ATOMIC_BOOLEAN";
  public static final String START_TIME_NS = "START_TIME_NS";
  public static final String MOVEMENT_INTENT_BUS = "MOVEMENT_INTENT_BUS";
  public static final String POLICY_INTENT_BUS = "POLICY_INTENT_BUS";
  public static final String VIEWTURN_INTENT_BUS = "VIEWTURN_INTENT_BUS";
  public static final String SHOOT_INTENT_BUS = "SHOOT_INTENT_BUS";
  public static final String WEAPON_SELECT_INTENT_BUS = "WEAPON_SELECT_INTENT_BUS";
  public static final String PRODUCER = "PRODUCER";
  public static final String GENERIC_PREDICTOR = "GENERIC_PREDICTOR";
  public static final String VR_SHOOT_EXECUTOR = "VR_SHOOT_EXECUTOR";
  public static final String VR_SHOOT_GAMESTATES = "VR_SHOOT_GAMESTATES";
  public static final String EXECUTOR_THREADS = "EXECUTOR_THREADS";
  // When true, request the Java app to terminate (used for "UT99 closed while stats active").
  public static final String APP_EXIT_REQUESTED = "APP_EXIT_REQUESTED";

  // RL experience collection
  public static final String EXPERIENCE_COLLECTOR = "EXPERIENCE_COLLECTOR";
  public static final String REWARD_COMPUTER = "REWARD_COMPUTER";

  // Joint VR+shooting experience recorder — schrijft NPZ schema met per-skill
  // reward decomp en target_label aux supervision.
  public static final String JOINT_PAWN_EXPERIENCE_RECORDER = "JOINT_PAWN_EXPERIENCE_RECORDER";

  // Process tracking for multi-instance support
  public static final String UT99_SERVER_PID = "UT99_SERVER_PID";

  // Multi-bot-per-JVM: per-instance config (InstanceConfig) and window detector
  public static final String INSTANCE_CONFIG = "INSTANCE_CONFIG";

  // Headless mode: HTTP POST command sender (replaces xdotool)
  public static final String HEADLESS_COMMAND_SENDER = "HEADLESS_COMMAND_SENDER";

  // Binary UDP state receiver (replaces HTTP GET game-state pull).
  // Shared per-instance: all bots in the same instance read the same receiver.
  public static final String UDP_STATE_RECEIVER = "UDP_STATE_RECEIVER";

  // Command controller executor
  public static final String COMMAND_EXECUTOR = "COMMAND_EXECUTOR";
  public static final String COMMAND_GAMESTATES = "COMMAND_GAMESTATES";

  // Weapon planner executor (low-Hz lane: decides which weapon a bot should hold)
  public static final String WEAPON_EXECUTOR = "WEAPON_EXECUTOR";
  public static final String WEAPON_GAMESTATES = "WEAPON_GAMESTATES";

  // Model config
  public static final String MODEL_CONFIG_REPOSITORY = "MODEL_CONFIG_REPOSITORY";

  // Mission/Tactical layer
  public static final String TACTICAL_INTENT_BUS = "TACTICAL_INTENT_BUS";
  public static final String MISSION_EXECUTOR = "MISSION_EXECUTOR";
  public static final String MISSION_GAMESTATES = "MISSION_GAMESTATES";

  // Runtime composition
  public static final String BOT_RUNTIME = "BOT_RUNTIME";
  public static final String RUNTIME_CLOCK = "RUNTIME_CLOCK";

  // Multi-bot identity (per-bot name, team, and role — read by threads to set PlayerIdentityContext)
  public static final String BOT_IDENTITY_NAME = "BOT_IDENTITY_NAME";
  public static final String BOT_IDENTITY_TEAM = "BOT_IDENTITY_TEAM";
  public static final String BOT_IDENTITY_ROLE = "BOT_IDENTITY_ROLE";
  // Effective per-instance RL bot layout. In self-play this may differ from
  // gameplay.json because odd instances automatically swap team 0/1.
  public static final String EFFECTIVE_RL_BOTS = "EFFECTIVE_RL_BOTS";

  // Multi-bot: skip server start for bots that share an already-running server
  public static final String SKIP_SERVER_START = "SKIP_SERVER_START";
}
