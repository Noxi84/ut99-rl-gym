# gameplay.json -- referentie

**Bestand:** `resources/config/gameplay.json`
**Laden:** eenmalig gecacht per proces; expliciet herladen via invalidatie.
**Politiek:** geen fallbacks. Ontbrekend verplicht veld = exception bij laden.

---

## 1. Top-level schema

| Property | Type | Vereist | Doel |
|---|---|---|---|
| `near_dist_norm` | `double` | **ja** | Genormaliseerde near-afstand-drempel. |
| `weapon_profile` | `string` | **ja** | Wapen-arsenaal voor alle bots. Zie SS1.1. |
| `match_time_minutes` | `int` | **ja** | Matchduur in minuten (`> 0`). |
| `flag_drop_auto_return_seconds` | `double` | **ja** | Auto-return-timer voor een gedropte vlag in seconden (`> 0`). |
| `mapName` | `string` | **ja** | UT99-map-URL die wordt doorgegeven aan `ucc server`. Mag query-parameters bevatten. |
| `ai_bots` | `array` | minstens 1 bot (`ai_bots + ut99_bots` niet leeg) | RL-bots aangestuurd via UDP. Zie SS2. |
| `ut99_bots` | `array` | idem | Stock-UT99-bots bestuurd door de engine. Zie SS3. |
| `debug_features` | `array<string>` | nee | Feature-IDs waarvoor extra debug-logging actief is. Leeg = geen extra logs. |
| `debug_sanity_enabled` | `bool` | **ja** | Schakelt sanity-debug-checks in. |
| `debug_log_every_n` | `int` | **ja** | Logging-throttle: 1 op N samples per feature. |
| `debug_log_min_interval_ms` | `long` | **ja** | Minimaal interval (ms) tussen logregels per feature. |
| `debug_log_only_on_change` | `bool` | **ja** | Onveranderde feature-waarden overslaan. |
| `debug_log_change_epsilon` | `double` | **ja** | Drempel voor "veranderd". |
| `debug_log_max_lines_per_feature` | `int` | **ja** | Plafond logregels per feature per proces. |

### 1.1 `weapon_profile` -- toegestane waarden

Alle profielen krijgen automatisch `SmartCTF` + `EnhancedFeedback` mutators aangehangen.

| Waarde | Effect |
|---|---|
| `"instagib"` | SuperShockRifle (1-shot hitscan). Strip health/armor/shield/invis/udamage. |
| `"flak"` | UT_FlakCannon (primary chunks + secondary slug). Pickup-strip. |
| `"shock"` | ShockRifle (hitscan + shock-ball + combo). Pickup-strip. |
| `"sniper"` | SniperRifle (hitscan, 2x headshot). Pickup-strip. |
| `"rocket"` | UT_Eightball (rockets + 6-load grenades). Pickup-strip. |
| `"minigun"` | Minigun2 (hitscan dakka, spray + windup). Pickup-strip. |
| `"pulse"` | PulseGun (hitscan beam + plasma-ball). Pickup-strip. |
| `"all"` | Volledig arsenaal, maar health/armor/shield/invis/udamage gestript. |
| `"stock"` | Pure stock UT99 CTF: alle wapens + alle pickups. |

Ongeldige waarde = exception bij config-load.

**Weapon-pickup gedrag.** `RLCTFGame.InitGame` forceert `bMultiWeaponStay=false` (weapons-stay UIT = stock CTF): gedropte wapens van dode spelers zijn oppakbaar — volledig als je het wapen mist, ammo tot max als je het al draagt — en map-wapens respawnen na pickup. Zonder dit (UT99-default `bMultiWeaponStay=True`) zet `TournamentWeapon.SetWeaponStay()` `bWeaponStay=true` op elk gespawnd wapen, en blokkeert `HandlePickupQuery` elk gedropt wapen dat de oppakker al heeft; bij `all` (volledig arsenaal per bot via `PickupStripOnly`) blijft dan élk gedropt wapen onneembaar liggen.

