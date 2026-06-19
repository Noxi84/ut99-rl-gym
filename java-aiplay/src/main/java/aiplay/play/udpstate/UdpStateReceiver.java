package aiplay.play.udpstate;

import aiplay.play.udpstate.model.StateFrame;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Binary UDP state receiver — the live {@link StateFrameSource}. Owns the socket
 * and a per-instance dedicated reader thread; strips the 8-byte transport header,
 * delegates reassembly to {@link FrameReassembler} and decoding to
 * {@link StateFrameCodec}, then publishes the latest {@link StateFrame} via an
 * {@link AtomicReference}.
 *
 * <p>Thread safety: the reader thread is the sole writer; consumers read a
 * volatile snapshot with no locking.
 */
public final class UdpStateReceiver implements StateFrameSource {

    private static final Logger LOG = Logger.getLogger(UdpStateReceiver.class.getName());

    private static final byte MAGIC = (byte) 0xBB;
    private static final int HEADER_SIZE = 8;

    private final int listenPort;
    private final DatagramChannel channel;
    private final Thread readerThread;
    private final AtomicReference<StateFrame> latest = new AtomicReference<>();
    private final ReceiverStats stats = new ReceiverStats();
    private final FrameReassembler reassembler = new FrameReassembler(stats);
    private volatile boolean running = true;

    public UdpStateReceiver(int listenPort, String threadLabel) throws IOException {
        this.listenPort = listenPort;
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(true);
        this.channel.socket().setReceiveBufferSize(512 * 1024);
        // SO_REUSEADDR: bind even if the previous JVM's socket is still in TIME_WAIT.
        // Without this, a quick restart leaves UdpStateReceiver unable to bind for ~60s,
        // which silently breaks the affected instances for the whole session.
        this.channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
        bindWithRetry(listenPort);
        this.readerThread = new Thread(this::runLoop, "udp-state-reader-" + threadLabel);
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        LOG.info("UdpStateReceiver: listening on 127.0.0.1:" + listenPort
            + " (thread=" + readerThread.getName() + ")");
    }

    private void bindWithRetry(int port) throws IOException {
        // Retry a few times — rare case: OS still releasing port after a kill-9
        // even with SO_REUSEADDR set. Fail loudly if it still can't bind.
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                this.channel.bind(new InetSocketAddress("127.0.0.1", port));
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during bind retry", ie);
                }
            }
        }
        throw new IOException("Failed to bind UDP " + port + " after 5 attempts", last);
    }

    @Override
    public StateFrame getLatestFrame() { return latest.get(); }

    public long getPacketsReceived()   { return stats.packetsReceived.get(); }
    public long getFramesAssembled()   { return stats.framesAssembled.get(); }
    public long getPacketsDropped()    { return stats.packetsDropped.get(); }
    public long getParseErrors()       { return stats.parseErrors.get(); }
    public int  getListenPort()        { return listenPort; }

    public void close() {
        running = false;
        try { channel.close(); } catch (IOException ignore) {}
    }

    private void runLoop() {
        ByteBuffer buf = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        while (running) {
            try {
                buf.clear();
                channel.receive(buf);
                buf.flip();
                handlePacket(buf);
            } catch (IOException e) {
                if (running) LOG.log(Level.WARNING, "UdpStateReceiver: receive error", e);
            } catch (Exception e) {
                stats.parseErrors.incrementAndGet();
                if (stats.parseErrors.get() % 100 == 1) {
                    LOG.log(Level.WARNING, "UdpStateReceiver: parse error", e);
                }
            }
        }
    }

    private void handlePacket(ByteBuffer buf) {
        if (buf.remaining() < HEADER_SIZE) { stats.packetsDropped.incrementAndGet(); return; }
        byte magic = buf.get();
        if (magic != MAGIC) { stats.packetsDropped.incrementAndGet(); return; }
        int frameType = buf.get() & 0xFF;
        int frameId = buf.getShort() & 0xFFFF;
        int payloadLen = buf.getShort() & 0xFFFF;
        int packetIdx = buf.get() & 0xFF;
        int packetCount = buf.get() & 0xFF;
        if (frameType != 0 || packetCount < 1 || buf.remaining() < payloadLen) {
            stats.packetsDropped.incrementAndGet(); return;
        }
        stats.packetsReceived.incrementAndGet();

        if (packetCount == 1) {
            ByteBuffer payload = buf.slice().order(ByteOrder.LITTLE_ENDIAN);
            payload.limit(payloadLen);
            buf.position(buf.position() + payloadLen);
            tryParseAndPublish(frameId, payload);
            return;
        }
        byte[] slice = new byte[payloadLen];
        buf.get(slice);
        byte[] combined = reassembler.accept(frameId, packetIdx, packetCount, slice);
        if (combined != null) {
            tryParseAndPublish(frameId, combined);
        }
    }

    private void tryParseAndPublish(int frameId, byte[] payload) {
        tryParseAndPublish(frameId, ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN));
    }

    private void tryParseAndPublish(int frameId, ByteBuffer payload) {
        try {
            StateFrame frame = StateFrameCodec.parse(frameId, payload, System.nanoTime());
            if (frame == null) { stats.parseErrors.incrementAndGet(); return; }
            latest.set(frame);
            long count = stats.framesAssembled.incrementAndGet();
            if ((count % 600) == 1) {
                LOG.info("UdpStateReceiver[" + listenPort + "]: frames=" + count
                    + " rxPkts=" + stats.packetsReceived.get()
                    + " drops=" + stats.packetsDropped.get()
                    + " parseErr=" + stats.parseErrors.get()
                    + " fid=" + frameId
                    + " flags=" + frame.flags().size()
                    + " players=" + frame.players().size()
                    + " projectiles=" + frame.projectiles().size()
                    + " red=" + frame.mapInfo().redScore()
                    + " blue=" + frame.mapInfo().blueScore());
            }
        } catch (Exception e) {
            stats.parseErrors.incrementAndGet();
            if (stats.parseErrors.get() % 100 == 1) {
                LOG.log(Level.WARNING, "parsePayload failed", e);
            }
        }
    }
}
