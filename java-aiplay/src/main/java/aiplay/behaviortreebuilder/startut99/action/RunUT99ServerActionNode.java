package aiplay.behaviortreebuilder.startut99.action;

import aiplay.behaviortreebuilder.blackboard.BlackboardKeys;
import aiplay.config.global.BotAppearanceConfig;
import aiplay.config.global.BotConfig;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.Ut99BotConfig;
import aiplay.runtime.config.NeuralNetEndpointResolver;
import aiplay.runtime.config.SessionPaths;
import aiplay.runtime.config.Ut99InstallResolver;
import aiplay.instance.InstanceConfig;
import behaviortree.ActionNode;
import behaviortree.BehaviorTreeContext;
import behaviortree.BehaviorTreeStatus;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts a local dedicated server (ucc) so the NeuralNet webservice is available and the UT client can connect.
 */
public class RunUT99ServerActionNode extends ActionNode {

  private static final String UCC_BINARY_RELATIVE = "System64/ucc-bin-amd64";
  private static final String SYSTEM_INI_RELATIVE = "System64/UnrealTournament.ini";

  public RunUT99ServerActionNode(String name) {
    super(name);
  }

  @Override
  protected BehaviorTreeStatus execute(BehaviorTreeContext context) {
    System.out.println("RunUT99ServerActionNode");

    // Multi-bot: second bot in an instance skips server start (first bot already started it).
    if (context != null && context.getBlackboard() != null) {
      try {
        Boolean skip = context.getBlackboard().get(BlackboardKeys.SKIP_SERVER_START);
        if (Boolean.TRUE.equals(skip)) {
          System.out.println("ℹ️ Skipping server start (another bot in this instance handles it)");
          return BehaviorTreeStatus.SUCCESS;
        }
      } catch (Exception ignore) {
      }
    }

    Path utRoot = Paths.get(Ut99InstallResolver.resolve());
    Path uccBinary = utRoot.resolve(UCC_BINARY_RELATIVE);
    Path systemIni = utRoot.resolve(SYSTEM_INI_RELATIVE);

    if (!Files.isExecutable(uccBinary)) {
      System.err.println("❌ ucc-bin not found or not executable: " + uccBinary);
      return BehaviorTreeStatus.FAILURE;
    }
    if (!Files.isRegularFile(systemIni)) {
      System.err.println("❌ UnrealTournament.ini not found: " + systemIni);
      return BehaviorTreeStatus.FAILURE;
    }

    String mapName = GlobalConfigRepository.shared().gameplay().mapName();
    if (mapName == null || mapName.isBlank()) {
      mapName = "CTF-andACTION";
    }

    InstanceConfig instanceConfig = null;
    if (context != null && context.getBlackboard() != null) {
      try {
        instanceConfig = context.getBlackboard().get(BlackboardKeys.INSTANCE_CONFIG);
      } catch (Exception ignore) {
      }
    }
    int port = NeuralNetEndpointResolver.resolveServerPort(instanceConfig);
    // Prefer sys/env overrides for hosts where 8080 is occupied (e.g. Jenkins on desktop-3070).
    // Falls back to constants and then to 8080.
    int requestedWebPort = Math.max(1, NeuralNetEndpointResolver.resolveUWebListenPort(instanceConfig));
    int webPort = chooseAvailableWebPort(requestedWebPort);
    if (webPort != requestedWebPort) {
      System.out.println(
          "ℹ️ UWeb listen port " + requestedWebPort + " unavailable; switching to " + webPort);
      // Update instance config (multi-bot) or system property (legacy single-bot).
      if (instanceConfig != null) {
        instanceConfig.setActualUwebPort(webPort);
      } else {
        System.setProperty("UT99_UWEB_LISTEN_PORT", String.valueOf(webPort));
      }
    }

    String mapUrl = mapName.trim();
    // Use RLCTFGame gametype (headless, no human player needed)
    mapUrl = mapUrl.replaceAll("(?i)\\?game=Botpack\\.CTFGame", "?game=NeuralNetWebserver.RLCTFGame");
    if (!mapUrl.toLowerCase().contains("?game=")) {
      mapUrl = mapUrl + (mapUrl.contains("?") ? "?" : "?") + "game=NeuralNetWebserver.RLCTFGame";
    }

    // Strip any ?Mutator=... left in mapName — weapon_profile drives mutator selection.
    mapUrl = mapUrl.replaceAll("(?i)\\?Mutator=[^?]+", "");
    String weaponProfile = GlobalConfigRepository.shared().gameplay().weaponProfile();
    // weaponMutator == "" → pure stock weapons (no Arena, no pickup-strip).
    // weaponMutator non-empty → either an Arena-subclass (single-weapon match)
    // or PickupStripOnly (full arsenal but RL-clean pickup landscape).
    String weaponMutator = switch (weaponProfile) {
      case "instagib"        -> "Botpack.InstaGibDM";
      case "enforcer"        -> "NeuralNetWebserver.EnforcerOnlyArena";
      case "doubleenforcer"  -> "NeuralNetWebserver.DoubleEnforcerOnlyArena";
      case "shock"           -> "NeuralNetWebserver.ShockOnlyArena";
      case "biorifle"        -> "NeuralNetWebserver.BioOnlyArena";
      case "sniper"          -> "NeuralNetWebserver.SniperOnlyArena";
      case "flak"            -> "NeuralNetWebserver.FlakOnlyArena";
      case "rocket"          -> "NeuralNetWebserver.RocketOnlyArena";
      case "ripper"          -> "NeuralNetWebserver.RipperOnlyArena";
      case "minigun"         -> "NeuralNetWebserver.MinigunOnlyArena";
      case "pulse"           -> "NeuralNetWebserver.PulseOnlyArena";
      case "all"             -> "NeuralNetWebserver.PickupStripOnly";
      case "stock"           -> "";
      default -> throw new IllegalStateException("Unknown weapon_profile: " + weaponProfile);
    };
    // SmartCTF chained naast de weapon-mutator: serveert covers/seals/assists/returns
    // scoring + scoreboard HUD voor live-spec. PRI.Score-bonussen worden door RL
    // rewards genegeerd via eigen counters; zie CombatEventReward / FlagEventReward.
    // EnhancedFeedback: "you took the lead" / "frags left" announcer-feedback
    // (in team-games beperkt — zie EnhancedFeedback.txt v0.8).
    // NB: 2k4Combos (UT2004 multi-kill broadcasts) zit NIET in deze chain —
    // het moet als ServerActor (`2k4Combos.CombosSA`) geregistreerd zijn in
    // [Engine.GameEngine]. CombosSA is een MessagingSpectator die zichzelf
    // spawnt en de Combos-mutator daarna aan BaseMutator hangt. Als we Combos
    // ook nog via ?Mutator= toevoegen krijgen we dubbele broadcasts; en zonder
    // MessagingSpectator gaan de localized messages niet naar alle clients
    // (waardoor een spectator de Double/MultiKill/HolyShit-strings nooit ziet).
    // Beide tools raken PRI.Score niet, dus RL-rewards blijven onveranderd.
    String mutatorChain = (weaponMutator.isEmpty() ? "" : weaponMutator + ",")
        + "SmartCTF_4G.SmartCTF"
        + ",EnhancedFeedback.EnhancedFeedbackMutator";
    mapUrl = mapUrl + "?Mutator=" + mutatorChain;
    System.out.println("ℹ️ Weapon profile: " + weaponProfile + " → Mutator=" + mutatorChain);

    // Prevent the match from ending too quickly (default GoalTeamScore=3 in UnrealTournament.ini),
    // which causes a server travel to another map after just a few captures.
    // Keep it high so 1-minute benchmarks can accumulate a meaningful score.
    if (!mapUrl.toLowerCase().contains("goalteamscore=")) {
      mapUrl = mapUrl + "?GoalTeamScore=0";
    }

    // Multi-bot configuration: only active AI bots join the server; UT99 stock bots only if enabled.
    // Inactive RL bots must not join at all — otherwise UCC spawns them but they have no Java-side
    // controller (MultiInstanceLauncher skips inactive bots), so they sit idle on the server.
    List<BotConfig> allBots = GlobalConfigRepository.shared().bots();
    List<BotConfig> rlBots = effectiveRlBotsFromBlackboard(context);
    if (rlBots == null) {
      rlBots = allBots.stream().filter(b -> b.isRl() && b.active()).toList();
    }
    List<BotConfig> enabledUt99Bots = allBots.stream().filter(b -> b.isUt99() && b.enabled()).toList();

    int minPlayers = rlBots.size() + enabledUt99Bots.size();
    minPlayers = Math.max(minPlayers, GlobalConfigRepository.shared().server().minPlayers());
    System.out.println("ℹ️ Bot config: " + rlBots.size() + " AI + " + enabledUt99Bots.size() + " UT99 (enabled) → MinPlayers=" + minPlayers);
    // Replace existing MinPlayers or append — mapName in gameplay.json may already contain MinPlayers
    mapUrl = mapUrl.replaceAll("(?i)MinPlayers=\\d+", "MinPlayers=" + minPlayers);
    if (!mapUrl.toLowerCase().contains("minplayers=")) {
      mapUrl = mapUrl + "?MinPlayers=" + minPlayers;
    }

    // ── UDP transport ports FIRST (before the bot roster) ──────────────────
    // UT99 truncates the map URL it hands to InitGame at ~1024 chars, so any
    // param trailing the (long) bot list is silently dropped. Ports at the
    // front always survive. Losing them spawns neither the command receiver
    // (bots can't be driven → they stand still) nor the state sender (no
    // observations → no PLAYER_SCORES) — the exact failure mode a 5v5 roster
    // produced before this reorder. HTTP GET+POST endpoints remain for curl.
    if (instanceConfig != null && instanceConfig.getUdpListenPort() > 0
        && !mapUrl.toLowerCase().contains("rludpport=")) {
      mapUrl = mapUrl + "?RLUdpPort=" + instanceConfig.getUdpListenPort();
      System.out.println("ℹ️ RLUdpPort=" + instanceConfig.getUdpListenPort()
          + " (binary command channel)");
    }
    if (instanceConfig != null && instanceConfig.getStateUdpListenPort() > 0
        && !mapUrl.toLowerCase().contains("rlstateudpport=")) {
      mapUrl = mapUrl + "?RLStateUdpPort=" + instanceConfig.getStateUdpListenPort();
      System.out.println("ℹ️ RLStateUdpPort=" + instanceConfig.getStateUdpListenPort()
          + " (binary state channel)");
    }

    // ── Bot roster, URL-compact (two CSV params, not 3 indexed keys per bot) ──
    //   ?Apr=cls|skin|face|voice,cls|skin|face|voice,...   (deduped appearances)
    //   ?RLBots=name|team|aprIdx,name|team|aprIdx,...        (one field per bot)
    // Appearances are interned: self-play rosters reuse a handful across both
    // teams, so 10–16 bots still fit comfortably under the ~1024-char InitGame
    // limit. The previous per-bot ?RLBotNApr=<full-appearance> encoding blew
    // past it at ~5 bots, truncating trailing bots AND the ports above —
    // the root cause of "only the first few bots join, and none of them move".
    if (!mapUrl.toLowerCase().contains("?rlbots=")) {
      List<String> aprTable = new java.util.ArrayList<>();
      StringBuilder roster = new StringBuilder();
      for (BotConfig bot : rlBots) {
        // ',', '|' and '?' are structural CSV/URL separators — validate each
        // field individually. (The assembled `apr` below intentionally CONTAINS
        // '|' as its own field separator, so it must NOT be checked directly.)
        requireRosterSafe(bot.name(), "bot name");
        requireRosterSafe(bot.appearance().meshClass(), "bot mesh class");
        requireRosterSafe(bot.appearance().skin(), "bot skin");
        requireRosterSafe(bot.appearance().face(), "bot face");
        requireRosterSafe(bot.appearance().voice(), "bot voice");
        String apr = bot.appearance().meshClass() + "|" + bot.appearance().skin()
            + "|" + bot.appearance().face() + "|" + bot.appearance().voice();
        int aprIdx = aprTable.indexOf(apr);
        if (aprIdx < 0) {
          aprIdx = aprTable.size();
          aprTable.add(apr);
        }
        if (roster.length() > 0) {
          roster.append(",");
        }
        roster.append(bot.name()).append("|").append(bot.team()).append("|").append(aprIdx);
      }
      mapUrl = mapUrl + "?Apr=" + String.join(",", aprTable) + "?RLBots=" + roster;
      System.out.println("ℹ️ RL roster: " + rlBots.size() + " bot(s), "
          + aprTable.size() + " unique appearance(s)");
    }

    // Set game speed for RL training acceleration (slomo equivalent).
    double gameSpeed = Ut99InstallResolver.getEffectiveGameSpeed();
    if (gameSpeed > 0.0 && gameSpeed != 1.0 && !mapUrl.toLowerCase().contains("gamespeed=")) {
      mapUrl = mapUrl + "?GameSpeed=" + gameSpeed;
      System.out.println("ℹ️ Server game speed set to " + gameSpeed + "x for RL training");
    }

    // Pin TimeLimit (minutes) so every match runs to a known duration.
    // Needed for the champion gate: with compute_delta(window_minutes=10) hardcoded,
    // a fixed match length keeps eval windows comparable across cycles.
    int matchTimeMinutes = GlobalConfigRepository.shared().gameplay().matchTimeMinutes();
    if (!mapUrl.toLowerCase().contains("timelimit=")) {
      mapUrl = mapUrl + "?TimeLimit=" + matchTimeMinutes;
      System.out.println("ℹ️ Server TimeLimit set to " + matchTimeMinutes + " min");
    }

    // UT99 prepends a ~127-char DefaultPlayer prefix (?Name=...?Class=...) when
    // it builds the InitGame Options string, then truncates the whole thing at
    // ~1024 chars. Warn loudly before we get close — silent truncation drops
    // trailing params (late bots, and historically the UDP ports), which reads
    // as "bots stand still" / stock-bot fill rather than an obvious error.
    int approxOptionsLen = mapUrl.length() + 130;
    if (approxOptionsLen > 1000) {
      System.err.println("⚠️ Map URL ~" + approxOptionsLen + " chars (incl. UT99 DefaultPlayer"
          + " prefix) is approaching the ~1024-char InitGame limit. Reduce active bots or share"
          + " appearances across the roster. URL=" + mapUrl);
    }

    // Use a session-local copy of UnrealTournament.ini so we can change ListenPort without breaking system config.
    // This is required on hosts where port 8080 is occupied (e.g. Jenkins).
    Path iniToUse;
    try {
      iniToUse = createSessionLocalIni(context, systemIni, webPort, port);
    } catch (Exception e) {
      System.err.println("⚠️ Failed to create session-local UnrealTournament.ini, using system ini. msg=" + e.getMessage());
      iniToUse = systemIni;
    }

    ProcessBuilder pb = new ProcessBuilder(
        uccBinary.toString(),
        "server",
        mapUrl,
        "-port=" + port,
        "-ini=" + iniToUse,
        "-log=server.log");
    pb.directory(utRoot.toFile());
    pb.inheritIO();

    try {
      Process proc = pb.start();
      // Store PID on blackboard for multi-instance support (avoids global pgrep).
      if (context != null && context.getBlackboard() != null) {
        context.getBlackboard().set(aiplay.behaviortreebuilder.blackboard.BlackboardKeys.UT99_SERVER_PID, proc.pid());
      }
      return BehaviorTreeStatus.SUCCESS;
    } catch (IOException e) {
      System.err.println("❌ Failed to start UT99 server: " + e.getMessage());
      return BehaviorTreeStatus.FAILURE;
    }
  }

