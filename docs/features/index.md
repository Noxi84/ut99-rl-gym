# Feature Documentatie

Overzicht van alle features voor het `rl_pawn` joint model. Elke feature-beschrijving bevat de datapipeline (van webservice tot modelinvoer) en normalisatie. Augmentatie-documentatie staat apart in [../augmentation/](../augmentation/training-augmentation-architecture.md).

## Feature-bestanden

| Bestand | Onderwerp | Augmentatie |
|---|---|---|
| [bot-state.md](bot-state.md) | Botstoestand (positie, kijkrichting, snelheid, dodge, status) | AugmentedFeatureResolver (mirror) |
| [collision-rays.md](collision-rays.md) | Collision rays (self 16+16, per-enemy 16+16, floor/drop 8, verticale probes 2) | AugmentedFeatureResolver (ring-interpolatie) |
| [enemy-features.md](enemy-features.md) | Enemy-slots (5 slots x 14 features) + projectielen per enemy | AugmentedFeatureResolver (mirror) |
| [feature-groups-architecture.md](feature-groups-architecture.md) | Temporele feature-groepen, window-masking, data-flow | -- |
| [flag-features.md](flag-features.md) | Vlag-features (relatief + LOS-rays per vlag) | AugmentedFeatureResolver (mirror) |
| [input-and-map.md](input-and-map.md) | Schietinvoer, kaart-ID, match-context, weapon state | Geen |
| [map-movers.md](map-movers.md) | Map movers + elevator triggers -- statische T3D-extractie naar `<map>.json` | Geen |
| [match-clock.md](match-clock.md) | Match-context features (resterende tijd, scoreverschil, fase-indicatoren) | Geen |

## Actuele features

Zie `resources/models/rl_pawn/features.json` voor de exacte, actuele feature-lijst.

### rl_pawn -- joint LSTM 640h x 2 lagen + PlayerEncoder + TargetHead, 30 Hz

49 feature-groepen, ~2279 flat features (+ 1 dynamische rewardgroups-groep). Het model ontvangt alle observaties in een enkele input-tensor en produceert alle acties in een enkele forward pass.

