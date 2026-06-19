package aiplay.rl.rewards.objective.objectiveprogress;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.rl.rewards.core.RewardUtils;
import aiplay.dto.CoordinatesDto;
import aiplay.dto.FlagDto;
import aiplay.dto.GameStateDto;
import aiplay.shared.objective.CarrierObjectiveResolver;
import aiplay.shared.objective.CounterGrabResolver;
import aiplay.shared.objective.EscortObjectiveResolver;

/**
 * Dense objective progress reward: alive bonus + distance-based progress toward the current
 * objective.
 */
public class ObjectiveProgressReward implements RewardComponent {

  /** Max per-tick progress delta in UU. Clamps large jumps caused by objective switches. */
  private static final double MAX_PROGRESS_DELTA = 50.0;

  private final ObjectiveProgressParams params;

  public ObjectiveProgressReward(ObjectiveProgressParams params) {
    if (params == null) {
      throw new IllegalArgumentException(
          "ObjectiveProgressReward requires non-null ObjectiveProgressParams");
    }
    this.params = params;
  }

  public record Result(double aliveBonus, double objectiveProgress, double carrierProximity) {
    public double total() {
      return aliveBonus + objectiveProgress + carrierProximity;
    }
  }

  @Override
  public double compute(RewardContext ctx) {
    return computeDetailed(ctx).total();
  }

  public Result computeDetailed(RewardContext ctx) {
    if (ctx.curr().playerPawn.health <= 0) {
      return new Result(0.0, 0.0, 0.0);
    }

    double aliveBonus = params.aliveBonus();
    double objectiveProgress;

    // Engagement-standoff floor: clamp prev/curr afstand-tot-objective op max(dist, floor) zodat
    // sluiten tot de band-rand beloond wordt en beweging binnen de band 0 oplevert (de band-rand
    // wordt de attractor). Vier gevallen:
    //   • Carrier (draagt de enemy-vlag): staging-zone-floor. Kan de carrier scoren (onze vlag thuis)
    //     of haalt hij zelf een gedropte vlag op → floor 0 (naar het exacte base-/vlag-punt). Is de
    //     capture geblokkeerd (onze vlag weg) ÉN staat er een enemy over midfield op onze helft, dan
    //     is "recht naar het exacte base-punt lopen" voorspelbaar en dodelijk → floor = zone-radius
    //     (0.35×inter-base): vrij manoeuvreren binnen de zone, zone-rand als zachte wand. Gedeelde
    //     CarrierObjectiveResolver.stageZoneRadiusUu houdt dit synchroon met de navTarget-feature.
    //   • EFC-interceptor (non-carrier, niet de counter-grabber, onze vlag CARRIED): floor =
    //     efcEngagementRangeUu → trail+schiet de bewegende EFC op ~range i.p.v. erdoorheen te lopen.
    //   • Escort (non-carrier, teammate draagt de enemy-vlag, objective = de carrier-positie):
    //     floor = escort-standoff (2026-06-06) → het reward-optimum is niet langer "sta ÍN de
    //     carrier" (pawns botsen — de escort duwde en blokkeerde de carrier fysiek) maar de
    //     band-rand. Gedeelde EscortObjectiveResolver houdt dit synchroon met de navTarget-feature
    //     en cover_escort.
    //   • Al het andere (capture-run, dropped-touch, counter-grabber die de enemy-vlag AANRAAKT):
    //     floor 0 → monotone Δdist→0, identiek oude gedrag.
    boolean escortObjective = RewardUtils.isTeammateCarrierEscortObjective(ctx.curr());
    double efcEngagementFloor;
    if (ctx.curr().playerPawn.hasFlag) {
      efcEngagementFloor = CarrierObjectiveResolver.stageZoneRadiusUu(ctx.curr());
    } else if (isOwnFlagCarriedByEnemy(ctx.curr())
        && !CounterGrabResolver.isDesignatedGrabber(ctx.curr())) {
      efcEngagementFloor = params.efcEngagementRangeUu();
    } else if (escortObjective) {
      efcEngagementFloor = EscortObjectiveResolver.escortStandoffUu(ctx.curr());
    } else {
      efcEngagementFloor = 0.0;
    }

    // Het own-flag-return pad (verhoogde schaal naar ownFlag.location) geldt alleen voor NON-carriers
    // — dedicated recoverers / EFC-interceptors. Een bot die zelf de enemy-vlag draagt volgt het
    // gedeelde carrier-objective (resolveMovementPrimaryObjective → CarrierObjectiveResolver) zodat de
    // reward exact de navTarget-feature volgt: naar huis, of naar een nabije gedropte eigen vlag als
    // dat een goedkope on-route detour is. Zonder deze !carrier-guard trok pad 1 de carrier naar
    // ownFlag.location (de bewegende EFC of een verre gedropte vlag) terwijl de feature naar base wees
    // — een reward/feature-drift (zie CLAUDE.md "Objective dual source").
    boolean carrier = ctx.curr().playerPawn.hasFlag;
    if (!carrier && params.ownFlagReturnProgressScale() != 0.0
        && RewardUtils.isOwnFlagReturnPriority(ctx.curr())) {
      double ownFlagDelta = computeProgressDeltaToOwnFlag(ctx, efcEngagementFloor);
      objectiveProgress = params.ownFlagReturnProgressScale() * ownFlagDelta;
    } else if (escortObjective && EscortObjectiveResolver.isCaptureFunnelActive(ctx.curr())) {
      // Capture-funnel-release (2026-06-06): de teammate-carrier kan scoren (onze vlag thuis) en
      // zit in de last-mile (< funnel-radius van base). Zelfs de band-rand-attractor zou de escort
      // de capture-funnel in trekken — dus de hele progress-pull los. De sparse team_captured (+
      // team_assist.team_captured_assist binnen assist_radius) houdt de escort in de buurt; de
      // navTarget-bearing squasht parallel naar neutraal (gedeelde EscortObjectiveResolver).
      objectiveProgress = 0.0;
    } else {
      double progressDelta = computeProgressDeltaToObjective(ctx, efcEngagementFloor);
      // Carrier (draagt de enemy-vlag) → carry-home: sterkere dense pull naar huis dan de grab-run
      // (carrierProgressScale > progressScale) zodat de bot het ontsnappen + thuisbrengen prioriteert
      // en de sparse captured-event niet de enige carry-home-leersignaal is. carrierProgressScale=0 →
      // valt terug op de gewone progressScale (oude gedrag).
      double scale = (carrier && params.carrierProgressScale() != 0.0)
          ? params.carrierProgressScale() : params.progressScale();
      objectiveProgress = scale * progressDelta;
    }

    // EFC deny-progress (threat): straf naarmate de EFC zijn capture-punt nadert, geschaald met de
    // nabijheid van DEZE bot tot de EFC (de blokker voelt het 't sterkst). Gevouwen in de movement-head
    // (objectiveProgress) zodat het samen met de standoff de intercept-en-blokkeer skill vormt.
    objectiveProgress += computeEfcThreatPenalty(ctx);

    double carrierProximity = computeCarrierProximityBonus(ctx);

    return new Result(aliveBonus, objectiveProgress, carrierProximity);
  }

