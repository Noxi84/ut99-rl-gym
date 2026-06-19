package aiplay;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.ProjectileDto;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.rl.rewards.catalog.RewardCatalog;
import aiplay.rl.rewards.catalog.json.JsonRewardCatalog;
import aiplay.rl.rewards.combat.shockcomboclick.ShockComboClickReward;
import aiplay.rl.rewards.combat.shockcomboevent.ShockComboEventReward;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardTuningConfig;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.scanners.feature.resolver.shoot.FireCooldownIncrementalEnricher;
import aiplay.scanners.feature.resolver.shoot.WeaponReadyIncrementalEnricher;
import aiplay.runtime.context.PlayerIdentityContext;
import aiplay.scanners.model.writer.trainingcsvwriter.reader.ReaderFacade;
import aiplay.shared.view.FireModeAimTargeting;

import java.util.List;
import java.util.Locale;

/**
 * Offline grondwaarheid-validatie van de shock-combo reward-keten tegen menselijke
 * gameplay-recordings (json-recording-sessions zips met échte combo's).
 *
 * <p>Draait de PRODUCTIE-componenten ({@link ShockComboEventReward} detectie +
 * {@link ShockComboClickReward} klik-economie) over de frames zoals de game-servers dat live
 * doen, en rapporteert per gedetecteerde combo / klik de context. Antwoord op twee vragen:
 * (1) vuurt de event-detectie op de menselijke combo's in de opname, en (2) krijgt de
 * menselijke (goed getimede) klik een hoog klik-krediet terwijl spam-kliks laag/negatief
 * scoren — d.w.z. wijst de reward-economie naar het gedrag dat we willen aanleren.
 *
 * <p>Gebruik: {@code java aiplay.ValidateComboRewardsMain <zip-pad> [<zip-pad>...]}
 * (vereist resources/ op het classpath-werkpad voor rewards.json, zoals alle mains).
 */
public final class ValidateComboRewardsMain {

  private ValidateComboRewardsMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Usage: ValidateComboRewardsMain <recording-zip> [...]");
      System.exit(2);
    }
    GlobalConfigRepository globalCfg = GlobalConfigRepository.shared();
    PlayerIdentityContext.init(
        globalCfg.recording().playerName(),
        globalCfg.player().team(),
        globalCfg.player().role());
    System.out.println("Recording player: " + globalCfg.recording().playerName());
    RewardCatalog catalog = JsonRewardCatalog.from("rl_pawn", "Attack");
    RewardTuningConfig tuning = RewardTuningConfig.fromModel("rl_pawn");
    ShockComboEventReward eventReward = new ShockComboEventReward(catalog.shockComboEvent());
    ShockComboClickReward clickReward = new ShockComboClickReward(catalog.shockComboClick());
    FireCooldownIncrementalEnricher fireEnricher = new FireCooldownIncrementalEnricher();
    WeaponReadyIncrementalEnricher readyEnricher = new WeaponReadyIncrementalEnricher();

    for (String zip : args) {
      System.out.println("=== RECORDING: " + zip + " ===");
      List<GameStateDto> frames = new ReaderFacade().getGameStates("validate-combo", zip);
      System.out.println("frames=" + frames.size());
      fireEnricher.enrichBatch(frames);
      readyEnricher.enrichBatch(frames);

      int clicks = 0;
      int clicksWithBall = 0;
      double clickSum = 0.0;
      int clickNeg = 0;
      int events = 0;
      double bestClick = 0.0;
      double readySum = 0.0;
      double readyMax = 0.0;
      int readyN = 0;

      for (int i = 1; i < frames.size(); i++) {
        GameStateDto prev = frames.get(i - 1);
        GameStateDto curr = frames.get(i);
        if (prev == null || curr == null || prev.playerPawn == null || curr.playerPawn == null) {
          continue;
        }
        RewardContext ctx = new RewardContext(prev, curr, null, tuning);

        double ev = eventReward.compute(ctx);
        if (ev > 0.0) {
          events++;
          System.out.printf(Locale.ROOT,
              "COMBO_EVENT frame=%d reward=%.2f weapon=%s%n", i, ev, curr.playerPawn.weaponClass);
        }

        boolean edge = curr.playerPawn.fireActive
            && !(prev.playerPawn != null && prev.playerPawn.fireActive);
        if (edge && FireModeAimTargeting.isShockRifleClass(curr.playerPawn.weaponClass)) {
          clicks++;
          readySum += curr.playerPawn.weaponReadyInNorm;
          readyMax = Math.max(readyMax, curr.playerPawn.weaponReadyInNorm);
          readyN++;
          double click = clickReward.compute(ctx);
          if (click != 0.0) {
            clicksWithBall++;
            clickSum += click;
            if (click < 0.0) clickNeg++;
            if (click > bestClick) bestClick = click;
            if (click > 1.0) {
              PlayerDto enemy = RewardUtils.findClosestVisibleEnemy(curr);
              if (enemy == null) enemy = RewardUtils.findClosestEnemy(curr);
              double ballEnemy = closestOwnBallToEnemy(curr, enemy);
              System.out.printf(Locale.ROOT,
                  "HIGH_CLICK frame=%d reward=%.2f ballEnemyDist=%.0f%n", i, click, ballEnemy);
            }
          }
        }
      }
      System.out.printf(Locale.ROOT,
          "SUMMARY events=%d shockClicks=%d withBall=%d neg=%d meanClick=%.3f maxClick=%.2f "
              + "readyInOnClick(mean=%.3f max=%.3f)%n%n",
          events, clicks, clicksWithBall, clickNeg,
          clicksWithBall > 0 ? clickSum / clicksWithBall : 0.0, bestClick,
          readyN > 0 ? readySum / readyN : 0.0, readyMax);
    }
  }

  private static double closestOwnBallToEnemy(GameStateDto frame, PlayerDto enemy) {
    if (frame.projectiles == null || enemy == null || enemy.location == null
        || frame.playerPawn == null || frame.playerPawn.name == null) {
      return Double.NaN;
    }
    double best = Double.NaN;
    for (ProjectileDto p : frame.projectiles) {
      if (p == null || p.location == null || p.projectileClass == null) continue;
      if (!"Botpack.ShockProj".equalsIgnoreCase(p.projectileClass)) continue;
      if (!frame.playerPawn.name.equals(p.instigatorName)) continue;
      double dx = p.location.x - enemy.location.x;
      double dy = p.location.y - enemy.location.y;
      double dz = p.location.z - enemy.location.z;
      double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (Double.isNaN(best) || d < best) best = d;
    }
    return best;
  }
}
