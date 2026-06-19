package aiplay.rl.rewards.core;

/**
 * Canonieke set van reward-uitkomst-signalen voor het joint {@code rl_pawn} model.
 *
 * <p>Eén signaal = één benoemde scalar-bijdrage aan de per-tick reward. Dit is
 * fijnmaziger dan {@link aiplay.rl.rewards.catalog.RewardId} (24 componenten):
 * één component levert via zijn {@code computeDetailed()}-{@code Result} vaak
 * meerdere signalen (bv. {@code CombatEventReward} → frag, death, shot-on/off,
 * …). Dáárom is de reward-<em>registratie</em> (de {@code RewardModule}-SPI) al
 * één-plek-schoon, maar reisde de reward-<em>uitkomst</em> voorheen door vijf
 * hand-gesynchroniseerde structuren ({@link RewardBreakdown}-velden, de
 * subtotalen, de positionele constructie in {@code RewardComputer}, de
 * decompositie-routing en de window-accumulatoren in
 * {@code PerModelExperienceRecorder}). Dit enum is de single source of truth
 * die al die plekken via iteratie bedient.</p>
 *
 * <p><b>Volgorde is contract:</b> de declaratievolgorde (= {@link #ordinal()})
 * is identiek aan de historische {@link RewardBreakdown}-veldvolgorde en wordt
 * gebruikt als index in de {@code double[]} achter de breakdown. Itereren in
 * deze volgorde reproduceert de bestaande optel-sequentie, zodat de
 * gedecomposeerde scalar binnen {@link RewardDecomposition#INVARIANT_EPSILON}
 * gelijk blijft. Voeg nieuwe signalen daarom <em>achteraan</em> toe.</p>
 *
 * <p>De {@link RewardCategory} (sparse/dense/action) leeft hier omdat ze
 * intrinsiek is aan de reward. De skill-kanaal-routing + gewicht-sleutel leven
 * bewust niet hier maar in {@code java-aiplay} (joint-model-decompositie,
 * {@code JointRewardDecompositionStrategy}); zo blijft de module-DAG acyclisch.</p>
 */
