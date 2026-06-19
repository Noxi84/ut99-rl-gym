package aiplay.play.udpstate.model;

/** Flag TLV (tag 0x02): team, status, location, home-base, holder. */
public record Flag(
        int team,
        int status,
        double locX, double locY, double locZ,
        double baseX, double baseY, double baseZ,
        int holderSlot,
        // UC slot van de pawn die op de huidige state-frame de eigen-team vlag returnde
        // (CTFGame.ScoreFlag own-team branch). -1 = geen recente return / auto-return.
        int lastReturnInstigatorSlot) {
}