  /**
   * EFC deny-progress (threat) penalty: dense, potential-based shaping op de afstand van de enemy
   * flag carrier (EFC) tot ZIJN capture-punt — de vijandelijke vlagbasis, waar ze onze vlag scoren.
   *
   * <p>{@code threatDelta = prevEfcDist - currEfcDist} (positief = EFC kwam dichter bij capture).
   * Reward = {@code -scale × threatDelta × proximityFactor}: straf wanneer de EFC vooruitkomt richting
   * de capture, beloning (positief) wanneer hij wordt teruggedrongen of geblokt (threatDelta ≤ 0).
   * Symmetrisch → potential-based (Φ = EFC-afstand-tot-capture-punt), verandert het optimum niet maar
   * geeft oplopende urgentie + beloont blokkeren emergent.
   *
   * <p>{@code proximityFactor = max(0, 1 - botToEfc / range)} schaalt met hoe dicht deze bot bij de
   * EFC is: de bot die kan blokkeren/killen voelt de urgentie, verre bots krijgen 0 (geen ruis).
   * Alleen actief wanneer onze vlag in prev EN curr door een enemy wordt gedragen.
   */
  private double computeEfcThreatPenalty(RewardContext ctx) {
    double scale = params.efcThreatProgressScale();
    double range = params.efcThreatProximityRangeUu();
    if (scale == 0.0 || range <= 0.0) {
      return 0.0;
    }
    GameStateDto prev = ctx.prev();
    GameStateDto curr = ctx.curr();
    if (!isOwnFlagCarriedByEnemy(prev) || !isOwnFlagCarriedByEnemy(curr)) {
      return 0.0;
    }
    // Carrier-vrijstelling (2026-05-29): een bot die zelf de enemy-vlag draagt is NIET
    // verantwoordelijk voor het blokkeren van de EFC — die gaat naar huis (priority 0). Geen
    // deny-progress-straf op de carrier, anders wordt hij naar de EFC getrokken i.p.v. naar huis.
    if (curr.playerPawn.hasFlag) {
      return 0.0;
    }
    int team = curr.playerPawn.team;
    FlagDto prevOwnFlag = (team == 0) ? prev.redFlag : prev.blueFlag;
    FlagDto currOwnFlag = (team == 0) ? curr.redFlag : curr.blueFlag;
    FlagDto enemyFlag = (team == 0) ? curr.blueFlag : curr.redFlag;
    if (prevOwnFlag == null || prevOwnFlag.location == null
        || currOwnFlag == null || currOwnFlag.location == null
        || enemyFlag == null || enemyFlag.baseLocation == null) {
      return 0.0;
    }
    CoordinatesDto botLoc = curr.playerPawn.location;
    if (botLoc == null) {
      return 0.0;
    }
    // EFC-positie == ownFlag.location bij CARRIED (UC stuurt holder.Location). Capture-punt = de
    // vijandelijke vlagbasis (daar scoort het enemy-team onze vlag). Geodesisch waar een veld
    // bestaat: de threat is de ROUTE-afstand van de EFC naar zijn capture-punt, niet de vogelvlucht.
    CoordinatesDto capturePoint = enemyFlag.baseLocation;
    double[] efcDists = aiplay.rl.rewards.core.RouteDistances.pairTo2dFallback(
        ctx.curr(), prevOwnFlag.location, currOwnFlag.location, capturePoint);
    double prevEfcDist = efcDists[0];
    double currEfcDist = efcDists[1];
    double threatDelta = prevEfcDist - currEfcDist;
    threatDelta = Math.max(-MAX_PROGRESS_DELTA, Math.min(MAX_PROGRESS_DELTA, threatDelta));

    double botToEfc = RewardUtils.distance2d(botLoc, currOwnFlag.location);
    double proximityFactor = 1.0 - botToEfc / range;
    if (proximityFactor <= 0.0) {
      return 0.0; // te ver om te blokkeren → geen straf/ruis
    }
    return -scale * threatDelta * proximityFactor;
  }

