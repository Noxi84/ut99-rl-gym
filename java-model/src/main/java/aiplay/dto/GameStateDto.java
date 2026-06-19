package aiplay.dto;

import aiplay.shared.engagement.AttentionTargetType;
import aiplay.shared.engagement.EngagementType;
import aiplay.shared.mission.MissionType;

import java.util.ArrayList;
import java.util.List;

public class GameStateDto {
    public MapInfoDto mapInfo;
    public PlayerDto playerPawn;
    /** Backward-compat alias: always points to enemies[0] (closest enemy). */
    public PlayerDto player1;
    /** Sorted enemy slots (index 0 = closest). Max 2 for 2v2. Null entries = no enemy in that slot. */
    public PlayerDto[] enemies;
    /** Sorted teammate slots (index 0 = closest). Max 1 for 2v2. Null entries = no teammate in that slot. */
    public PlayerDto[] teammates;
    public FlagDto redFlag;
    public FlagDto blueFlag;
    public SpawnPointDto[] spawnPoints;
    public List<ProjectileDto> projectiles;
    /** Live pickup-state per UC-pickup, gevuld door PickupsJsonToDtoConverter uit
     *  {@link aiplay.ut99webmodel.GameState#Pickups}. Null/empty op CSV-replay frames
     *  zonder pickup data (oude recordings). */
    public List<PickupDto> livePickups;
    public List<MoverDto> movers;
    public long timestampMillis;
    public int frameNumber;
    public boolean flagBasicResolved;
    public boolean flagRelativeResolved;
    public boolean realtimeIncrementalEnriched;

    // Mission/Engagement annotation — set by MissionAnnotator, not from UT99 JSON.
    // Used by feature resolvers for both realtime and CSV parity.
    public MissionType annotatedMission;
    public EngagementType annotatedEngagement;
    public AttentionTargetType annotatedAttentionTarget;

  /**
   * Sticky aim-target gekozen door {@code AimTargetSelector} (java-model) en per frame
   * geannoteerd door {@code AimTargetEnricher}. Gelezen door ViewTargeting
   * (rewards + BC labels + runtime features) in plaats van het per-frame wisselende
   * frame.player1 (= slot 0). Voorkomt oscillatie/upward-bias bij 2+ enemies.
   * Kan null zijn — callers vallen dan terug op frame.player1.
   */
  public PlayerDto annotatedAimEnemy;

  /**
   * Phase 2e: shooting model's target_index (0-4 enemy slot, -1 = no choice yet).
   * Set by ShootingTargetIndexEnricher — at runtime from ShootingTargetIndexBus,
   * at CSV-writer time from post-hoc kill attribution. Read by VR's
   * target_index_onehot input feature so VR sees the same target as shooting.
   */
  public int annotatedShootingTargetIndex = -1;

  /**
   * Slot index (0-4) of {@link #annotatedAimEnemy} in {@link #enemies}, or -1 if no aim target.
   * Computed by {@code AimTargetSelector}, written by {@code AimTargetEnricher} with annotatedAimEnemy; the
   * one-hot feature group {@code aim_target_index_onehot_0..4} reads this so the viewrotation
   * model has an explicit signal of which enemy slot to track per role-aware aim target.
   * Distinct from {@link #annotatedShootingTargetIndex} (shooting's pick), since role-aware
   * VR attention may pick a different enemy than shooting (e.g. Defend → carrier, while
   * shooting may target the closest threat).
   */
  public int annotatedAimTargetIndex = -1;

  /**
   * Sticky enemy-team spawn aim point used while all enemies are dead. Set by
   * EnemySpawnTargetEnricher from map spawn_points; null as soon as any enemy is alive.
   */
  public CoordinatesDto annotatedEnemySpawnTarget;

