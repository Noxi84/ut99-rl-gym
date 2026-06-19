# Map Movers

## Overzicht

Map movers (liften, deuren, platforms) worden statisch geextraheerd uit UT99 T3D-mapbestanden en opgeslagen in `resources/config/maps/<map>.json` onder `movers[]` en `elevator_triggers[]`. Deze data is beschikbaar als offline inventaris per map.

---

## Het `movers[]` schema

Een entry per Mover-actor in de T3D. Voorbeeld (lift in DM-Morbias):

```json
{
  "name": "Mover3",
  "subclass": "Mover",
  "initial_state": "StandOpenTimed",
  "base_location": [0.0, 1856.0, -64.0],
  "key_positions": [
    [0.0, 1856.0, -64.0],
    [0.0, 1856.0, 180.0]
  ],
  "platform_bounds_local": {
    "min": [-128.0, -64.0, -64.0],
    "max": [128.0,  64.0,  16.0]
  },
  "move_time": 1.5,
  "stay_open_time": 2.0,
  "delay_time": 0.0,
  "num_keys": 2,
  "glide_type": "Linear",
  "encroach_type": "Return",
  "bump_type": "PawnBump",
  "tag": "Red"
}
```

### Velden

| Veld | Type | Beschrijving |
|---|---|---|
| `name` | string | UnrealEd-naam. Stabiel binnen een map |
| `subclass` | string | Mover / ElevatorMover / GradualMover / LoopMover / MixMover / AttachMover / AssertMover / RotatingMover |
| `initial_state` | string | Activatiemodus: BumpOpenTimed / StandOpenTimed / TriggerOpenTimed / TriggerToggle / TriggerControl / TriggerPound / BumpButton |
| `base_location` | [x,y,z] | Wereld-anker (Unreal Units, 1 unit ~ 2 cm) |
| `base_rotation` | [pitch,yaw,roll] | Rotator units (65536 = 360 graden). Alleen aanwezig als niet (0,0,0) |
| `key_positions` | [[x,y,z], ...] | Absolute wereld-coords per keyframe. Lengte = num_keys |
| `key_rotations` | [[p,y,r], ...] | Absolute rotator-coords per keyframe. Alleen als non-zero rotatie |
| `platform_bounds_local` | {min, max} | Brush-AABB in actor-lokale coordinaten |
| `move_time` | float (s) | Tijd tussen keyframes |
| `stay_open_time` | float (s) | Tijd open voor terugkeren (timed states) |
| `delay_time` | float (s) | Vertraging voor openen |
| `num_keys` | int | Aantal keyframes (typisch 2, tot 8 mogelijk) |
| `glide_type` | string | Linear (constante snelheid) of Glide (smoothed) |
| `encroach_type` | string | Gedrag bij blokkering: Stop / Return / Crush / Ignore |
| `bump_type` | string | Wie kan de trigger activeren: PlayerBump / PawnBump / AnyBump |
| `tag` | string | UnrealEd-tag voor trigger-koppeling |
| `event` | string | Event-naam die deze mover triggert na openen |

### Afleidingen voor consumers

| Afleiding | Formule |
|---|---|
| Travel range | max(\|key_positions[i] - base_location\|) over alle keyframes |
| Pure verticale lift? | Alle delta's hebben (X,Y) ~ (0,0); alleen Z varieert |
| Wereld-AABB op keyframe k | key_positions[k] + platform_bounds_local (elementwise) |
| Crush-gevaar? | encroach_type == "Crush" |
| Tactisch blokkeerbaar? | encroach_type == "Return" (eronder staan stuurt mover terug) |

---

## Het `elevator_triggers[]` schema

Aparte proximity-triggers gekoppeld aan ElevatorMovers via `event` -> mover-`tag`. Sturen een lift naar een specifiek keyframe.

```json
{
  "name": "ElevatorTrigger0",
  "location": [x, y, z],
  "goto_keyframe": 2,
  "move_time": 1.5,
  "trigger_type": "PlayerProximity",
  "event": "Lift_Main",
  "radius": 60.0,
  "height": 60.0,
  "trigger_once_only": false
}
```

`trigger_type`: PlayerProximity / PawnProximity / ClassProximity / AnyProximity / Shoot.

---

## T3D parsing

De parser itereert over alle actors in het T3D-bestand en filtert op een whitelist van 8 mover-subclasses. Belangrijke semantiek:

