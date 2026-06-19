package aiplay.scanners.executors;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PlayExecutionService {

  private static final String BASE_PACKAGE = "aiplay.scanners.executors";

  private final List<IPlayExecutor> executors;
  private final PlayContext ctx;

  public PlayExecutionService() {
    this.ctx = null;
    List<IPlayExecutor> list = scanExecutors();
    this.executors = List.copyOf(list);
  }

  public PlayExecutionService(PlayContext ctx) {
    this.ctx = ctx;
    List<IPlayExecutor> list = scanExecutors();

    // init executors (each executor picks its own logger)
    for (IPlayExecutor e : list) {
      if (ctx != null) {
        ctx.executor = e;
        ctx.executorLogger = e.getPlayExecutorLogger();
        e.init(ctx);
      }
    }
    this.executors = List.copyOf(list);
  }

  public IPlayExecutor getExecutor(String executorKey) {
    for (IPlayExecutor executor : executors) {
      if (executor.getExecutorKey().equalsIgnoreCase(executorKey)) {
        return executor;
      }
    }

    throw new IllegalArgumentException("Executor " + executorKey + " not found");
  }

  private static List<IPlayExecutor> scanExecutors() {
    List<IPlayExecutor> list = new ArrayList<IPlayExecutor>();

    try (ScanResult scan = new ClassGraph()
        .enableClassInfo()
        .enableAnnotationInfo()
        .acceptPackages(BASE_PACKAGE)
        .scan()) {

      for (ClassInfo ci : scan.getClassesWithAnnotation(PlayExecutorComponent.class.getName())) {
        Class<?> raw = ci.loadClass();

        if (!IPlayExecutor.class.isAssignableFrom(raw)) {
          continue;
        }

        @SuppressWarnings("unchecked")
        Class<? extends IPlayExecutor> clazz = (Class<? extends IPlayExecutor>) raw;

        Constructor<? extends IPlayExecutor> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        IPlayExecutor instance = ctor.newInstance();

        if (!instance.isActive()) {
          continue;
        }

        list.add(instance);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to scan executors in package: " + BASE_PACKAGE, e);
    }

    list.sort(Comparator.comparingInt(PlayExecutionService::priorityOf));
    return list;
  }

  private static int priorityOf(IPlayExecutor r) {
    PlayExecutorComponent ann = r.getClass().getAnnotation(PlayExecutorComponent.class);
    return ann != null ? ann.priority() : 100;
  }

  public int getMaxPredictionFps() {
    int max = 0;
    for (IPlayExecutor executor : executors) {
      if (executor.getPredictionFps() > max) {
        max = executor.getPredictionFps();
      }
    }
    return max;
  }
}
