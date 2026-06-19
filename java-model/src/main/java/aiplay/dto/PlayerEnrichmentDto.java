package aiplay.dto;

/**
 * Enriched features that require multi-frame or cross-entity computation
 * (e.g. relative distances between entities). Simple per-frame values
 * like velocity/acceleration belong directly on PlayerDto.
 */
public class PlayerEnrichmentDto {
    /**
     * Relation van deze speler t.o.v. playerPawn (enkel gezet op 'enemy' PlayerDto).
     * Backward-compat alias: always equals enemyRels[0] when available.
     */
    public PlayerRelationDto enemyRel;

    /**
     * Relation per enemy slot (index 0 = closest enemy, 1 = second enemy).
     * Null entries mean no enemy in that slot.
     */
    public PlayerRelationDto[] enemyRels;

    /**
     * Relation per teammate slot (index 0 = closest teammate).
     * Null entries mean no teammate in that slot.
     */
    public PlayerRelationDto[] teammateRels;

    /**
     * Relation vanaf playerPawn naar RED / BLUE flag current location.
     */
    public PlayerRelationDto redFlagRel;
    public PlayerRelationDto blueFlagRel;

    /**
     * Relation vanaf playerPawn naar own flag's fixed base location.
     * Used for RETURN_HOME objective (go to base, not to moving flag).
     */
    public PlayerRelationDto homeBaseRel;

    /**
     * Relation vanaf playerPawn naar enemy flag's fixed base location.
     * Used for CAPTURE_FLAG objective (go to enemy base to pick up flag).
     */
    public PlayerRelationDto enemyBaseRel;

    /**
     * Self-fired projectiles, sorted by distance-to-bot (proxy for recency:
     * just-fired rockets are still close to firing origin = bot location).
     * Null entries = fewer self-projectiles than slots.
     */
    public ProjectileRelationDto[] selfProjectileRels;

    /**
     * Enemy-fired projectiles per enemy slot. enemyProjectileRels[N][M] =
     * the M-th most threatening projectile fired by the enemy in slot N
     * (matched by instigatorName == enemyN.name). Sort: time-to-closest-approach.
     * Null = no projectile in that (enemy, projectile) slot.
     */
    public ProjectileRelationDto[][] enemyProjectileRels;

    /**
     * Teammate-fired projectiles per teammate slot. Same layout as enemy.
     * Reserved for future use; enricher leaves null until features.json
     * includes teammate{N}_projectile{M}_* IDs.
     */
    public ProjectileRelationDto[][] teammateProjectileRels;

    /**
     * Top-N nearest jump pads in the active map, sorted by 3D distance to bot
     * (index 0 = closest). Null entries = fewer pads available than slots.
     * Populated by {@code JumpPadEnricher}.
     */
    public JumpPadRelationDto[] jumpPadRels;

    public PlayerEnrichmentDto deepCopy() {
        PlayerEnrichmentDto c = new PlayerEnrichmentDto();

        if (this.enemyRel != null) c.enemyRel = this.enemyRel.deepCopy();
        if (this.enemyRels != null) {
            c.enemyRels = new PlayerRelationDto[this.enemyRels.length];
            for (int i = 0; i < this.enemyRels.length; i++) {
                c.enemyRels[i] = (this.enemyRels[i] != null) ? this.enemyRels[i].deepCopy() : null;
            }
        }
        if (this.teammateRels != null) {
            c.teammateRels = new PlayerRelationDto[this.teammateRels.length];
            for (int i = 0; i < this.teammateRels.length; i++) {
                c.teammateRels[i] = (this.teammateRels[i] != null) ? this.teammateRels[i].deepCopy() : null;
            }
        }
        if (this.redFlagRel != null) c.redFlagRel = this.redFlagRel.deepCopy();
        if (this.blueFlagRel != null) c.blueFlagRel = this.blueFlagRel.deepCopy();
        if (this.homeBaseRel != null) c.homeBaseRel = this.homeBaseRel.deepCopy();
        if (this.enemyBaseRel != null) c.enemyBaseRel = this.enemyBaseRel.deepCopy();
        if (this.selfProjectileRels != null) {
            c.selfProjectileRels = new ProjectileRelationDto[this.selfProjectileRels.length];
            for (int i = 0; i < this.selfProjectileRels.length; i++) {
                c.selfProjectileRels[i] = (this.selfProjectileRels[i] != null) ? this.selfProjectileRels[i].deepCopy() : null;
            }
        }
        if (this.enemyProjectileRels != null) {
            c.enemyProjectileRels = new ProjectileRelationDto[this.enemyProjectileRels.length][];
            for (int i = 0; i < this.enemyProjectileRels.length; i++) {
                ProjectileRelationDto[] inner = this.enemyProjectileRels[i];
                if (inner == null) continue;
                c.enemyProjectileRels[i] = new ProjectileRelationDto[inner.length];
                for (int j = 0; j < inner.length; j++) {
                    c.enemyProjectileRels[i][j] = (inner[j] != null) ? inner[j].deepCopy() : null;
                }
            }
        }
        if (this.teammateProjectileRels != null) {
            c.teammateProjectileRels = new ProjectileRelationDto[this.teammateProjectileRels.length][];
            for (int i = 0; i < this.teammateProjectileRels.length; i++) {
                ProjectileRelationDto[] inner = this.teammateProjectileRels[i];
                if (inner == null) continue;
                c.teammateProjectileRels[i] = new ProjectileRelationDto[inner.length];
                for (int j = 0; j < inner.length; j++) {
                    c.teammateProjectileRels[i][j] = (inner[j] != null) ? inner[j].deepCopy() : null;
                }
            }
        }
        if (this.jumpPadRels != null) {
            c.jumpPadRels = new JumpPadRelationDto[this.jumpPadRels.length];
            for (int i = 0; i < this.jumpPadRels.length; i++) {
                c.jumpPadRels[i] = (this.jumpPadRels[i] != null) ? this.jumpPadRels[i].deepCopy() : null;
            }
        }
        return c;
    }
}
