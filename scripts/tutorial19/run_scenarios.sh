#!/bin/bash
if [ "$#" -ne 2 ]; then
	echo "Missing arguments! Please provide number of parallel processes and number of iterations."
	echo "Usage: '$0 4 10'"
	exit 1
fi

re='^[0-9]+$'
if ! [[ $1 =~ $re ]] ; then
	echo "$1 is not an integer! Please provide number of parallel processes."
	echo "Usage: '$0 4 10'"
	exit 1
fi

if ! [[ $2 =~ $re ]] ; then
	echo "$1 is not an integer! Please provide number of iterations."
	echo "Usage: '$0 4 10'"
	exit 1
fi

script_root_path="$(dirname "$(readlink -f "$0")")"
root_out_folder=${script_root_path}/output
num_of_processes=$1
iterationNumber=$2
process_counter=0

# ONAT: A tutorial can come with several events files (events.xml, events_<anything>.xml in config/) and is
# run in full for each of them. The events column of simulation.list is one events file or a pattern such as
# events*.xml that matches several. A run is stored under the name its events file has in
# config/event_names.properties (the file name without .xml if it has none), which is also the folder of its
# results: output/<date>/<scenario name>/<events name>/ite<N>.tar.gz
tutorial_key="$(basename "$script_root_path")"
event_names_file=${script_root_path}/../../config/event_names.properties
config_folder=${script_root_path}/config

get_events_name() {
	local base=${1%.xml}
	local name=""
	if [ -f "$event_names_file" ]; then
		name=$(awk -F= -v key="${tutorial_key}.${base}" '{ k=$1; gsub(/^[ \t]+|[ \t]+$/, "", k) } k==key { v=substr($0, index($0, "=")+1); gsub(/^[ \t\r]+|[ \t\r]+$/, "", v); found=v } END { print found }' "$event_names_file")
	fi
	echo "${name:-$base}"
}

date=$(date '+%d-%m-%Y_%H-%M')
simulation_out_folder=${root_out_folder}/${date}
mkdir -p $simulation_out_folder

simulations=$(cat ${script_root_path}/simulation.list)

rm -rf ${script_root_path}/tmp_runner*
used_runs=" "
		
for sim_args in $simulations
do
	scenario_name=$(echo $sim_args | cut -d ';' -f1)
	edge_devices_file=$(echo $sim_args | cut -d ';' -f2)
	applications_file=$(echo $sim_args | cut -d ';' -f3)
	streams_file=$(echo $sim_args | cut -d ';' -f4)
	events_pattern=$(echo $sim_args | cut -d ';' -f5)

	events_files=$(cd "$config_folder" && ls -1 $events_pattern 2>/dev/null)
	if [ -z "$events_files" ]; then
		echo "No events file in ${config_folder} matches '${events_pattern}' (see simulation.list)!"
		exit 1
	fi

	for events_file in $events_files
	do
		events_name=$(get_events_name $events_file)
		if ! [[ $events_name =~ ^[A-Za-z0-9_.-]+$ ]] ; then
			echo "'${events_name}' is not a valid name for ${events_file}! Use only letters, digits, '_', '-' and '.' (see ${event_names_file})."
			exit 1
		fi
		if [[ $used_runs == *" ${scenario_name}/${events_name} "* ]] ; then
			echo "The run '${scenario_name}/${events_name}' is listed more than once (see simulation.list and ${event_names_file})!"
			exit 1
		fi
		used_runs="${used_runs}${scenario_name}/${events_name} "

		mkdir -p $simulation_out_folder/${scenario_name}/${events_name}
		echo "$(date '+%Y-%m-%d %H:%M:%S') - STARTED" > $simulation_out_folder/${scenario_name}/${events_name}/progress.log

		for (( i=1; i<=$iterationNumber; i++ ))
		do
			process_id=$(($process_counter % $num_of_processes))
			process_counter=$(($process_counter + 1))

			echo "${script_root_path}/runner.sh $simulation_out_folder $scenario_name $edge_devices_file $applications_file $streams_file $events_file $events_name ${i}" >> "${simulation_out_folder}/tmp_runner${process_id}.sh"
		done
	done
done

#num_of_cores=$(grep -c ^processor /proc/cpuinfo)

for (( i=0; i<$num_of_processes; i++ ))
do
	chmod +x ${simulation_out_folder}/tmp_runner${i}.sh
	setsid ${simulation_out_folder}/tmp_runner${i}.sh &
	pid=$!
	echo $pid >> "${simulation_out_folder}/simulation_pids.txt"
done

echo "###############################################################"
echo "                  SIMULATIONS ARE STARTED!"
echo "###############################################################"
echo "Simulation ID: ${date}"
echo "###############################################################"
echo "You can follow the progress via the following command"
echo "tail -f <simulation_folder>/<events name>/progress.log"
echo "e.g."
echo "tail -f output/${date}/${scenario_name}/${events_name}/progress.log"
echo "###############################################################"
echo "You can inspect each iteration via the following command"
echo "tail -f <simulation_folder>/<events name>/ite[n].log"
echo "e.g."
echo "tail -f output/${date}/${scenario_name}/${events_name}/ite1.log"
echo "###############################################################"
echo "You can stop all simulations via the following command"
echo "./stop_simulation.sh <simulation_id>"
echo "e.g."
echo "./stop_simulation.sh ${date}"
echo "###############################################################"
