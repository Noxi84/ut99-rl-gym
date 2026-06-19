# UT99 Transport-laag & Instance-instellingen

De hot path tussen het bot-proces en UT99 loopt over twee binaire UDP-kanalen. De HTTP/UWeb-interface bestaat als debug-endpoint maar is niet onderdeel van de bot-loop.

---

## Mod-pakket

De mod-broncode staat in `scripts/mutator/NeuralNetWebserver/Classes/`. Het pakket bevat vijf componenten:

| Component | Rol |
|---|---|
| RLCTFGame | Aangepast CTF-speltype: start zonder menselijke spelers, houdt bot-registry bij, parst UDP-poorten uit URL-opties, spawnt de twee UDP-actors. |
| RLBot | Headless bot, volledig aangestuurd via UDP. Movement, view, fire/jump/duck/dodge direct vanuit ontvangen commands. |
| RLUdpCommandReceiver | Ontvangt actiepakketten van het bot-proces. Polt elke tick (manual polling), zoekt bot op via index, zet action-velden. |
| RLUdpStateSender | Bouwt @60 Hz een volledige binary state-frame in een 8 kB buffer, splitst over meerdere UDP-pakketten. TLV-secties voor MapInfo, Flags, Players en Projectiles. |
| NeuralNetWebserver | HTTP debug-endpoint. GET = game state als JSON, POST = actiecommando. Niet meer in de hot path. |

### Compilatie en deploy

`deploy.sh` compileert de mod op de dev-machine, kopieert het voorgecompileerde bestand als fallback, en rolt hetzelfde bestand uit naar alle servers. Hercompilatie per server is verwijderd omdat verschillende compilers byte-verschillen produceren -- versiemismatch voor toeschouwers.

Het mod-pakket moet in de INI staan als `EditPackages` (niet `ServerPackages`) -- `ServerPackages` forceert client-download en veroorzaakt versiemismatch.

---

## Binaire UDP-transport (hot path)

Beide kanalen gebruiken `127.0.0.1` + een per-instance port uit `servers.json`. Zie `ports.udp_port_base` (commands) en `ports.state_udp_port_base` (state). Per instance: `base + instance_idx`.

**Poortallocatie:**

| Machine | Commands (bot->UT99) | State (UT99->bot) |
|---|---|---|
| 4090 | 11000+i | 11500+i |
| 4070 | 11100+i | 11600+i |
| 3070 | 11200+i | 11700+i |
| p15v | 11300+i | 11800+i |
| 2070 | 11400+i | 11900+i |

Beide poorten worden aan UCC meegegeven via URL-opties: `?RLUdpPort=N?RLStateUdpPort=M`.

---

### Command-kanaal (bot-proces -> UT99), 12 bytes per packet

| Offset | Grootte | Veld | Encoding |
|---:|---:|---|---|
| 0 | 1 | `magic` | `0xAA` |
| 1 | 1 | `botIdx` | 0..15, index in bot-registry |
| 2 | 1 | `flags` | Bitmask: fwd, back, left, right, jump, duck, fire, altfire |
| 3 | 1 | `dodge` | 0..8 (0 = geen, 1..8 = richting) |
| 4-5 | 2 | `yaw` | uint16 LE, UT rotation units 0..65535 |
| 6-7 | 2 | `pitch` | uint16 LE, UT rotation units |
| 8-9 | 2 | `moveYaw` | uint16 LE, world-space movement heading |
| 10-11 | 2 | `seqNum` | uint16 LE, drop-detectie |

**Verzender:** Een gedeeld non-blocking UDP-kanaal per JVM, thread-local buffer, fire-and-forget.

**Ontvanger:** Polt elke tick (max 64 packets per tick), valideert magic + botIdx, past bot-velden toe. Packets voor geparkeerde bots worden gedropt.

---

### Suicide-command (magic `0xAB`)

Naast het 12-byte action-packet bestaat een 2-byte suicide-command voor de ammo-deadlock guard:

| Offset | Grootte | Veld |
|---:|---:|---|
| 0 | 1 | `magic` = `0xAB` |
| 1 | 1 | `botIdx` (0..15) |

Ontvanger detecteert magic `0xAB` voor de 12-byte guard, resolved bot via index, triggert suicide. Zie [ammo-deadlock-guard.md](../ammo-deadlock-guard.md).

