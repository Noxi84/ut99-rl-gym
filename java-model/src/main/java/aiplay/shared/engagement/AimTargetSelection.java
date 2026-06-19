package aiplay.shared.engagement;

import aiplay.dto.PlayerDto;

/**
 * Uitkomst van {@link AimTargetSelector#select}: de gekozen vijand plus zijn slot-index in
 * {@code frame.enemies} ({@code -1} wanneer er geen doelwit is). Beide horen bij elkaar — lezers
 * annoteren {@code annotatedAimEnemy} en {@code annotatedAimTargetIndex} synchroon, zodat enemy
 * en index nooit uit de pas lopen.
 */
public record AimTargetSelection(PlayerDto enemy, int slotIndex) {

    public static final AimTargetSelection NONE = new AimTargetSelection(null, -1);
}
