package aiplay.runtime.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Parses the canonical server inventory into a collection of {@link MachineRecord}s.
 */
public final class MachineInventory {

    private static final Logger LOG = Logger.getLogger(MachineInventory.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Comparator<MachineRecord> PRIMARY_TRAINER_ORDER =
        Comparator.comparingInt(MachineRecord::totalTrainerSlots).reversed()
            .thenComparing(Comparator.comparingInt(MachineRecord::gpuInstances).reversed())
            .thenComparing(MachineRecord::machineId, String.CASE_INSENSITIVE_ORDER);

    private final List<MachineRecord> machines;

    private MachineInventory(List<MachineRecord> machines) {
        this.machines = List.copyOf(machines);
    }

    public static MachineInventory load() {
        File jsonFile = findServersJson();
        if (jsonFile != null) {
            return loadJson(jsonFile);
        }
        LOG.warning("canonical server inventory not found — empty machine inventory");
        return new MachineInventory(List.of());
    }

    public static MachineInventory loadJson(File jsonFile) {
        List<MachineRecord> list = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(jsonFile);
            JsonNode machines = root.path("machines");
            if (!machines.isArray()) {
                LOG.warning("servers.json missing 'machines' array: " + jsonFile.getAbsolutePath());
                return new MachineInventory(List.of());
            }
            for (JsonNode machine : machines) {
                MachineRecord record = parseJsonMachine(machine);
                if (record != null) list.add(record);
            }
        } catch (IOException e) {
            LOG.warning("Failed to read servers.json: " + e.getMessage());
        }
        return new MachineInventory(list);
    }

    public List<MachineRecord> all() {
        return machines;
    }

    public Optional<MachineRecord> byId(String machineId) {
        return machines.stream().filter(m -> m.machineId().equalsIgnoreCase(machineId)).findFirst();
    }

    public Optional<MachineRecord> trainer() {
        return primaryTrainer();
    }

    public Optional<MachineRecord> primaryTrainer() {
        return machines.stream().filter(MachineRecord::isTrainer).sorted(PRIMARY_TRAINER_ORDER).findFirst();
    }

    public List<MachineRecord> botRunners() {
        return machines.stream().filter(MachineRecord::canRunBots).toList();
    }

    public void logSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌─ Machine Inventory ───────────────────────┐\n");
        for (MachineRecord m : machines) {
            sb.append("│  ").append(String.format("%-8s", m.machineId()))
              .append(" ").append(String.format("%-30s", m.hostname()))
              .append(" gpu=").append(m.gpuInstances())
              .append(" cpu=").append(m.cpuInstances())
                .append(m.bcTrainerSlots() > 0 ? " bc_train=" + m.bcTrainerSlots() : "")
                .append(m.sacTrainerSlots() > 0 ? " sac_train=" + m.sacTrainerSlots() : "")
              .append(m.hasCuda() ? " [cuda]" : "")
              .append(m.csvWriterSlots() > 0 ? " csv=" + m.csvWriterSlots() : "")
              .append("\n");
        }
        sb.append("└───────────────────────────────────────────┘");
        LOG.info(sb.toString());
    }

    // ── parsing ──

    private static MachineRecord parseJsonMachine(JsonNode machine) {
        try {
            JsonNode ssh = requireObject(machine, "ssh");
            JsonNode capacity = requireObject(machine, "capacity");
            JsonNode roles = requireObject(machine, "roles");
            JsonNode ports = requireObject(machine, "ports");
            JsonNode gameplay = requireObject(machine, "gameplay");

            String machineId = requireText(machine, "machine_id");
            String hostname = requireText(machine, "hostname");
            String user = requireText(ssh, "user");

            int gpuInstances = requireInt(capacity, "gpu_instances");
            int cpuInstances = requireInt(capacity, "cpu_instances");
            boolean cudaEnabled = requireBoolean(capacity, "cuda_enabled");
          int bcTrainerSlots = parseTrainerSlots(roles.get("bc_trainer_slots"), "bc_trainer_slots");
          int sacTrainerSlots = parseTrainerSlots(roles.get("sac_trainer_slots"), "sac_trainer_slots");
            int csvWriterSlots = requireInt(roles, "csv_writer_slots");
            int displayBase = requireInt(ports, "display_base");
            int webPortBase = requireInt(ports, "web_port_base");
            int gamePortBase = requireInt(ports, "game_port_base");
            int gamePortStep = requireInt(ports, "game_port_step");
            double gameSpeed = requireDouble(gameplay, "speed");
            String gameStyle = requireText(gameplay, "style");

          return new MachineRecord(machineId, hostname, user, cudaEnabled, bcTrainerSlots,
              sacTrainerSlots, csvWriterSlots, gpuInstances, cpuInstances, displayBase, webPortBase,
                gamePortBase, gamePortStep, gameSpeed, gameStyle);
        } catch (Exception e) {
            LOG.warning("Failed to parse servers.json machine: " + e.getMessage());
            return null;
        }
    }

  private static int parseTrainerSlots(JsonNode raw, String fieldName) {
        if (raw == null || raw.isNull()) {
          throw new IllegalArgumentException(fieldName + " missing");
        }
        if (raw.isBoolean()) {
            return raw.booleanValue() ? 1 : 0;
        }
        if (raw.isInt()) {
            return raw.intValue();
        }
        if (raw.isTextual()) {
            String lower = raw.asText().trim().toLowerCase(Locale.ROOT);
            return switch (lower) {
                case "true", "yes", "on" -> 1;
                case "false", "no", "off", "" -> 0;
                default -> Integer.parseInt(lower);
            };
        }
    throw new IllegalArgumentException(fieldName + " must be integer or boolean");
    }

    private static JsonNode requireObject(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return child;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return child.asText();
    }

    private static int requireInt(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return child.intValue();
    }

    private static double requireDouble(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        return child.doubleValue();
    }

    private static boolean requireBoolean(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return child.booleanValue();
    }

    private static File findServersJson() {
        String projectRoot = System.getProperty("user.dir");
        File f = new File(projectRoot, "resources/config/servers.json");
        if (f.exists()) return f;
        f = new File(projectRoot, "../resources/config/servers.json");
        if (f.exists()) return f;
        String envRoot = System.getenv("UT99_PROJECT_ROOT");
        if (envRoot != null) {
            f = new File(envRoot, "resources/config/servers.json");
            if (f.exists()) return f;
        }
        return null;
    }
}
