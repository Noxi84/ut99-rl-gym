package aiplay.rl.rewards.catalog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markeert een {@link RewardModule}-implementatie zodat {@link RewardModules} haar via
 * classpath-scanning automatisch oppikt — geen handmatige registratie in een centrale lijst meer.
 * Zelfde zelf-registrerende patroon als {@code @TrainingFeatureComponent} voor de feature-resolvers.
 *
 * <p>Bewust een marker zonder elementen: de reward-<em>volgorde</em> komt niet uit een per-module
 * priority maar uit {@link RewardId#ordinal()} (de enum is de single source of truth). De
 * geannoteerde class moet {@link RewardModule} implementeren en een no-arg constructor hebben;
 * {@link RewardModules} valideert bij class-load dat er exact één module per {@link RewardId} is.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RewardModuleComponent {}
