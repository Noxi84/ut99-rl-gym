# Team Feature en Perspectief-normalisatie

## Overzicht

De `self_team_norm`-feature identificeert op welk team de bot speelt en geeft het model expliciet onderscheid tussen rood- en blauw-specifieke kaartgeometrie.

> **Status:** `self_team_norm` wordt door de feature-resolver *resolved* en door `CanonicalPerspectiveNormalizer` afgehandeld (override naar 1.0 voor rood), maar staat momenteel **niet** in `resources/models/rl_pawn/features.json` — het is dus een resolved-maar-niet-actief-gevoede feature. De normalizer slaat de override simpelweg over wanneer de feature afwezig is in de feature-volgorde.

Trainingsdata wordt opgenomen op **blauw team** (team=1). Bij runtime transformeert **canonical perspective normalization** rode bots naar blauw-perspectief — zodat het model altijd denkt dat het blauw speelt.

## Feature

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_team_norm` | 0 of 1 | Team van de bot (0 = rood, 1 = blauw) |

In de runtime-genormaliseerde DTO wordt `self_team_norm` voor rode bots **overschreven naar 1.0** (canonieke waarde) zodat het model consistent blauw-perspectief input krijgt.

## Datapipeline

```
UT99 Server -> PlayerReplicationInfo.Team (int: 0 rood, 1 blauw)
    |
    v
JSON: Players[].Team
    |
    v
Bot: team -> feature resolver
    |
    v
Runtime-normalisatie (rood bot -> self_team_norm = 1.0, 180-graden kaartrotatie features)
    |
    v
Model input: feature "self_team_norm"
```

## Rol in het model

Wanneer `self_team_norm` actief gevoed wordt, is de waarde in de runtime altijd 1 door de normalizer; de feature blijft aanwezig zodat het model:

1. In de CSV-trainingsdata consistent teamcontext krijgt
2. In self-play op beide teams tegelijk draait zonder extra runtime-transform

```
    BLAUW TEAM (native blauw)           ROOD TEAM (gespiegeld naar blauw)

    +------------------------+          +------------------------+
    | * rode vlag            |          | * rode vlag (na        |
    |   (enemy objective)    |          |   180-graden rotatie)  |
    |                        |          |                        |
    |        KAART           |          |        KAART           |
    |                        |          |                        |
    | # blauwe basis         |          | # blauwe basis         |
    |   (home)               |          |   (home, originele     |
    |                        |          |    rode basis)         |
    +------------------------+          +------------------------+

    Home/enemy mapping wordt afgehandeld door de vlag-feature component
    op basis van het team. De perspectief-normalizer transformeert
    de wereldcoordinaten zodat beide teams dezelfde blauw-relatieve
    input zien.
```

## Configuratie

| Pad | Beschrijving |
|---|---|
| `resources/models/rl_pawn/features.json` | feature-volgorde; `self_team_norm` is momenteel niet opgenomen |
| `resources/config/maps/<mapKey>.json` | Kaartconfiguratie per map |

Zie [perspective-normalization.md](../augmentation/perspective-normalization.md) voor de volledige normalisatie-architectuur.