  private static List<BotConfig> effectiveRlBotsFromBlackboard(BehaviorTreeContext context) {
    if (context == null || context.getBlackboard() == null) {
      return null;
    }
    try {
      if (!context.getBlackboard().has(BlackboardKeys.EFFECTIVE_RL_BOTS)) {
        return null;
      }
      List<BotConfig> bots = context.getBlackboard().get(BlackboardKeys.EFFECTIVE_RL_BOTS);
      return (bots == null || bots.isEmpty()) ? null : bots;
    } catch (Exception ignore) {
      return null;
    }
  }

  /**
   * Fail fast if a roster value contains a CSV/URL structural separator. The
   * ?Apr= / ?RLBots= encoding uses ',' between entries and '|' between fields,
   * and '?' delimits UT99 URL options — none may appear inside a bot name or
   * appearance token or the server-side split would desync silently.
   */
  private static void requireRosterSafe(String value, String what) {
    if (value != null
        && (value.indexOf(',') >= 0 || value.indexOf('|') >= 0 || value.indexOf('?') >= 0)) {
      throw new IllegalStateException(
          "RLCTFGame roster " + what + " must not contain ',', '|' or '?': '" + value + "'");
    }
  }

  private static Path createSessionLocalIni(BehaviorTreeContext context, Path systemIni, int webListenPort, int gamePort) throws Exception {
    String sessionId = null;
    if (context != null && context.getBlackboard() != null) {
      try {
        sessionId = context.getBlackboard().get(aiplay.behaviortreebuilder.blackboard.BlackboardKeys.SESSION_ID);
      } catch (Exception ignore) {
      }
    }
    if (sessionId == null || sessionId.isBlank()) {
      sessionId = "default";
    }

    Path sessionDir = Path.of(SessionPaths.getSessionDir());
    Path tmpDir = sessionDir.resolve("tmp");
    Files.createDirectories(tmpDir);

    Path outIni = tmpDir.resolve("UnrealTournament-server-" + webListenPort + ".ini");
    String raw = Files.readString(systemIni, StandardCharsets.UTF_8);
    String patched = patchListenPort(raw, webListenPort);
    patched = patchAdminPassword(patched, "rlbot");
    aiplay.instance.InstanceConfig ic = null;
    if (context != null && context.getBlackboard() != null && context.getBlackboard().has(aiplay.behaviortreebuilder.blackboard.BlackboardKeys.INSTANCE_CONFIG)) {
      ic = context.getBlackboard().get(aiplay.behaviortreebuilder.blackboard.BlackboardKeys.INSTANCE_CONFIG);
    }
    patched = patchServerName(patched, gamePort, ic);
    patched = patchSelfPlayMotd(patched, effectiveRlBotsFromBlackboard(context));
    patched = patchGameStyle(patched);
    // Ensure NeuralNetWebserver is in EditPackages (NOT ServerPackages — that forces client download).
    patched = ensureEditPackage(patched, "NeuralNetWebserver");
    Files.writeString(outIni, patched, StandardCharsets.UTF_8);

    // Patch User.ini for UT99 stock bot configuration (BotTeams, BotNames, etc.)
    patchUserIniForStockBots(systemIni.getParent());

    return outIni;
  }

