package aiplay.play.udpstate.model;

import java.util.List;

/**
 * One fully reassembled and decoded state frame. All collections are defensively
 * copied to immutable lists. Published by {@code UdpStateReceiver} via
 * {@code StateFrameSource.getLatestFrame()}.
 */
public record StateFrame(
        int frameId,
        long receivedAtNanos,
        MapInfo mapInfo,
        List<Flag> flags,
        List<Player> players,
        List<Projectile> projectiles,
        List<Pickup> pickups,
        List<MoverState> movers) {

    public StateFrame {
        flags = List.copyOf(flags);
        players = List.copyOf(players);
        projectiles = List.copyOf(projectiles);
        pickups = List.copyOf(pickups);
        movers = List.copyOf(movers);
    }
}
