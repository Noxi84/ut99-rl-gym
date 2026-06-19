# Invoer- en Contextfeatures

## Overzicht

Dit document groepeert de kleinere feature-categorieen: schietinvoer, fire cooldowns, weapon state, map identity, rewardgroups, role-tactical context en team-aggregaten.

---

## Schietinvoer

| Feature | Type | Beschrijving |
|---|---|---|
| `bFire` | 0 of 1 | Primair vuren ingedrukt |
| `bAltFire` | 0 of 1 | Alternatief vuren ingedrukt |

Deze features zijn zowel observatie (wat doet de bot nu) als target (wat moet het model produceren).

---

## Fire cooldown

Wapen-state counters die aangeven hoe lang tot het wapen weer kan vuren:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `fireCooldown` | [0, 1] | Resterende cooldown primair vuur |
| `altFireCooldown` | [0, 1] | Resterende cooldown alternatief vuur |

---

## Shooting-conditioned pitch

Cross-feed features die aim-context geven:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `shootIntentFire` | 0 of 1 | Intentie om primair te vuren |
| `shootIntentAltFire` | 0 of 1 | Intentie om alternatief te vuren |
| `primaryAimPitchError_norm` | [-1, 1] | Ballistic pitch-fout voor primair vuur |
| `secondaryAimPitchError_norm` | [-1, 1] | Ballistic pitch-fout voor alternatief vuur |
| `shootIntentPitchError_norm` | [-1, 1] | Pitch-fout voor het gekozen vuur-type |

---

## Map identity

| Feature | Type | Beschrijving |
|---|---|---|
| `map_id` | integer | Categorische map-ID uit `resources/config/maps/<map>.json:map_id`. Het netwerk verwerkt dit als learned embedding, niet als ordinale input |

---

## Rewardgroups (rolcontext)

Dynamische multi-hot vector uit `rewards.json`. Het aantal features is gelijk aan het aantal niet-default rewardgroup-entries (typisch 4: Attack/Cover/Defend/DeathMatch). Als een bot meerdere rewardgroups kiest, worden meerdere features actief.

Configuratie via `features_from: "rewardgroups"` in `features.json` -- geen harde feature-lijst nodig.

---

## Role-tactical context

Drie observeerbare signalen die de policy laten conditioneren op het strategische beeld:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_proximityToOwnFlag_norm` | [0, 1] | Nabijheid tot eigen vlag/basis. Defender blijft dichtbij |
| `enemy_depthInOwnHalf_norm` | [0, 1] | Diepte van vijanden in eigen helft. Defender engageert zodra vijand voorbij middenlijn is |
| `teammateCarrier_proximity_norm` | [0, 1] | Nabijheid tot teamgenoot die de vlag draagt. Escort-signaal voor Cover/Attacker |

---

## Team-aggregaten

Vijf team-niveau aggregaten zodat de policy team-context oppikt:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `team_meanHealth_norm` | [0, 1] | Gemiddelde gezondheid van het team |
| `team_meanDepth_norm` | [0, 1] | Gemiddelde diepte in vijandelijke helft over levende teamleden |
| `team_aliveCount_norm` | [0, 1] | Fractie levende teamleden |
| `team_captureProgress_norm` | [0, 1] | Voortgang van vlag-terugtransport (0.5 default zonder carrier) |
| `team_droppedCount_norm` | [0, 1] | Aantal gedropte vlaggen / 2 |

---

## Temporele vensters

| Groep | Venster (first, last) | Beschrijving |
|---|---|---|
| Map identity | (0, 1) | Statisch per match |
| Rewardgroups | (0, 1) | Stabiel per bot-instantie |
| Fire cooldown | (0, 3) | Korte historie voor cooldown-tracking |
| Shooting-conditioned pitch | (0, 4) | Recente frames voor aim-feedback |
| Role-tactical | (0, 1) | Alleen huidig frame |
| Team-aggregaten | (0, 1) | Alleen huidig frame |

---

## Augmentatie

Geen van deze features wordt geaugmenteerd. Ze zijn onafhankelijk van kijkrichting.

---

## Configuratie

| Pad | Beschrijving |
|---|---|
| `resources/models/rl_pawn/features.json` | Welke features actief zijn |
| `resources/models/rl_pawn/rewards.json` | Rewardgroup-definities (bepaalt rolcontext features) |