  /**
   * Ensure a package is in EditPackages so it's loaded at startup (for game type class resolution). Unlike ServerPackages, EditPackages does NOT force client download — spectators can join without having the package.
   */
  static String ensureEditPackage(String ini, String packageName) {
    if (ini == null) {
      return "";
    }
    // Check if already present
    if (Pattern.compile("(?im)^EditPackages=" + Pattern.quote(packageName) + "\\s*$").matcher(ini).find()) {
      return ini;
    }
    // Also remove any ServerPackages entry for this package (prevents version mismatch for spectators)
    ini = ini.replaceAll("(?im)^ServerPackages=" + Pattern.quote(packageName) + "\\s*\\r?\\n?", "");
    // Find last EditPackages= line and add after it
    Pattern p = Pattern.compile("(?im)^EditPackages=.*$");
    Matcher m = p.matcher(ini);
    int lastEnd = -1;
    while (m.find()) {
      lastEnd = m.end();
    }
    if (lastEnd > 0) {
      String nl = ini.contains("\r\n") ? "\r\n" : "\n";
      return ini.substring(0, lastEnd) + nl + "EditPackages=" + packageName + ini.substring(lastEnd);
    }
    // Fallback: append to [Editor.EditorEngine]
    String nl = ini.contains("\r\n") ? "\r\n" : "\n";
    return ini + nl + "[Editor.EditorEngine]" + nl + "EditPackages=" + packageName + nl;
  }

