package aiplay.rl.rewards.catalog;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry van alle {@link RewardModule}-implementaties, ontdekt via classpath-scanning: elke class
 * met {@link RewardModuleComponent} wordt automatisch opgepikt — geen handmatig onderhouden lijst
 * meer. Een nieuwe reward toevoegen vereist alleen nog de module annoteren (plus haar
 * {@link RewardId}-enumwaarde en {@code *Params}/{@code *Reward}-klassen); deze klasse hoeft niet
 * meer aangeraakt te worden.
 *
 * <p>De resulterende volgorde is <em>niet</em> de (niet-deterministische) scan-volgorde maar de
 * {@link RewardId}-enumvolgorde ({@link RewardId#ordinal()}). De enum is de single source of truth
 * voor de reward-ordening, zodat de catalog-iteratie en de floating-point reward-sommatie in
 * {@code RewardComputer} hun bestaande, deterministische volgorde behouden.
 *
 * <p>Een class-load guard verifieert dat elke {@link RewardId} exact één module heeft — een
 * ontbrekende annotatie of dubbele {@link RewardId} crasht bij class-load (no-fallback).
 * {@code EndgameUrgency} valt buiten deze set: het is geen {@link RewardBlock} en wordt top-level
 * geparsed door {@code JsonRewardCatalog}.
 */
public final class RewardModules {

  private static final String BASE_PACKAGE = "aiplay.rl.rewards";

  private static final List<RewardModule<?>> ALL = scanAndValidate();

  private RewardModules() {}

  /** Alle modules in {@link RewardId}-enumvolgorde — exact één per {@link RewardId}. */
  public static List<RewardModule<?>> all() {
    return ALL;
  }

  private static List<RewardModule<?>> scanAndValidate() {
    List<RewardModule<?>> modules = scan();
    modules.sort(Comparator.comparingInt((RewardModule<?> m) -> m.id().ordinal()));
    validateExactlyOnePerId(modules);
    return List.copyOf(modules);
  }

  private static List<RewardModule<?>> scan() {
    List<RewardModule<?>> modules = new ArrayList<>();
    try (ScanResult scan = new ClassGraph()
        .enableClassInfo()
        .enableAnnotationInfo()
        .acceptPackages(BASE_PACKAGE)
        .scan()) {
      for (ClassInfo ci : scan.getClassesWithAnnotation(RewardModuleComponent.class.getName())) {
        Class<?> raw = ci.loadClass();
        if (!RewardModule.class.isAssignableFrom(raw)) {
          throw new IllegalStateException(
              "@RewardModuleComponent staat op een class die geen RewardModule is: " + raw.getName());
        }
        modules.add(instantiate(raw));
      }
    }
    if (modules.isEmpty()) {
      throw new IllegalStateException(
          "Geen enkele @RewardModuleComponent gevonden in package '" + BASE_PACKAGE
              + "' — classpath-scan mislukt of alle annotaties ontbreken.");
    }
    return modules;
  }

  private static RewardModule<?> instantiate(Class<?> raw) {
    try {
      Constructor<?> ctor = raw.getDeclaredConstructor();
      ctor.setAccessible(true);
      return (RewardModule<?>) ctor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "RewardModule " + raw.getName() + " mist een toegankelijke no-arg constructor", e);
    }
  }

  private static void validateExactlyOnePerId(List<RewardModule<?>> modules) {
    Map<RewardId, RewardModule<?>> byId = new EnumMap<>(RewardId.class);
    for (RewardModule<?> m : modules) {
      if (byId.put(m.id(), m) != null) {
        throw new IllegalStateException("Dubbele RewardModule geregistreerd voor id " + m.id());
      }
    }
    for (RewardId id : RewardId.values()) {
      if (!byId.containsKey(id)) {
        throw new IllegalStateException(
            "Geen RewardModule met @RewardModuleComponent voor RewardId " + id);
      }
    }
  }
}