> **Let op bij `all`:** de RL-weapon-overrides (RLShockRifle/RLEightball/RLPulseGun) worden alléén via de single-weapon arena-mutators geactiveerd. Onder `all` draaien de bots op de stock-wapens, dus shock-combo / multi-rocket-load / pulse-beam-aim gedragen zich daar niet zoals in de arena-profielen.

### 1.2 `mapName` -- URL-bewerkingen door de launcher

```
gameplay.json mapName
    |
    v
+--------------------------------------------------------------+
| Strip   ?game=Botpack.CTFGame  -> vervangen door RLCTFGame   |
| Strip   ?Mutator=...                                         |
| Inject  ?Mutator=[weapon-mutator,]SmartCTF,EnhancedFeedback  |
| Inject  ?GoalTeamScore=0                                     |
| Inject  ?MinPlayers=<#bots>                                  |
| Inject  ?RLUdpPort / ?RLStateUdpPort (transport, voor roster)|
| Inject  ?Apr=<appearance-tabel> + ?RLBots=name|team|aprIdx,. |
| Inject  ?GameSpeed=<factor>                                  |
+--------------------------------------------------------------+
    |
    v
ucc server <mapUrl> -port=<gamePort> -ini=<session-ini>
```

---

## 2. `ai_bots[]` -- RL-bots (aangestuurd via UDP)

Elke entry definieert een bot die via UDP door het bot-proces wordt aangestuurd. De bot joint altijd de server; `active=false` parkeert hem (geen inferentie, geen commands).

| Property | Type | Vereist | Beschrijving |
|---|---|---|---|
| `name` | `string` | **ja** | Unieke naam. Matching-key voor team-balance en park/restore. |
| `team` | `int` (0 of 1) | **ja** | `0` = rood, `1` = blauw. |
| `active` | `bool` | **ja** | `true` = inferentie + commands. `false` = geparkeerd. |
| `role` | `string` | **ja** | Tactische rol: `Attack`, `Cover`, `Defend` of `DeathMatch`. Selecteert de rewardgroup. |
| `preferred_weapon` | `string` | **ja** | Logisch wapen-token dat de weapon-planner-lane voor deze bot actief houdt. Zie SS2.2. |
| `appearance` | `object` | **ja** | Visuele identiteit. Zie SS4. |
| `models` | `object` | **ja** | Per model: enabled-vlag + snapshot. Zie SS2.1. |

### 2.1 `models[modelKey]` -- model-spec

Het enige model is `rl_pawn` (joint LSTM: movement + viewrotation + fire/altFire + target_index).

| Veld | Type | Vereist | Betekenis |
|---|---|---|---|
| `enabled` | `bool` | **ja** | Model voert inferentie uit voor deze bot. |
| `snapshot` | `string` | **ja** | `"current"` = live trainingsmodel, of `"rl_pawn/<counter>"` = gepinde champion (bv. `"rl_pawn/newest"`). |

### 2.2 `preferred_weapon` -- wapen-token

Logisch token (geen class path) dat de **weapon-planner-lane** (parallelle behavior-tree-tak op lage refreshrate, `runtime.json` -> `weapon_planner.fps`) gebruikt om te bepalen welk wapen deze bot actief moet hebben. De `CommandController` activeert het gekozen wapen alleen wanneer het nog niet actief is (edge-triggered UDP-commando, magic `0xAC`).

Geldige tokens (bron: `WeaponCatalog.java`):

`instagib`, `enforcer`, `doubleenforcer`, `shock`, `biorifle`, `sniper`, `flak`, `rocket`, `ripper`, `minigun`, `pulse`, `translocator`, `impacthammer`, `redeemer`

**Fallback-regel.** Is het gekozen wapen niet in de inventory of zonder ammo, dan valt de bot terug op het *next-best* bruikbare wapen volgens een vaste prioriteitslijst in Java (`WeaponCatalog.fallbackOrder()`). Een wapen telt als bruikbaar wanneer `ammo > 0` **of** het geen AmmoType heeft (impact hammer / translocator -> altijd bruikbaar, dus gegarandeerde laatste fallback).