  /**
   * Patches User.ini with UT99 stock bot configuration from gameplay.json bots array. Writes BotTeams[N], BotNames[N], BotJumpy[N], etc. under [Botpack.ChallengeBotInfo]. Only patches if there are UT99 stock bots configured. Index N starts at 0 and increments per UT99 bot.
   */
  static void patchUserIniForStockBots(Path system64Dir) {
    List<BotConfig> ut99Bots = GlobalConfigRepository.shared().bots().stream()
        .filter(b -> b.isUt99() && b.enabled()).toList();
    if (ut99Bots.isEmpty()) {
      return;
    }

    // UT99 OldUnreal reads User.ini from ~/.local/share/OldUnreal/UnrealTournament/System64/
    Path oldUnrealDir = Paths.get(System.getProperty("user.home"),
        ".local", "share", "OldUnreal", "UnrealTournament", "System64");
    Path userIni = oldUnrealDir.resolve("User.ini");
    if (!Files.exists(userIni)) {
      // Fallback: try the install dir
      userIni = system64Dir.resolve("User.ini");
    }
    if (!Files.exists(userIni)) {
      System.err.println("⚠️ User.ini not found at " + userIni + " — skipping stock bot patching");
      return;
    }

    try {
      String raw = Files.readString(userIni, StandardCharsets.UTF_8);
      String nl = raw.contains("\r\n") ? "\r\n" : "\n";

      // Build the replacement block for [Botpack.ChallengeBotInfo].
      // Force deterministic bot selection: bRandomOrder=False makes
      // ChallengeBotInfo.ChooseBotInfo() iterate from index 0 instead of
      // Rand(32), so our configured bots at indices 0..N-1 spawn in order
      // instead of random default-bots from the leftover slots N..31.
      // bAdjustSkill=False prevents the engine from rewriting Difficulty
      // mid-match (would persist via SaveConfig and drift over time).
      StringBuilder botBlock = new StringBuilder();
      botBlock.append("bRandomOrder=False").append(nl);
      botBlock.append("bAdjustSkill=False").append(nl);
      for (int i = 0; i < ut99Bots.size(); i++) {
        BotConfig bot = ut99Bots.get(i);
        Ut99BotConfig uc = bot.ut99Config();
        BotAppearanceConfig ap = bot.appearance();
        botBlock.append(String.format(Locale.US, "BotTeams[%d]=%d", i, bot.team())).append(nl);
        botBlock.append(String.format(Locale.US, "BotNames[%d]=%s", i, bot.name())).append(nl);
        botBlock.append(String.format(Locale.US, "BotClasses[%d]=BotPack.%s", i, ap.meshClass())).append(nl);
        botBlock.append(String.format(Locale.US, "BotSkins[%d]=%s", i, ap.skin())).append(nl);
        botBlock.append(String.format(Locale.US, "BotFaces[%d]=%s", i, ap.face())).append(nl);
        botBlock.append(String.format(Locale.US, "Voices[%d]=%s", i, ap.voice())).append(nl);
        botBlock.append(String.format(Locale.US, "BotJumpy[%d]=%d", i, uc.jumpy())).append(nl);
        botBlock.append(String.format(Locale.US, "FavoriteWeapon[%d]=%s", i, uc.favoriteWeapon())).append(nl);
        botBlock.append(String.format(Locale.US, "Camping[%d]=%f", i, uc.camping())).append(nl);
        botBlock.append(String.format(Locale.US, "StrafingAbility[%d]=%f", i, uc.strafingAbility())).append(nl);
        botBlock.append(String.format(Locale.US, "CombatStyle[%d]=%f", i, uc.combatStyle())).append(nl);
        botBlock.append(String.format(Locale.US, "Alertness[%d]=%f", i, uc.alertness())).append(nl);
        botBlock.append(String.format(Locale.US, "BotAccuracy[%d]=%f", i, uc.accuracy())).append(nl);
        botBlock.append(String.format(Locale.US, "BotSkills[%d]=%f", i, uc.skill())).append(nl);
      }

      // Replace or append the [Botpack.ChallengeBotInfo] section
      Pattern sectionPattern = Pattern.compile(
          "(?is)(\\[Botpack\\.ChallengeBotInfo\\]\\s*\\r?\\n)(.*?)(\\r?\\n\\[|\\z)");
      Matcher m = sectionPattern.matcher(raw);
      String patched;
      if (m.find()) {
        patched = raw.substring(0, m.start(2)) + botBlock + m.group(3) + raw.substring(m.end(3));
      } else {
        patched = raw + nl + "[Botpack.ChallengeBotInfo]" + nl + botBlock;
      }

      Files.writeString(userIni, patched, StandardCharsets.UTF_8);
      System.out.println("ℹ️ User.ini patched with " + ut99Bots.size() + " stock bot(s)");
    } catch (IOException e) {
      System.err.println("⚠️ Failed to patch User.ini: " + e.getMessage());
    }
  }

