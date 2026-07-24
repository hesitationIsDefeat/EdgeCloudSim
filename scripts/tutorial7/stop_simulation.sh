#!/bin/bash
# stop_simulation.sh
# Usage: ./stop_simulation.sh <simulation_id>

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <simulation_id>"
    exit 1
fi

simulation_id=$1
script_root_path="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
simulation_out_folder="${script_root_path}/output/${simulation_id}"
pid_file="${simulation_out_folder}/simulation_pids.txt"

if [ ! -f "$pid_file" ]; then
    echo "No PID file found for simulation ID '${simulation_id}'."
    exit 1
fi

echo "Stopping simulation ${simulation_id}..."

while read pid; do
    if kill -0 $pid 2>/dev/null; then
        echo "Killing runner PID $pid and its child processes..."
        kill "$pid" 2>/dev/null || true
        if command -v pkill >/dev/null 2>&1; then
            pkill -P "$pid" 2>/dev/null || true
        fi
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            echo "Force killing PID $pid and its children"
            kill -9 "$pid" 2>/dev/null || true
            if command -v pkill >/dev/null 2>&1; then
                pkill -9 -P "$pid" 2>/dev/null || true
            fi
        fi
    fi
done < "$pid_file"

rm -f "$pid_file"
echo "Simulation ${simulation_id} stopped."
