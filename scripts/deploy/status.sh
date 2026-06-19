#!/bin/bash
# Show status of all servers: CPU load, RAM, process counts, GPU usage, config.
#
# Usage:
#   bash scripts/deploy/status.sh
#   bash scripts/deploy/status.sh 4090          # single server

set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

parse_servers_conf
if [ $# -gt 0 ]; then
    filter_servers "$@"
fi

if [ ${#SERVERS[@]} -eq 0 ]; then
    echo "ERROR: No servers matched"
    exit 1
fi

# Collect data from all servers in parallel
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

for i in "${!SERVERS[@]}"; do
    (
        host="${SERVERS[$i]}"
        user="${USERS[$i]}"
        pass="${PASSES[$i]}"
        mid="${MACHINE_IDS[$i]}"
        instances="${INSTANCES_RAW[$i]}"
        bc_slots="${BC_TRAINER_SLOTS[$i]}"
        sac_slots="${SAC_TRAINER_SLOTS[$i]}"
        speed="${GAME_SPEEDS[$i]}"
        out="$TMPDIR/$mid"

        data=$(ssh_cmd "$host" "$user" "$pass" "
            LOAD=\$(awk '{print \$1, \$2, \$3}' /proc/loadavg)
            THREADS=\$(nproc)
            RAM=\$(free -m | LC_NUMERIC=C awk 'NR==2 {printf \"%.1f,%.1f\", \$3/1024, \$2/1024}')
            DISK=\$(df -BG /home/kris 2>/dev/null | awk 'NR==2 {gsub(\"G\",\"\"); printf \"%d,%d\", \$3, \$2}')
            UCC=\$(ps aux | grep ucc-bin | grep -v grep | wc -l)
            JAVA=\$(ps aux | grep 'java.*aiplay' | grep -v grep | wc -l)
            SAC=\$(ps aux | grep '\.trainSAC' | grep -v grep | wc -l)
            BC=\$(ps aux | grep '\.trainBC' | grep -v grep | wc -l)
            SYNC=\$(ps aux | grep sync_replay | grep -v grep | wc -l)
            TMUX=\$(tmux ls 2>/dev/null | awk -F: '{printf \"%s \", \$1}' || true)
            if command -v nvidia-smi &>/dev/null; then
                GPU=\$(nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total --format=csv,noheader,nounits 2>/dev/null | head -1)
            else
                GPU='-,-,-'
            fi
            echo \"\$LOAD|\$THREADS|\$RAM|\$DISK|\$UCC|\$JAVA|\$SAC|\$BC|\$SYNC|\$TMUX|\$GPU\"
        " 2>/dev/null || echo "UNREACHABLE")

        echo "$mid|$instances|$bc_slots|$sac_slots|$speed|$data" > "$out"
    ) &
done
wait

# Print table.
# CPU% = load_avg / threads * 100. A `!` marker means the 5-min average is at
# or above 85% saturation; `+` means at or above 60%. Same convention for RAM%
# (85% / 70% thresholds).
printf "\n"
FMT="%-6s | %-18s | %3s | %-7s | %-15s | %-16s | %-9s | %2s | %3s | %5s | %4s | %4s | %3s | %2s | %4s | %5s | %-15s | %s\n"
SEP="%-6s-+-%-18s-+-%-3s-+-%-7s-+-%-15s-+-%-16s-+-%-9s-+-%-2s-+-%-3s-+-%-5s-+-%-4s-+-%-4s-+-%-3s-+-%-2s-+-%-4s-+-%-5s-+-%-15s-+-%s\n"
printf "$FMT" \
    "ID" "CPU% 1m/5m/15m" "Thr" "RAM%" "RAM used/total" "Disk" "Instances" "BC" "SAC" "Speed" "UCC" "Java" "SAC" "BC" "Sync" "GPU %" "VRAM used/total" "tmux"
printf "$SEP" \
    "------" "------------------" "---" "-------" "---------------" "----------------" "---------" "--" "---" "-----" "----" "----" "---" "--" "----" "-----" "---------------" "----"

for mid in $(ls "$TMPDIR" | sort); do
    IFS='|' read -r id instances bc_slots sac_slots speed load threads ram disk ucc java sac bc sync tmux gpu < "$TMPDIR/$mid"

    # Format instances: "80/0" → "80 gpu/0 cpu" or "0/3" → "0 gpu/3 cpu"
    if [[ "$instances" == */* ]]; then
        inst_str="${instances%%/*}g/${instances##*/}c"
    else
        inst_str="$instances"
    fi

    speed_str="${speed}x"

    if [ "$load" = "UNREACHABLE" ]; then
        printf "$FMT" "$id" "OFFLINE" "-" "-" "-" "-" "$inst_str" "$bc_slots" "$sac_slots" "$speed_str" "-" "-" "-" "-" "-" "-" "-" "-"
        continue
    fi

    # CPU utilization: load_avg / threads * 100 — easier to read than raw load.
    read -r load_1 load_5 load_15 <<< "$load"
    if [ -z "${threads:-}" ] || [ "$threads" = "0" ]; then
        cpu_str="-"
        threads_str="-"
    else
        cpu_1=$(LC_NUMERIC=C awk -v l="$load_1"  -v t="$threads" 'BEGIN{printf "%.0f", l/t*100}')
        cpu_5=$(LC_NUMERIC=C awk -v l="$load_5"  -v t="$threads" 'BEGIN{printf "%.0f", l/t*100}')
        cpu_15=$(LC_NUMERIC=C awk -v l="$load_15" -v t="$threads" 'BEGIN{printf "%.0f", l/t*100}')
        if   [ "$cpu_5" -ge 85 ]; then cpu_marker="!"
        elif [ "$cpu_5" -ge 60 ]; then cpu_marker="+"
        else                           cpu_marker=" "
        fi
        cpu_str=$(printf "%3d%% %3d%% %3d%% %s" "$cpu_1" "$cpu_5" "$cpu_15" "$cpu_marker")
        threads_str="$threads"
    fi

    # RAM: combine used/total + show percentage with overload marker.
    IFS=',' read -r ram_used ram_total <<< "$ram"
    if [ -z "$ram_used" ]; then
        ram_str="-"
        ram_pct_str="-"
    else
        ram_pct=$(LC_NUMERIC=C awk -v u="$ram_used" -v t="$ram_total" 'BEGIN{printf "%.0f", u/t*100}')
        if   [ "$ram_pct" -ge 85 ]; then ram_marker="!"
        elif [ "$ram_pct" -ge 70 ]; then ram_marker="+"
        else                             ram_marker=" "
        fi
        ram_str=$(LC_NUMERIC=C printf "%5.1f/%5.1f GB" "$ram_used" "$ram_total")
        ram_pct_str=$(printf "%3d%% %s" "$ram_pct" "$ram_marker")
    fi

    # Disk: used/total + percentage.
    IFS=',' read -r disk_used disk_total <<< "$disk"
    if [ -z "$disk_used" ] || [ "$disk_total" = "0" ]; then
        disk_str="-"
    else
        disk_pct=$(LC_NUMERIC=C awk -v u="$disk_used" -v t="$disk_total" 'BEGIN{printf "%.0f", u/t*100}')
        disk_str=$(printf "%4d/%4dG %3d%%" "$disk_used" "$disk_total" "$disk_pct")
    fi

    # GPU: util + combined VRAM used/total.
    IFS=',' read -r gpu_util gpu_mem gpu_total <<< "$gpu"
    gpu_util=$(echo "$gpu_util" | tr -d ' ')
    gpu_mem=$(echo "$gpu_mem" | tr -d ' ')
    gpu_total=$(echo "$gpu_total" | tr -d ' ')

    if [ "$gpu_util" = "-" ]; then
        gpu_str="-"
        vram_str="-"
    else
        gpu_str="${gpu_util}%"
        vram_str=$(printf "%5d/%5d MB" "$gpu_mem" "$gpu_total")
    fi

    printf "$FMT" "$id" "$cpu_str" "$threads_str" "$ram_pct_str" "$ram_str" "$disk_str" "$inst_str" "$bc_slots" "$sac_slots" "$speed_str" "$ucc" "$java" "$sac" "$bc" "$sync" "$gpu_str" "$vram_str" "$tmux"
done
printf "\n"

# Show SAC assignment summary from the primary trainer when available.
parse_servers_conf
if find_primary_trainer >/dev/null 2>&1; then
    ASSIGNMENTS=$(ssh_cmd "$TRAINER_HOST" "$TRAINER_USER" "$TRAINER_PASS" \
        "cat '$SAC_ASSIGNMENTS_PATH' 2>/dev/null || true" 2>/dev/null || true)
    if [ -n "${ASSIGNMENTS// }" ]; then
        echo "SAC assignments (primary: $TRAINER_MACHINE_ID)"
        while IFS= read -r line; do
            [[ -z "$line" || "$line" == \#* ]] && continue
            echo "  $line"
        done <<< "$ASSIGNMENTS"
        printf "\n"
    fi
fi