  static String patchServerName(String ini, int gamePort, aiplay.instance.InstanceConfig ic) {
    if (ini == null) {
      return "";
    }
    String machineId = System.getProperty("UT99_MACHINE_ID",
        System.getenv().getOrDefault("UT99_MACHINE_ID", ""));
    if (machineId.isBlank()) {
      try {
        machineId = java.net.InetAddress.getLocalHost().getHostName();
      } catch (Exception e) {
        machineId = "unknown";
      }
    }
    String serverName;
    if (ic != null) {
      String deviceTag = ic.isUseGpu() ? "GPU" : "CPU";
      // e.g. RL-p15v-GPU-1/3-20tot-7080
      serverName = "RL-" + machineId + "-" + deviceTag + "-" + ic.getGroupIndex() + "/" + ic.getGroupSize() + "-" + ic.getTotalInstances() + "tot-" + ic.getUwebListenPort();
    } else {
      serverName = "RL-" + machineId + "-" + gamePort;
    }
    Pattern p = Pattern.compile("(?im)^ServerName\\s*=.*$");
    Matcher m = p.matcher(ini);
    if (m.find()) {
      return m.replaceFirst("ServerName=" + serverName);
    }
    String nl = ini.contains("\r\n") ? "\r\n" : "\n";
    return ini + nl + "[Engine.GameReplicationInfo]" + nl + "ServerName=" + serverName + nl;
  }

