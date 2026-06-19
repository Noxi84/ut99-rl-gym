package aiplay.scanners.feature.resolver.weaponidentity;

import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.scanners.feature.TrainingFeatureValueResolver;
import aiplay.util.WeaponNameCanonicalizer;

import java.util.Map;

/**
 * Resolver voor de {@code self_weapon_is<X>} one-hot features (14 UT99 wapens).
 *
 * <p>Leest {@link PlayerDto#weaponClass} (bv. {@code "Botpack.PulseGun"} of
 * {@code "NeuralNetWebserver.RLPulseGun"}), canonicaliseert dezelfde manier als
 * de Python {@code _canonical_weapon} in {@code player_scores_eval.py}, en zet
 * exact één bit op 1.0 (de actieve wapen-feature). Voor de "none"-bucket
 * (geen wapen) zijn alle bits 0.
 *
 * <p>Gebruikt door:
 * <ul>
 *   <li><b>Probe stratificatie</b> ({@code strata.py active_weapon_<X>}) —
 *       classifieert validatie-samples op het wapen dat de bot droeg, zodat
 *       de probe per-weapon collapse kan detecteren (bv. pitch_spread
 *       collapse bij hitscan-wapens zoals PulseGun).</li>
 *   <li><b>Policy conditioning</b> — wapen-conditional gedrag (pulse-aim ≠
 *       rocket-aim ≠ sniper-aim), zonder dat het model dit hoeft af te leiden
 *       uit projectile-types (die niet 1-op-1 mappen, bv. hitscan wapens
 *       hebben geen projectile).</li>
 * </ul>
 */
public class WeaponIdentityFeatureValueResolver implements TrainingFeatureValueResolver {

    /**
     * Mapping feature-ID → canonical wapen-key. De Python parser canonicaliseert
     * weaponClass naar dezelfde keys voor per-weapon DeltaGate KPI attribution,
     * zodat dezelfde wapen-identity beide systemen voedt (consistent stratum
     * in probes + consistent baseline-key in baseline.json per_weapon_baselines).
     *
     * <p>14 wapens uit de officiële trainings-curriculum lijst (zonder
     * ChainSaw — niet relevant voor competitive play). Enforcer en
     * DoubleEnforcer zijn afzonderlijke wapens: stock UT99 gebruikt
     * dezelfde {@code Botpack.Enforcer} class met een {@code bIsDual} flag,
     * maar het verschil in feel/strategy maakt aparte tracking waardevol.
     * Zie {@link WeaponNameCanonicalizer#canonicalize(String, boolean)} voor de dual-flag detectie.
     */
    static final Map<String, String> FEATURE_TO_WEAPON = Map.ofEntries(
        Map.entry("self_weapon_isImpactHammer", "ImpactHammer"),
        Map.entry("self_weapon_isTranslocator", "Translocator"),
        Map.entry("self_weapon_isEnforcer", "Enforcer"),
        Map.entry("self_weapon_isDoubleEnforcer", "DoubleEnforcer"),
        Map.entry("self_weapon_isBioRifle", "BioRifle"),
        Map.entry("self_weapon_isShockRifle", "ShockRifle"),
        Map.entry("self_weapon_isPulseGun", "PulseGun"),
        Map.entry("self_weapon_isRipper", "Ripper"),
        Map.entry("self_weapon_isMinigun", "Minigun"),
        Map.entry("self_weapon_isFlakCannon", "FlakCannon"),
        Map.entry("self_weapon_isEightball", "Eightball"),
        Map.entry("self_weapon_isSniperRifle", "SniperRifle"),
        Map.entry("self_weapon_isWarheadLauncher", "WarheadLauncher"),
        Map.entry("self_weapon_isSuperShockRifle", "SuperShockRifle")
    );

    @Override
    public Float resolveFeatureValueForRealTimePlay(String featureId, GameStateDto f) {
        String wantedWeapon = FEATURE_TO_WEAPON.get(featureId);
        if (wantedWeapon == null) return null;
        PlayerDto pawn = (f != null) ? f.playerPawn : null;
        if (pawn == null) return 0.0f;
        String canonical = WeaponNameCanonicalizer.canonicalize(pawn.weaponClass, pawn.weaponIsDual);
        return wantedWeapon.equals(canonical) ? 1.0f : 0.0f;
    }
}
