# Botstoestand

## Overzicht

De botstoestand bevat de basale eigenschappen van de bot: positie, kijkrichting, snelheid, dodge state, gezondheid en physics-toestand. Deze vormen de kern van de zelf-beschrijving in elk frame.

Genormaliseerde features (`_norm`, `_sin`, `_cos`) worden als tijdlijnfeatures aan het model gevoed.

---

## Datapipeline

```
UT99 webservice
    |
    v
Leest speler-properties (Location, ViewRotation, Velocity, etc.)
    |
    v
JSON: Players array, per speler alle velden
    |
    v
Feature resolvers -- normalisatie per feature type
    |
    v
Model input (tijdlijnfeatures in LSTM-sequentie)
```

---

## Feature-tabellen

### Positie en verticale context

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_zAboveSpawn_norm` | [-1, 1] | Verticale offset t.o.v. team-spawn mediaan. tanh(dz/1024). Positief = boven team-base niveau |
| `self_floorBelow_norm` | [0, 1] | Strikt downward trace. 0 = grounded, 1 = mid-air over deep void. Lineair clamp 1024 UU |
| `self_ceilingAbove_norm` | [0, 1] | Strikt upward trace. 0 = head against ceiling, 1 = open sky. Lineair clamp 512 UU |

> `locationX_norm`/`locationY_norm` worden in Java berekend (edge-squash genormaliseerd naar de kaartgrenzen) maar zijn **geen** model-input: ze staan niet in `features.json` en worden niet aan het netwerk gevoed. Net als de Z-positie is de absolute positie bewust uitgesloten — de bot beschrijft zijn omgeving egocentrisch. De edge-squash-normalisatie hieronder beschrijft hoe deze X/Y-waarden geresolved worden.

De Z-positie is bewust egocentrisch en niet als absolute `locationZ_norm` beschikbaar. Map-bounds variieren per map (Coret Z-span ~970 UU, Face/Orbital ~3100 UU, KGalleon 12800 UU); een absolute Z_norm zou per map een ander concept aanduiden en is niet overdraagbaar.

### Kijkrichting

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_viewRotationX_sin` | [-1, 1] | Yaw sinus-component op de eenheidscirkel |
| `self_viewRotationX_cos` | [-1, 1] | Yaw cosinus-component op de eenheidscirkel |
| `self_viewRotationY_norm` | [-1, 1] | Pitch genormaliseerd. Positief = omhoog, negatief = omlaag |
| `yawAngularVelocity_norm` | [-1, 1] | Hoeksnelheid yaw |
| `pitchAngularVelocity_norm` | [-1, 1] | Hoeksnelheid pitch |
| `headingTargetPitchError_norm` | [-1, 1] | Pitch-fout naar huidig aim-doel |
| `headingTargetYawError_norm` | [-1, 1] | Yaw-fout naar huidig aim-doel |

Yaw wordt gerepresenteerd als sin/cos-paar op de eenheidscirkel om de discontinuiteit bij 0/65535 te vermijden. Pitch gebruikt speciale normalisatie vanwege UT99's 16-bit unsigned rotatie met wrap-around.

### Snelheid

| Feature | Bereik | Normalisatie | Beschrijving |
|---|---|---|---|
| `self_forwardVelocity_norm` | [-1, 1] | /1000 | Snelheid geprojecteerd op kijkrichting |
| `self_rightVelocity_norm` | [-1, 1] | /1000 | Snelheid geprojecteerd op rechter-as |
| `self_speed_norm` | [0, 1] | /1000 | Absolute snelheidsgrootte |
| `self_velocityZ_norm` | [-1, 1] | /1000 | Verticale snelheid. Positief = omhoog, negatief = val |

`self_velocityZ_norm` is bijzonder informatief: positieve waarden duiden op een sprong of opwaartse beweging, negatieve op een val. Het complement van de physics-vlaggen: stilstaan op de grond (vz=0, isWalking) vs apex van een sprong (vz~0, isFalling) zijn anders ononderscheidbaar.

### Dodge

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_dodgeCooldown_norm` | [0, 1] | Continue waarde: 0.0 = net gedodged, 1.0 = klaar voor volgende dodge |

### Physics state

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_physics_isWalking` | 0 of 1 | Op de grond (volledige strafe + dodge) |
| `self_physics_isFalling` | 0 of 1 | In de lucht (geen dodge, gereduceerde air-control) |
| `self_physics_isSwimming` | 0 of 1 | In water (lichaam onder de waterlijn → PHYS_Swimming) |