  static String patchSelfPlayMotd(String ini, List<BotConfig> effectiveRlBots) {
    if (ini == null) {
      return "";
    }
    if (effectiveRlBots == null || effectiveRlBots.isEmpty()) {
      return ini;
    }

    String red = summarizeTeamSnapshot(effectiveRlBots, 0);
    String blue = summarizeTeamSnapshot(effectiveRlBots, 1);
    if (red == null && blue == null) {
      return ini;
    }

    List<String> lines = List.of(
        "Red = " + (red == null ? "none" : red),
        "Blue = " + (blue == null ? "none" : blue));
    return patchMotdLines(ini, lines);
  }

  static String patchMotdLines(String ini, List<String> lines) {
    if (ini == null) {
      return "";
    }
    String patched = ini;
    int maxLines = Math.min(4, lines == null ? 0 : lines.size());
    for (int i = 0; i < maxLines; i++) {
      patched = patchGameReplicationInfoValue(patched, "MOTDLine" + (i + 1), sanitizeIniValue(lines.get(i)));
    }
    for (int i = maxLines; i < 4; i++) {
      patched = patchGameReplicationInfoValue(patched, "MOTDLine" + (i + 1), "");
    }
    return patched;
  }

  private static String patchGameReplicationInfoValue(String ini, String key, String value) {
    String nl = ini.contains("\r\n") ? "\r\n" : "\n";
    Pattern section = Pattern.compile("(?is)(\\[Engine\\.GameReplicationInfo\\]\\s*\\r?\\n)(.*?)(\\r?\\n\\[|\\z)");
    Matcher sectionMatcher = section.matcher(ini);
    if (sectionMatcher.find()) {
      String body = sectionMatcher.group(2);
      Pattern keyPattern = Pattern.compile("(?im)^" + Pattern.quote(key) + "\\s*=.*$");
      Matcher keyMatcher = keyPattern.matcher(body);
      String replacement = key + "=" + value;
      String newBody;
      if (keyMatcher.find()) {
        newBody = keyMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
      } else {
        if (!body.isEmpty() && !body.endsWith("\n") && !body.endsWith("\r")) {
          body = body + nl;
        }
        newBody = body + replacement + nl;
      }
      return ini.substring(0, sectionMatcher.start(2))
          + newBody
          + sectionMatcher.group(3)
          + ini.substring(sectionMatcher.end(3));
    }
    return ini + nl + "[Engine.GameReplicationInfo]" + nl + key + "=" + value + nl;
  }

