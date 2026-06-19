package aiplay.instance;

/**
 * Per-bot-instance configuration. In multi-instance mode, each BehaviorTree gets its own
 * InstanceConfig on the Blackboard. In single-instance mode (legacy), this is null and
 * the code falls back to System.getProperty / env vars.
 */
public final class InstanceConfig {

  private final int instanceId;
  private final String displayName;
  private final int serverPort;
  private final int uwebListenPort;
  private final int udpListenPort;
  private final int stateUdpListenPort;
  private final boolean useGpu;
  private final int gpuInstances;
  private final int cpuInstances;
  private final int totalInstances;

  // Mutable: RunUT99ServerActionNode may choose a different port at runtime.
  private volatile int actualUwebPort;

  public InstanceConfig(int instanceId, String displayName, int serverPort, int uwebListenPort,
                        int udpListenPort, int stateUdpListenPort,
                        boolean useGpu, int gpuInstances, int cpuInstances) {
    this.instanceId = instanceId;
    this.displayName = displayName;
    this.serverPort = serverPort;
    this.uwebListenPort = uwebListenPort;
    this.actualUwebPort = uwebListenPort;
    this.udpListenPort = udpListenPort;
    this.stateUdpListenPort = stateUdpListenPort;
    this.useGpu = useGpu;
    this.gpuInstances = gpuInstances;
    this.cpuInstances = cpuInstances;
    this.totalInstances = gpuInstances + cpuInstances;
  }

  public int getInstanceId() {
    return instanceId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public boolean isUseGpu() {
    return useGpu;
  }

  /** 1-based index within the GPU or CPU group. */
  public int getGroupIndex() {
    return useGpu ? instanceId + 1 : instanceId - gpuInstances + 1;
  }

  /** Total instances in this instance's group (GPU or CPU). */
  public int getGroupSize() {
    return useGpu ? gpuInstances : cpuInstances;
  }

  public int getTotalInstances() {
    return totalInstances;
  }

  public int getServerPort() {
    return serverPort;
  }

  public int getUwebListenPort() {
    return actualUwebPort;
  }

  public void setActualUwebPort(int port) {
    this.actualUwebPort = port;
  }

  public int getUdpListenPort() {
    return udpListenPort;
  }

  public int getStateUdpListenPort() {
    return stateUdpListenPort;
  }

  public String getNeuralNetUrl() {
    return "http://127.0.0.1:" + actualUwebPort + "/utneuralnet/";
  }

  @Override
  public String toString() {
    return "Instance[" + instanceId + " display=" + displayName
        + " game=" + serverPort + " web=" + actualUwebPort
        + " udp=" + udpListenPort + " stateUdp=" + stateUdpListenPort
        + " gpu=" + useGpu + "]";
  }
}
