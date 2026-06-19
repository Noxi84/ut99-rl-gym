package aiplay.play.udpstate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable counters shared between {@link UdpStateReceiver} (transport + parse)
 * and {@link FrameReassembler} (reassembly drops). Package-private fields;
 * read by the receiver's getters and periodic log.
 */
final class ReceiverStats {
    final AtomicLong packetsReceived = new AtomicLong();
    final AtomicLong framesAssembled = new AtomicLong();
    final AtomicLong packetsDropped  = new AtomicLong();
    final AtomicLong parseErrors     = new AtomicLong();
}