| Categorie | Venster (first, last) | Features | Beschrijving |
|---|---|---|---|
| Map identity | (0, 1) | 1 | Categorische map-ID (learned embedding in netwerk) |
| Goal direction | (0, 1) | 4 | Egocentrische bearings naar home/enemy base |
| Flag status | (0, 1) | 9 | self_hasFlag + vlag-bearings + holders + dropReturnRemaining |
| Player isAlive | (0, 1) | 9 | enemy0..4, teammate0..3 |
| Enemy slots (5x14) | (20, 4) | 70 | Per slot: hasFlag, bearing, distance, pitch, aimAlignment, visible, velocity (3), relatieve velocity (3), relZ |
| Teammate slots (4x7) | (20, 4) | 28 | Per slot: hasFlag, bearing, distance, pitch, visible, relZ |
| Self velocity + verticaal | (20, 4) | 5 | forward/right/speed + velocityZ + zAboveSpawn |
| Verticale probes | (0, 4) | 2 | floorBelow + ceilingAbove |
| Self collision yaw-relatief | (0, 4) | 16 | 16 stralen meedraaiend met kijkrichting |
| Self collision world-axis | (0, 4) | 16 | 16 stralen op vaste kompasrichtingen |
| Enemy collision yaw-relatief | (0, 1) | 80 | 5 slots x 16 stralen |
| Enemy collision world-axis | (0, 1) | 80 | 5 slots x 16 stralen |
| Self projectiel aanwezigheid | (0, 1) | 7 | top-7 eigen projectielen in vlucht |
| Self projectiel details | (10, 1) | 147 | 7 slots x 21 (bearing, velocity, tti, type one-hot, damage, charge) |
| View state | (0, 4) | 5 | pitch + yaw/pitch angular velocity + heading errors |
| Enemy-spawn target | (0, 4) | 8 | Sticky nearest spawn-point als alle enemies dood zijn |
| Shooting-conditioned pitch | (0, 4) | 5 | shootIntent fire/altFire + pitch-error hints |
| Fire cooldown | (0, 3) | 2 | fire + altFire cooldown counters |
| Physics state | (0, 1) | 7 | self walking/falling + enemy0..4 falling |
| Base distances | (0, 1) | 2 | Scalaire afstand naar home/enemy base |
| Rewardgroups | (0, 1) | dynamisch | Multi-hot rolcontext uit rewards.json |
| Health | (0, 1) | 10 | self + 5 enemies + 4 teammates |
| Target index one-hot | (0, 1) | 5 | Welke enemy-slot de target_head selecteert |
| Aim target index one-hot | (0, 1) | 5 | Rol-aware aim-target |
| Enemy forwardDist/rightDist | (10, 4) | 10 | 5 slots x 2 body-frame projecties |
| Onder-water / submersie | (0, 1) | 21 | self isSwimming/headUnderwater/breathRemaining (3) + enemy0..4 ×2 (10) + teammate0..3 ×2 (8) |
| Jump pads aanwezigheid | (0, 1) | 4 | top-4 dichtstbijzijnde pads |
| Jump pads details | (5, 1) | 40 | 4 slots x 10 (bearing, distance, zOffset, landing-predictie) |
| Mover aanwezigheid | (0, 1) | 12 | top-4 dichtstbijzijnde movers: present, onPlatform, isMoving |
| Mover details | (5, 1) | 44 | 4 slots x 11 (bearing, distance, zOffset, moveProgress, dest, timeToArrive, travelRange) |
| Teammate forwardDist/rightDist | (10, 4) | 8 | 4 slots x 2 body-frame projecties |
| Navigation target | (0, 1) | 2 | Bearing naar huidig missiedoel |
| Floor-elevation fan | (0, 4) | 8 | Signed omlaag-probe per sector: drop (−) / step-up (+), tanh(uu/64) |
| Low rays (voethoogte) | (0, 4) | 8 | Horizontale stralen op voethoogte voor lage-obstakel detectie |
| View rotation | (0, 3) | 2 | viewRotationX_sin/cos |
| Dodge cooldown | (0, 0) | 1 | Continue 0.0 (net gedodged) tot 1.0 (klaar) |
| Idle duration | (0, 0) | 1 | Tijd sinds laatste beweging |
| Role-tactical | (0, 1) | 3 | proximityToOwnFlag, enemyDepth, teammateCarrier |
| Match-context | (0, 1) | 5 | Resterende tijd, scoreverschil, match-fase one-hot |
| Team-aggregaten | (0, 1) | 5 | Team gemiddelde health, diepte, alive count, capture progress, dropped count |
| Enemy projectiel aanwezigheid | (0, 1) | 35 | 5 enemies x 7 projectiel-slots |
| Enemy projectiel details | (10, 1) | 735 | 5 enemies x 7 slots x 21 features |
| Ammo + weapon-mode | (0, 1) | 6 | self_ammo_currWeapon_norm + charge/multi/grenadeMode/sniping/tightWad |
| Weapon one-hot | (0, 1) | 14 | self_weapon_is* over 14 UT99 wapens |
| Translocator disc | (0, 1) | 7 | self_disc_* (present, bearing, distance, pitch, z-offset, timeSinceThrow) |
| Teammate projectiel aanwezigheid | (0, 1) | 35 | 5 teammates x 7 projectiel-slots |
| Teammate projectiel details | (10, 1) | 735 | 5 teammates x 7 slots x 21 features |
| Projectiel-enemy relatie | (10, 1) | 14 | 7 self-slots x (enemyClosestApproach, enemyTimeToClosest) |
| Weapon refire-klok | (10, 1) | 1 | weaponReadyIn_norm: resterende tijd tot wapen weer schietbaar |

**Targets (10 dimensies):**

| Index | Naam | Type | Beschrijving |
|---|---|---|---|
| 0 | `moveDir_sin` | continuous | Bewegingsrichting sinus |
| 1 | `moveDir_cos` | continuous | Bewegingsrichting cosinus |
| 2 | `dodge` | binary | Dodge activeren |
| 3 | `bJump` | binary | Springen |
| 4 | `bDuck` | binary | Hurken |
| 5 | `bIdle` | binary | Stilstaan |
| 6 | `yawDelta_norm` | steering | Horizontale kijkrichting-delta |
| 7 | `pitchDelta_norm` | steering | Verticale kijkrichting-delta |
| 8 | `bFire` | binary | Primair vuren |
| 9 | `bAltFire` | binary | Alternatief vuren |

**Aux output:** `target_index` (categorical_5 over enemy-slots) + `target_index_confidence` (weight).

**Augmentatie:** mirror only (1 identity + 1 mirrored). Yaw delta wordt geflipt; fire labels en slot-identifiers niet.

## Augmentatie-documentatie

| Document | Onderwerp |
|---|---|
| [training-augmentation-architecture.md](../augmentation/training-augmentation-architecture.md) | Pipeline, architectuur, ITrainingModel-contract |
| [yaw-rotation-augmentation.md](../augmentation/yaw-rotation-augmentation.md) | Augmentatie (mirror) |
| [perspective-normalization.md](../augmentation/perspective-normalization.md) | Runtime perspectief-normalisatie (180 graden rotatie voor rood team) |
| [features/](../augmentation/features/index.md) | Alle feature augmenters (per augmenter een apart document) |
