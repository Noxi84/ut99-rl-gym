# Vlagfeatures

## Overzicht

Capture The Flag (CTF) kent twee vlaggen: een rode en een blauwe. De bot moet de vijandelijke vlag ophalen en terugbrengen naar de eigen basis om te scoren. Vlaggen kennen drie toestanden: op de basis (home), gedragen door een speler (held), of gevallen op de grond (dropped).

Alle vlagfeatures gebruiken de termen "home" en "enemy" op basis van het team van de bot. Het model is team-agnostisch: het werkt altijd met "eigen vlag" en "vijandelijke vlag".

---

## Home/Enemy Mapping

```
    Bot op BLAUW team (team=1):         Bot op ROOD team (team=0):
    +-- home flag  = blauwe vlag        +-- home flag  = rode vlag
    +-- enemy flag = rode vlag          +-- enemy flag = blauwe vlag
    +-- homeBase   = blauwe basis       +-- homeBase   = rode basis
    +-- enemyBase  = rode basis         +-- enemyBase  = blauwe basis
```

---

## Datapipeline

```
UT99 webservice
    -> Leest vlag-actors (Team, Location, HomeBase, status, Holder)
    -> Vuurt collision rays + LOS rays per vlag af
    -> JSON: Flags array met per vlag alle velden
    -> Feature resolver: team-mapping + egocentrische berekening
    -> Normalisatie
    -> Model input
```

---

## Vlagstatus features

| Feature | Bereik | Beschrijving |
|---|---|---|
| `self_hasFlag` | 0 of 1 | Bot draagt de vijandelijke vlag |
| `homeFlagHasHolder` | 0 of 1 | Eigen vlag wordt gedragen door een vijand |
| `enemyFlagHasHolder` | 0 of 1 | Vijandelijke vlag wordt gedragen |

## Egocentrische vlag-richtingen

Richting en afstand relatief aan de kijkrichting van de bot:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `enemyFlag_relSin` | [-1, 1] | Sinus van de relatieve hoek naar de vijandelijke vlag |
| `enemyFlag_relCos` | [-1, 1] | Cosinus van de relatieve hoek naar de vijandelijke vlag |
| `homeFlag_relSin` | [-1, 1] | Sinus van de relatieve hoek naar de eigen vlag |
| `homeFlag_relCos` | [-1, 1] | Cosinus van de relatieve hoek naar de eigen vlag |
| `homeBase_relSin` | [-1, 1] | Sinus van de relatieve hoek naar de eigen basispositie |
| `homeBase_relCos` | [-1, 1] | Cosinus van de relatieve hoek naar de eigen basispositie |

`relCos` = 1.0 als de bot recht naar het doel kijkt, -1.0 als het doel achter de bot ligt. `relSin` geeft de zijwaartse afwijking: positief = rechts, negatief = links.

## Base-afstanden

| Feature | Bereik | Beschrijving |
|---|---|---|
| `homeBaseDistance_norm` | [0, 1] | Scalaire afstand naar eigen basis |
| `enemyBaseDistance_norm` | [0, 1] | Scalaire afstand naar vijandelijke basis |

## Egocentrische base-richtingen

| Feature | Bereik | Beschrijving |
|---|---|---|
| `homeBase_relSin` | [-1, 1] | Bearing naar eigen basis (sinus) |
| `homeBase_relCos` | [-1, 1] | Bearing naar eigen basis (cosinus) |
| `enemyBase_relSin` | [-1, 1] | Bearing naar vijandelijke basis (sinus) |
| `enemyBase_relCos` | [-1, 1] | Bearing naar vijandelijke basis (cosinus) |

---

## homeFlag vs homeBase

| Doel | Features | Gedrag |
|---|---|---|
| **homeFlag** | `homeFlag_relSin/relCos` | De **bewegende** eigen vlag -- kan op de basis staan, gedragen worden, of ergens gevallen liggen |
| **homeBase** | `homeBase_relSin/relCos` | De **vaste** basispositie -- verandert nooit, de plek waar de bot de vijandelijke vlag moet afleveren |

Wanneer de eigen vlag op de basis staat, zijn `homeFlag_*` en `homeBase_*` identiek. Zodra een vijand de vlag oppakt, gaan ze uiteen.

---

## Auto-return timer (gedropte vlag)

De engine returnt een gedropte vlag automatisch naar de basis na een configureerbare timer (standaard 25 seconden). De feature-resolver leidt de resterende timer per tick af uit status-overgangen.

| Feature | Bereik | Beschrijving |
|---|---|---|
| `homeFlag_dropReturnRemaining_norm` | [0, 1] | 1.0 = net gedropt (25s over), 0.0 = niet gedropt of bijna terug |
| `enemyFlag_dropReturnRemaining_norm` | [0, 1] | Idem voor vijandelijke vlag |

Berekening:

```
status overgang != DROPPED -> DROPPED  : dropStart = elapsedTime
status == DROPPED                      : remaining = max(0, T_auto - (elapsedTime - dropStart))
status != DROPPED                      : remaining = 0

T_auto = gameplay.flag_drop_auto_return_seconds (standaard 25.0)
Normalisatie: remaining / T_auto
```

---

## Navigation target

De huidige missie (capture, return, intercept) bepaalt een unified bearing naar het missiedoel:

| Feature | Bereik | Beschrijving |
|---|---|---|
| `navTarget_relSin` | [-1, 1] | Bearing naar huidig missiedoel (sinus) |
| `navTarget_relCos` | [-1, 1] | Bearing naar huidig missiedoel (cosinus) |

Twee zone-squashes maken de bearing neutraal `(0, 1)` op basis van een effectieve afstand
`max(0, dist − R)` (gedeelde resolvers, synchroon met de `objective_progress`-floor — dual source):

- **Carrier staging-zone** (`CarrierObjectiveResolver`): de carrier binnen de zone rond eigen base
  wanneer de capture geblokkeerd is en een enemy over midfield staat.
- **Escort-standoff / capture-funnel** (`EscortObjectiveResolver`, 2026-06-06): doel is de
  teammate-carrier (priority-6 met CARRIED enemy-vlag) → bearing wijst naar de standoff-band-rand
  (250 UU) en squasht binnen de band naar neutraal; is de **capture-funnel** actief (own flag HOME +
  carrier < 500 UU van base) dan is de bearing volledig neutraal — geen feature-richting die de
  escort de capture-route van de carrier op stuurt. Zie
  [mission-architecture.md](../policy/mission-architecture.md#escort-standoff--capture-funnel-release-de-teamgenoot-blokkeert-de-capture-niet-2026-06-06).

---

## Temporele vensters

| Groep | Venster (first, last) | Beschrijving |
|---|---|---|
| Flag status | (0, 1) | Alleen huidig frame -- status verandert traag |
| Goal direction | (0, 1) | Alleen huidig frame |
| Base distances | (0, 1) | Alleen huidig frame |
| Navigation target | (0, 1) | Alleen huidig frame |

---

## Augmentatie

Bij mirror-augmentatie worden alle kijkrichting-relatieve vlagfeatures (`relSin/relCos`) herberekend vanuit de gespiegelde kijkrichting. Toestandsvlaggen (hasHolder, dropRemaining) en base-afstanden blijven ongewijzigd.
