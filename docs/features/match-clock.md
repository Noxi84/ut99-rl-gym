# Match-context Features

## Overzicht

Het model ontvangt match-voortgang als expliciete features in de input-tensor. Dit stelt de policy in staat om gedrag te conditioneren op wedstrijdfase en scoreverloop -- een bot in de laatste 30 seconden van een gelijkspel speelt anders dan een bot aan het begin van een ruime voorsprong.

---

## Features

| Feature | Bereik | Beschrijving |
|---|---|---|
| `remaining_time_norm` | [0, 1] | Resterende tijd / tijdslimiet. 1.0 = match begint, 0.0 = einde |
| `score_diff_norm` | [-1, 1] | tanh((eigen_score - vijand_score) / 3.0). Perspectief-invariant via team van de bot |
| `match_phase_early` | 0 of 1 | Elapsed progress < 0.5 |
| `match_phase_mid` | 0 of 1 | 0.5 <= elapsed progress < 0.85 |
| `match_phase_late` | 0 of 1 | Elapsed progress >= 0.85 |

De continue `remaining_time_norm` biedt een smooth gradient; de drie fase-indicatoren geven het model een hard signaal dat fasegrenzen materieel zijn (vroeg/mid/laat vergt niet-lineair beleid).

---

## Gebruik door de policy

| Situatie | Optimaal beleid | Match-context signaal |
|---|---|---|
| Score 1-1, laatste 30s, vlag in eigen base | Defensief, geen risk-taking | `match_phase_late=1`, `score_diff_norm~0` |
| Score 0-1 achter, laatste 60s | Aggressief carrier-push | `match_phase_late=1`, `score_diff_norm<0` |
| Score 3-0 voor, eerste 90s | Opportunistisch | `match_phase_early=1`, `score_diff_norm>0` |
| Overtime (resterende tijd = 0) | Maximale urgentie | `remaining_time_norm=0` |

---

## Datapipeline

```
UT99 webservice
    -> GameReplicationInfo: ElapsedTime, RemainingTime, TimeLimit
    -> JSON: MapInfo { elapsedTime, remainingTime, timeLimit }
    |
    v
Feature resolver
    -> remaining_time_norm = remainingTime / (timeLimit * 60)
    -> score_diff_norm = tanh((ourScore - theirScore) / 3.0)
    -> match_phase_early/mid/late op basis van elapsed/timeLimit bins
    |
    v
Model input (venster (0,1) -- alleen huidig frame)
```

---

## Match-tijd configuratie

Match-tijd is gefixeerd op 20 minuten, server-wide geconfigureerd. Drie redenen:

| Reden | Toelichting |
|---|---|
| Voldoende late-game observaties | Op 10 min zijn late-game samples zeldzaam vanwege CTF-score-cap |
| Integer veelvoud van restart-cyclus | Drie matches per 60-min restart |
| Past op DeltaGate eval-window | Eval dekt precies een of meerdere volledige matches |

---

## Augmentatie

Geen. Match-context is kijkrichting-invariant, team-invariant en mirror-invariant.
