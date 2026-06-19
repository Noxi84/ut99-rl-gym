# Multi-Enemy Slot Feature Architectuur

Bron van waarheid voor het dynamische speler-slot feature-systeem.

---

## 1. Overzicht

Het feature-systeem ondersteunt een variabel aantal vijanden en teamgenoten via **dynamische slot-gebaseerde features**. Elke vijand en teamgenoot krijgt een genummerd slot (`enemy0`, `enemy1`, `teammate0`, ...) met een consistente feature-naamconventie.

**Huidige runtime-configuratie:** tot 5 enemy-slots en 4 teammate-slots (5v5 CTF). Welke slots daadwerkelijk actief zijn wordt bepaald door `resources/models/rl_pawn/features.json`.

```
features.json bepaalt welke slots actief zijn
         |
         v
+---------------------------------------------------------------------+
|  Model kiest subset van slots                                       |
|                                                                     |
|  enemy{0..4}_*     beschikbaar in DTO (runtime)                     |
|  teammate{0..3}_*  beschikbaar in DTO (runtime)                     |
|                                                                     |
|  Pre-geregistreerde feature IDs: {0..6} voor beide categorieen      |
|                                                                     |
|  Uitbreiden naar meer slots vereist alleen features.json            |
|  wijzigingen + eventueel MAX_ENEMY_SLOTS / MAX_TEAMMATE_SLOTS       |
|  verhogen                                                           |
+---------------------------------------------------------------------+
```

---

## 2. Naamconventie

Alle speler-slot features volgen het patroon `{categorie}{N}_{suffix}`:

| Patroon | Voorbeeld | Beschrijving |
|---|---|---|
| `enemy{N}_{suffix}` | `enemy0_relSin`, `enemy2_hasFlag` | Vijand in slot N |
| `teammate{N}_{suffix}` | `teammate0_present`, `teammate1_distance_norm` | Teamgenoot in slot N |

N = 0 is altijd de **dichtstbijzijnde** speler in die categorie. Hogere N = verder weg.

---

## 3. Beschikbare features per slot

### Enemy features (14 per slot)

De egocentrische kern-suffixes per enemy-slot zoals gedefinieerd in `EnemySlotFeatureComponent` (12 enemy-only suffixes) plus de gedeelde status-features (`hasFlag`, `visible`). De snelheids- (`velocity*_norm`), collision- en projectiel-features zijn gedeeld via `PlayerDtoFeatureResolver` en worden automatisch beschikbaar voor elk enemy-slot.

| Suffix | Bereik | Categorie | Beschrijving |
|---|---|---|---|
| `isAlive` | 0/1 | Status | Vijand bestaat en health > 0 (guard) |
| `visible` | 0/1 | Status (gedeeld) | Line-of-sight naar vijand |
| `hasFlag` | 0/1 | Status (gedeeld) | Vijand draagt een vlag |
| `relSin` | [-1, 1] | Egocentrisch | Sinus hoek naar vijand t.o.v. kijkrichting bot |
| `relCos` | [-1, 1] | Egocentrisch | Cosinus hoek naar vijand |
| `forwardDist_norm` | [0, 1] | Egocentrisch | Projectie afstand op forward-as |
| `rightDist_norm` | [0, 1] | Egocentrisch | Projectie afstand op right-as |
| `distance_norm` | [0, 1] | Egocentrisch | 3D-afstand genormaliseerd |
| `pitchBearing_norm` | [-1, 1] | Egocentrisch | Verticale bearing naar vijand (pitch) |
| `aimAlignmentDot_norm` | [-1, 1] | Egocentrisch | Dot van bot-aim met richting-naar-vijand |
| `relVelForward_norm` | [-1, 1] | Egocentrisch | Vijand-velocity op bot forward-as (lead-aim) |
| `relVelRight_norm` | [-1, 1] | Egocentrisch | Vijand-velocity op bot right-as |
| `relVelUp_norm` | [-1, 1] | Egocentrisch | Vijand-velocity op bot up-as |
| `relZ_norm` | [-1, 1] | Egocentrisch | Verticale offset vijand (tanh op 512 UU) |

### Teammate features (7 per slot)

| Suffix | Bereik | Categorie | Beschrijving |
|---|---|---|---|
| `present` | 0/1 | Status | Teamgenoot bestaat en health > 0 |
| `hasFlag` | 0/1 | Status | Teamgenoot draagt vijandvlag |
| `relSin` | [-1, 1] | Egocentrisch | Sinus hoek naar teamgenoot |
| `relCos` | [-1, 1] | Egocentrisch | Cosinus hoek naar teamgenoot |
| `forwardDist_norm` | [0, 1] | Egocentrisch | Projectie op forward-as |
| `rightDist_norm` | [0, 1] | Egocentrisch | Projectie op right-as |
| `distance_norm` | [0, 1] | Egocentrisch | 3D-afstand genormaliseerd |

---

## 4. Feature-verdeling per categorie

