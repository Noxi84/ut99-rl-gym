package aiplay.runtime.controlplane;

/**
 * One machine in the cluster. Parsed from the canonical server inventory.
 */
public record MachineRecord(
    String machineId,
    String hostname,
    String user,
    boolean hasCuda,
    int bcTrainerSlots,
    int sacTrainerSlots,
    int csvWriterSlots,
    int gpuInstances,
    int cpuInstances,
    int displayBase,
    int webPortBase,
    int gamePortBase,
    int gamePortStep,
    double gameSpeed,
    String gameStyle
) {
    public int totalInstances() {
        return gpuInstances + cpuInstances;
    }

  public int totalTrainerSlots() {
    return bcTrainerSlots + sacTrainerSlots;
  }

    public boolean isTrainer() {
      return bcTrainerSlots > 0 || sacTrainerSlots > 0;
    }

    public boolean canTrain() {
      return isTrainer() && hasCuda;
    }

    public boolean canRunBots() {
        return totalInstances() > 0;
    }

    public boolean canWriteCsv() {
        return csvWriterSlots > 0;
    }
}