**Werkingsgebied.** `preferred_weapon` heeft alleen merkbaar effect wanneer `weapon_profile` meerdere wapens uitdeelt (`all` / `stock`). In single-weapon arenas (`shock`, `flak`, ...) draagt de bot maar één wapen; de planner kiest dat ene wapen (geen switch). Het veld blijft verplicht voor élke AI-bot (no-fallback-config-regel).

> **Onderscheid met `ut99_config.favorite_weapon` (SS3.2):** dat veld is voor stock UT99-bots en gebruikt volledige class paths voor de engine-pickup-heuristiek. `preferred_weapon` is voor RL-bots en gebruikt logische tokens die Java naar concrete inventory-classes mapt (incl. RL-overrides als `NeuralNetWebserver.RLPulseGun`).

---

## 3. `ut99_bots[]` -- stock UT99-bots (engine-bestuurd)

De launcher schrijft deze bots in `User.ini` met `bRandomOrder=False` en `bAdjustSkill=False`, zodat spawn-volgorde 1-op-1 matcht met de geconfigureerde slots.

| Property | Type | Vereist | Beschrijving |
|---|---|---|---|
| `enabled` | `bool` | **ja** | `true` = bot joint de server. `false` = overgeslagen. |
| `name` | `string` | **ja** | Botnaam in User.ini. |
| `team` | `int` (0 of 1) | **ja** | Team-index. |
| `appearance` | `object` | **ja** | Visuele identiteit. Zie SS4. |
| `ut99_config` | `object` | **ja** | AI-tuning. Zie SS3.1. |

### 3.1 `ut99_config` -- AI-tuning

Worden 1-op-1 naar User.ini geschreven:

| Veld | Type | Range | Effect |
|---|---|---|---|
| `jumpy` | `int` | 0 of 1 | 1 = bot springt vaker tijdens combat. |
| `favorite_weapon` | `string` | UT99 class path | Voorkeurswapen voor pickup-heuristiek. Zie SS3.2. |
| `camping` | `double` | 0.0 -- 1.0 | Neiging om op een plek te blijven. |
| `strafing_ability` | `double` | 0.0 -- 99.0 | Strafe-/dodge-vaardigheid. |
| `combat_style` | `double` | -1.0 -- 2.0 | -1 = defensief, 2 = zeer agressief. |
| `alertness` | `double` | 0.0 -- 99.0 | Reactiesnelheid + situational awareness. |
| `accuracy` | `double` | 0.0 -- 1.0 | Aim-nauwkeurigheid. |
| `skill` | `double` | 0.0 -- 1.0 | Algemeen skill-level. |

### 3.2 `favorite_weapon` -- bekende wapens

| Wapen | Class path |
|---|---|
| Impact Hammer | `Botpack.ImpactHammer` |
| Translocator | `Botpack.Translocator` |
| Enforcer | `Botpack.Enforcer` |
| Bio Rifle | `Botpack.UT_BioRifle` |
| Shock Rifle | `Botpack.ShockRifle` |
| Pulse Gun | `Botpack.PulseGun` |
| Ripper | `Botpack.Ripper` |
| Minigun | `Botpack.Minigun2` |
| Flak Cannon | `Botpack.UT_FlakCannon` |
| Rocket Launcher | `Botpack.UT_Eightball` |
| Sniper Rifle | `Botpack.SniperRifle` |
| Redeemer | `Botpack.WarheadLauncher` |
| InstaGib SuperShock | `Botpack.SuperShockRifle` |

Als `weapon_profile` het wapen niet uitdeelt, is `favorite_weapon` inactief -- UT99 gebruikt dan het beschikbare arsenaal.

---

## 4. `appearance` -- visuele identiteit

