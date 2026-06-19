package aiplay.scanners.feature.resolver.shoot;

import aiplay.runtime.config.CoordinatesConverter;
import aiplay.scanners.feature.jsontodtoconverters.KeyboardMoveDtoConverter;
import aiplay.dto.GameStateDto;
import aiplay.dto.InventoryItemDto;
import aiplay.dto.PlayerDto;
import aiplay.dto.PlayerPawnDto;
import aiplay.dto.ProjectileDto;
import aiplay.ut99webmodel.GameState;
import aiplay.ut99webmodel.InventoryWeapon;
import aiplay.ut99webmodel.Player;
import aiplay.ut99webmodel.ProjectileEntry;
import aiplay.scanners.feature.TrainingFeatureJsonToDtoConverter;
import aiplay.config.global.GlobalConfigRepository;
import aiplay.config.global.RecordingKeysConfig;

import java.util.ArrayList;
import java.util.List;

public class PlayerPawnShootFeatureJsonToDtoConverter implements TrainingFeatureJsonToDtoConverter {

    private final KeyboardMoveDtoConverter keyboardMoveDtoConverter = new KeyboardMoveDtoConverter();
    private final CoordinatesConverter coordinatesConverter = new CoordinatesConverter();

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
        // Weapon class (needed by ShootingActionDecoder)
        if (dto.playerPawn.weaponClass == null && pawn.Weapon != null && pawn.Weapon.WeaponClass != null) {
            dto.playerPawn.weaponClass = pawn.Weapon.WeaponClass;
        }

        // bCanClientFire from TournamentWeapon
        if (pawn.Weapon != null && pawn.Weapon.SubWeapon != null
                && pawn.Weapon.SubWeapon.TournamentWeapon != null
                && pawn.Weapon.SubWeapon.TournamentWeapon.bCanClientFire != null) {
            dto.playerPawn.weaponCanFire = "True".equalsIgnoreCase(pawn.Weapon.SubWeapon.TournamentWeapon.bCanClientFire);
        }

        // Inventory
        if (pawn.Inventory != null) {
            List<InventoryItemDto> items = new ArrayList<>(pawn.Inventory.size());
            for (InventoryWeapon inv : pawn.Inventory) {
                if (inv == null) continue;
                InventoryItemDto item = new InventoryItemDto();
                item.weaponClass = inv.WeaponClass;
                item.ammoAmount = parseIntSafe(inv.AmmoAmount);
                item.maxAmmo = parseIntSafe(inv.MaxAmmo);
                items.add(item);
            }
            dto.playerPawn.inventory = items;
        }

        // Projectiles (global state, parsed once)
        if (dto.projectiles == null && gs.Projectiles != null) {
            List<ProjectileDto> projectiles = new ArrayList<>(gs.Projectiles.size());
            for (ProjectileEntry entry : gs.Projectiles) {
                if (entry == null) continue;
                ProjectileDto p = new ProjectileDto();
                p.projectileClass = entry.Class;
                p.location = coordinatesConverter.convert(entry.Location);
                p.velocity = coordinatesConverter.convert(entry.Velocity);
                p.speed = parseFloatSafe(entry.Speed);
                p.damage = parseFloatSafe(entry.Damage);
                p.instigatorName = entry.InstigatorName;
                p.instigatorTeam = parseIntSafe(entry.InstigatorTeam);
                p.drawScale = parseFloatSafe(entry.DrawScale);
                projectiles.add(p);
            }
            dto.projectiles = projectiles;
        }

        RecordingKeysConfig keys = GlobalConfigRepository.shared().recording().keys();
        dto.playerPawn.bFire = keyboardMoveDtoConverter.convert(keys.fire(), pawn.bFire);
        dto.playerPawn.bAltFire = keyboardMoveDtoConverter.convert(keys.altFire(), pawn.bAltFire);
        return dto;
    }

    @Override
    public GameStateDto enrichDto(String sessionId, String featureId, GameState gs, GameStateDto dto) {
        return enrichAll(sessionId, gs, dto);
    }

    private static int parseIntSafe(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float parseFloatSafe(String value) {
        if (value == null || value.isEmpty()) return 0f;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static Player findPlayer(GameState gs, String aiName) {
        for (Player p : gs.Players) {
            if (p != null && p.Name != null && p.Name.equalsIgnoreCase(aiName)) {
                return p;
            }
        }
        return null;
    }
}