- `KeyPos(N)` is sparse -- alleen non-zero componenten staan in T3D. Ontbrekende componenten defaulten naar 0
- `num_keys` = max(2, 1 + hoogste index in KeyPos/KeyRot)
- `key_positions` zijn absolute wereld-coords (BasePos + KeyPos[i])
- `base_rotation` / `key_rotations` weggelaten als alles zero
- `platform_bounds_local` = AABB over alle brush-vertices in actor-lokale coords

### Idempotentie

Bij heruitvoer worden `movers[]` en `elevator_triggers[]` altijd opnieuw geserialiseerd uit T3D (T3D is de waarheid). Het bestand wordt alleen geschreven als de inhoud verschilt van de bestaande versie.

---

## Beschikbare maps

| Map | Movers | Subclasses | Initial states |
|---|---|---|---|
| DM-Morbias][ | 2 | Mover x2 | StandOpenTimed x2 |
| CTF-Niven | 5 | Mover x4 + AttachMover x1 | TriggerControl x4 + BumpOpenTimed x1 |
| CTF-Face | 0 | -- | -- |

---

## Runtime pipeline (TLV tag 0x06)

Runtime mover-state wordt per frame verstuurd via UDP (tag 0x06, 25 bytes per mover):

```
nameHash uint32 (FNV1a van actor Name — matching key met statische map data)
locX/Y/Z int32 (×10)
keyNum uint8, prevKeyNum uint8, numKeys uint8
stateFlags uint8 (bit0=bOpening, bit1=bDelaying)
moveProgress uint16 (PhysAlpha × 10000, range 0–10000)
```

### Data flow

```
UT99 Mover actor
    ↓ RLUdpStateSender.WriteMovers() — tag 0x06
    ↓ UDP
UdpStateReceiver.parseMover() → MoverState
    ↓ StateFrameToGameStateConverter.buildMovers()
GameState.Movers (MoverEntry[])
    ↓ MoverJsonToDtoConverter.enrichAll()
GameStateDto.movers (MoverDto[])
    ↓ MoverEnricher.enrichOne()
    ↓   combine runtime + static map JSON (via MapMoversResolver, nameHash matching)
    ↓   compute egocentric features (bearing, distance, onPlatform, dest, etc.)
    ↓   sort by distance, keep top 4
MoverDto[] (enriched, sorted, max 4 slots)
    ↓ MoverFeatureValueResolver
features.json self_mover{0..3}_{suffix}
```

### Feature set per slot (14 features)

| Feature | Type | Beschrijving |
|---|---|---|
| `present` | bit | Slot bezet |
| `relSin` | [-1,1] | Bearing sinus (egocentrisch) |
| `relCos` | [-1,1] | Bearing cosinus (egocentrisch) |
| `distance_norm` | [0,1] | Soft distance (tau=600) |
| `forwardDist_norm` | [-1,1] | Body-frame forward projectie |
| `rightDist_norm` | [-1,1] | Body-frame right projectie |
| `zOffset_norm` | [-1,1] | Verticaal verschil bot↔mover (tanh/512) |
| `onPlatform` | bit | Bot staat op dit platform (bounds + Z check) |
| `isMoving` | bit | Mover is in transitie (bOpening) |
| `moveProgress_norm` | [0,1] | Interpolatie-voortgang (PhysAlpha) |
| `destZOffset_norm` | [-1,1] | Verticaal verschil bot↔destination keyframe |
| `destDistance_norm` | [0,1] | Soft distance tot destination keyframe |
| `timeToArrive_norm` | [0,1] | Genormaliseerde resterende reistijd (tau=5s) |
| `travelRange_norm` | [0,1] | Totale verticale reisafstand (tau=600, karakteriseert lift-hoogte) |

### Perspectief-normalisatie

Mover features zijn egocentrisch (relSin/relCos = bearing − bot.yaw) en invariant onder de 180° team-perspectief-rotatie, net als pickup features. Geen transformatie nodig in CanonicalPerspectiveNormalizer.

### onPlatform berekening

Bot is "op het platform" als zijn positie binnen de mover's lokale AABB valt (platform_bounds_local), met een Z-threshold van 80 UU boven/onder het platform.

---

## Hergeneratie

```bash
./mvnw package -DskipTests
bash scripts/deploy/extract-map-bounds.sh "DM-Morbias]["   # specifieke map
bash scripts/deploy/extract-map-bounds.sh --all            # alle maps
bash scripts/deploy/extract-map-bounds.sh --discover       # maps uit recordings
```
