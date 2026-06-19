package aiplay.rl.rewards.objective.pickupevent;
import aiplay.rl.rewards.core.RewardComponent;
import aiplay.rl.rewards.core.RewardContext;
import aiplay.runtime.config.PickupConfigRepository;
import aiplay.runtime.config.PickupConfigRepository.MapPickups;
import aiplay.runtime.config.PickupConfigRepository.StaticPickup;
import aiplay.dto.GameStateDto;
import aiplay.runtime.context.MapKey;
import aiplay.dto.InventoryItemDto;
import aiplay.dto.PickupDto;
import aiplay.dto.PlayerDto;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sparse pickup-event reward voor de joint rl_pawn policy.
 *
 * <p><b>Detector</b> — Java state-diff (geen UC hook): voor elke statische high-value
 * pickup-slot, kijk naar de transitie {@code prev.hidden=false → curr.hidden=true}.
 * Het is een "taken" event. Voor de instigator:
 * <ul>
 *   <li>de self-bot is gerapporteerd als instigator als hij binnen
 *       {@link PickupEventParams#INSTIGATOR_RADIUS_UU} van de pickup-locatie was op
 *       het prev-frame;
 *   <li>ambiguity (meerdere mogelijk-instigators in radius — zou alleen bij
 *       overlappende RL+stock-bot teams kunnen) → reward = 0 (niet dubbeltellen).
 * </ul>
 *
 * <p><b>Reward formula</b>:
 * <ul>
 *   <li>Voor health/armor pickups (shieldbelt/armor/thighpads/megahealth):
 *       {@code min(Δhealth + Δarmor, cap[type]) * weight[type]}. Cap is per-type:
 *       shieldbelt/armor = 100, thighpads = 50, megahealth = 100. Bot met full
 *       HP+armor krijgt 0 (anti-greed) want Δhp+Δarmor = 0.
 *   <li>Voor amp/UDamage: vaste {@code amp_flat_weight} reward (UDamage geeft geen
 *       Δhealth/Δarmor — een sparse event-bonus is hier het signaal).
 * </ul>
 *
 * <p>Decompositie: alle pickup-rewards → movement head (analoog aan flag_event;
 * pickup-awareness is een navigatie-/positionering-skill). Zie
 * {@code JointRewardDecompositionStrategy}.
 */
public class PickupEventReward implements RewardComponent {

    private final PickupEventParams params;

    public PickupEventReward(PickupEventParams params) {
        if (params == null) {
            throw new IllegalArgumentException("PickupEventReward requires non-null PickupEventParams");
        }
        this.params = params;
    }

    public record Result(
        double shieldbelt,
        double armor,
        double thighpads,
        double amp,
        double megahealth,
        // Pad-A new categories:
        double medbox,
        double vial,
        double weapon,
        double ammo) {
        public double total() {
            return shieldbelt + armor + thighpads + amp + megahealth
                + medbox + vial + weapon + ammo;
        }

        /** Backwards-compat helper for tests/diagnostics expecting 5 fields. */
        public static Result of(double sb, double a, double t, double amp, double mh) {
            return new Result(sb, a, t, amp, mh, 0, 0, 0, 0);
        }
    }

    @Override
    public double compute(RewardContext ctx) {
        return computeDetailed(ctx).total();
    }

    public Result computeDetailed(RewardContext ctx) {
        PlayerDto prevSelf = ctx.prev().playerPawn;
        PlayerDto currSelf = ctx.curr().playerPawn;
        if (prevSelf == null || currSelf == null || prevSelf.location == null) {
            return Result.of(0, 0, 0, 0, 0);
        }

        MapPickups mp = PickupConfigRepository.forMap(MapKey.fromFrame(ctx.curr()));
        // Per-slot key (canonical + slot-index) → previous-frame hidden state.
        // We doen geen state-bewaring tussen frames (RewardContext is stateless);
        // i.p.v. dat doen we paarse-match: zoek prev/curr pickups op classHash+loc
        // en detecteer transities binnen het paar.
        Map<Long, Boolean> prevHidden = collectPickupHidden(ctx.prev(), mp);
        Map<Long, Boolean> currHidden = collectPickupHidden(ctx.curr(), mp);

        double dHp = (currSelf.health > 0 && prevSelf.health > 0)
            ? Math.max(0, currSelf.health - prevSelf.health) : 0;
        double dArm = Math.max(0, currSelf.armor - prevSelf.armor);

        // Distance bot → pickup op prev-frame: was self binnen INSTIGATOR_RADIUS_UU?
        // Gebruik prev (de tick waarop pickup nog beschikbaar was) niet curr (zou
        // false-negatives geven als bot al doorgelopen is).
        double rShield = 0, rArmor = 0, rThigh = 0, rAmp = 0, rMega = 0;
        double rMedbox = 0, rVial = 0, rWeapon = 0, rAmmo = 0;
        for (StaticPickup sp : mp.allPickups()) {
            long key = slotKey(sp);
            Boolean prevH = prevHidden.get(key);
            Boolean currH = currHidden.get(key);
            // Take event: prev had pickup present (hidden=false), curr has it taken.
            if (prevH == null || currH == null) continue;
            if (prevH || !currH) continue; // niet een falling-edge available

            // Was self binnen radius op prev?
            if (!withinRadius(prevSelf, sp, PickupEventParams.INSTIGATOR_RADIUS_UU)) continue;

            String semantic = sp.semanticType();
            // Bestaande high-value semantic routes.
            switch (semantic) {
                case "shieldbelt" -> rShield += clamp(dHp + dArm, params.shieldbeltCap()) * params.shieldbeltWeight();
                case "armor"      -> rArmor  += clamp(dHp + dArm, params.armorCap()) * params.armorWeight();
                case "thighpads"  -> rThigh  += clamp(dHp + dArm, params.thighpadsCap()) * params.thighpadsWeight();
                case "amp"        -> rAmp    += params.ampFlatWeight();
                case "megahealth" -> rMega   += clamp(dHp + dArm, params.megahealthCap()) * params.megahealthWeight();
                default -> {
                    // Pad-A: extended_groups (ext:heal / ext:weapon) of ammo:<canonical>.
                    if ("ext:heal".equals(semantic)) {
                        String canonical = sp.canonicalClass();
                        if ("HealthVial".equals(canonical)) {
                            rVial += clamp(dHp + dArm, params.vialCap()) * params.vialWeight();
                        } else {
                            // MedBox / HealthPack share the medbox weight (gelijk gedrag in UT99).
                            rMedbox += clamp(dHp + dArm, params.medboxCap()) * params.medboxWeight();
                        }
                    } else if ("ext:weapon".equals(semantic)) {
                        boolean owned = ownedPrev(prevSelf, sp.canonicalClass());
                        rWeapon += owned ? params.weaponOwnedFlat() : params.weaponNewFlat();
                    } else if (semantic != null && semantic.startsWith("ammo:")) {
                        String canonicalLower = sp.canonicalClass().toLowerCase(Locale.ROOT);
                        Double w = params.ammoWeights().get(canonicalLower);
                        if (w != null) rAmmo += w;
                    }
                }
            }
        }
        return new Result(rShield, rArmor, rThigh, rAmp, rMega, rMedbox, rVial, rWeapon, rAmmo);
    }

    private static boolean ownedPrev(PlayerDto prevSelf, String weaponCanonical) {
        if (prevSelf == null || prevSelf.inventory == null || weaponCanonical == null) return false;
        for (InventoryItemDto item : prevSelf.inventory) {
            if (item == null || item.weaponClass == null) continue;
            String fqcn = item.weaponClass;
            String stripped = fqcn.startsWith("Botpack.") ? fqcn.substring("Botpack.".length()) : fqcn;
            int dot = stripped.lastIndexOf('.');
            if (dot >= 0) stripped = stripped.substring(dot + 1);
            if (weaponCanonical.equals(stripped)) return true;
        }
        return false;
    }

    private static double clamp(double delta, double cap) {
        if (delta <= 0) return 0;
        if (cap <= 0) return delta;
        return Math.min(delta, cap);
    }

    private static boolean withinRadius(PlayerDto self, StaticPickup sp, double radius) {
        double dx = self.location.x - sp.x();
        double dy = self.location.y - sp.y();
        double dz = self.location.z - sp.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Map elke staticPickup naar zijn hidden-state in dit frame (via classHash + locatie-ε). */
    private static Map<Long, Boolean> collectPickupHidden(GameStateDto frame, MapPickups mp) {
        Map<Long, Boolean> out = new HashMap<>();
        if (frame.livePickups == null) return out;
        for (PickupDto live : frame.livePickups) {
            var match = mp.matchLive(live.classHash, live.locX, live.locY, live.locZ);
            if (match.isEmpty()) continue;
            out.put(slotKey(match.get()), live.hidden);
        }
        return out;
    }

    private static long slotKey(StaticPickup sp) {
        // Combineer classHash + slotIndex tot een lange key (statische pickup is uniek
        // identificeerbaar door beide samen).
        return (((long) sp.classHash()) << 8) | (sp.slotIndex() & 0xFF);
    }
}
