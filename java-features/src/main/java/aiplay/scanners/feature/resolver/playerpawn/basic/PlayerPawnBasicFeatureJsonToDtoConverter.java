package aiplay.scanners.feature.resolver.playerpawn.basic;

import aiplay.runtime.config.CoordinatesConverter;
import aiplay.dto.DodgeState;
import aiplay.dto.GameStateDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerPawnDto;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.scanners.feature.jsontodtoconverters.CollisionsConverter;
import aiplay.scanners.feature.jsontodtoconverters.ViewRotationConverter;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.Player;
import aiplay.util.NormalizationUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerPawnBasicFeatureJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    private final CoordinatesConverter coordinatesConverter = new CoordinatesConverter();
    private final ViewRotationConverter viewRotationConverter = new ViewRotationConverter();
    private final CollisionsConverter collisionsConverter = new CollisionsConverter();
    private static final Pattern TEAM_TRAILING_INT = Pattern.compile(".*?(\\d+)\\s*$");

    @Override
    public Integer priority() {
        return 0;
    }

    @Override
    public GameStateDto enrichAll(String sessionId, GameState gs, GameStateDto dto) {
        if (gs == null || gs.Players == null) {
            return dto;
        }
        String aiName = aiplay.runtime.context.PlayerIdentityContext.effectivePlayerName();
        Player pawn = findPlayer(gs, aiName);
        if (pawn == null) {
            return dto;
        }

        if (dto.playerPawn == null) {
            dto.playerPawn = new PlayerDto();
        }
        if (dto.playerPawn.playerPawn == null) {
            dto.playerPawn.playerPawn = new PlayerPawnDto();
        }

        // ViewRotation: convert once, reuse for velocity/acceleration projections
        var vr = (pawn.ViewRotation != null) ? viewRotationConverter.convert(pawn.ViewRotation) : null;
        double yaw = 0, fx = 0, fy = 0, rx = 0, ry = 0;
        if (vr != null) {
            dto.playerPawn.viewRotation = vr;
            yaw = NormalizationUtils.viewRotationXToRad(vr.x);
            fx = Math.cos(yaw);
            fy = Math.sin(yaw);
            rx = NormalizationUtils.RIGHT_IS_POSITIVE ? fy : -fy;
            ry = NormalizationUtils.RIGHT_IS_POSITIVE ? -fx : fx;
        }

        // DodgeState
        if (pawn.DodgeState != null) {
            dto.playerPawn.dodgeState = DodgeState.fromInt(parseIntSafe(pawn.DodgeState));
            dto.playerPawn.dodgeStateResolved = true;
        }

        // Physics state — opaque int from UDP (PHYS_Walking=1, PHYS_Falling=2, ...)
        if (pawn.Physics != null) {
            dto.playerPawn.physics = aiplay.dto.Ut99PhysicsType.fromInt(parseIntSafe(pawn.Physics));
        }

        // Water/submersion state. breathRemaining defaults to 1.0 (full) on the DTO,
        // so we only overwrite it when the field is actually present.
        dto.playerPawn.headUnderwater = isTrue(pawn.bHeadUnderwater);
        if (pawn.BreathRemaining != null && !pawn.BreathRemaining.isEmpty()) {
            dto.playerPawn.breathRemaining = (float) parseDoubleSafe(pawn.BreathRemaining);
        }

        // Velocity
        if (pawn.Velocity != null) {
            try {
                String[] parts = pawn.Velocity.split(",");
                if (parts.length >= 2) {
                    double vx = Double.parseDouble(parts[0].trim());
                    double vy = Double.parseDouble(parts[1].trim());
                    dto.playerPawn.speed = Math.hypot(vx, vy);
                    dto.playerPawn.speed_norm = (float) Math.min(1.0, dto.playerPawn.speed / 1000.0);
                    dto.playerPawn.velocityX_norm = (float) Math.max(-1.0, Math.min(1.0, vx / 1000.0));
                    dto.playerPawn.velocityY_norm = (float) Math.max(-1.0, Math.min(1.0, vy / 1000.0));
                    if (vr != null) {
                        double forwardVel = vx * fx + vy * fy;
                        double rightVel = vx * rx + vy * ry;
                        dto.playerPawn.forwardVelocity_norm = (float) Math.max(-1.0, Math.min(1.0, forwardVel / 1000.0));
                        dto.playerPawn.rightVelocity_norm = (float) Math.max(-1.0, Math.min(1.0, rightVel / 1000.0));
                    }
                    if (parts.length >= 3) {
                        double vz = Double.parseDouble(parts[2].trim());
                        dto.playerPawn.velocityZ_norm = (float) Math.max(-1.0, Math.min(1.0, vz / 1000.0));
                    }
                }
            } catch (Exception ignore) {}
            dto.playerPawn.velocityResolved = true;
        }

        // Acceleration
        if (pawn.Acceleration != null) {
            try {
                String[] parts = pawn.Acceleration.split(",");
                if (parts.length >= 2) {
                    double ax = Double.parseDouble(parts[0].trim());
                    double ay = Double.parseDouble(parts[1].trim());
                    dto.playerPawn.accelerationX_norm = (float) Math.max(-1.0, Math.min(1.0, ax / 1000.0));
                    dto.playerPawn.accelerationY_norm = (float) Math.max(-1.0, Math.min(1.0, ay / 1000.0));
                    if (vr != null) {
                        double forwardAccel = ax * fx + ay * fy;
                        double rightAccel = ax * rx + ay * ry;
                        dto.playerPawn.forwardAcceleration_norm = (float) Math.max(-1.0, Math.min(1.0, forwardAccel / 1000.0));
                        dto.playerPawn.rightAcceleration_norm = (float) Math.max(-1.0, Math.min(1.0, rightAccel / 1000.0));
                    }
                    if (parts.length >= 3) {
                        double az = Double.parseDouble(parts[2].trim());
                        dto.playerPawn.accelerationZ_norm = (float) Math.max(-1.0, Math.min(1.0, az / 1000.0));
                    }
                }
            } catch (Exception ignore) {}
            dto.playerPawn.accelerationResolved = true;
        }

        dto.playerPawn.forwardAccelVelocityMismatch_norm = Math.min(
            1.0f,
            Math.abs(dto.playerPawn.forwardAcceleration_norm - dto.playerPawn.forwardVelocity_norm)
        );
        dto.playerPawn.rightAccelVelocityMismatch_norm = Math.min(
            1.0f,
            Math.abs(dto.playerPawn.rightAcceleration_norm - dto.playerPawn.rightVelocity_norm)
        );

        // All remaining fields: populate in one pass (no per-feature switch)
        dto.playerPawn.name = pawn.Name;
        dto.playerPawn.location = coordinatesConverter.convert(pawn.Location);
        dto.playerPawn.hasFlag = !"None".equalsIgnoreCase(pawn.HasFlag);
        dto.playerPawn.hasFlag_norm = dto.playerPawn.hasFlag ? 1.0f : 0.0f;
        dto.playerPawn.collisions = collisionsConverter.convert(pawn.Collisions);
        dto.playerPawn.baseEyeHeight = parseDoubleSafe(pawn.BaseEyeHeight);
        dto.playerPawn.health = parseIntSafe(pawn.Health);
        dto.playerPawn.armor = parseIntSafe(pawn.Armor);
        dto.playerPawn.team = parseTeamIndexSafe(pawn.Team, aiplay.runtime.context.PlayerIdentityContext.effectivePlayerTeam());
        dto.playerPawn.score = (int) Math.round(parseDoubleSafe(pawn.Score));
        dto.playerPawn.bFeigningDeath = isTrue(pawn.bFeigningDeath);
        dto.playerPawn.oldLocation = coordinatesConverter.convert(pawn.OldLocation);
        dto.playerPawn.bIsSpectator = isTrue(pawn.bIsSpectator);
        dto.playerPawn.bIsABot = isTrue(pawn.bIsABot);
        dto.playerPawn.bIsRLControlled = isTrue(pawn.bIsRLControlled);
        dto.playerPawn.bWaitingPlayer = isTrue(pawn.bWaitingPlayer);

        // Damage event: carried from UDP WriteDamageEvent → ut99webmodel.Player.
        // Non-zero amount indicates a TakeDamage fired on this frame.
        dto.playerPawn.lastDamageAmount         = parseIntSafe(pawn.LastDamageAmount);
        dto.playerPawn.lastDamageType           = pawn.LastDamageType;
        dto.playerPawn.lastDamageSelfInflicted  = isTrue(pawn.LastDamageSelfInflicted);
        dto.playerPawn.lastDamageInstigatorSlot = parseInstigatorSlotSafe(pawn.LastDamageInstigatorSlot);
        dto.playerPawn.slot                     = parseInstigatorSlotSafe(pawn.Slot);

        // KPI counters (Plan A/B/D2). Default 0 als pawn-veld leeg is (legacy .u).
        dto.playerPawn.frags          = parseIntSafe(pawn.Frags);
        dto.playerPawn.flagsTaken     = parseIntSafe(pawn.FlagsTaken);
        dto.playerPawn.flagsCaptured  = parseIntSafe(pawn.FlagsCaptured);
        dto.playerPawn.flagsReturned  = parseIntSafe(pawn.FlagsReturned);
        dto.playerPawn.shots          = parseIntSafe(pawn.Shots);
        dto.playerPawn.shotsOnTarget  = parseIntSafe(pawn.ShotsOnTarget);
        dto.playerPawn.damageDealtTotal = parseIntSafe(pawn.DamageDealtTotal);
        dto.playerPawn.damageTakenTotal = parseIntSafe(pawn.DamageTakenTotal);

        // Weapon class + inventory. Vroeger werden deze alleen gevuld door PlayerPawnShoot
        // (model-specific feature), waardoor reward-componenten zoals AmmoConsumptionPenalty
        // geen ammo-data zagen op modellen die de shoot-feature niet hadden geladen. Hier
        // vullen we ze in de basic enricher zodat ze altijd beschikbaar zijn voor reward
        // computation, ongeacht welke features actief zijn.
        if (pawn.Weapon != null) {
            if (pawn.Weapon.WeaponClass != null) {
                dto.playerPawn.weaponClass = pawn.Weapon.WeaponClass;
            }
            dto.playerPawn.weaponAmmo        = parseIntSafe(pawn.Weapon.AmmoAmount);
            dto.playerPawn.weaponMaxAmmo     = parseIntSafe(pawn.Weapon.MaxAmmo);
            dto.playerPawn.weaponIsDual      = isTrue(pawn.Weapon.bIsDual);
            dto.playerPawn.weaponSniping     = isTrue(pawn.Weapon.bSniping);
            dto.playerPawn.weaponGrenadeMode = isTrue(pawn.Weapon.bGrenadeMode);
            // Oude recordings (vóór 2026-05-17) hebben geen bTightWad-veld in
            // het JSON — isTrue(null) returnt false, dus oude data leest correct
            // als "geen tight-wad". Pas vanaf nieuwe recordings na UC-mod update
            // krijgt BC training de echte signal.
            dto.playerPawn.weaponTightWad    = isTrue(pawn.Weapon.bTightWad);
            dto.playerPawn.weaponMultiCount  = parseIntSafe(pawn.Weapon.MultiCount);
            dto.playerPawn.weaponChargeAmount = parseIntSafe(pawn.Weapon.ChargeAmount);
        }
        if (pawn.Inventory != null) {
            java.util.List<aiplay.dto.InventoryItemDto> items =
                new java.util.ArrayList<>(pawn.Inventory.size());
            for (aiplay.ut99webmodel.InventoryWeapon inv : pawn.Inventory) {
                if (inv == null) continue;
                aiplay.dto.InventoryItemDto item = new aiplay.dto.InventoryItemDto();
                item.weaponClass = inv.WeaponClass;
                item.ammoAmount  = parseIntSafe(inv.AmmoAmount);
                item.maxAmmo     = parseIntSafe(inv.MaxAmmo);
                items.add(item);
            }
            dto.playerPawn.inventory = items;
        }

        // Translocator-disc state (raw); enricher zorgt voor time-since-throw norm.
        dto.playerPawn.discPresent = isTrue(pawn.bDiscPresent);
        if (dto.playerPawn.discPresent && pawn.DiscLocation != null) {
            dto.playerPawn.discLocation = coordinatesConverter.convert(pawn.DiscLocation);
        } else {
            dto.playerPawn.discLocation = null;
        }

        return dto;
    }

    /** Signed parse: "-1" is unknown, 0..N is a valid slot. */
    private static int parseInstigatorSlotSafe(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    @Override
    public GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto) {
        // Legacy per-feature-ID path — only used if called directly.
        // enrichAll() is the preferred entry point.
        return enrichAll(sessionId, gs, dto);
    }

    private static Player findPlayer(GameState gs, String aiName) {
        for (Player p : gs.Players) {
            if (p != null && p.Name != null && p.Name.equalsIgnoreCase(aiName)) {
                return p;
            }
        }
        return null;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseTeamIndexSafe(String raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception ignore) {
        }
        // Common legacy shapes:
        // - "Botpack.CTFTeam0"
        // - "CTFTeam1"
        // - "TeamInfo1"
        Matcher m = TEAM_TRAILING_INT.matcher(s);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
            }
        }
        // String fallback (rare): "red"/"blue"
        String lower = s.toLowerCase();
        if (lower.contains("red")) return 0;
        if (lower.contains("blue")) return 1;
        return defaultValue;
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static boolean isTrue(String v) {
        return "True".equalsIgnoreCase(v) || "1".equalsIgnoreCase(v);
    }
}