### Select-weapon-command (magic `0xAC`)

Een 6-byte edge-getriggerd commando dat het actieve wapen van een bot wijzigt. Verstuurd door de `CommandController` wanneer de weapon-planner-lane een ander wapen kiest dan het actieve (niet op de per-tick hot-path):

| Offset | Grootte | Veld |
|---:|---:|---|
| 0 | 1 | `magic` = `0xAC` |
| 1 | 1 | `botIdx` (0..15) |
| 2-5 | 4 | `weaponClassHash` int32 LE -- FNV-1a van de UT99 class-string (zelfde routine + seed als het state-kanaal) |

Ontvanger detecteert magic `0xAC` voor de 12-byte guard, resolved bot via index, en roept `RLBot.RLSelectWeaponByHash(hash)`: zoekt het inventory-wapen met matchende class-hash en wisselt via de stock `PendingWeapon`/`PutDown`-flow. Idempotent (no-op als al actief / al pending), dus veilig her-verzendbaar. De wapen-keuze + next-best-met-ammo fallback zit volledig in Java (`PreferredWeaponResolver` + `WeaponCatalog`); UnrealScript voert alleen uit. Zie [gameplay.md](../config/json/gameplay.md) SS2.2.

---

### State-kanaal (UT99 -> bot-proces), multi-packet @ 60 Hz

Elke UDP-datagram heeft een 8-byte header + TLV-payload. Een frame kan gesplitst zijn over 1..N packets; het bot-proces reassembleert op basis van frameId.

**Header (8 bytes):**

| Offset | Grootte | Veld | Betekenis |
|---:|---:|---|---|
| 0 | 1 | `magic` | `0xBB` |
| 1 | 1 | `frameType` | 0 = full frame |
| 2-3 | 2 | `frameId` | uint16 LE, wraps; drop-detectie |
| 4-5 | 2 | `payloadLen` | uint16 LE, bytes in dit packet |
| 6 | 1 | `packetIdx` | 0-based binnen frame |
| 7 | 1 | `packetCount` | Totaal packets voor dit frame |

**Payload TLV-secties** (tag 1B + len uint16 LE + data):

| Tag | Sectie | Inhoud |
|---:|---|---|
| 0x01 | MapInfo | Scores, tijden, TimeDilation, bHardCoreMode, bMegaSpeed, mapNameHash |
| 0x02 | Flag | Team, status, locatie, home-base, holderSlot |
| 0x03 | Player | Volledige spelerstate (zie hieronder) |
| 0x04 | Projectile | classHash, locatie, velocity, speed, damage, instigator |
| 0x05 | Pickup | classHash, locatie, hidden-status + resterende respawn-tijd |
| 0x06 | Mover | nameHash, locatie, keyNum/prevKeyNum/numKeys, stateFlags, moveProgress (map-movers + elevator triggers; zie [map-movers.md](../features/map-movers.md)) |

**Player TLV (~230 B per speler):** slot, team, physics, dodgeState, actionFlags (bitmask), health, score, deaths, armor, location, oldLocation, velocity, acceleration, viewPitch, viewYaw, baseEyeHeight, groundSpeed, airSpeed, jumpZ, airControl, 6x hold-duur, nameHash, naam (variabele lengte), Weapon-blok (classHash + ammo + maxAmmo + flags), Inventory (count + N x 8 B), visibilityMask uint32, flagLoS (14 x uint8 ratio x255), Collisions (maxDist + capsuleMargin + 32 x uint16 richtingen).

**Schaal-conventies:**
- Locaties: int32 x10 (precisie 0.1 UU)
- Velocity/acceleration: int16 x10
- Hold-duren: uint8 x10 (tot 25.5 s)
- Visibility: bitmask op slot-index (0..RLBotCount-1 RLBot, ≤16; daarna overige pawns tot 31)

**Verzender:** Bouwt payload in 8 kB scratchbuffer, splitst op 247-byte boundaries, stuurt via SendBinary. Rate-limited op 60 Hz.

**Ontvanger:** Dedicated reader-thread per instance, buffert partial frames (max 8 pending, oudste gedropt bij overflow), parst bij compleet frame, publiceert via atomaire referentie. Bots binnen dezelfde instance delen een ontvanger.