    public GameStateDto deepCopy() {
        GameStateDto copy = new GameStateDto();
        copy.timestampMillis = this.timestampMillis;
        copy.frameNumber = this.frameNumber;
        copy.flagBasicResolved = this.flagBasicResolved;
        copy.flagRelativeResolved = this.flagRelativeResolved;
        copy.realtimeIncrementalEnriched = this.realtimeIncrementalEnriched;
        if (this.mapInfo != null) {
            copy.mapInfo = this.mapInfo.deepCopy();
        }
        if (this.playerPawn != null) {
            copy.playerPawn = this.playerPawn.deepCopy();
        }
        if (this.player1 != null) {
            copy.player1 = this.player1.deepCopy();
        }
        if (this.enemies != null) {
            copy.enemies = new PlayerDto[this.enemies.length];
            for (int i = 0; i < this.enemies.length; i++) {
                copy.enemies[i] = (this.enemies[i] != null) ? this.enemies[i].deepCopy() : null;
            }
            if (copy.enemies.length > 0 && copy.enemies[0] != null) {
                copy.player1 = copy.enemies[0];
            }
        }
        if (this.teammates != null) {
            copy.teammates = new PlayerDto[this.teammates.length];
            for (int i = 0; i < this.teammates.length; i++) {
                copy.teammates[i] = (this.teammates[i] != null) ? this.teammates[i].deepCopy() : null;
            }
        }
        if (this.redFlag != null)   copy.redFlag = this.redFlag.deepCopy();
        if (this.blueFlag != null)   copy.blueFlag = this.blueFlag.deepCopy();
        if (this.spawnPoints != null) {
            copy.spawnPoints = new SpawnPointDto[this.spawnPoints.length];
            for (int i = 0; i < this.spawnPoints.length; i++) {
                copy.spawnPoints[i] = (this.spawnPoints[i] != null) ? this.spawnPoints[i].deepCopy() : null;
            }
        }
        if (this.projectiles != null) {
            copy.projectiles = new ArrayList<>(this.projectiles.size());
            for (ProjectileDto p : this.projectiles) {
                copy.projectiles.add(p.deepCopy());
            }
        }
        if (this.livePickups != null) {
            copy.livePickups = new ArrayList<>(this.livePickups.size());
            for (PickupDto p : this.livePickups) {
                copy.livePickups.add(p.deepCopy());
            }
        }
        if (this.movers != null) {
            copy.movers = new ArrayList<>(this.movers.size());
            for (MoverDto m : this.movers) {
                copy.movers.add(m.deepCopy());
            }
        }
        copy.annotatedMission = this.annotatedMission;
        copy.annotatedEngagement = this.annotatedEngagement;
        copy.annotatedAttentionTarget = this.annotatedAttentionTarget;
      if (this.annotatedAimEnemy != null && copy.enemies != null) {
          for (PlayerDto e : copy.enemies) {
              if (e != null && e.name != null && e.name.equals(this.annotatedAimEnemy.name)) {
                  copy.annotatedAimEnemy = e;
                  break;
              }
          }
      }
      copy.annotatedShootingTargetIndex = this.annotatedShootingTargetIndex;
      copy.annotatedAimTargetIndex = this.annotatedAimTargetIndex;
      if (this.annotatedEnemySpawnTarget != null) {
          copy.annotatedEnemySpawnTarget = this.annotatedEnemySpawnTarget.deepCopy();
      }
        return copy;
    }

    /**
     * Lightweight copy that only clones objects mutated by enrichers
     * (MissionAnnotationFeatureEnricher, FlagRelativeBatchEnricher, EnemyRelativeBatchEnricher).
     * Immutable sub-objects after JSON parsing (CollisionsDto, CoordinatesDto,
     * ViewRotationDto, FlagDto, player1, mapInfo) are shared by reference —
     * ~11 objects vs ~55 for deepCopy.
     */
    public GameStateDto shallowCopyForEnrichment() {
        GameStateDto copy = new GameStateDto();
        copy.timestampMillis = this.timestampMillis;
        copy.frameNumber = this.frameNumber;
        copy.flagBasicResolved = this.flagBasicResolved;
        copy.flagRelativeResolved = this.flagRelativeResolved;
        copy.realtimeIncrementalEnriched = this.realtimeIncrementalEnriched;
        copy.annotatedMission = this.annotatedMission;
        copy.annotatedEngagement = this.annotatedEngagement;
        copy.annotatedAttentionTarget = this.annotatedAttentionTarget;
      copy.annotatedAimEnemy = this.annotatedAimEnemy;
      copy.annotatedShootingTargetIndex = this.annotatedShootingTargetIndex;
      copy.annotatedAimTargetIndex = this.annotatedAimTargetIndex;
      copy.annotatedEnemySpawnTarget = this.annotatedEnemySpawnTarget;

        // Shared by reference — not mutated after JSON parsing
        copy.mapInfo = this.mapInfo;
        copy.player1 = this.player1;
        copy.enemies = this.enemies;
        copy.teammates = this.teammates;
        copy.redFlag = this.redFlag;
        copy.blueFlag = this.blueFlag;
        copy.spawnPoints = this.spawnPoints;
        copy.projectiles = this.projectiles;
        copy.movers = this.movers;

        // playerPawn needs a shallow copy: enrichers write to enrichment fields
        if (this.playerPawn != null) {
            copy.playerPawn = this.playerPawn.shallowCopyForEnrichment();
            if (this.realtimeIncrementalEnriched && this.playerPawn.enrichments != null) {
                copy.playerPawn.enrichments = this.playerPawn.enrichments;
            }
        }
        return copy;
    }
}