| Slot-categorie | Features per slot | Gebruik |
|---|---:|---|
| `enemy0` | Volledig pakket | Directe bedreiging — egocentrische kern + gedeelde collision + projectielen |
| `enemy1..4` | Subset | Verder weg — kern-features (ego + status) volstaan |
| `teammate0..3` | 7 | Coordinatie — positie en vlag-status |

Uitbreiden van actieve slots vereist alleen wijzigingen in `features.json`.

---

## 5. Slot-toewijzing

### Dataflow

```
+---------------------------------------------------------------------+
|  UT99 Dedicated Server                                              |
|                                                                     |
|  Webservice -> GET /gamestate                                       |
|  +-----------------------------------------------------------+     |
|  |  Players[] array: ALLE niet-spectator spelers              |     |
|  |  (ongesorteerd, inclusief self)                             |     |
|  +----------------------------+------------------------------+     |
+--------------------------------|------------------------------------+
                                 | JSON
                                 v
+---------------------------------------------------------------------+
|  Bot Process                                                        |
|                                                                     |
|  1. Classificatie                                                   |
|     +----------------------------------------------------------+   |
|     |  Identificeer self op naam                                |   |
|     |  Splits in vijanden + teamgenoten                         |   |
|     |  Sorteer op 2D-afstand (pure distance-sort, geen state)   |   |
|     |                                                           |   |
|     |  enemies[0] = dichtstbijzijnde vijand                     |   |
|     |  enemies[1] = tweede vijand (of null)                     |   |
|     |  teammates[0] = dichtstbijzijnde teamgenoot               |   |
|     +----------------------------------------------------------+   |
|                                                                     |
|  2. Egocentrische verrijking (per bezet slot)                       |
|     +----------------------------------------------------------+   |
|     |  Bereken relSin, relCos, forwardDist, rightDist,          |   |
|     |  distance_norm vanuit bot-perspectief                     |   |
|     +----------------------------------------------------------+   |
|                                                                     |
|  3. Feature resolution                                              |
|     +----------------------------------------------------------+   |
|     |  Feature ID "enemy2_relSin"                               |   |
|     |    -> parse: slot=2, suffix="relSin"                      |   |
|     |    -> lees verrijkingsdata voor slot 2                    |   |
|     |    -> return float waarde (of 0.0 als slot leeg)          |   |
|     +----------------------------------------------------------+   |
+---------------------------------------------------------------------+
```

### Slot-volgorde: pure afstand-sortering

`PlayerSlotConverter` doet een **pure distance-sort** (dichtstbij = slot 0) zonder per-sessie state. Eerdere hysteresis is **verwijderd**: met de permutatie-invariante pooling in het model (gedeelde encoder + mean/max-pool over slots) is slot-volgorde architectureel irrelevant — identieke output ongeacht welke speler welk slot bezet. Distance-sort blijft alleen om de CSV feature-layout deterministisch te houden. Hysteresis introduceerde bovendien stale slot-toewijzingen (een verre "incumbent" hield zijn slot terwijl een dichtere bedreiging naar een later slot werd geduwd).

### Guard-condities

```
    +------------------------------------------------------+
    |  enemy{N}_present = 1                                |
    |  +----------------------------------------------+   |
    |  |  Alle features bevatten echte data            |   |
    |  +----------------------------------------------+   |
    |                                                      |
    |  enemy{N}_present = 0                                |
    |  +----------------------------------------------+   |
    |  |  Alle features = 0.0 (zero-padded)            |   |
    |  |  Reden: slot leeg, vijand dood,               |   |
    |  |  of minder vijanden dan slots                 |   |
    |  +----------------------------------------------+   |
    +------------------------------------------------------+
```

---

## 6. Dynamische feature resolution

### Hoe feature IDs worden gerouted

```
    features.json: "enemy2_distance_norm"
           |
           v
    Feature service
    +----------------------------------------------------+
    |  Feature index (gebouwd bij startup):               |
    |                                                    |
    |  "enemy0_isAlive"      -> Enemy slot component     |
    |  "enemy0_relSin"       -> Enemy slot component     |
    |  "enemy2_distance_norm"-> Enemy slot component     |
    |  "teammate0_isAlive"   -> Teammate slot component  |
    |  "teammate3_relCos"    -> Teammate slot component  |
    |  ...                                               |
    |                                                    |
    |  Een component handelt ALLE slots af               |
    |  (slots 0..6 pre-geregistreerd)                    |
    +----------------------------+-----------------------+
                                 |
                                 v
    Feature resolver
    +----------------------------------------------------+
    |  "enemy2_distance_norm"                            |
    |    |                                               |
    |    +-- parse prefix: "enemy" -> enemy-categorie    |
    |    +-- parse slot:   "2"     -> index 2            |
    |    +-- parse suffix: "distance_norm" -> feature type|
    |    |                                               |
    |    +-- lees enemies[2] egocentrische afstand       |
    |        -> return genormaliseerde float             |
    +----------------------------------------------------+
```

### Component-registratie

