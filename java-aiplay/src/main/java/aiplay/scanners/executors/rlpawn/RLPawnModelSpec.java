package aiplay.scanners.executors.rlpawn;

import aiplay.config.PropertyReaderUtils;
import aiplay.config.model.ModelConfig;
import aiplay.rl.RealtimeSequenceInputBuilder;
import aiplay.runtime.role.ModelRole;
import aiplay.runtime.role.ModelRoleRegistry;
import aiplay.scanners.executors.rlpawn.movement.MovementActionSchema;
import aiplay.scanners.feature.contract.FeatureContract;
import aiplay.scanners.feature.contract.FeatureContractRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Immutable spec voor het full-joint movement + VR + shooting model.
 * Valideert dat alle tien target_features aanwezig zijn in de exacte volgorde
 * die {@link RLPawnActionDecoder} en de shared movement decoder
 * verwachten.
 *
 * <p>Fail-fast bij {@link #loadStrict()}: missing target_feature, ongeldige
 * volgorde of ontbrekende {@code target_commitment_lock_ticks} crashen direct
 * met een diagnostic. CLAUDE.md preference: geen config-fallbacks.</p>
 */
public final class RLPawnModelSpec implements MovementActionSchema {

    private final String modelKey;
    private final int sequenceLength;
    private final int csvFps;
    private final int predictionFps;
    private final int targetCommitmentLockTicks;
    private final List<String> inputFeatures;
    private final List<String> targetFeatures;
    private final RealtimeSequenceInputBuilder inputBuilder;
    private final int sinIndex;
    private final int cosIndex;
    private final int dodgeIndex;
    private final int jumpIndex;
    private final int duckIndex;
    private final int idleIndex;
    private final int yawIndex;
    private final int pitchIndex;
    private final int fireIndex;
    private final int altFireIndex;
    private final double idleEnterThreshold;
    private final double idleExitThreshold;
    private final int idxEnemy0RelSin;
    private final int idxEnemy0RelCos;

    private RLPawnModelSpec(ModelConfig cfg, FeatureContract contract) {
        this.modelKey = cfg.modelKey();
        this.sequenceLength = cfg.sequenceLength();
        this.csvFps = Math.max(1, cfg.trainingCsv().csvFps());
        this.predictionFps = cfg.runtime().predictionFps();
        this.inputFeatures = contract.inputFeatures();
        this.targetFeatures = contract.targetFeatures();
        this.inputBuilder = new RealtimeSequenceInputBuilder(modelKey, contract);

        this.sinIndex = requireIndex(targetFeatures, "moveDir_sin");
        this.cosIndex = requireIndex(targetFeatures, "moveDir_cos");
        this.dodgeIndex = requireIndex(targetFeatures, "dodge");
        this.jumpIndex = requireIndex(targetFeatures, "bJump");
        this.duckIndex = requireIndex(targetFeatures, "bDuck");
        this.idleIndex = requireIndex(targetFeatures, "bIdle");
        this.yawIndex = requireIndex(targetFeatures, "yawDelta_norm");
        this.pitchIndex = requireIndex(targetFeatures, "pitchDelta_norm");
        this.fireIndex = requireIndex(targetFeatures, "bFire");
        this.altFireIndex = requireIndex(targetFeatures, "bAltFire");

        this.idleEnterThreshold = cfg.runtime().idleEnterThreshold();
        this.idleExitThreshold = cfg.runtime().idleExitThreshold();

        this.idxEnemy0RelSin = inputFeatures.indexOf("enemy0_relSin");
        this.idxEnemy0RelCos = inputFeatures.indexOf("enemy0_relCos");

        if (!(sinIndex == RLPawnActionDecoder.IDX_MOVE_SIN
                && cosIndex == RLPawnActionDecoder.IDX_MOVE_COS
                && dodgeIndex == RLPawnActionDecoder.IDX_DODGE
                && jumpIndex == RLPawnActionDecoder.IDX_JUMP
                && duckIndex == RLPawnActionDecoder.IDX_DUCK
                && idleIndex == RLPawnActionDecoder.IDX_IDLE
                && yawIndex == RLPawnActionDecoder.IDX_YAW
                && pitchIndex == RLPawnActionDecoder.IDX_PITCH
                && fireIndex == RLPawnActionDecoder.IDX_FIRE
                && altFireIndex == RLPawnActionDecoder.IDX_ALTFIRE)) {
            throw new IllegalStateException(modelKey
                + ": target_features volgorde mismatch met full-joint ONNX export. "
                + "Verwacht [moveDir_sin, moveDir_cos, dodge, bJump, bDuck, bIdle, "
                + "yawDelta_norm, pitchDelta_norm, bFire, bAltFire]; kreeg "
                + targetFeatures);
        }

        this.targetCommitmentLockTicks = loadCommitmentLockTicks(modelKey);
    }

    public static RLPawnModelSpec loadStrict() {
        ModelConfig cfg = ModelRoleRegistry.shared().resolve(ModelRole.PAWN_POLICY);
        return new RLPawnModelSpec(
            cfg,
            FeatureContractRepository.shared().get(cfg.modelKey())
        );
    }

    public static Optional<RLPawnModelSpec> loadOptional() {
        return ModelRoleRegistry.shared().resolveOptional(ModelRole.PAWN_POLICY)
            .map(cfg -> new RLPawnModelSpec(
                cfg,
                FeatureContractRepository.shared().get(cfg.modelKey())
            ));
    }

    public String modelKey()        { return modelKey; }
    public int sequenceLength()     { return sequenceLength; }
    public int csvFps()             { return csvFps; }
    public int predictionFps()      { return predictionFps; }
    public int targetCommitmentLockTicks() { return targetCommitmentLockTicks; }
    public List<String> inputFeatures()    { return inputFeatures; }
    public List<String> targetFeatures()   { return targetFeatures; }
    @Override
    public List<String> targetOrder()      { return targetFeatures; }
    public RealtimeSequenceInputBuilder inputBuilder() { return inputBuilder; }
    @Override
    public int sinIndex()           { return sinIndex; }
    @Override
    public int cosIndex()           { return cosIndex; }
    @Override
    public int dodgeIndex()         { return dodgeIndex; }
    @Override
    public int jumpIndex()          { return jumpIndex; }
    @Override
    public int duckIndex()          { return duckIndex; }
    @Override
    public int idleIndex()          { return idleIndex; }
    public int yawIndex()           { return yawIndex; }
    public int pitchIndex()         { return pitchIndex; }
    @Override
    public int fireIndex()          { return fireIndex; }
    @Override
    public int altFireIndex()       { return altFireIndex; }
    @Override
    public double idleEnterThreshold() { return idleEnterThreshold; }
    @Override
    public double idleExitThreshold()  { return idleExitThreshold; }
    public int idxEnemy0RelSin()    { return idxEnemy0RelSin; }
    public int idxEnemy0RelCos()    { return idxEnemy0RelCos; }

    private static int requireIndex(List<String> features, String name) {
        int idx = features.indexOf(name);
        if (idx < 0) {
            throw new IllegalStateException(
                "rl_pawn: target_features mist vereist veld: " + name
                    + ". Beschikbaar: " + features);
        }
        return idx;
    }

    /**
     * Lees {@code target_commitment_lock_ticks} strict uit
     * {@code /models/<modelKey>/runtime}. Geen fallback — CLAUDE.md preference.
     */
    private static int loadCommitmentLockTicks(String modelKey) {
        JsonNode runtime = PropertyReaderUtils.getSubtree("/models/" + modelKey + "/runtime");
        if (runtime == null || runtime.isMissingNode()) {
            throw new IllegalStateException(modelKey
                + ": missing /models/" + modelKey + "/runtime — kan target_commitment_lock_ticks niet resolven");
        }
        JsonNode node = runtime.get("target_commitment_lock_ticks");
        if (node == null || node.isMissingNode() || !node.isNumber()) {
            throw new IllegalStateException(modelKey
                + "/runtime.json: missing required integer 'target_commitment_lock_ticks'. "
                + "Geen silent default (CLAUDE.md no-fallback preference). "
                + "Aanbevolen waarde: 12 ticks ≈ 400ms @ 30 Hz (vr-shooting-sac-merge.md sectie 4.3).");
        }
        int v = node.asInt();
        if (v < 0) {
            throw new IllegalStateException(modelKey
                + "/runtime.json: target_commitment_lock_ticks moet >= 0 (kreeg " + v + ")");
        }
        return v;
    }
}
