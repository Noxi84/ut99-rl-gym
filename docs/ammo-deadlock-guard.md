# Ammo-deadlock guard

Detectie en automatische oplossing voor matches die vastlopen wanneer alle levende RL-bots gelijktijdig zonder bruikbaar wapen zitten. Typisch in arena-modes (PulseOnlyArena, RocketOnlyArena, etc.) waar bots geen melee-fallback hebben. Zonder deze guard staan twee bots oneindig naast elkaar zonder schade te doen of te respawnen.

---

## Mechanisme

Per bot-instance draait een deadlock-guard per tick:

```
Per tick:
  1. Verzamel alle levende RL-bots uit de game-state
  2. Markeer bot als "no-ammo" wanneer geen enkel inventory-item bruikbaar is
     (bruikbaar = melee-wapen of ammo > 0)
  3. Alle levende RL-bots no-ammo?
     |
     +-- nee -> reset timer
     +-- ja  -> tel duur
              |
              +-- duur < threshold -> wacht
              +-- duur >= threshold ->
                    Kies lexicografisch laagste bot-naam uit no-ammo set
                    (deterministisch over gedeelde state = exact 1 bot)
                    |
                    v
                    Stuur suicide-command (UDP magic 0xAB)
                    Bot suicidet -> respawn-cyclus geeft verse inventory
                    |
                    v
                    3 seconden cooldown (voorkomt dubbele commands)
```

De keuze van de lexicografisch laagste bot-naam is deterministisch over de gedeelde game-state. Hierdoor suicidet exact een bot zonder cross-process synchronisatie.

---

## Configuratie

`resources/config/ammo-deadlock-guard.json`:

```json
{
  "enabled": true,
  "threshold_seconds": 8.0
}
```

| Veld | Type | Beschrijving |
|---|---|---|
| `enabled` | bool | Detector volledig uitschakelen. Verplicht veld. |
| `threshold_seconds` | double | Minimale duur dat alle RL-bots no-ammo zijn voordat suicide getriggerd wordt. |

Geen fallbacks -- ontbrekend veld crasht config-load.

---

## Wire format (UDP magic `0xAB`)

2-byte suicide-command naast het bestaande 12-byte action-packet (magic `0xAA`):

```
[0] magic  = 0xAB
[1] botIdx (0..15)
```

De command-ontvanger detecteert magic `0xAB` voor de 12-byte guard, resolved de bot via index, en triggert suicide. De standaard respawn-cyclus geeft verse default-inventory.

---

## Scope en edge cases

| Situatie | Gedrag |
|---|---|
| Alleen RL-bots tellen | Stock UT99-bots doen al zelf suicide bij ammo-deadlocks. |
| Een RL-bot leeft alleen | Is altijd de lexicografisch laagste. Suicidet bij no-ammo + threshold. |
| Netwerk-jitter | Worst-case: twee runtimes suiciden gelijktijdig (acceptabel) of geen (volgende tick fixt zich). |
| RL-policy | Suicide is geen action-dimensie en geen reward-shaping -- pure infrastructuur. De policy hoeft niets te leren. |
