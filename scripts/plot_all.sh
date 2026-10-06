#!/bin/bash
# Activates the shared venv (tutorial6/python/.venv) and runs plot.sh for the listed tutorial numbers.
# Edit TUTORIALS below to choose which tutorials to plot.

TUTORIALS=(14)

# Non-interactive backend so plt.show() doesn't pop up a window and block each script.
export MPLBACKEND=Agg

script_root_path="$(dirname "$(readlink -f "$0")")"
venv_activate="${script_root_path}/tutorial6/python/.venv/bin/activate"

if [ ! -f "$venv_activate" ]; then
	echo "Cannot find venv activate script at ${venv_activate}"
	exit 1
fi

# shellcheck source=/dev/null
source "$venv_activate"

# Collect every tutorial's results (each under its own dedicated name) into a single
# timestamped folder for this run, instead of scattering them under sim_results/tutorialN/.
run_timestamp=$(date '+%Y-%m-%d-%H-%M')
export PLOT_RUN_DIR="${script_root_path}/../sim_results/${run_timestamp}"
mkdir -p "$PLOT_RUN_DIR"
echo "Collecting plots for this run under ${PLOT_RUN_DIR}"

for num in "${TUTORIALS[@]}"; do
	tutorial="tutorial${num}"
	python_dir="${script_root_path}/${tutorial}/python"
	plot_script="${python_dir}/plot.sh"

	if [ ! -f "$plot_script" ]; then
		echo "Skipping ${tutorial}: ${plot_script} not found"
		continue
	fi

	echo "###############################################################"
	echo "Plotting ${tutorial}"
	echo "###############################################################"
	(cd "$python_dir" && ./plot.sh)
done

deactivate
