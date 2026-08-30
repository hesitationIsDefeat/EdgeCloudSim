#!/bin/bash

# ONAT: archive the config used for this run into the results folder, so reviewing
# results later doesn't require cross-referencing scripts/tutorial10/config (which may
# have changed since, e.g. for a later sweep). Kept as .xml/.properties (not renamed to
# .txt) since that's the simpler default - rename below if a viewer/tool needs .txt.
config_dir="../config"
results_dir="../../../sim_results/tutorial10"
mkdir -p "$results_dir"
cp "$config_dir/default_config.properties" "$results_dir/"
cp "$config_dir/edge_devices.xml" "$results_dir/"
cp "$config_dir/applications.xml" "$results_dir/"

# Array of python scripts to execute
scripts=(
    "plotAvgFailedTask.py"
    #"plotAvgNetworkDelay.py"
    #"plotAvgProcessingTime.py"
    #"plotAvgServiceTime.py"
    "plotAvgVmUtilization.py"
    "plotUserLocationHeatmapVideo.py"
    #"plotDelayReasonAsBar.py"
    #"plotGenericLine.py"
    #"plotLocation.py"
    #"plotTaskFailureReason.py"
    #"plotTimeComplexity.py"
)

echo "Starting plot generation sequence..."
echo "------------------------------------"

for script in "${scripts[@]}"
do
    if [ -f "$script" ]; then
        echo "Running $script..."
        python3 "$script"
        
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

echo "All tasks completed."
