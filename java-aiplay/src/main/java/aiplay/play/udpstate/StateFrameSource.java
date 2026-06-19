package aiplay.play.udpstate;

import aiplay.play.udpstate.model.StateFrame;

/**
 * Port for the latest decoded UT99 state frame. The runtime consumes frames
 * through this seam without knowing the transport.
 *
 * <p>Live adapter: {@link UdpStateReceiver} (binary UDP from {@code RLUdpStateSender}).
 * A replay-from-capture or synthetic test source can implement the same contract.
 */
public interface StateFrameSource {

    /**
     * The most recently published frame, or {@code null} before the first frame
     * has been received. Returns a volatile snapshot with no locking.
     */
    StateFrame getLatestFrame();
}