**DTO-conversie:** State frames worden omgezet naar het interne game-state formaat. Wapen-class-hashes worden opgelost via een hash-tabel (FNV-1a, seed `0x811C9DC5`, prime `16777619`) -- exact gespiegeld aan de UT99-kant.

---

## Debug-endpoints (HTTP)

- **GET** `http://127.0.0.1:<uweb_port>/utneuralnet/` -- game state als JSON. Bruikbaar voor handmatige inspectie:
  ```bash
  curl -s http://127.0.0.1:9080/utneuralnet/ | jq .MapInfo
  ```
- **POST** (zelfde URL) -- enkel actiecommando via form-urlencoded. Handig om een bot handmatig te besturen zonder het bot-proces.

UWeb-poort is configureerbaar in `resources/config/runtime.json`. In multi-instance-mode kiest de launcher zelf een vrije poort als de geconfigureerde bezet is.

**Toeschouwers:** Spectators worden uitgefilterd uit de state. Player-identificatie via naam-matching.

**Respawn:** In headless-modus handelt de game-mode respawnen automatisch af via de engine's standaard respawn-cyclus. Geen vuurknop nodig.

---

## UT99 Instance-instellingen

De UT99-clientinstellingen worden bij deployment uitgerold als master-INI-bestanden naar elke server. Die client-INI's zijn **niet** meegeleverd in deze publieke repo (de oorspronkelijke `ini-recorder`-directory is bewust verwijderd) — je levert ze zelf aan op basis van de hier beschreven instellingen.

**Bot-instellingen:** `MinPlayers` en `InitialBots` worden geconfigureerd om het juiste aantal bots te spawnen. `bHumansOnly=False` is vereist. Let op: `MinPlayers` is een `globalconfig` in UT99 -- gelezen uit de DeathMatchPlus INI-sectie, niet uit CTFGame of URL-parameters.

---

## Spelsnelheid, Spelstijl & Luchtcontrole

Drie onafhankelijke instellingen beinvloeden de gameplay-fysica:

### De drie instellingen

| Instelling | Wat het bestuurt | Config |
|---|---|---|
| Spelsnelheid | Tijddilatatie-multiplier (1.0 = normaal) | `servers.json gameplay.speed` |
| Spelstijl | Schade, springhoogte, bewegingssnelheid-preset | `servers.json gameplay.style` |
| Luchtcontrole | Laterale bewegingscontrole in de lucht (0.0-1.0) | INI-bestand |

### Spelstijl-presets

| Stijl | Schade | Springhoogte | Bewegingssnelheid | TimeDilation-basis |
|---|---|---|---|---|
| **Classic** | 1.0x | 1.0x | 1.0x | 1.0 |
| **Hardcore** | 1.5x | +10% | 1.0x | 1.1 |
| **Turbo** | 1.5x | +20% | +40% | 1.1 |

### Resulterende TimeDilation

| Modus | Formule |
|---|---|
| Classic | `GameSpeed` |
| Hardcore / Turbo | `1.1 x GameSpeed` |

Voorbeelden:
```
speed=1.0  style=classic   -> TimeDilation = 1.0
speed=1.0  style=hardcore  -> TimeDilation = 1.1
speed=2.0  style=hardcore  -> TimeDilation = 2.2
```

**SaveConfig-valkuil:** UT99 schrijft de berekende GameSpeed terug naar de INI. Een server die ooit in Hardcore draaide houdt GameSpeed=1.1 over. Het deploy-script kopieert een schone INI om dit te voorkomen.

### Luchtcontrole

Standaardwaarde 0.35. De game-mode kopieert deze waarde naar elke gespawnde bot.

### State-velden beschikbaar voor training

**MapInfo:** TimeDilation, bHardCoreMode, bMegaSpeed.
**Per speler:** GroundSpeed (standaard 400, Turbo 560), AirSpeed, JumpZ (standaard ~310, Hardcore 341, Turbo 372), AirControl.

Dit zijn de werkelijke runtime-waarden uit de engine, inclusief alle spelstijl-modifiers.

**Belangrijk voor BC-opnames:** Trainingsdata moet worden opgenomen met dezelfde spelstijl en snelheid als waarmee de bots draaien. Anders komen bewegingspatronen niet overeen.
