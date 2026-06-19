package aiplay.rl.rewards.aim.viewalignment;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.rl.rewards.core.LeadAimUtils;
import aiplay.rl.rewards.core.RewardTuningConfig;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.shared.view.ViewTargeting;

/**
 * Dense view alignment reward: yaw alignment with enemy/objective + acquisition (rate of
 * improvement). Uses ViewTargeting for attention-aware target resolution.
 */
public class ViewAlignmentReward implements RewardComponent {

  private final ViewAlignmentParams params;

  public ViewAlignmentReward(ViewAlignmentParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "ViewAlignmentReward requires non-null ViewAlignmentParams");
    }
    this.params = params;
  }

  public record Result(double viewAlignment, double viewAlignmentAcquisition) {
    public double total() {
      return viewAlignment + viewAlignmentAcquisition;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    if (ctx.curr().playerPawn.health <= 0) {
      return new Result(0.0, 0.0);
    }

    double viewAlignment = computeViewAlignment(ctx);
    double viewAlignmentAcquisition = computeAcquisition(ctx);
    double preFireStability = computePreFireStability(ctx);

    return new Result(viewAlignment + preFireStability, viewAlignmentAcquisition);
  }

  /**
   * Pre-fire aim-stability bonus: rewards the policy when aim is on the engagement target AND the
   * view is settled (low yaw velocity). Encourages the model to settle before firing instead of
   * swinging through targets. Only fires when an engagement target exists — won't farm in idle
   * states.
   */
  private double computePreFireStability(RewardContext ctx) {
    double bonus = params.preFireStabilityBonus();
    if (bonus == 0.0) return 0.0;

    GameStateDto curr = ctx.curr();
    GameStateDto prev = ctx.prev();
    if (curr.playerPawn == null
        || curr.playerPawn.location == null
        || curr.playerPawn.viewRotation == null
        || prev.playerPawn == null
        || prev.playerPawn.viewRotation == null) {
      return 0.0;
    }

    PlayerDto enemy = LeadAimUtils.resolveLeadEnemy(curr, ctx.modelTargetIndex());
    if (enemy == null) return 0.0;

    // VR heeft geen fire-mode signaal — gebruik primary speed als default. Voor
    // shooting (waar dit reward weight=0 heeft) niet kritiek; voor VR self-reward
    // geldt dat primary fire de dominante mode is post per-mode fix.
    double projSpeed =
        LeadAimUtils.projectileSpeedForWeapon(
            curr.playerPawn.weaponClass,
            ctx.config().getProjectileSpeedFlakPrimaryUu(),
            ctx.config().getProjectileSpeedFlakSecondaryUu(),
            false);
    CoordinatesDto target =
        LeadAimUtils.applyLeadOrFallback(
            enemy.location, curr.playerPawn.location, enemy, projSpeed);

    double aimScore = RewardUtils.computeAimDot3D(curr, target);
    if (aimScore < params.preFireStabilityAimScoreThreshold()) {
      return 0.0;
    }

    int prevYaw = prev.playerPawn.viewRotation.x & 0xFFFF;
    int currYaw = curr.playerPawn.viewRotation.x & 0xFFFF;
    int rawDelta = currYaw - prevYaw;
    if (rawDelta > 32768) rawDelta -= 65536;
    if (rawDelta < -32768) rawDelta += 65536;
    double yawVelNorm = Math.abs(rawDelta) / 65536.0;

    if (yawVelNorm > params.preFireStabilityYawVelocityThresholdNorm()) {
      return 0.0;
    }

    return bonus;
  }

  private double computeViewAlignment(RewardContext ctx) {
    if (params.enemyAlignmentBonus() == 0.0 && params.objectiveAlignmentBonus() == 0.0) {
      return 0.0;
    }

    GameStateDto curr = ctx.curr();
    if (curr.playerPawn == null
        || curr.playerPawn.location == null
        || curr.playerPawn.viewRotation == null) {
      return 0.0;
    }

    int playerTeam = curr.playerPawn.team;
    FlagDto ownFlag = (playerTeam == 1) ? curr.blueFlag : curr.redFlag;
    FlagDto enemyFlag = (playerTeam == 1) ? curr.redFlag : curr.blueFlag;

    CoordinatesDto target;
    double bonus;
    CoordinatesDto attentionTarget =
        RewardUtils.isEnemyAttentionTarget(curr)
            ? ViewTargeting.resolveAttentionTarget(curr, ownFlag, enemyFlag)
            : null;
    // Range-gate (2026-06-11, CTF-Face cowardice-fix): de per-tick holding-aim-reward op de enemy
    // (enemy_alignment_bonus ≈ 2.5/tick = +500/window, veruit de dominante dense term) mag ALLEEN
    // tellen binnen effectieve engagement-afstand. Buiten bereik = geen echte engagement → val terug
    // op de objective-tak (objective_alignment_bonus). Anders is "blijf bij eigen base + track verre
    // enemies" lokaal optimaal (de policy maximaliseerde de reward = cowardice, terwijl in-game
    // objectiveProgress plateaut). Absolute UU → map-onafhankelijk; onzichtbaar op de kleine
    // AndAction-arena (alles al binnen bereik). De acquisitie-PBRS + vuren/raken-rewards blijven
    // ongemoeid: dit dempt uitsluitend het passieve lange-afstand-aim-farmen.
    if (attentionTarget != null) {
      double gdx = attentionTarget.x - curr.playerPawn.location.x;
      double gdy = attentionTarget.y - curr.playerPawn.location.y;
      if (Math.sqrt(gdx * gdx + gdy * gdy) > params.enemyAlignmentMaxRangeUu()) {
        attentionTarget = null;
      }
    }
    if (attentionTarget != null) {
      // Lead-aim: voor projectile-wapens shift target naar voorspelde impact-positie
      // (enemyPos + enemyVel × travelTime). Hitscan + null-velocity → no-op.
      PlayerDto leadEnemy = LeadAimUtils.resolveLeadEnemy(curr, ctx.modelTargetIndex());
      double projSpeed =
          LeadAimUtils.projectileSpeedForWeapon(
              curr.playerPawn.weaponClass,
              ctx.config().getProjectileSpeedFlakPrimaryUu(),
              ctx.config().getProjectileSpeedFlakSecondaryUu(),
              false);
      target =
          LeadAimUtils.applyLeadOrFallback(
              attentionTarget, curr.playerPawn.location, leadEnemy, projSpeed);
      bonus = params.enemyAlignmentBonus();
      // GEEN combo-bal-target-switch hier (verwijderd 2026-06-05): het verleggen van de yaw-target
      // naar de eigen ShockProj liet het beloonde aim-doel meermaals per seconde enemy↔bal pingpongen
      // (bal doorkruist het gate-venster in ~0.5s bij speed 1000 UU/s) → policy leerde schokkerige
      // yaw (gemeten: 2× RMS-yawΔ en 2.3× richtingswissels/s t.o.v. flak) zonder combo-winst.
      // De combo-skill wordt gevormd via de shock_combo_* rewards, niet via het view-target.
    } else {
      target = ViewTargeting.resolveHeadingTarget(curr);
      bonus = params.objectiveAlignmentBonus();
    }

    if (target == null || bonus == 0.0) {
      return 0.0;
    }

    double toDirX = target.x - curr.playerPawn.location.x;
    double toDirY = target.y - curr.playerPawn.location.y;
    double toDist = Math.sqrt(toDirX * toDirX + toDirY * toDirY);
    if (toDist < 1.0) {
      return bonus;
    }
    toDirX /= toDist;
    toDirY /= toDist;

    double yawRad = (curr.playerPawn.viewRotation.x & 0xFFFF) * (2.0 * Math.PI / 65536.0);
    double viewDirX = Math.cos(yawRad);
    double viewDirY = Math.sin(yawRad);

    double dot = viewDirX * toDirX + viewDirY * toDirY;
    double alignmentReward = bonus * dot * Math.abs(dot);

    // Precision-tier bonuses: only awarded when aiming at the enemy (not objective).
    // Uses a strict 3D dot so both yaw AND pitch must be on target — required for
    // instagib-class precision where any vertical offset is a miss.
    //
    // NOTE: these state-based dense bonuses proved to be a reward-hacking magnet.
    // Disable them by setting enemy_alignment_precision_bonus and _on_target_bonus
    // to 0.0 in rewards.json. Kept here so per-weapon configs can re-enable them
    // where it genuinely fits (e.g. hitscan with mild weight).
    double precisionReward = 0.0;
    if (attentionTarget != null) {
      double precBonus = params.precisionBonus();
      double onTargetBonus = params.onTargetBonus();
      if (precBonus != 0.0 || onTargetBonus != 0.0) {
        double dot3D = RewardUtils.computeAimDot3D(curr, target);
        if (precBonus != 0.0 && dot3D > params.precisionThreshold()) {
          precisionReward += precBonus;
        }
        if (onTargetBonus != 0.0 && dot3D > params.onTargetThreshold()) {
          precisionReward += onTargetBonus;
        }
      }
    }
    return alignmentReward + precisionReward;
  }

  private double computeAcquisition(RewardContext ctx) {
    double bonus = params.acquisitionBonus();
    if (bonus == 0.0) {
      return 0.0;
    }
    // Potential-based shaping (Ng et al. 1999): F = Φ(s') − Φ(s), met Φ = de 2D yaw-alignment
    // (cosine) naar het lead-gecorrigeerde enemy-target. Dit telescopeert — en laat de optimale
    // policy dus invariant — ALLEEN als Φ een zuivere state-functie is: elk frame meet daarom
    // tegen z'n EIGEN lead-target, en we shapen uitsluitend tijdens een CONTINUE engagement
    // (prev én curr enemy-attention). Bij een attention-boundary (enemy verschijnt/verdwijnt of
    // flikkert in/uit zicht) is Φ discontinu → F=0, zodat re-engages geen eenmalige bonus farmen.
    //
    // De oude implementatie mat prev's view tegen CURR's lead-target en negeerde prev's
    // attention-status. Dat telescopeert niet: bij een bewegende/flikkerende enemy kreeg de
    // policy elke tick een 'inhaal'-bonus die lineair accumuleerde zolang de bot in de
    // enemy-attention-state bleef — zónder ooit op-target te hoeven komen (viewAlign≈0) of te
    // killen (een dode enemy beëindigt de farm juist). Live droeg deze term ~86% van álle dense
    // reward; de policy was naar het farm-optimum gedreven (stilstaan + yaw-wiebelen bij een
    // enemy i.p.v. vechten, met zelf-splash als bijschade). Per-frame Φ verwijdert dat optimum
    // zonder de convergentie-versnelling (beloning voor het daadwerkelijk verbeteren van aim) te
    // verliezen. γ=1 (zuivere potential-difference) i.p.v. γΦ(s')−Φ(s): de SAC-gamma leeft in
    // sac.json die de bots niet lezen, en dit spiegelt de pitch-acquisition (PitchReward) die al
    // op deze γ=1-vorm draait — geen tweede gamma-bron / desync-risico.
    if (!RewardUtils.isEnemyAttentionTarget(ctx.curr())
        || !RewardUtils.isEnemyAttentionTarget(ctx.prev())) {
      return 0.0;
    }
    if (ctx.prev().playerPawn == null
        || ctx.prev().playerPawn.viewRotation == null
        || ctx.prev().playerPawn.location == null
        || ctx.curr().playerPawn == null
        || ctx.curr().playerPawn.viewRotation == null
        || ctx.curr().playerPawn.location == null) {
      return 0.0;
    }

    double phiCurr = yawAlignmentPotential(ctx.curr(), ctx.modelTargetIndex(), ctx.config());
    double phiPrev = yawAlignmentPotential(ctx.prev(), ctx.modelTargetIndex(), ctx.config());
    if (Double.isNaN(phiCurr) || Double.isNaN(phiPrev)) {
      return 0.0;
    }

    return bonus * (phiCurr - phiPrev);
  }

  /**
   * Potential Φ(s) for the yaw-acquisition PBRS: the 2D yaw alignment (cosine) between the frame's
   * own view direction and its own lead-corrected enemy target. Because it is a pure function of
   * this frame's state (view rotation + that frame's enemy position/velocity), the shaping
   * F = Φ(s') − Φ(s) telescopes and leaves the optimal policy invariant. Returns NaN when no enemy
   * target is resolvable, signalling the caller to emit no shaping for that transition.
   */
  private double yawAlignmentPotential(GameStateDto frame, int modelTargetIndex, RewardTuningConfig config) {
    CoordinatesDto enemyTarget = ViewTargeting.resolveEnemyPlayerTarget(frame);
    if (enemyTarget == null) {
      return Double.NaN;
    }
    // Lead-aim: meet alignment naar het voorspelde lead-point i.p.v. de huidige enemy-positie,
    // zodat het potentieel het echte (lead-gecorrigeerde) mikpunt waardeert.
    PlayerDto leadEnemy = LeadAimUtils.resolveLeadEnemy(frame, modelTargetIndex);
    double projSpeed =
        LeadAimUtils.projectileSpeedForWeapon(
            frame.playerPawn.weaponClass,
            config.getProjectileSpeedFlakPrimaryUu(),
            config.getProjectileSpeedFlakSecondaryUu(),
            false);
    CoordinatesDto leadTarget =
        LeadAimUtils.applyLeadOrFallback(
            enemyTarget, frame.playerPawn.location, leadEnemy, projSpeed);
    return RewardUtils.computeYawDot(frame, leadTarget);
  }
}