Per bot (RL en UT99) verplicht met vier velden. Ontbreken = exception.

```json
"appearance": {
  "class": "TMale1Bot",
  "skin":  "CommandoSkins.cmdo",
  "face":  "CommandoSkins.Blake",
  "voice": "BotPack.VoiceMaleTwo"
}
```

### 4.1 Veld-specs

| Veld | Domein | Vorm | Bepaalt |
|---|---|---|---|
| `class` | Pawn-class (SS4.2) | Bare class name | Mesh, body-scale, collision-volume. |
| `skin` | `<SkinPackage>.<code>` | Volledig pad | Uniform-textuur (lichaam). |
| `face` | `<SkinPackage>.<FaceName>` | Volledig pad | Gezichtstextuur (hoofd). Package moet bij `class` passen. |
| `voice` | `<Package>.<VoicePack>` | Volledig class path | Taunt/order-callouts (puur cosmetisch). |

### 4.2 `class` -- beschikbare pawn-classes

| `class` | Mesh | Geslacht | Skin-package |
|---|---|---|---|
| `TMale1Bot` | Commando | mannelijk | `CommandoSkins` |
| `TMale2Bot` | Soldier | mannelijk | `SoldierSkins` |
| `TFemale1Bot` | Female Commando | vrouwelijk | `FCommandoSkins` |
| `TFemale2Bot` | Female Soldier | vrouwelijk | `SGirlSkins` |

### 4.3 Skin x class -- compatibiliteit

`skin` en `face` MOETEN uit het skin-package van de bijbehorende `class` komen. Verkeerd package = placeholder-textuur (groene vlek).

| `class` | `skin` package | `face` package |
|---|---|---|
| `TMale1Bot` | `CommandoSkins` | `CommandoSkins` |
| `TMale2Bot` | `SoldierSkins` | `SoldierSkins` |
| `TFemale1Bot` | `FCommandoSkins` | `FCommandoSkins` |
| `TFemale2Bot` | `SGirlSkins` | `SGirlSkins` |

### 4.4 Skin-codes (uniform) per package

| `class` | Package | Code | In-game display |
|---|---|---|---|
| `TMale1Bot` | `CommandoSkins` | `cmdo` | Commando |
| | | `daco` | Mercenary |
| | | `goth` | Necris |
| `TMale2Bot` | `SoldierSkins` | `blkt` | Marine |
| | | `Gard` | Metal Guard |
| | | `RawS` | RawSteel |
| | | `sldr` | Soldier |
| | | `hkil` | War Machine |
| `TFemale1Bot` | `FCommandoSkins` | `cmdo` | Commando |
| | | `daco` | Mercenary |
| | | `goth` | Necris |
| | | `aphe` | Aphex |
| `TFemale2Bot` | `SGirlSkins` | `army` | Soldier |
| | | `Garf` | Metal Guard |
| | | `Venm` | Venom |
| | | `fbth` | Marine |
| | | `fwar` | War Machine |

### 4.5 Face-namen per skin-code

**`CommandoSkins` (TMale1Bot)**

| Code | Faces |
|---|---|
| `cmdo` | `Blake`, `Gorn`, `Nickolai`, `Whitman` |
| `daco` | `Boris`, `Luthor`, `Ramirez`, `Graves` |
| `goth` | `Kragoth`, `Malakai`, `Necrotic`, `Grail` |

**`SoldierSkins` (TMale2Bot)**

| Code | Faces |
|---|---|
| `blkt` | `Malcom`, `Othello`, `Riker` |
| `Gard` | `Drake`, `Radkin`, `Wraith`, `Von` |
| `RawS` | `Arkon`, `Bruce`, `Kregore`, `Manwell`, `Slain` |
| `sldr` | `Brock`, `Harlin`, `Johnson`, `Rankin` |
| `hkil` | `Vector`, `Matrix`, `Tensor` |

**`FCommandoSkins` (TFemale1Bot)**

