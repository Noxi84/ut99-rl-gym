package aiplay.play;

import java.util.HashMap;
import java.util.Map;

/**
 * Reverse lookup table for the FNV-1a uint32 class-name hashes sent by the
 * UDP state channel ({@code RLUdpStateSender}).
 *
 * <p>Must use the exact same FNV-1a variant as the UScript side:
 * seed = 0x811C9DC5, prime = 16777619, processing one character byte at a time.
 *
 * <p>Add known UT99 class strings below so downstream feature resolvers that
 * match on {@code "Botpack.ShockRifle"} etc. keep working. Unknown hashes
 * fall back to {@code "Unknown#<hex>"} which lets the parser proceed but
 * signals a missing entry in the log.
 */
public final class WeaponClassNameTable {

    private static final Map<Integer, String> HASH_TO_NAME = new HashMap<>();

    static {
        // Weapons. NB: UT99 registreert classes onder hun .uc-bestandsnaam-casing,
        // en UScript's string(W.Class) volgt die — NIET de "class X" declaratie-casing.
        // Stock Botpack heeft enkele lowercase bestandsnamen (enforcer.uc, ripper.uc,
        // minigun2.uc, doubleenforcer.uc, ut_biorifle.uc) en "WarheadLauncher" (kleine h).
        // De hash moet die exacte casing matchen, anders komt het wapen als
        // "Unknown#<hex>" terug. Geverifieerd via live data: een actieve enforcer kwam
        // binnen als FNV-1a van "Botpack.enforcer" (= 0xabeea4b7), niet "Botpack.Enforcer".
        add("Botpack.ImpactHammer");
        add("Botpack.Translocator");
        add("Botpack.enforcer");
        add("Botpack.doubleenforcer");
        add("Botpack.ut_biorifle");
        add("Botpack.ShockRifle");
        add("Botpack.SuperShockRifle");
        add("Botpack.PulseGun");
        add("Botpack.ripper");
        add("Botpack.minigun2");
        add("Botpack.UT_FlakCannon");
        add("Botpack.UT_Eightball");
        add("Botpack.SniperRifle");
        add("Botpack.WarheadLauncher");

        // RL weapon subclasses — bots in single-weapon arenas (PulseOnlyArena
        // etc.) carry these instead of stock Botpack weapons. UC emits the raw
        // class string via FNV1aHash(string(W.Class)); zonder deze entries komen
        // de hashes als "Unknown#<hex>" terug → WeaponIdentityFeatureValueResolver
        // kan ze niet canonicaliseren → self_weapon_is<X> blijft 0 voor alle 14
        // bits → STRATA_UNDERVOL[active_weapon_*]=0/64 in de probe pipeline.
        add("NeuralNetWebserver.RLPulseGun");
        add("NeuralNetWebserver.RLEightball");
        add("NeuralNetWebserver.RLShockRifle");

        // Projectiles (actor subclasses spawned by weapons)
        add("Botpack.flakslug");
        add("Botpack.UTFlakShell");
        add("Botpack.UTChunk1");
        add("Botpack.UTChunk2");
        add("Botpack.UTChunk3");
        add("Botpack.UTChunk4");
        add("Botpack.ShockProj");          // shock-rifle blue ball
        add("Botpack.RocketMk2");          // rocket-launcher primary rocket
        add("Botpack.Grenade");            // rocket-launcher alt-fire grenade
        add("Botpack.UT_BioGel");          // bio-rifle primary spray glob
        add("Botpack.BioGlob");            // bio-rifle charged alt-fire glob (extends UT_BioGel)
        add("Botpack.PlasmaSphere");       // pulse-gun primary plasma chunk
        add("Botpack.Razor2");             // ripper sawblade
        add("Botpack.WarShell");           // redeemer warhead missile
        add("NeuralNetWebserver.RLStarterBolt"); // pulse-gun primary beam (RL variant
                                                 // zonder Bot-AI aim-hijacking)
        add("Botpack.TranslocatorTarget"); // translocator disc (Decoration, niet
                                           // standaard Projectile — Java enricher
                                           // mag deze classnaam wel zien indien
                                           // we 'm via een aparte projectiel-feed
                                           // publiceren in de toekomst).

        // Damage types (subset — add more on demand)
        add("Botpack.DamTypeShockBeam");
        add("Botpack.DamTypeShockCore");
        add("Botpack.DamTypeShockCombo");
        add("Botpack.DamTypeBioGel");
        add("Botpack.DamTypeBioGoop");
        add("Botpack.DamTypeBioPuddle");
        add("Botpack.DamTypePulsePlasma");
        add("Botpack.DamTypePulseBeam");
        add("Botpack.DamTypeRazorBlade");
        add("Botpack.DamTypeRazorAlt");
        add("Botpack.DamTypeMinigun");
        add("Botpack.DamTypeFlakChunk");
        add("Botpack.DamTypeFlakShell");
        add("Botpack.DamTypeRocket");
        add("Botpack.DamTypeSeekingRocket");
        add("Botpack.DamTypeGrenade");
        add("Botpack.DamTypeSniperShot");
        add("Botpack.DamTypeImpactHammer");
        add("Botpack.DamTypeRedeemer");
        add("Botpack.DamTypeEnforcer");
        add("Botpack.DamTypeTeleFrag");
        add("Botpack.DamTypeTransDisrupt");

        // UnrealScript damage *name* enums — used by Pawn.TakeDamage(damageType).
        // Sent via RLUdpStateSender.WriteDamageEvent. Canonical casing matters for
        // FNV matching (source: UnrealI/* and Botpack/* UC files).
        add("shredded");     // Flak primary chunks (UTChunk*)
        add("FlakDeath");    // Flak alt-fire explosion (UTFlakShell HurtRadius)
        add("exploded");     // Generic explosion (FlakShell UnrealI variant, rockets)
        add("Fell");         // Fall damage
        add("Drowned");
        add("Burned");
        add("Corroded");     // Bio rifle puddle
        add("jolted");       // Shock beam
        add("Frozen");
        add("RedeemerDeath");
        add("Eviscerated");  // Ripper blade
        add("Impaled");
        add("Pancaked");
        add("Fragged");
        add("SniperHit");
        add("LowGravity");
        // SniperRifle damage names: MyDamageType="shot" (body, 45 HP),
        // AltDamageType="Decapitated" (head-zone hit, 100 HP). Headshot-detectie
        // (DamageDeltaReward) matcht hierop. UnrealScript `name`-waarden zijn
        // case-insensitive en string(name) volgt de globale name-tabel-casing
        // (eerste registratie wint) — de bron gebruikt zowel 'Decapitated'
        // (SniperRifle/Pawn defaultprops) als 'decapitated' (TournamentPlayer/
        // Engine/Bot gameplay-checks), dus beide hashes registreren we zodat de
        // wire-waarde nooit als Unknown#<hex> binnenkomt. DamageDeltaReward
        // vergelijkt case-insensitive, dus welke casing ook arriveert telt mee.
        add("shot");
        add("Decapitated");
        add("decapitated");

        // Empty string (no weapon / no damage type)
        HASH_TO_NAME.put(fnv1a(""), "");
    }

    /** Exact mirror of the UScript FNV-1a routine; must remain in lock-step. */
    public static int fnv1a(String s) {
        int h = 0x811C9DC5;
        if (s == null) return h;
        for (int i = 0; i < s.length(); i++) {
            h ^= (s.charAt(i) & 0xFF);
            h *= 16777619;
        }
        return h;
    }

    /**
     * Returns the class string matching the given hash, or {@code "Unknown#<hex>"}
     * when the hash is not registered. Hash 0 maps to empty string.
     */
    public static String lookup(int hash) {
        if (hash == 0) return "";
        String name = HASH_TO_NAME.get(hash);
        if (name != null) return name;
        return "Unknown#" + Integer.toHexString(hash);
    }

    private static void add(String className) {
        HASH_TO_NAME.put(fnv1a(className), className);
    }

    private WeaponClassNameTable() {}
}