public enum RewardSignal {
  // --- Sparse: flag-events ---
  FLAG_TAKEN("flagTaken", RewardCategory.SPARSE),
  FLAG_DROPPED("flagDropped", RewardCategory.SPARSE),
  FLAG_CAPTURED("flagCaptured", RewardCategory.SPARSE),
  FLAG_TEAM_CAPTURED("flagTeamCaptured", RewardCategory.SPARSE),
  FLAG_ENEMY_CAPTURED("flagEnemyCaptured", RewardCategory.SPARSE),
  FRAG("frag", RewardCategory.SPARSE),
  DEATH("death", RewardCategory.SPARSE),
  FLAG_RETURNED("flagReturned", RewardCategory.SPARSE),
  FLAG_TEAM_RETURNED("flagTeamReturned", RewardCategory.SPARSE),
  FIRE_PENALTY("firePenalty", RewardCategory.SPARSE),
  FIRE_COOLDOWN_PENALTY("fireCooldownPenalty", RewardCategory.SPARSE),
  // --- Dense: per-tick shaping ---
  ALIVE_BONUS("aliveBonus", RewardCategory.DENSE),
  OBJECTIVE_PROGRESS("objectiveProgress", RewardCategory.DENSE),
  SPEED("speed", RewardCategory.DENSE),
  FACING("facing", RewardCategory.DENSE),
  VIEW_ALIGNMENT("viewAlignment", RewardCategory.DENSE),
  VIEW_ALIGNMENT_ACQUISITION("viewAlignmentAcquisition", RewardCategory.DENSE),
  PITCH_ALIGNMENT("pitchAlignment", RewardCategory.DENSE),
  ENEMY_SPACING("enemySpacing", RewardCategory.DENSE),
  VIEW_SMOOTHNESS("viewSmoothness", RewardCategory.DENSE),
  // --- Sparse: shooting (state-gated on shot onset) ---
  SHOT_ON_TARGET("shotOnTarget", RewardCategory.SPARSE),
  SHOT_OFF_TARGET("shotOffTarget", RewardCategory.SPARSE),
  SHOT_ON_TARGET_ALT("shotOnTargetAlt", RewardCategory.SPARSE),
  SHOT_OFF_TARGET_ALT("shotOffTargetAlt", RewardCategory.SPARSE),
  ENEMY_KILLED_BY_FIRE("enemyKilledByFire", RewardCategory.SPARSE),
  FIRE_HOLDING_PENALTY("fireHoldingPenalty", RewardCategory.SPARSE),
  FIRE_HOLDING_PENALTY_ALT("fireHoldingPenaltyAlt", RewardCategory.SPARSE),
  AMMO_CONSUMPTION_PENALTY("ammoConsumptionPenalty", RewardCategory.SPARSE),
  // --- Sparse: flag-carrier kill ---
  FLAG_CARRIER_KILL("flagCarrierKill", RewardCategory.SPARSE),
  FLAG_CARRIER_KILL_NEAR_BASE("flagCarrierKillNearBase", RewardCategory.SPARSE),
  // --- Action-based movement penalties ---
  COLLISION("collision", RewardCategory.ACTION),
  STUCK("stuck", RewardCategory.ACTION),
  AREA_STUCK("areaStuck", RewardCategory.ACTION),
  EXPLORATION("exploration", RewardCategory.ACTION),
  DODGE("dodge", RewardCategory.ACTION),
  IDLE_URGENCY("idleUrgency", RewardCategory.ACTION),
  EXPOSED_IDLE("exposedIdle", RewardCategory.ACTION),
  // --- Sparse: damage (flak-mode) ---
  DAMAGE_DEALT("damageDealt", RewardCategory.SPARSE),
  DAMAGE_TAKEN("damageTaken", RewardCategory.SPARSE),
  SELF_DAMAGE("selfDamage", RewardCategory.SPARSE),
  FRIENDLY_FIRE("friendlyFire", RewardCategory.SPARSE),
  HEADSHOT("headshot", RewardCategory.SPARSE),
  // --- Dense: aim shaping ---
  PROJECTILE_AIM("projectileAim", RewardCategory.DENSE),
  PRIMARY_FIRE_AIM("primaryFireAim", RewardCategory.DENSE),
  SPAWN_ATTENTION("spawnAttention", RewardCategory.DENSE),
  SCORE_GAIN_RATE("scoreGainRate", RewardCategory.DENSE),
  FLAK_AVOIDANCE_INSTANT("flakAvoidanceInstant", RewardCategory.DENSE),
  FLAK_AVOIDANCE_DELTA("flakAvoidanceDelta", RewardCategory.DENSE),
  // --- Sparse: pickup + shock-combo ---
  PICKUP_EVENT("pickupEvent", RewardCategory.SPARSE),
  SHOCK_COMBO_EVENT("shockComboEvent", RewardCategory.SPARSE),
  // Dense ontdekkings-shaping voor de shock-combo (beam-op-eigen-bal nabij enemy).
  SHOCK_COMBO_AIM("shockComboAim", RewardCategory.DENSE),
  // --- Dense: team-coordination shaping ---
  DEFENDER_PRESENCE("defenderPresence", RewardCategory.DENSE),
  COVER_ESCORT("coverEscort", RewardCategory.DENSE),
  // teamAssist telde voorheen in geen enkel subtotaal mee (drift); routeert naar
  // de team_assist critic-head en is per saldo dense team-shaping.
  TEAM_ASSIST("teamAssist", RewardCategory.DENSE),
  CARRIER_PROXIMITY("carrierProximity", RewardCategory.DENSE),
  // Dense PBRS-curriculum voor de shock-combo: progressie van beam-op-bal-bij-enemy (Φ-delta).
  // Achteraan toegevoegd zodat de ordinal-indices van bestaande breakdown-velden niet verschuiven.
  SHOCK_COMBO_CURRICULUM_SHAPING("shockComboCurriculumShaping", RewardCategory.DENSE),
  // Rising-edge klik-reward voor de shock-combo (schot-moment × beam-op-bal × bal-bij-enemy).
  // Achteraan (ordinal-stabiliteit van de breakdown).
  SHOCK_COMBO_CLICK("shockComboClick", RewardCategory.DENSE),
  // PBRS edge-exposure potential: anticipatieve val-vermijding. Φ(s) = -scale × fractie van de
  // 8 floor-delta-sectoren met een val-grade drop binnen de probe; F = Φ(curr) - Φ(prev) straft
  // het naderen van een afgrond en beloont terugkeer naar veilige vloer. Map-onafhankelijk
  // (nul effect op void-vrije maps) → movement-head (ACTION). Achteraan (ordinal-stabiliteit).
  VOID_AVOIDANCE("voidAvoidance", RewardCategory.ACTION);

  /** Aantal signalen — gebruik als lengte voor de {@code double[]} achter de breakdown. */
  public static final int COUNT = values().length;

  private final String fieldName;
  private final RewardCategory category;

  RewardSignal(String fieldName, RewardCategory category) {
    this.fieldName = fieldName;
    this.category = category;
  }

  /** camelCase-naam, gelijk aan de overeenkomstige {@link RewardBreakdown}-accessor + CSV/log-kolom. */
  public String fieldName() {
    return fieldName;
  }

  public RewardCategory category() {
    return category;
  }
}