| Code | Faces |
|---|---|
| `cmdo` | `Ivana`, `Nikita`, `Anna`, `Gromida` |
| `daco` | `Kyla`, `Mariana`, `Jayce`, `Tanya` |
| `goth` | `Cryss`, `Freylis`, `Visse`, `Malise` |
| `aphe` | `Indina` (display: Idina), `Portia` |

**`SGirlSkins` (TFemale2Bot)**

| Code | Faces |
|---|---|
| `army` | `Lauren`, `Rylisa`, `Sara`, `Shyann` |
| `Garf` | `Isis`, `Risa`, `Tasha`, `Vixen` |
| `Venm` | `Athena`, `Cilia`, `Sarena`, `Tara`, `Zanna` |
| `fbth` | `Azure`, `Aryss`, `Annaka`, `Olga`, `Ryanna` |
| `fwar` | `Cathode`, `Fury`, `Lilith` |

**Let op:** De asset-naam kan afwijken van de display-naam. Voorbeeld: Aphex/Idina heeft asset-naam `aphe4Indina` (met N). In `appearance.face` moet `FCommandoSkins.Indina` staan.

### 4.6 `voice` -- taunt-packs

| `voice` waarde | Geslacht |
|---|---|
| `BotPack.VoiceMaleOne` | mannelijk |
| `BotPack.VoiceMaleTwo` | mannelijk |
| `BotPack.VoiceFemaleOne` | vrouwelijk |
| `BotPack.VoiceFemaleTwo` | vrouwelijk |
| `BotPack.VoiceBoss` | Boss/Xan stijl |

Voice is puur cosmetisch (audio-taunts + chat). Raakt de RL-policy niet.

### 4.7 Aanbevolen mesh x voice combinaties

| `class` | Aanbevolen `voice` |
|---|---|
| `TMale1Bot` | `BotPack.VoiceMaleOne` of `BotPack.VoiceMaleTwo` |
| `TMale2Bot` | `BotPack.VoiceMaleOne` of `BotPack.VoiceMaleTwo` |
| `TFemale1Bot` | `BotPack.VoiceFemaleOne` of `BotPack.VoiceFemaleTwo` |
| `TFemale2Bot` | `BotPack.VoiceFemaleOne` of `BotPack.VoiceFemaleTwo` |

---

## 5. Hoe `appearance` van JSON naar UT99 reist

### 5.1 RL-bot pad

```
gameplay.json
  ai_bots[i].appearance.{class,skin,face,voice}
         |
         v  (laden -> bot-configuratie)
URL-builder compacteert naar twee CSV-params (gededupeerde appearances;
houdt de URL onder UT99's ~1024-char InitGame-limiet tot 16 bots):
  "?Apr=TMale1Bot|CommandoSkins.cmdo|CommandoSkins.Blake|BotPack.VoiceMaleTwo,..."
  "?RLBots=Champion-Attack|0|0,Other-Defend|1|0,..."   (name|team|aprIdx)
         |
         v  (ucc server <mapUrl>)
UT99 server splitst ?Apr= -> appearance-tabel, ?RLBots= -> per-bot
  name/team/aprIdx, resolved appearance via index
  -> MeshClasses[], Skins[], Faces[], Voices[]
         |
         v  (per spawn)
SpawnBot:
  * Laadt mesh/scale/collision uit pawn-class
  * Zet multi-skin texturen op de juiste slots
  * Koppelt voice-pack
         |
         v
Bot rendert als gewenste character met team-tint (T_0 = rood, T_1 = blauw).
```

De vier appearance-velden worden als een pipe-separated parameter (`Apr`) in de URL gecompacteerd. Dit voorkomt dat de UT99 ~1024-char URL-limiet overschreden wordt bij meerdere bots.

Skin-slot dispatch gaat via de mesh-class van de `class`-waarde. De slot-indices voor team-kleuren, gezicht en lichaam verschillen per mesh-type. Dispatch via de verkeerde class schrijft texturen in verkeerde slots.

