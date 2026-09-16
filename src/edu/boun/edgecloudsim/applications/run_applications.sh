#!/bin/bash
# Compiles and runs MainApp (with default args) for the listed tutorial numbers, one after another.
# Edit TUTORIALS below to choose which tutorials to run.

TUTORIALS=(8 9 10 11 12)

script_root_path="$(dirname "$(readlink -f "$0")")"
repo_root="$(readlink -f "${script_root_path}/../../../../..")"
cd "$repo_root" || exit 1

classpath="lib/cloudsim-7.0.0-alpha.jar:lib/commons-math3-3.6.1.jar:lib/colt.jar"

rm -rf bin
mkdir bin

for num in "${TUTORIALS[@]}"; do
	tutorial="tutorial${num}"
	main_class="edu.boun.edgecloudsim.applications.${tutorial}.MainApp"
	main_source="src/edu/boun/edgecloudsim/applications/${tutorial}/MainApp.java"

	if [ ! -f "$main_source" ]; then
		echo "Skipping ${tutorial}: ${main_source} not found"
		continue
	fi

	echo "###############################################################"
	echo "Compiling ${tutorial}"
	echo "###############################################################"
	javac -classpath "$classpath" -sourcepath src "$main_source" -d bin
	if [ $? -ne 0 ]; then
		echo "Compilation failed for ${tutorial}, skipping run"
		continue
	fi

	echo "###############################################################"
	echo "Running ${tutorial}"
	echo "###############################################################"
	java -classpath "bin:$classpath" "$main_class"
done
