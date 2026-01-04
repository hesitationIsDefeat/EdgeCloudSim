#!/bin/bash

# Array of python scripts to execute
scripts=(
    "plotAvgFailedTask.py"
    "plotAvgNetworkDelay.py"
    "plotAvgProcessingTime.py"
    "plotAvgServiceTime.py"
    "plotAvgVmUtilization.py"
    "plotDelayReasonAsBar.py"
    "plotGenericLine.py"
    "plotLocation.py"
    "plotTaskFailureReason.py"
    "plotTimeComplexity.py"
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