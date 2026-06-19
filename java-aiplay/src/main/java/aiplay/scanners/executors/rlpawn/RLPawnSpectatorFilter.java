package aiplay.scanners.executors.rlpawn;

import aiplay.dto.GameStateDto;

/**
 * Spectator filter voor het joint VR+shooting controller (CLAUDE.md "Ignore
 * spectators"). De UC webservice hoort spectators al uit te filteren via
 * {@code Spectator(P) == None}; deze defense-in-depth voorkomt dat een
 * gelekt spectator-frame nog steeds een ShootIntent of ViewTurnIntent op de
 * buses gooit.
 *
 * <p>Pure functie zonder state — handig voor unit tests én voor hergebruik
 * mocht een andere controller in de toekomst eenzelfde guard nodig hebben.</p>
 */
public final class RLPawnSpectatorFilter {

    private RLPawnSpectatorFilter() {}

    /**
     * @return {@code true} als de huidige tick moet worden overgeslagen
     *     (frame komt van een spectator). {@code false} betekent een
     *     normale RL-controlled bot.
     */
    public static boolean shouldSkip(GameStateDto frame) {
        if (frame == null) {
            return true;
        }
        if (frame.playerPawn == null) {
            return true;
        }
        return frame.playerPawn.bIsSpectator;
    }
}