  private static String summarizeTeamSnapshot(List<BotConfig> bots, int team) {
    Set<String> rawSnapshots = new LinkedHashSet<>();
    Set<String> labels = new LinkedHashSet<>();
    for (BotConfig bot : bots) {
      if (bot == null || bot.team() != team || bot.models() == null) {
        continue;
      }
      for (var model : bot.models().values()) {
        if (model == null || !model.enabled()) {
          continue;
        }
        String snapshot = normalizeSnapshot(model.snapshot());
        rawSnapshots.add(snapshot);
        labels.add(compactSnapshotLabel(snapshot));
      }
    }

    if (rawSnapshots.isEmpty()) {
      return null;
    }
    if (rawSnapshots.size() == 1 && rawSnapshots.contains("current")) {
      return "Candidate";
    }
    if (!rawSnapshots.contains("current") && labels.size() == 1) {
      String label = labels.iterator().next();
      return "newest".equals(label) ? "Champion" : "Champion " + label;
    }
    return rawSnapshots.contains("current") ? "Mixed candidate/champion" : "Mixed champions";
  }

  private static String normalizeSnapshot(String snapshot) {
    if (snapshot == null || snapshot.isBlank()) {
      return "current";
    }
    return snapshot.trim();
  }

  private static String compactSnapshotLabel(String snapshot) {
    if ("current".equals(snapshot)) {
      return "current";
    }
    int slash = snapshot.lastIndexOf('/');
    if (slash >= 0 && slash < snapshot.length() - 1) {
      return snapshot.substring(slash + 1);
    }
    int colon = snapshot.lastIndexOf(':');
    if (colon >= 0 && colon < snapshot.length() - 1) {
      return snapshot.substring(colon + 1);
    }
    return snapshot;
  }

  private static String sanitizeIniValue(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\r', ' ').replace('\n', ' ').trim();
  }

  /**
   * Patches bHardCoreMode and bMegaSpeed in [Botpack.DeathMatchPlus] based on UT99_GAME_STYLE env var. classic = both false, hardcore = bHardCoreMode=True, turbo = both true.
   */
  static String patchGameStyle(String ini) {
    if (ini == null) {
      return "";
    }
    String style = System.getProperty("UT99_GAME_STYLE",
        System.getenv().getOrDefault("UT99_GAME_STYLE", "classic")).toLowerCase().trim();
    boolean hardCore = style.equals("hardcore") || style.equals("turbo");
    boolean megaSpeed = style.equals("turbo");

    ini = ini.replaceAll("(?im)^bHardCoreMode\\s*=.*$", "bHardCoreMode=" + (hardCore ? "True" : "False"));
    ini = ini.replaceAll("(?im)^bMegaSpeed\\s*=.*$", "bMegaSpeed=" + (megaSpeed ? "True" : "False"));
    return ini;
  }

