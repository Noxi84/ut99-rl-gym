package aiplay.play;

import aiplay.instance.InstanceConfig;
import aiplay.runtime.port.CommandSink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Binary UDP command sender — replaces HTTP POST for the per-tick action hot path.
 *
 * <p>Serialises a 12-byte packet per call and sends it fire-and-forget to the
 * configured RLUdpCommandReceiver (bound inside the UT99 server). Packet format:
 *
 * <pre>
 *   [0]     magic    = 0xAA
 *   [1]     botIdx   (0..7, matches UScript RLBots[] slot)
 *   [2]     flags    (bit0..7 = fwd,back,left,right,jump,duck,fire,altfire)
 *   [3]     dodge    (0..8)
 *   [4-5]   yaw      uint16 LE
 *   [6-7]   pitch    uint16 LE
 *   [8-9]   moveYaw  uint16 LE
 *   [10-11] seq      uint16 LE
 * </pre>
 *
 * <p>Suicide-commands gebruiken een aparte 2-byte variant:
 *
 * <pre>
 *   [0] magic  = 0xAB
 *   [1] botIdx (0..7)
 * </pre>
 *
 * <p>Select-weapon-commands gebruiken een aparte 6-byte variant (edge-triggered,
 * niet op de per-tick hot-path):
 *
 * <pre>
 *   [0]   magic = 0xAC
 *   [1]   botIdx (0..7)
 *   [2-5] weaponClassHash int32 LE (FNV-1a van de UT99 class-string)
 * </pre>
 *
 * <p>Single shared non-blocking DatagramChannel per JVM. {@code channel.send()}
 * is thread-safe so all bots share one socket.
 */
public class UdpCommandSender implements CommandSink {

    private static final Logger LOG = Logger.getLogger(UdpCommandSender.class.getName());

    private static final byte MAGIC = (byte) 0xAA;
    private static final byte MAGIC_SUICIDE = (byte) 0xAB;
    private static final byte MAGIC_SELECT_WEAPON = (byte) 0xAC;
    private static final int PACKET_SIZE = 12;
    private static final int SUICIDE_PACKET_SIZE = 2;
    private static final int SELECT_WEAPON_PACKET_SIZE = 6;

    private static final DatagramChannel SHARED_CHANNEL;
    static {
        try {
            SHARED_CHANNEL = DatagramChannel.open();
            SHARED_CHANNEL.configureBlocking(false);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Per-thread reusable buffer avoids allocation on the hot path.
    private static final ThreadLocal<ByteBuffer> TL_BUFFER =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN));

    private final InetSocketAddress target;
    private final int botIdx;
    private final AtomicInteger seq = new AtomicInteger(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    public UdpCommandSender(InstanceConfig config, int botIdx) {
        int port = config.getUdpListenPort();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(
                "UDP listen port must be in [1,65535]; got " + port);
        }
        if (botIdx < 0 || botIdx > 255) {
            throw new IllegalArgumentException("botIdx must be in [0,255]; got " + botIdx);
        }
        this.target = new InetSocketAddress("127.0.0.1", port);
        this.botIdx = botIdx;
    }

    @Override
    public void sendCommand(boolean fwd, boolean back, boolean left, boolean right,
                            boolean jump, boolean duck, boolean fire, boolean altFire,
                            int dodge, int yaw, int pitch, int moveYaw) {
        int flags = 0;
        if (fwd)     flags |= 0x01;
        if (back)    flags |= 0x02;
        if (left)    flags |= 0x04;
        if (right)   flags |= 0x08;
        if (jump)    flags |= 0x10;
        if (duck)    flags |= 0x20;
        if (fire)    flags |= 0x40;
        if (altFire) flags |= 0x80;

        int s = seq.incrementAndGet() & 0xFFFF;

        ByteBuffer buf = TL_BUFFER.get();
        buf.clear();
        buf.put(MAGIC);
        buf.put((byte) (botIdx & 0xFF));
        buf.put((byte) (flags & 0xFF));
        buf.put((byte) (dodge & 0xFF));
        buf.putShort((short) (yaw & 0xFFFF));
        buf.putShort((short) (pitch & 0xFFFF));
        buf.putShort((short) (moveYaw & 0xFFFF));
        buf.putShort((short) s);
        buf.flip();

        try {
            SHARED_CHANNEL.send(buf, target);
        } catch (IOException e) {
            long c = errorCount.getAndIncrement();
            if (c % 1000 == 0) {
                LOG.warning("UDP_SEND_FAIL target=" + target + " bot=" + botIdx
                    + " err=" + e.getMessage());
            }
        }
    }

    @Override
    public void sendSuicide() {
        ByteBuffer buf = ByteBuffer.allocate(SUICIDE_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC_SUICIDE);
        buf.put((byte) (botIdx & 0xFF));
        buf.flip();

        try {
            SHARED_CHANNEL.send(buf, target);
            LOG.info("UDP_SUICIDE_SENT target=" + target + " bot=" + botIdx);
        } catch (IOException e) {
            LOG.warning("UDP_SUICIDE_FAIL target=" + target + " bot=" + botIdx
                + " err=" + e.getMessage());
        }
    }

    @Override
    public void selectWeapon(int weaponClassHash) {
        ByteBuffer buf = ByteBuffer.allocate(SELECT_WEAPON_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC_SELECT_WEAPON);
        buf.put((byte) (botIdx & 0xFF));
        buf.putInt(weaponClassHash);
        buf.flip();

        try {
            SHARED_CHANNEL.send(buf, target);
        } catch (IOException e) {
            long c = errorCount.getAndIncrement();
            if (c % 1000 == 0) {
                LOG.warning("UDP_SELECT_WEAPON_FAIL target=" + target + " bot=" + botIdx
                    + " err=" + e.getMessage());
            }
        }
    }
}