| Component | Pattern | Max slots | Features per slot |
|---|---|---|---|
| Enemy slot component | `enemy{0..6}_*` | 7 | 20 |
| Teammate slot component | `teammate{0..6}_*` | 7 | 7 |

Totaal pre-geregistreerde feature IDs: 7 x 20 + 7 x 7 = **189 feature IDs**. Alleen de IDs die in `features.json` staan worden daadwerkelijk gebruikt.

---

## 7. WorldFacts (mission-input)

WorldFacts is de brug tussen de DTO-laag en de mission-policies. Het bevat afgeleide feiten voor enemy0, enemy1 en teammate0:

### Enemy0 (dichtstbijzijnde vijand)

| Veld | Type | Beschrijving |
|---|---|---|
| `enemyPresent` | boolean | Enemy0 bestaat en health > 0 |
| `enemyVisible` | boolean | Enemy0 zichtbaar (line-of-sight) |
| `enemyHasFlag` | boolean | Enemy0 draagt een vlag |
| `enemyDistanceNorm` | double | Genormaliseerde 2D-afstand |
| `enemyFacingUs` | boolean | Enemy0 kijkt naar ons (60-graden cone) |
| `enemyFiring` | boolean | Enemy0 vuurt |

### Enemy1 (tweede vijand)

| Veld | Type | Beschrijving |
|---|---|---|
| `enemy1Present` | boolean | Enemy1 bestaat en health > 0 |
| `enemy1Visible` | boolean | Enemy1 zichtbaar |
| `enemy1HasFlag` | boolean | Enemy1 draagt een vlag |
| `enemy1DistanceNorm` | double | Genormaliseerde 2D-afstand |

### Teammate0 (dichtstbijzijnde teamgenoot)

| Veld | Type | Beschrijving |
|---|---|---|
| `teammatePresent` | boolean | Teamgenoot bestaat en health > 0 |
| `teammateHasFlag` | boolean | Teamgenoot draagt vijandvlag |
| `teammateDistanceNorm` | double | Genormaliseerde 2D-afstand |

### Carrier-detectie

| Veld | Type | Beschrijving |
|---|---|---|
| `enemyTeamHasOurFlag` | boolean | Onze vlag wordt gedragen (onafhankelijk van slot) |
| `carrierIsPlayer1` | boolean | Enemy0 is de carrier |
| `carrierIsEnemy1` | boolean | Enemy1 is de carrier |

### Helper-methoden

| Methode | Beschrijving |
|---|---|
| `enemyNearby()` | Enemy0 binnen engagement range (< 0.25 norm) |
| `anyEnemyNearby()` | Enemy0 of enemy1 binnen engagement range |

---

## 8. Augmentatie

Bij yaw-rotatie-augmentatie worden egocentrische slot-features (`relSin/relCos`, `forwardDist_norm/rightDist_norm`) herberekend. Status- en afstandsfeatures blijven ongewijzigd.

Zie [egocentric-player-slot-augmenter.md](../augmentation/features/egocentric-player-slot-augmenter.md) voor details.

---

## 9. Perspectief-normalisatie (rood team)

De perspectief-normalizer transformeert features van rood naar blauw perspectief (180-graden rotatie). De per-enemy yaw-relatieve collision-swaps zijn **verwijderd** uit `CanonicalPerspectiveNormalizer.buildSwapList()`: `enemy{N}_fwdCollision_norm` is een raytrace in het lichaamsframe van die vijand zelf — onder de wereld-flip roteert de vijand mee en blijft de raytrace-waarde identiek, dus een fwd↔back-swap corrumpeerde het signaal. Met permutatie-invariante pooling is het punt sowieso irrelevant.

Egocentrische features (`relSin`, `relCos`, `forwardDist`, `rightDist`, `distance_norm`) zijn wiskundig invariant onder 180-graden rotatie en worden **niet** getransformeerd. Zie [perspective-normalization.md](../augmentation/perspective-normalization.md) voor het wiskundige bewijs.

---

## 10. Configuratieconstanten

| Constante | Waarde | Beschrijving |
|---|---:|---|
| MAX_ENEMY_SLOTS | 7 | Max vijandslots in de DTO array (`PlayerSlotConverter`) |
| MAX_TEAMMATE_SLOTS | 7 | Max teamgenootslots in de DTO array (`PlayerSlotConverter`) |
| MAX_SLOTS (enemy/teammate) | 7 | Max pre-geregistreerde slots (`EnemySlotFeatureComponent`) |
| DIST_TAU | 600.0 | Softmax tau voor afstandsnormalisatie |

`MAX_ENEMY_SLOTS`/`MAX_TEAMMATE_SLOTS` (beide 7 in Java) zijn de DTO-array-groottes; tot 8v8 (7 enemies + 7 teammates) past. Het **werkelijk gevoede** aantal slots is een keuze in `features.json` (momenteel 5 enemy- en 4 teammate-slots voor 5v5 CTF) — dat is een subset van de 7 pre-geregistreerde slots, niet een aparte runtime-constante. De feature-components registreren slots 0..6 zodat `features.json` vrij kan kiezen.
