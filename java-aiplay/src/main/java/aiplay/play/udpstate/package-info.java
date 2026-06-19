/**
 * Binary UDP state-receive stack: transport, multi-packet reassembly, and the
 * codec that decodes frames pushed by {@code RLUdpStateSender} inside UT99.
 *
 * <p>Pipeline (low → high):
 * <pre>
 *   RLUdpStateSender.uc (60 Hz binary UDP)
 *     → {@link aiplay.play.udpstate.UdpStateReceiver}   socket + reader thread + 8-byte header
 *     → {@link aiplay.play.udpstate.FrameReassembler}   multi-packet reassembly by frameId
 *     → {@link aiplay.play.udpstate.StateFrameCodec}    payload bytes → {@code model.StateFrame}
 *     → published via {@link aiplay.play.udpstate.StateFrameSource#getLatestFrame()}
 * </pre>
 *
 * <p>The codec is the exact dual of the UScript writer: every {@code parseX} in
 * {@link aiplay.play.udpstate.StateFrameCodec} mirrors a {@code WriteX} in
 * {@code scripts/mutator/NeuralNetWebserver/Classes/RLUdpStateSender.uc}. Keep
 * the two byte-aligned when either side changes.
 *
 * <p>{@link aiplay.play.udpstate.StateFrameSource} is the port consumers depend
 * on; {@code UdpStateReceiver} is the live adapter, but a replay-from-capture or
 * synthetic test source can be substituted without touching the converter chain.
 */
package aiplay.play.udpstate;
