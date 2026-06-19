package aiplay.scanners.executors.rlpawn.movement;

import java.util.List;

/**
 * Minimal action-layout contract consumed by {@link MovementActionDecoder}.
 *
 * <p>The joint {@code rl_pawn} policy uses this schema to describe the
 * indices of its movement-related action dims (sin, cos, dodge, idle, jump,
 * duck, fire, altFire) inside the 10-dim action vector.</p>
 */
public interface MovementActionSchema {
    List<String> targetOrder();

    int sinIndex();
    int cosIndex();
    int dodgeIndex();
    int idleIndex();

    int jumpIndex();
    int duckIndex();
    int fireIndex();
    int altFireIndex();

    double idleEnterThreshold();
    double idleExitThreshold();
}
