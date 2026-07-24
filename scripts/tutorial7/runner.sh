java -classpath "$repo_root_path/bin:$repo_root_path/lib/cloudsim-7.0.0-alpha.jar:$repo_root_path/lib/commons-math3-3.6.1.jar:$repo_root_path/lib/colt.jar" edu.boun.edgecloudsim.applications.tutorial7.MainApp "$scenario_conf_file" "$scenario_edge_devices_file" "$scenario_applications_file" "$scenario_out_folder" "$iteration_number" > "${scenario_out_folder}.log" 2>&1
#!/bin/sh
set -eu

script_root_path="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
repo_root_path="$(CDPATH= cd -- "$script_root_path/../.." && pwd)"
simulation_out_folder=$1
scenario_name=$2
edge_devices_file=$3
applications_file=$4
iteration_number=$5

scenario_out_folder=${simulation_out_folder}/${scenario_name}/ite${iteration_number}
scenario_conf_file=${script_root_path}/config/${scenario_name}.properties
scenario_edge_devices_file=${script_root_path}/config/${edge_devices_file}
scenario_applications_file=${script_root_path}/config/${applications_file}

mkdir -p "$scenario_out_folder"
java -classpath "$repo_root_path/bin:$repo_root_path/lib/cloudsim-7.0.0-alpha.jar:$repo_root_path/lib/commons-math3-3.6.1.jar:$repo_root_path/lib/colt.jar" edu.boun.edgecloudsim.applications.tutorial7.MainApp "$scenario_conf_file" "$scenario_edge_devices_file" "$scenario_applications_file" "$scenario_out_folder" "$iteration_number" > "${scenario_out_folder}.log" 2>&1

if [ $? -eq 0 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') - ite${iteration_number} OK" >> "${simulation_out_folder}/${scenario_name}/progress.log"
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') - ite${iteration_number} FAIL !!!" >> "${simulation_out_folder}/${scenario_name}/progress.log"
fi

tar -czf "${scenario_out_folder}.tar.gz" -C "$simulation_out_folder/${scenario_name}" "ite${iteration_number}"