### 5.2 UT99-bot pad

```
gameplay.json
  ut99_bots[j].appearance.{class,skin,face,voice}
         |
         v  (laden -> bot-configuratie)
User.ini patcher schrijft:
  [Botpack.ChallengeBotInfo]
    bRandomOrder=False
    bAdjustSkill=False
    BotClasses[N]=BotPack.TMale1Bot
    BotSkins[N]=CommandoSkins.daco
    BotFaces[N]=CommandoSkins.Gorn
    Voices[N]=BotPack.VoiceMaleOne
    BotJumpy[N]/FavoriteWeapon[N]/... = ut99_config
         |
         v  (ucc server start -> AddBot loop)
UT99 leest BotClasses/Skins/Faces/Voices uit User.ini
  -> spawn + SetMultiSkin + voice-koppeling
         |
         v
Stock-UT99-pawn rendert met geconfigureerde appearance.
```

---

## 6. Park / Restore (RL-bots, dynamische team-balance)

Wanneer een menselijke speler joint, parkeert de game een RL-bot uit het overbezette team. Bij vertrek van de speler wordt diezelfde bot opnieuw gespawnd. Appearance is deterministisch uit de configuratie -- bij restore draait het appearance-proces opnieuw.

```
Human player joint (team 0)
    -> CheckTeamBalance()
    -> ParkOneBot(team 0)
    -> Bot wordt verwijderd, slot gemarkeerd als geparkeerd

Human player vertrekt
    -> CheckTeamBalance()
    -> RestoreOneBot(team 0)
    -> Nieuw bot-object gespawnd
    -> Appearance opnieuw toegepast vanuit configuratie
    -> Bot gaat terug naar RL-aangestuurd
```

---

## 7. Voorbeelden

### 7.1 Minimale `ai_bots` entry

```json
{
  "name": "Champion-Attack",
  "team": 0,
  "active": true,
  "role": "Attack",
  "appearance": {
    "class": "TMale1Bot",
    "skin":  "CommandoSkins.cmdo",
    "face":  "CommandoSkins.Blake",
    "voice": "BotPack.VoiceMaleTwo"
  },
  "models": {
    "rl_pawn": {"enabled": true, "snapshot": "rl_pawn/newest"}
  }
}
```

### 7.2 Minimale `ut99_bots` entry

```json
{
  "enabled": true,
  "name": "Tamerlane",
  "team": 1,
  "appearance": {
    "class": "TMale2Bot",
    "skin":  "SoldierSkins.sldr",
    "face":  "SoldierSkins.Othello",
    "voice": "BotPack.VoiceMaleOne"
  },
  "ut99_config": {
    "jumpy": 0,
    "favorite_weapon": "Botpack.PulseGun",
    "camping": 0.0,
    "strafing_ability": 40.0,
    "combat_style": 0.8,
    "alertness": 40.0,
    "accuracy": 0.4,
    "skill": 0.4
  }
}
```

### 7.3 Self-play 5v5 -- [ALPHA] vs [BETA]

Per rol een gedeelde appearance; team-tint (T_0 rood / T_1 blauw) wordt automatisch toegepast.

**Clan-tags:**
- `[ALPHA]...` (team 0, rood) -- gepinde champion `rl_pawn/newest`.
- `[BETA]...` (team 1, blauw) -- live `current` policy die actief getraind wordt.

**Appearance per rol:**

| Rol | class | skin | face |
|---|---|---|---|
| Attack | `TFemale1Bot` | `FCommandoSkins.aphe` | `FCommandoSkins.Indina` |
| Cover | `TFemale2Bot` | `SGirlSkins.fbth` | `SGirlSkins.Annaka` |
| Defend | `TFemale1Bot` | `FCommandoSkins.daco` | `FCommandoSkins.Jayce` |

**Bot-roster (5 per team, gespiegeld -- 3 actief = 3v3, 2 reserve):**