  static String patchAdminPassword(String ini, String password) {
    if (ini == null) {
      return "";
    }
    // Replace AdminPassword= in [Engine.GameInfo] block
    Pattern p = Pattern.compile("(?im)^AdminPassword\\s*=.*$");
    Matcher m = p.matcher(ini);
    if (m.find()) {
      return m.replaceFirst("AdminPassword=" + password);
    }
    // No AdminPassword found — append to end
    String nl = ini.contains("\r\n") ? "\r\n" : "\n";
    return ini + nl + "[Engine.GameInfo]" + nl + "AdminPassword=" + password + nl;
  }

  private static String patchListenPort(String ini, int port) {
    if (ini == null) {
      return "";
    }
    int p = Math.max(1, port);

    // Replace within [UWeb.WebServer] block if present, else append a block.
    // Always ensure bEnabled=True so UT99 actually starts the webserver.
    Pattern block = Pattern.compile("(?is)(\\[UWeb\\.WebServer\\]\\s*)(.*?)(\\r?\\n\\[|\\z)");
    Matcher m = block.matcher(ini);
    if (!m.find()) {
      String nl = ini.contains("\r\n") ? "\r\n" : "\n";
      return ini + nl + "[UWeb.WebServer]" + nl
          + "bEnabled=True" + nl
          + "ListenPort=" + p + nl
          + "DefaultApplication=2" + nl
          + "Applications[0]=UTServerAdmin.UTServerAdmin" + nl
          + "ApplicationPaths[0]=/ServerAdmin" + nl
          + "Applications[1]=UTServerAdmin.UTImageServer" + nl
          + "ApplicationPaths[1]=/images" + nl
          + "Applications[2]=NeuralNetWebserver.NeuralNetWebserver" + nl
          + "ApplicationPaths[2]=/utneuralnet" + nl;
    }

    String header = m.group(1);
    String body = m.group(2);
    String tail = m.group(3);

    String patchedBody;
    if (body.toLowerCase().contains("listenport=")) {
      patchedBody = body.replaceAll("(?im)^ListenPort\\s*=\\s*\\d+\\s*$", "ListenPort=" + p);
    } else {
      String nl = body.contains("\r\n") ? "\r\n" : "\n";
      patchedBody = "ListenPort=" + p + nl + body;
    }

    // Ensure bEnabled=True is present
    if (!patchedBody.toLowerCase().contains("benabled=true")) {
      patchedBody = patchedBody.replaceAll("(?im)^bEnabled\\s*=.*$", "bEnabled=True");
      if (!patchedBody.toLowerCase().contains("benabled=")) {
        String nl = patchedBody.contains("\r\n") ? "\r\n" : "\n";
        patchedBody = "bEnabled=True" + nl + patchedBody;
      }
    }

    // Ensure NeuralNetWebserver application is registered
    if (!patchedBody.toLowerCase().contains("neuralnetwebserver")) {
      String nl = patchedBody.contains("\r\n") ? "\r\n" : "\n";
      patchedBody = patchedBody
          + "DefaultApplication=2" + nl
          + "Applications[0]=UTServerAdmin.UTServerAdmin" + nl
          + "ApplicationPaths[0]=/ServerAdmin" + nl
          + "Applications[1]=UTServerAdmin.UTImageServer" + nl
          + "ApplicationPaths[1]=/images" + nl
          + "Applications[2]=NeuralNetWebserver.NeuralNetWebserver" + nl
          + "ApplicationPaths[2]=/utneuralnet" + nl;
    }

    return ini.substring(0, m.start(1)) + header + patchedBody + tail + ini.substring(m.end(3));
  }

  private static int chooseAvailableWebPort(int requested) {
    int r = Math.max(1, requested);
    int[] candidates = new int[]{r, 5080, 18080, 28080, 38080};
    for (int p : candidates) {
      if (p <= 0) {
        continue;
      }
      if (isPortAvailable(p)) {
        return p;
      }
    }
    // Last resort: keep requested (even if unavailable) so behavior stays deterministic.
    return r;
  }

  private static boolean isPortAvailable(int port) {
    try (ServerSocket ss = new ServerSocket()) {
      ss.setReuseAddress(true);
      ss.bind(new InetSocketAddress("127.0.0.1", port), 1);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
