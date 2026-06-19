package aiplay.runtime.port;

/**
 * Port for emitting game commands. The runtime kernel publishes movement,
 * view rotation and action commands through this port without knowing
 * the transport (binary UDP in production, test capture in tests).
 *
 * <p>Live adapter: {@code UdpCommandSender} (binary UDP to RLUdpCommandReceiver
 * inside UT99). Test adapter: captures commands for assertion.</p>
 */
public interface CommandSink {

    /**
     * Send a combined movement + view + action command.
     *
     * @param fwd     forward movement
     * @param back    backward movement
     * @param left    left strafe
     * @param right   right strafe
     * @param jump    jump
     * @param duck    duck/crouch
     * @param fire    primary fire
     * @param altFire alt fire
     * @param dodge   0=none, 1=forward, 2=back, 3=left, 4=right
     * @param yaw     target yaw in UT rotation units (0-65535)
     * @param pitch   target pitch in UT rotation units
     */
    void sendCommand(boolean fwd, boolean back, boolean left, boolean right,
                     boolean jump, boolean duck, boolean fire, boolean altFire,
                     int dodge, int yaw, int pitch, int moveYaw);

    /**
     * Notify the command infrastructure that this bot is parked (removed from the
     * UT99 server by dynamic team balancing) or restored.
     *
     * <p>No-op for fire-and-forget transports like UDP where there is no per-bot
     * flush barrier to maintain.</p>
     */
    default void notifyParked(boolean parked) {}

    /**
     * Forceer suicide voor deze bot's pawn op de UT99 server. Gebruikt door
     * {@link aiplay.runtime.AmmoDeadlockGuard} om een respawn te triggeren
     * wanneer alle RLBots in een match gelijktijdig zonder ammo staan.
     *
     * <p>Fire-and-forget; default no-op voor test-sinks die geen UDP-transport
     * hebben.</p>
     */
    default void sendSuicide() {}

    /**
     * Activeer het wapen met de gegeven FNV-1a class-hash op de UT99 server. Edge-getriggerd
     * door de {@code CommandController}: alleen verstuurd wanneer het gewenste wapen nog niet
     * actief is. De UScript-kant zoekt het wapen in de inventory op dezelfde hash en wisselt
     * via de standaard {@code PendingWeapon/PutDown}-flow (idempotent).
     *
     * <p>Fire-and-forget; default no-op voor test-sinks zonder UDP-transport.</p>
     */
    default void selectWeapon(int weaponClassHash) {}
}
