#!/bin/sh

script_root_path="$(dirname "$(readlink -f "$0")")"
simulation_out_folder=$1
scenario_name=$2
edge_devices_file=$3
applications_file=$4
streams_file=$5
events_file=$6
iteration_number=$7

scenario_out_folder=${simulation_out_folder}/${scenario_name}/ite${iteration_number}
scenario_conf_file=${script_root_path}/config/${scenario_name}.properties
scenario_edge_devices_file=${script_root_path}/config/${edge_devices_file}
scenario_applications_file=${script_root_path}/config/${applications_file}
scenario_streams_file=${script_root_path}/config/${streams_file}
scenario_events_file=${script_root_path}/config/${events_file}

mkdir -p $scenario_out_folder
java -classpath '../../bin:../../lib/cloudsim-7.0.0-alpha.jar:../../lib/commons-math3-3.6.1.jar:../../lib/colt.jar' edu.boun.edgecloudsim.applications.tutorial19.MainApp $scenario_conf_file $scenario_edge_devices_file $scenario_applications_file $scenario_streams_file $scenario_events_file $scenario_out_folder $iteration_number > ${scenario_out_folder}.log

if [ $? -eq 0 ]; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') - ite${iteration_number} OK" >> ${simulation_out_folder}/${scenario_name}/progress.log
else
    echo "$(date '+%Y-%m-%d %H:%M:%S') - ite${iteration_number} FAIL !!!" >> ${simulation_out_folder}/${scenario_name}/progress.log
fi

tar -czf ${scenario_out_folder}.tar.gz -C $simulation_out_folder/${scenario_name} ite${iteration_number}
rm -rf $scenario_out_folder
