package aiplay.dto;

public class FlagDto {
    public int team;                 // 0 = RED, 1 = BLUE
    public FlagStatusDto status;     // HOME | CARRIED | DROPPED | UNKNOWN

    public CoordinatesDto location;      // actuele vlagpositie (indien gekend)
    public CoordinatesDto baseLocation;  // vaste base-positie

    public boolean bHome;                // serverhint (kan ontbreken → false)
    public boolean hasHolder;            // true als iemand de vlag draagt
    public String holderName;            // naam drager of ""

    /** UC slot van de speler die de vlag het laatst returnde (CTFGame.ScoreFlag own-team
     *  branch), -1 voor auto-returns of geen recente return. Gevuld door
     *  {@code RLUdpStateSender.RecordFlagReturn} en gedraind bij elke state-frame
     *  zodat één return-event exact één tick credit geeft. Gebruikt door
     *  {@code FlagEventReward} om {@code returned} (self) van {@code team_returned}
     *  (teamgenoot) te onderscheiden. */
    public int lastReturnInstigatorSlot = -1;

    /** Tijd in seconden tot de vlag vanzelf terugkeert naar de basis wanneer hij gedropped
     *  ligt. UE1's {@code CTFFlag.state Dropped} doet {@code SetTimer(25.0, false)}, dus
     *  hoogwaarde = 25. Buiten dropped state geldt 0.0 (timer inactief). Wordt gevuld door
     *  {@link aiplay.scanners.feature.resolver.flag.flagrelative.FlagDropTimerEnricher}. */
    public double dropReturnRemainingSec = 0.0;

    public CollisionsDto collisions;

    public FlagLosDto los;

    public FlagDto deepCopy() {
        FlagDto c = new FlagDto();
        c.team = this.team;
        c.status = this.status;
        c.bHome = this.bHome;
        c.hasHolder = this.hasHolder;
        c.holderName = this.holderName;
        c.lastReturnInstigatorSlot = this.lastReturnInstigatorSlot;
        c.dropReturnRemainingSec = this.dropReturnRemainingSec;

        if (this.location != null) c.location = this.location.deepCopy();
        if (this.baseLocation != null) c.baseLocation = this.baseLocation.deepCopy();

        if (this.collisions != null) c.collisions = this.collisions.deepCopy();

        if (this.los != null) c.los = this.los.deepCopy();

        return c;
    }
}
