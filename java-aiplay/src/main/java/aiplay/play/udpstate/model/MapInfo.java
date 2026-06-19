package aiplay.play.udpstate.model;

/** MapInfo TLV (tag 0x01): scores, timers, gameplay-style flags, map hash. */
public record MapInfo(
        int redScore,
        int blueScore,
        int remainingTime,
        int elapsedTime,
        float timeDilation,
        boolean hardcore,
        boolean megaSpeed,
        // UT99 GameInfo.bGameEnded — true tussen EndGame() en ServerTravel.
        // Gepropageerd door trainer-side MatchEndLogger (false→true transitie
        // emit MATCH_ENDED voor de match-aligned DualKPIDeltaGate).
        boolean gameEnded,
        int mapNameHash) {
}