  /** True wanneer onze eigen vlag op dit moment door een enemy wordt gedragen (EFC bestaat + beweegt). */
  private static boolean isOwnFlagCarriedByEnemy(GameStateDto state) {
    if (state == null || state.playerPawn == null) {
      return false;
    }
    int team = state.playerPawn.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    return ownFlag != null && RewardUtils.isFlagCarried(ownFlag);
  }

  private double computeProgressDeltaToObjective(RewardContext ctx, double engagementFloorUu) {
    CoordinatesDto target = RewardUtils.resolveMovementPrimaryObjective(ctx.curr());
    return clampedDistanceDelta(ctx, target, engagementFloorUu);
  }

  private double computeProgressDeltaToOwnFlag(RewardContext ctx, double engagementFloorUu) {
    GameStateDto state = ctx.curr();
    if (state.playerPawn == null) {
      return 0.0;
    }
    int team = state.playerPawn.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    if (ownFlag == null) {
      return 0.0;
    }
    return clampedDistanceDelta(ctx, ownFlag.location, engagementFloorUu);
  }

  private double computeCarrierProximityBonus(RewardContext ctx) {
    double bonus = params.carrierProximityBonus();
    double radius = params.carrierProximityRadiusUu();
    if (bonus == 0.0 || radius <= 0.0) {
      return 0.0;
    }
    GameStateDto state = ctx.curr();
    if (state.playerPawn == null || !state.playerPawn.hasFlag
        || state.playerPawn.location == null || state.playerPawn.health <= 0) {
      return 0.0;
    }
    // Staging-zone (capture geblokkeerd + enemy over midfield): geen pull naar het exacte base-centrum
    // — dat voorspelbare camp-punt is juist wat we vermijden. De quadratische proximity-bonus dient
    // alleen om de scorende carrier de last-mile naar base te laten afleggen; binnen de zone uit, zodat
    // de vrijheid (weggevallen progress-pull + alive bonus) niet wordt tegengewerkt.
    if (CarrierObjectiveResolver.isStagingZoneActive(state)) {
      return 0.0;
    }
    int team = state.playerPawn.team;
    FlagDto ownFlag = (team == 0) ? state.redFlag : state.blueFlag;
    if (ownFlag == null || ownFlag.baseLocation == null) {
      return 0.0;
    }
    double dist = RewardUtils.distance(state.playerPawn.location, ownFlag.baseLocation);
    if (dist >= radius) {
      return 0.0;
    }
    double ratio = 1.0 - dist / radius;
    return bonus * ratio * ratio;
  }

  private double clampedDistanceDelta(RewardContext ctx, CoordinatesDto target,
                                      double engagementFloorUu) {
    if (target == null) {
      return 0.0;
    }
    CoordinatesDto prevLoc = ctx.prev().playerPawn.location;
    CoordinatesDto currLoc = ctx.curr().playerPawn.location;
    if (prevLoc == null || currLoc == null) {
      return 0.0;
    }

    // Geodesisch (langs de bezoekgraaf) waar de map een veld heeft: in gangen en om
    // obstakels heen wijst de progress-gradient dan de route uit i.p.v. een lokaal
    // optimum tegen de muur te creëren. Euclidische fallback buiten dekking.
    double[] dists = aiplay.rl.rewards.core.RouteDistances.pairTo(
        ctx.curr(), prevLoc, currLoc, target);
    double prevDist = dists[0];
    double currDist = dists[1];

    // Engagement-standoff: clamp beide afstanden op de floor zodat sluiten binnen de band geen
    // (overshoot-)reward meer geeft en de band-rand een attractor wordt. floor=0 → no-op (oude gedrag).
    if (engagementFloorUu > 0.0) {
      prevDist = Math.max(prevDist, engagementFloorUu);
      currDist = Math.max(currDist, engagementFloorUu);
    }

    double delta = prevDist - currDist;
    return Math.max(-MAX_PROGRESS_DELTA, Math.min(MAX_PROGRESS_DELTA, delta));
  }
}
