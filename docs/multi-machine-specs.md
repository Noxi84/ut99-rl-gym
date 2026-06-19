# Multi-Machine Specs

Hardware specificaties van de 5 Linux Mint machines + de dev machine.

---

## Overzicht

| | Dev machine | 4090 | 4070 | 3070 | 2070 | P15v |
|---|---|---|---|---|---|---|
| Hostname | n.v.t. (lokaal) | desktop-4090.fritz.box | desktop-4070.fritz.box | desktop-3070.fritz.box | desktop-2070.fritz.box | LAPTOP-P15v.fritz.box |
| Rol | Code + deploy | BC + SAC training | BC training + experience collection | BC training + experience collection | Experience only | Experience only |
| CPU | Intel Core Ultra 7 255H (16C/16T) | Intel i9-10940X (14C/28T) | Intel i7-13700K (16C/24T) | Intel i9-11900K (8C/16T) | Intel i5-2500K (4C/4T) | Intel i7-11800H (8C/16T) |
| GPU | Intel iGPU (geen CUDA) | RTX 4090 (24 GB) | RTX 4070 Ti (12 GB) | RTX 3070 (8 GB) | RTX 2070 Super (8 GB) | T600 (4 GB) |
| RAM | 31 GB (LPDDR5 7500) | 128 GB (DDR4 2400, quad-channel) | 64 GB (DDR5 4200) | 32 GB (DDR4 3200) | 12 GB (DDR3 1333) | 32 GB (DDR4 3200) |
| Storage | NVMe SSD | 1TB SSD + 2TB HDD | 3x NVMe SSD | NVMe SSD + HDD | SATA SSD | NVMe SSD |
| Mainboard | Dell Pro Max 14 (MC14250) | MSI X299-A PRO (MS-7A94) | ASUS ROG Maximus Z790 Hero | Lenovo 374F | Gigabyte Z68X-UD3H-B3 | Lenovo ThinkPad P15v Gen 3 (21A9) |
| Koeling | Stock | Alphacool Core Ocean T38 360mm AIO | 240mm AIO | 240mm AIO | Stock | Stock laptop |
| OS | Linux Mint 22.3 | Linux Mint 22.3 | Linux Mint 22.3 | Linux Mint 22.3 | Linux Mint 22.3 | Linux Mint 22.3 |
| Java | Build only | OpenJDK 25 (Temurin) | OpenJDK 25 (Temurin) | OpenJDK 25 (Temurin) | OpenJDK 25 (Temurin) | OpenJDK 25 (Temurin) |
| Python / PyTorch | n.v.t. | Ja | Ja | Ja | Ja | Ja |
| CUDA | Nee | Ja | Ja | Ja | Ja | Ja |

---

## Capaciteit en rollen (uit servers.json)

| Machine | GPU instances | CPU instances | BC trainer slots | SAC trainer slots | CSV writer slots | BC prioriteit |
|---|---:|---:|---:|---:|---:|---:|
| 4090 | 0 | 0 | 1 | 2 | 4 | 1 (highest) |
| 4070 | 5 | 0 | 1 | 0 | 4 | 2 |
| 3070 | 2 | 0 | 1 | 0 | 2 | 3 |
| 2070 | 1 | 0 | 0 | 0 | 0 | 4 |
| P15v | 1 | 0 | 0 | 0 | 1 | 5 (lowest) |

**Dev machine** heeft geen CUDA -- Python parsing en ML training draaien daar niet.

**4090** draait geen bot instances (0 GPU + 0 CPU) en is dedicated trainer: 1 BC slot + 2 SAC slots. Dit is de primary trainer (hoogste trainer-slot capaciteit).

**4070 en 3070** combineren bot instances met BC training (elk 1 BC slot). Geen SAC slots.

**2070 en P15v** zijn experience-only: laagste instance counts, geen trainer slots.

---

## Netwerk

Alle machines op hetzelfde LAN via Fritz!Box router. Hostnamen resolvable via `<hostname>.fritz.box`.

SSH-toegang: `ssh kris@<hostname>`. Wachtwoord in de niet-getrackte `resources/config/secrets.local.json` (key `ssh_password`), niet in git. Non-interactief: `sshpass -p "$(jq -r .ssh_password /home/kris/projects/ut99neuralnet/resources/config/secrets.local.json)" ssh -o StrictHostKeyChecking=no kris@<hostname>`.

Recording server (waar .rec.gz experience-recordings centraal staan): **4090**.
