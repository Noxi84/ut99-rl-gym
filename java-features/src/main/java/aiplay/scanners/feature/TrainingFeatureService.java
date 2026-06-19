package aiplay.scanners.feature;

import aiplay.dto.GameStateDto;
import aiplay.scanners.feature.contract.RealTimeFeatureEnricher;
import aiplay.ut99webmodel.GameState;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class TrainingFeatureService implements RealTimeFeatureEnricher {

    private static final String BASE_PACKAGE = "aiplay.scanners.feature.resolver";

    private static volatile TrainingFeatureService SHARED;

    private final List<ITrainingFeature> resolvers;
    private final List<JsonConverterBinding> jsonConverters;
    private final List<IncrementalBinding> incrementalBindings;
    private final Map<String, ITrainingFeature> featureIndex;
    private final Set<String> booleanFeatures;

    public static TrainingFeatureService shared() {
        TrainingFeatureService s = SHARED;
        if (s == null) {
            synchronized (TrainingFeatureService.class) {
                s = SHARED;
                if (s == null) {
                    s = new TrainingFeatureService();
                    SHARED = s;
                }
            }
        }
        return s;
    }

    public TrainingFeatureService() {
        this.resolvers = List.copyOf(scanResolvers());
        this.jsonConverters = buildJsonConverters(resolvers);
        this.incrementalBindings = buildIncrementalBindings(resolvers);
        this.featureIndex = buildFeatureIndex(resolvers);
        this.booleanFeatures = buildBooleanFeatureSet(resolvers);
    }

    private record JsonConverterBinding(
            ITrainingFeature feature,
            TrainingFeatureJsonToDtoConverter converter) {}

    private record IncrementalBinding(
            ITrainingFeature feature,
            TrainingFeatureEnricher enricher,
            TrainingFeatureLogger logger) {}

    private static List<JsonConverterBinding> buildJsonConverters(List<ITrainingFeature> resolvers) {
        List<JsonConverterBinding> out = new ArrayList<>();
        for (ITrainingFeature r : resolvers) {
            TrainingFeatureJsonToDtoConverter converter = r.getTrainingFeatureJsonToDtoConverter();
            if (converter != null) {
                out.add(new JsonConverterBinding(r, converter));
            }
        }
        return List.copyOf(out);
    }

    private static List<IncrementalBinding> buildIncrementalBindings(List<ITrainingFeature> resolvers) {
        List<IncrementalBinding> out = new ArrayList<>();
        for (ITrainingFeature r : resolvers) {
            TrainingFeatureEnricher enricher = r.getTrainingFeatureEnricher();
            TrainingFeatureLogger logger = r.getTrainingFeatureLogger();
            if (enricher != null || logger != null) {
                out.add(new IncrementalBinding(r, enricher, logger));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, ITrainingFeature> buildFeatureIndex(List<ITrainingFeature> resolvers) {
        Map<String, ITrainingFeature> idx = new HashMap<>();
        for (ITrainingFeature r : resolvers) {
            for (String fid : r.getFeatureIds()) {
                idx.putIfAbsent(fid, r);
            }
        }
        return idx;
    }

    private static Set<String> buildBooleanFeatureSet(List<ITrainingFeature> resolvers) {
        Set<String> set = new HashSet<>();
        for (ITrainingFeature r : resolvers) {
            set.addAll(r.getBooleanFeatures());
        }
        return set;
    }
    // TODO: alle scanners moeten ook rekening gaan houden met individuele priorities en niet naar de generieke priority op het component

    private static List<ITrainingFeature> scanResolvers() {
        List<ITrainingFeature> list = new ArrayList<ITrainingFeature>();

        try (ScanResult scan = new ClassGraph()
                .enableClassInfo()
                .enableAnnotationInfo()
                .acceptPackages(BASE_PACKAGE)
                .scan()) {

            for (ClassInfo ci : scan.getClassesWithAnnotation(TrainingFeatureComponent.class.getName())) {
                Class<?> raw = ci.loadClass();

                if (!ITrainingFeature.class.isAssignableFrom(raw)) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                Class<? extends ITrainingFeature> clazz = (Class<? extends ITrainingFeature>) raw;

                Constructor<? extends ITrainingFeature> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                ITrainingFeature instance = ctor.newInstance();
                list.add(instance);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan feature resolvers in package: " + BASE_PACKAGE, e);
        }

        list.sort(Comparator.comparingInt(TrainingFeatureService::priorityOf));
        return list;
    }

    private static int priorityOf(ITrainingFeature r) {
        TrainingFeatureComponent ann = r.getClass().getAnnotation(TrainingFeatureComponent.class);
        return ann != null ? ann.priority() : 100;
    }

    public GameStateDto createGameStateDtoFromJsonSession(String sessionId, GameState gs) {
        GameStateDto dto = new GameStateDto();
        for (JsonConverterBinding binding : jsonConverters) {
            try {
                binding.converter.enrichAll(sessionId, gs, dto);
            } catch (RuntimeException ex) {
                logResolverExceptionToSameFeatureLog(
                    sessionId, "REALTIME_JSON", binding.feature, "enrichAll", null, ex);
                throw ex;
            }
        }
        return dto;
    }

    public float resolveFeatureValueForRealTimePlay(String sessionId, String modelKey, GameStateDto f, String featureId) {
        ITrainingFeature r = featureIndex.get(featureId);
        if (r == null) {
            throw new IllegalStateException("Feature '" + featureId + "' is (nog) niet geimplementeerd in TrainingFeatureComponent.");
        }
        try {
            return r.getFeatureValue(sessionId, modelKey, featureId, f).floatValue();
        } catch (RuntimeException ex) {
            logResolverExceptionToSameFeatureLog(sessionId, "REALTIME_VALUE", r, featureId, modelKey, ex);
            throw ex;
        }
    }

    public Float resolveCsvWriterFeatureValue(String modelKey, String sessionId, String featureId, List<GameStateDto> gameStates, GameStateDto current) {
        ITrainingFeature r = featureIndex.get(featureId);
        if (r == null) {
            throw new IllegalStateException("Feature '" + featureId + "' is (nog) niet geimplementeerd in TrainingFeatureComponent.");
        }
        try {
            return r.resolveCsvWriterFeatureValue(modelKey, sessionId, featureId, gameStates, current);
        } catch (RuntimeException ex) {
            logResolverExceptionToSameFeatureLog(sessionId, "CSV_VALUE", r, featureId, modelKey, ex);
            throw ex;
        }
    }

    public void enrichBatchForCsvWriter(String sessionId, String modelKey, List<GameStateDto> frames) {
        for (ITrainingFeature r : resolvers) {
            r.enrichBatchFramesForCsvWriter(sessionId, modelKey, frames);

            TrainingFeatureLogger logger = r.getTrainingFeatureLogger();
            if (logger != null) {
                logger.onEnrichBatch(sessionId, modelKey, r, frames);
            }
        }
    }

    public void enrichIncrementalForRealTimePlay(String sessionId, String modelKey, List<GameStateDto> frames) {
        List<GameStateDto> pending = pendingRealtimeFrames(frames);
        if (pending.isEmpty()) {
            return;
        }

        for (IncrementalBinding binding : incrementalBindings) {
            if (binding.enricher != null) {
                binding.enricher.enrichIncremental(sessionId, pending);
                if (binding.logger != null && binding.feature.isFeatureDebugEnabled()) {
                    binding.logger.onEnrichIncremental(sessionId, modelKey, binding.feature, pending);
                }
            }

            if (binding.logger != null) {
                binding.logger.onEnrichIncremental(sessionId, modelKey, binding.feature, pending);
            }
        }

        markRealtimeEnriched(pending);
    }

    private static List<GameStateDto> pendingRealtimeFrames(List<GameStateDto> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<GameStateDto> pending = null;
        for (GameStateDto frame : frames) {
            if (frame == null || frame.realtimeIncrementalEnriched) {
                continue;
            }
            if (pending == null) {
                pending = new ArrayList<>(frames.size());
            }
            pending.add(frame);
        }
        return pending != null ? pending : List.of();
    }

    private static void markRealtimeEnriched(List<GameStateDto> frames) {
        for (GameStateDto frame : frames) {
            if (frame != null) {
                frame.realtimeIncrementalEnriched = true;
            }
        }
    }

    /**
     * Booleans formatteren we als 0/1.
     */
    public boolean isBooleanFeature(String featureName) {
        return booleanFeatures.contains(featureName);
    }

    public Set<String> getRegisteredFeatureIds() {
        return java.util.Collections.unmodifiableSet(featureIndex.keySet());
    }

    /**
     * Geeft per feature-ID de naam van het owning component (simpele klassenaam).
     * Dit is de eerste component die het feature claimt (op priority-volgorde).
     */
    public Map<String, String> getFeatureOwnership() {
        Map<String, String> ownership = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ITrainingFeature> entry : featureIndex.entrySet()) {
            ownership.put(entry.getKey(), entry.getValue().getClass().getSimpleName());
        }
        return java.util.Collections.unmodifiableMap(ownership);
    }

    /**
     * Detecteert features die door meerdere componenten worden geclaimd.
     * Retourneert alleen features met meer dan één eigenaar.
     */
    public Map<String, List<String>> getDuplicateFeatures() {
        Map<String, List<String>> all = new java.util.LinkedHashMap<>();
        for (ITrainingFeature r : resolvers) {
            String name = r.getClass().getSimpleName();
            for (String fid : r.getFeatureIds()) {
                all.computeIfAbsent(fid, k -> new ArrayList<>()).add(name);
            }
        }
        Map<String, List<String>> duplicates = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : all.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return duplicates;
    }

    private static void logResolverExceptionToSameFeatureLog(String sessionId,
                                                             String phase,
                                                             ITrainingFeature resolver,
                                                             String featureId,
                                                             String modelKeyOrNull,
                                                             RuntimeException ex) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        if (resolver == null) {
            return;
        }

        TrainingFeatureLogger logger = resolver.getTrainingFeatureLogger();
        if (logger == null) {
            return;
        }

        String modelKey = (modelKeyOrNull == null || modelKeyOrNull.trim().isEmpty()) ? "n/a" : modelKeyOrNull;

        String line = "EX phase=" + phase
                + " modelKey=" + modelKey
                + " feature=" + featureId
                + " component=" + resolver.getClass().getName()
                + " ex=" + ex.getClass().getName()
                + " msg=" + TrainingFeatureLogger.safeMsg(ex.getMessage());

        logger.logLine(sessionId, Level.WARNING, line);
    }
}