| Rol | Status | [ALPHA] (rood) | [BETA] (blauw) |
|---|---|---|---|
| Attack | actief | `[ALPHA]Falcon` | `[BETA]Falcon` |
| Attack | reserve | `[ALPHA]Wolf` | `[BETA]Wolf` |
| Cover | actief | `[ALPHA]Hawk` | `[BETA]Hawk` |
| Defend | actief | `[ALPHA]Bear` | `[BETA]Bear` |
| Defend | reserve | `[ALPHA]Boar` | `[BETA]Boar` |

Reserve-bots blijven op de server staan maar krijgen geen inferentie. Flip `active: true` om op te schalen naar 4v4 of 5v5 zonder server-herstart.

---

## 8. Validatie & foutgevallen

Alle fouten worden bij config-laden gegooid.

| Fout | Beschrijving |
|---|---|
| `appearance` ontbreekt | Verplicht object voor elke bot. |
| `class`/`skin`/`face`/`voice` ontbreekt | Verplichte tekstvelden binnen appearance. |
| `weapon_profile` ontbreekt of ongeldig | Verplicht veld met bekende waarden. |
| `role` ontbreekt of multi-value | Enkele string verplicht. |
| `snapshot` ontbreekt | Verplicht per model-entry. |
| Geen bots geconfigureerd | `ai_bots` en `ut99_bots` mogen niet beide leeg zijn. |

### Runtime appearance-fouten

UT99 logt maar crasht niet bij ongeldige class of voice (typo in package-naam). De bot valt terug op standaard mesh en voice. De RL-policy blijft normaal werken.

---

## 9. Snelle referentie -- beslistabel voor `appearance`

| Doel | `class` | `skin` | `face` | `voice` |
|---|---|---|---|---|
| Male Commando - Blake | `TMale1Bot` | `CommandoSkins.cmdo` | `CommandoSkins.Blake` | `BotPack.VoiceMaleTwo` |
| Male Commando - Necris Grail | `TMale1Bot` | `CommandoSkins.goth` | `CommandoSkins.Grail` | `BotPack.VoiceMaleOne` |
| Male Commando - Mercenary Boris | `TMale1Bot` | `CommandoSkins.daco` | `CommandoSkins.Boris` | `BotPack.VoiceMaleOne` |
| Male Soldier - Johnson | `TMale2Bot` | `SoldierSkins.sldr` | `SoldierSkins.Johnson` | `BotPack.VoiceMaleOne` |
| Male Soldier - Marine Malcom | `TMale2Bot` | `SoldierSkins.blkt` | `SoldierSkins.Malcom` | `BotPack.VoiceMaleTwo` |
| Male Soldier - RawSteel Arkon | `TMale2Bot` | `SoldierSkins.RawS` | `SoldierSkins.Arkon` | `BotPack.VoiceMaleOne` |
| Female Commando - Mercenary Jayce | `TFemale1Bot` | `FCommandoSkins.daco` | `FCommandoSkins.Jayce` | `BotPack.VoiceFemaleOne` |
| Female Commando - Necris Cryss | `TFemale1Bot` | `FCommandoSkins.goth` | `FCommandoSkins.Cryss` | `BotPack.VoiceFemaleTwo` |
| Female Commando - Aphex Idina | `TFemale1Bot` | `FCommandoSkins.aphe` | `FCommandoSkins.Indina` | `BotPack.VoiceFemaleOne` |
| Female Soldier - Marine Aryss | `TFemale2Bot` | `SGirlSkins.fbth` | `SGirlSkins.Aryss` | `BotPack.VoiceFemaleTwo` |
| Female Soldier - Soldier Sara | `TFemale2Bot` | `SGirlSkins.army` | `SGirlSkins.Sara` | `BotPack.VoiceFemaleTwo` |
| Female Soldier - Venom Sarena | `TFemale2Bot` | `SGirlSkins.Venm` | `SGirlSkins.Sarena` | `BotPack.VoiceFemaleOne` |
