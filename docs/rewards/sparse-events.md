# Sparse Event Rewards

Sparse rewards die het `rl_pawn` model ontvangt. Afgeleid uit opeenvolgende frame-vergelijkingen; voor flag-return events aanvullend via event-driven instigator-attributie.

---

## Event-detectie

De meeste events worden gedetecteerd door twee opeenvolgende frames (prev en curr) te vergelijken. Voor `flag_returned` en `flag_team_returned` levert de UT99-server de slot van de scorer, zodat self vs teammate onderscheiden kan worden.

```
prev GameStateDto ----+
                      |---> vergelijk prev vs curr ---> sparse event rewards
curr GameStateDto ----+
                      ^
                      |
UT99 server: flag-return scorer-slot (instigator-attributie)
```

---

## Event-tabel

| Event | Detectie | Reward |
|---|---|---|
| Flag taken | hasFlag false -> true | +5.0 x time-multiplier |
| Flag captured | score stijgt + hasFlag true | +15.0 x time-multiplier |
| Flag team-captured | enemy flag thuis + teammate had flag | per-rol (default 0.0) |
| Flag dropped | hasFlag true -> false, nog in leven | -5.0 |
| Flag returned | own flag thuis + instigator = self | +3.0 |
| Flag team-returned | own flag thuis + instigator = teammate | per-rol (default 0.0) |
| Frag | score stijgt zonder hasFlag | +0.5 |
| Death | health -> 0 | -1.5 |

---

## Time-multiplier

Flag taken en flag captured worden geschaald met een multiplier die eerdere acties meer beloont:

```
multiplier = clamp(remainingTime / matchDuration, 0.1, 1.0)
```

| Situatie | Multiplier |
|---|---|
| Begin van de match | 1.0 |
| Halverwege | 0.5 |
| Bijna voorbij | 0.1 (floor) |
| Geen tijdslimiet | 1.0 |

---

## Shooting-specifieke sparse events

Deze events zijn actief voor de fire-head van `rl_pawn`:

| Event | Detectie | Reward |
|---|---|---|
| Fire onset | fireActive false -> true | -0.15 |
| Fire during cooldown | fireWantedDuringCooldown | -0.25 |
| Shot on-target | fire onset + aim >= 0.75 | +0.35 x aim |
| Shot off-target | fire onset + aim < 0.75 | -0.25 |
| Kill by fire | frag + was firing | +5.0 |
| Headshot (sniper) | enemy damage-type `Decapitated` (case-insensitief) + onze instigator-slot | +`headshot_bonus` (0.1) |

(Exacte weights staan in `rewards.json` -- deze tabel is een conceptueel overzicht.)

### Sniper headshot + head-aim

De Sniper Rifle past 100 HP toe (i.p.v. 45) met damage-name `Decapitated` wanneer de hit in de head-zone valt (`HitLocation.Z - Location.Z > 0.62 * CollisionHeight`, `SniperRifle.uc`). Twee samenwerkende signalen, beide **alleen actief wanneer de bot de Sniper draagt** (de rest van het arsenaal blijft ongewijzigd):

1. **Viewrotation-prescription naar het hoofd** -- `sniper_primary_aim_target_height_uu` (top-level in `rewards.json`, default 31 = head-center) verschuift het aim-target voor de Sniper van eye-height (+27) naar head-center, net als `rocket_primary_aim_target_height_uu` dat voor de rocket doet. `FireModeAimTargeting.aimTarget()` dispatcht per gedragen wapenclass, dus pitch_alignment + acquisition + de 3D view-precision (en de `primaryAimPitchError`-feature) trekken de aim recht op het hoofd. Andere wapens vallen in de eye-height-tak.
2. **Headshot outcome-bonus** -- `damage_delta.headshot_bonus` (fire-head, default 0.1) per gedecapiteerde enemy. Safe-by-construction: alleen de Sniper produceert `Decapitated`, dus 0 effect op andere wapens (zelfde patroon als `shock_combo_event`).

De damage-name komt als FNV-1a-hash over de wire; `WeaponClassNameTable` decodeert `Decapitated`/`decapitated`/`shot` terug naar string (zonder die entries kwam de headshot als `Unknown#<hex>` binnen en triggerde de bonus nooit).

---

## Reward-verdeling per head

Shooting ontvangt geen flag rewards -- het model hoeft niet te leren waar de vlag is, alleen wanneer te schieten.

| Config key | Movement + View | Fire |
|---|---|---|
| `flag_taken` | 5.0 | 0.0 |
| `flag_captured` | 15.0 | 0.0 |
| `flag_team_captured` | 0.0 | 0.0 |
| `frag` | 0.5 | 0.5 |
| `death` | -1.5 | -1.5 |
| `flag_dropped` | -5.0 | 0.0 |
| `flag_returned` | 3.0 | 0.0 |
| `flag_team_returned` | 0.0 | 0.0 |

---

## Rolverdeling flag-event weights

`team_captured` en `team_returned` zijn per-rol instelbaar in `rl_pawn/rewards.json`. Doel: voorkomen dat bots elkaar saboteren voor credit, zonder attributie te verwateren.

| Rol | captured | team_captured | returned | team_returned |
|---|---:|---:|---:|---:|
| Attack | 55.0 | 5.0 | 2.0 | 1.0 |
| Cover | 35.0 | 25.0 | 8.0 | 10.0 |
| Defend | 8.0 | 10.0 | 30.0 | 18.0 |
| DeathMatch | 0.0 | 0.0 | 0.0 | 0.0 |

---

## Instigator-attributie

Voor `flag_returned` (self) en `flag_team_returned` (teammate) is state-diff alleen onvoldoende: bij own-flag-thuis is `holderName` al leeg. De UT99-server levert de slot van de scorer:

1. UT99 flag-score hook detecteert own-team flag touch (return).
2. Slot van de scorer wordt opgeslagen per team-index.
3. State-sender schrijft 1 byte (signed, -1 = geen) per flag en draint direct -- exact een frame credit per return.
4. Reward-berekening vergelijkt instigator-slot met eigen bot-slot.

Auto-returns (CTF-timeout zonder scorer) bypassen de hook. Instigator-slot blijft -1, geen bot incasseert credit. Dit voorkomt willekeurige shaping bij vlag-timeouts.
