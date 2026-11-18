#!/bin/bash

# Non-interactive backend so plt.show() doesn't pop up a window and block each script.
export MPLBACKEND=Agg

# ONAT: archive the config used for this run into the results folder, so reviewing
# results later doesn't require cross-referencing scripts/tutorial19/config (which may
# have changed since, e.g. for a later sweep). Kept as .xml/.properties (not renamed to
# .txt) since that's the simpler default - rename below if a viewer/tool needs .txt.
config_dir="../config"
tutorial_names_file="../../../config/tutorial_names.properties"
tutorial_name=$(grep "^tutorial19=" "$tutorial_names_file" 2>/dev/null | cut -d'=' -f2- | tr -d '\r')
: "${tutorial_name:=tutorial19}"
# PLOT_RUN_DIR (set by plot_all.sh) redirects output into a shared timestamped folder.
output_root="${PLOT_RUN_DIR:-../../../sim_results/tutorial19}"
results_dir="${output_root}/${tutorial_name}"
mkdir -p "$results_dir"
cp "$config_dir/default_config.properties" "$results_dir/"
cp "$config_dir/edge_devices.xml" "$results_dir/"
cp "$config_dir/applications.xml" "$results_dir/"
cp "$config_dir/streams.xml" "$results_dir/"
# ONAT: the tutorial is run once for every events file (events*.xml - see config.py's get_events_files()), and
# each run's results are plotted into a folder of its own inside $results_dir, named after the events file
# (config/event_names.properties), next to a copy of that events file. Inside it, the plots go to two
# sub-folders - 'applications' (the applications devices request on their own) and 'streams' - see
# config.get_plot_groups(). The heat map videos (plotUserLocationHeatmapVideo.py) go to the events folder
# itself: they show the users' crowd, the UAVs (colored by the service they host), the world events
# (EVENTS.log) and, under the map, the timeline of the events with a dot for the current time.

# Array of python scripts to execute
scripts=(
    "plotAvgFailedTask.py"
    "plotAvgNetworkDelay.py"
    "plotAvgProcessingTime.py"
    "plotAvgServiceTime.py"
    "plotAvgVmUtilization.py"
    "plotUserLocationHeatmapVideo.py"
    #"plotDelayReasonAsBar.py"
    #"plotGenericLine.py"
    #"plotLocation.py"
    "plotTaskFailureReason.py"
    #"plotTimeComplexity.py"
)

echo "Starting plot generation sequence..."
echo "------------------------------------"

# One run per events file: "<name> <events file name>" lines, read into arrays first so that nothing
# started inside the loop below can eat the list from stdin.
events_list=$(python3 config.py --events) || { echo "Error: cannot list the events files."; exit 1; }
event_names=()
event_files=()
while read -r event_name events_file
do
    [ -z "$event_name" ] && continue
    event_names+=("$event_name")
    event_files+=("$events_file")
done <<< "$events_list"

for index in "${!event_names[@]}"
do
    event_name="${event_names[$index]}"
    echo "===================================="
    echo "Events: $event_name"
    echo "===================================="

    # config.py reads PLOT_EVENT to pick this run's results folder and plot folder
    export PLOT_EVENT="$event_name"
    mkdir -p "${results_dir}/${event_name}"
    cp "$config_dir/${event_files[$index]}" "${results_dir}/${event_name}/"

    for script in "${scripts[@]}"
    do
        if [ -f "$script" ]; then
            echo "Running $script..."
            python3 "$script" < /dev/null

            # Check if the script executed successfully
            if [ $? -eq 0 ]; then
                echo "Successfully finished $script."
            else
                echo "Error: $script failed to execute."
            fi
        else
            echo "Warning: $script not found in the current directory."
        fi
        echo "------------------------------------"
    done
done

echo "All tasks completed."