### Onder water / submersion

UT99-water in drie lagen: `physics_isSwimming` = lichaam onder de waterlijn (engine schakelt naar PHYS_Swimming = `Region.Zone.bWaterZone`); `headUnderwater` = hoofd volledig ondergedoken (`HeadRegion.Zone.bWaterZone`), waarna de adem afloopt en verdrinkingsschade volgt; `breathRemaining_norm` = resterende adem. De bot ziet dit voor zichzelf én voor alle andere spelers, zodat hij weet wanneer op te duiken en welke tegenstanders traag/voorspelbaar (of aan het verdrinken) zijn.

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_headUnderwater` | 0 of 1 | Eigen hoofd onder water (adem loopt af, verdrinkingsschade) |
| `self_breathRemaining_norm` | [0, 1] | Resterende adem (1.0 = volle longen / boven water, 0.0 = verdrinkt nu) |
| `enemy{0..4}_physics_isSwimming` | 0 of 1 | Tegenstander in water |
| `enemy{0..4}_headUnderwater` | 0 of 1 | Tegenstander volledig ondergedoken |
| `teammate{0..3}_physics_isSwimming` | 0 of 1 | Teamgenoot in water |
| `teammate{0..3}_headUnderwater` | 0 of 1 | Teamgenoot volledig ondergedoken |

> Onder water stuurt de bot 3D: horizontaal via de move-heading, verticaal via de kijk-pitch (omhoog kijken + vooruit = stijgen) plus jump = naar de oppervlakte / duck = duiken. Zie `RLBot.uc:ComputeDestination`.

### Gezondheid en status

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_health_norm` | [0, 1] | Genormaliseerde gezondheid |
| `self_hasFlag` | 0 of 1 | Bot draagt de vijandelijke vlag |

> `team_norm` (teamindex, 0 = rood / 1 = blauw) wordt in Java geresolved t.b.v. de perspectief-normalisatie, maar is **geen** model-input: het zit niet in `features.json` en wordt niet aan het netwerk gevoed (zie [perspective-normalization.md](../augmentation/perspective-normalization.md)).

### Idle duration

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_timeSinceLastMove_norm` | [0, 1] | Tijd sinds laatste beweging. 0.0 = beweegt nu / net gestopt, 1.0 = idle voor >= geconfigureerde duur |

---

## Edge-squash positienormalisatie

Posities (`locationX/Y`) worden genormaliseerd naar [-1, 1] met een zachte edge-squash functie. De kaartgrenzen worden per kaart gelezen uit `resources/config/maps/<mapKey>.json`. Deze genormaliseerde X/Y-waarden worden wel in Java geresolved maar **niet** aan het model gevoed (zie de noot bij de positie-tabel hierboven); de normalisatie wordt hier gedocumenteerd voor volledigheid.

```
    Invoer: ruwe positie in Unreal Units

    Stap 1 -- Lineaire normalisatie:
    v = positie / maxAfstand          (naar [-1, 1], geklemd)

    Stap 2 -- Toepassing:
    +-- |v| <= edge (0.98)  ->  resultaat = v  (lineair, ongewijzigd)
    +-- |v| >  edge (0.98)  ->  zachte tanh-squash in de randzone

    Squash-formule (randzone):
    t = (|v| - edge) / (1 - edge)
    s = edge + (1 - edge) * tanh(k * t) / tanh(k)

    Voorbeeld (edge=0.98, k=3.0):
    +-- v = 0.50  ->  0.50   (lineair, ver van de rand)
    +-- v = 0.98  ->  0.98   (precies op de drempel)
    +-- v = 0.99  ->  ~0.99  (zachte squash begint)
    +-- v = 1.00  ->  1.00   (verzadigd, niet hard geklipt)
```

Het voordeel: posities binnen de kaartgrenzen blijven vrijwel lineair, extreme waarden aan de randen knippen niet hard af maar verzadigen geleidelijk.

---

## Augmentatie

Bij mirror-augmentatie worden herberekend:
- `self_forwardVelocity_norm` en `self_rightVelocity_norm`
- `self_viewRotationX_sin` en `self_viewRotationX_cos`

Wereldas features en pitch worden niet geaugmenteerd.
