package aiplay.rl.rewards.aim.enemyspawnattention;

import aiplay.rl.rewards.catalog.RewardBlock;
import aiplay.rl.rewards.catalog.RewardMetadata;

/**
 * Dense bonus voor view-richting naar de dichtstbijzijnde enemy-team spawnlocatie wanneer alle
 * vijanden dood zijn. Voorkomt rondjes-draaien tijdens dual-respawn vensters: zonder dit signaal
 * hebben alle enemy-slots waarde 0 en heeft het LSTM geen indicatie waarheen te kijken.
 *
 * <p>Mutually exclusive met {@code ViewAlignment} enemy-target shaping (die schakelt in zodra er
 * een levende enemy is, deze schakelt alleen in als alle enemies dood zijn).
 *
 * <p>{@code holdTicks}: aantal ticks dat een eenmaal gekozen spawn wordt vastgehouden voordat
 * opnieuw de dichtstbijzijnde spawn wordt geselecteerd. Voorkomt dat de "dichtstbijzijnde" spawn
 * elke paar ticks flipt terwijl de bot beweegt — wat het gedrag laat ogen als doelloos rondjes
 * draaien. Reset gebeurt automatisch zodra een enemy weer leeft. {@code 0} = sticky uit (oud
 * gedrag).
 */
public record EnemySpawnAttentionParams(RewardMetadata metadata, double bonus, int holdTicks)
    implements RewardBlock {

  public EnemySpawnAttentionParams {
    if (metadata == null) {
      throw new IllegalArgumentException("EnemySpawnAttentionParams.metadata required");
    }
    if (holdTicks < 0) {
      throw new IllegalArgumentException(
          "EnemySpawnAttentionParams.holdTicks must be >= 0, got " + holdTicks);
    }
  }

  @Override
  public boolean enabled() {
    return bonus != 0.0;
  }
}
