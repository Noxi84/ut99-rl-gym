# Geodesisch Afstandsveld (route-afstand voor progress-shaping)

**Status:** LIVE (2026-06-10). Veld-data per map is optioneel; zonder veld is het gedrag exact
het oude (euclidische afstanden).

## Probleem

`objective_progress` shaped op afstand-tot-objective. Met **euclidische** (vogelvlucht-)afstand
ontstaat op maps met gangen/obstakels een lokaal optimum: in een gang die eerst van het doel
wég loopt, wijst de gradient het doodlopende uiteinde in ("sta tegen de muur, zo dicht mogelijk
bij de vlag" wordt lokaal optimaal). PBRS-theorie garandeert dat het optimum niet verandert,
maar de **eerste ontdekking** van de route wordt actief tegengewerkt — exploratie moet een
reward-dal door, en dat schaalt slecht met de lengte van het tegengestelde segment.

De fix: meet afstand **langs de beloopbare ruimte** (geodesisch). Dan wijst de gradient in de
gang gewoon naar de uitgang en bestaat het lokale optimum per constructie niet.

## Principe: bezoekgraaf uit gameplay — geen UT99-pathfinding

Het veld wordt offline gebouwd uit **geobserveerde gameplay**: elke pawn-positie is bewijs van
beloopbaarheid; elke overgang tussen twee opeenvolgende frames van dezelfde pawn is bewijs dat
je van A naar B kunt. De wereld wordt gevoxeliseerd (96 UU); transities vormen een **directed
graph** (val-routes tellen alleen omlaag; lifts en jumps zitten er automatisch correct in).
Afstand = kortste pad door die graaf (Dijkstra).

Bewuste grenzen:

- **Geen UT99-pathfinding/PathNodes**: de bron is observatie van gedrag (zoals BC-demo's), niet
  het engine-navigatienetwerk.
- **Alleen de reward-Φ** gebruikt het veld (`ObjectiveProgressReward`). Observatie-features
  (navTarget-bearing) blijven kaal — het model moet zelf leren navigeren; waypoint-following
  via features zou gescript gedrag zijn.
- **Geen T3D/CSG-walkability**: walkable ruimte uit brush-geometrie afleiden is een
  navmesh-generator (CSG-evaluatie, slopes, clearance, lifts) — foutgevoelig, en fouten in Φ
  zijn precies wat we niet willen. Het veld-formaat is bron-agnostisch: een latere
  engine-sampling-bron kan hetzelfde bestand vullen.

## Pipeline

```
gameplay-data                      builder                          runtime
─────────────                      ───────                          ───────
json-recording-sessions/*.zip ──┐
(RecordLauncher demo's;         ├─→ BuildGeodesicFieldMain ──→ resources/config/geodesic/ ──→ GeodesicFieldRepository
 alle pawns per frame)          │   • voxelise (96 UU)            <map>.geodesic.json          • per-map cache
experience-recordings/*.rec.gz ─┘   • transitie-edges                                        • verplichte flag-check
(geconverteerde/capture data)       • min-transitions filter   maps/<map>.json:          ──→ ObjectiveProgressReward
                                    • sanity (spawn-clusters)    "geodesic_field": t/f        via RouteDistances
```

### Bouwen (dev-machine)

```bash
# actieve map uit gameplay.json:
bash scripts/deploy/build-geodesic-field.sh
# of expliciet, met opties:
bash scripts/deploy/build-geodesic-field.sh CTF-Face --min-transitions 2 --dry-run
```

De builder leest default `$SESSIONS_DIR/json-recording-sessions` (recursief, alle zips) en de
geconfigureerde recordings-dir (`.rec.gz`), filtert op map (eerste frame per sessie/file),
en print sanity-stats: spawn-cluster-afstanden geodesisch vs euclidisch + bereikbaarheid.
Referentie (CTF-andACTION, 15 sessies, 1.39M frames): 1146 nodes, 3904 edges, geo ≈ eucl
(open arena — verwacht), 100% bereikbaarheid.

### Activeren

1. `"geodesic_field": true` in `resources/config/maps/<map>.json` (het veld is VERPLICHT
   aanwezig in elke map-json — `false` = euclidisch, geen silent default).
2. `resources/config/` syncen naar de play-machines (config sync is niet automatisch) + herstart.

Flag `true` zonder veld-bestand = harde fout bij eerste gebruik (fail-fast, geen fallback).

### Herbouwen

Het veld is een volledige herbouw uit wat er aan recordings ligt — periodiek herbouwen naarmate
er meer op de map gespeeld is verfijnt de dekking. Handmatig (geen auto-orchestratie).

## Runtime-semantiek

- `RouteDistances.pairTo(frame, prev, curr, target)` berekent prev/curr **als paar**: beide
  geodesisch, of (bij een miss) beide euclidisch — nooit gemengd binnen een tick. De bestaande
  ±50 UU clamp dempt metriek-wissels tússen ticks.
- Query: snap naar nabije voxels (±2 per as), `min over nodes n: ||p − c(n)|| + distGraph(n→t)`
  — continu in de bot-positie, dus per-tick delta's volgen de echte verplaatsing.
- Fallback naar euclidisch bij: `geodesic_field=false`, punt buiten dekking (bv. gevallen
  buiten de bezochte ruimte), of doel zonder route (disconnected). Shaping degradeert dus
  gracieus naar status quo.
- Geodesisch in: `clampedDistanceDelta` (objective- én own-flag-recovery-pad) en de
  EFC-threat-delta (route van de EFC naar zijn capture-punt). Euclidisch gebleven (bewust):
  `carrier_proximity` (last-mile burst), `botToEfc`-proximityFactor (bereikbaarheids-window),
  engagement-floors werken nu op de geodesische waarde (semantiek: "stop met belonen binnen X
  route-afstand" — strikt beter dan door-de-muur-banden).
- Per doel-voxel één reverse-Dijkstra, gecached (`MAX_CACHED_TARGETS`=128, clear bij overflow).
  Gemeten: ~3 µs per gecachte query; Dijkstra ~1-3 ms bij doel-voxel-wissel (bewegend doel:
  enkele keren/s).

## Self-bootstrapping: eigen play is de bron (geen demo's nodig)

Sinds 2026-06-10 logt elke bot tijdens normale play zijn eigen positie via
`PositionTraceLogger` (config: `runtime.json → position_trace`, default aan; ~7 regels/s per
bot naar `$SESSIONS_DIR/position-traces/`, retention-opruiming bij JVM-start, fail-safe — I/O-
fouten schakelen alleen de tracer uit). `build-geodesic-field.sh` pull't die traces van alle
servers (skip met `--no-pull`) en voedt er de bezoekgraaf mee. Daarmee ontstaat een vliegwiel:

```
bots exploreren → posities gelogd → veld herbouwd → reward kent de ontdekte routes
      ↑                                                        |
      └────────── bots komen verder (gradient door de route) ──┘
```

Exploratie wordt zo **collectief en permanent**: één bot die één keer door een corridor
loopt, maakt die route blijvend onderdeel van de Φ voor de hele fleet. Het herbouwen blijft
een handmatig commando (geen auto-orchestratie). De first-visit novelty-bonus
(`movement_action.first_visit_bonus`, zie rewards.json `_doc_first_visit`) levert de
exploratie-druk die nieuwe routes überhaupt doet ontstaan — veld en bonus gebruiken bewust
dezelfde 96-UU-voxelisatie.

Demo-sessies (RecordLauncher) blijven de snelste bootstrap voor een verse map — dezelfde
opnames dienen als BC-bron — maar zijn niet langer een vereiste. De per-pawn-tracks in
demo's omvatten ook stock-bots en enemies — alles wat beweegt draagt dekking bij.

## Beperkingen (v1)

- **Teleporters**: sprong > 250 UU/frame wordt als respawn gefilterd → teleporter-edges
  ontbreken. Gevolg: de Φ kent teleport-shortcuts niet (conservatief langer; nooit fout-korter).
  Op CTF-Face zijn de teleporters tower-intern en niet nodig voor flag-runs.
- **Dekking volgt gedrag**: plekken waar nooit gelopen is bestaan niet in het veld → euclidische
  fallback aldaar. Voor de shaping is dat veilig (paar-consistentie + clamp).
- **Voxel-granulariteit** (96 UU) introduceert kleine afstands-ruis; de ±50/tick clamp en de
  continue snap (afstand-tot-centroid term) houden dat onder de per-tick verplaatsing.

## Relatie met route-cost Φ (docs/TODO/route-cost-phi-design.md)

Orthogonaal: route-cost Φ herdefinieert WAT de potential meet (totale resterende route i.p.v.
huidige-subgoal-afstand; lost pickup-dips op), dit veld herdefinieert HOE afstand gemeten wordt
(geodesisch i.p.v. euclidisch; lost gang-topologie op). Bij een latere route-cost-implementatie
is `RouteDistances` de drop-in D(a,b).
