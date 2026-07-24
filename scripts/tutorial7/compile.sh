#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
bin_dir="$repo_root/bin"

rm -rf "$bin_dir"
mkdir -p "$bin_dir"
javac -classpath "$repo_root/lib/cloudsim-7.0.0-alpha.jar:$repo_root/lib/commons-math3-3.6.1.jar:$repo_root/lib/colt.jar" -sourcepath "$repo_root/src" "$repo_root/src/edu/boun/edgecloudsim/applications/tutorial7/MainApp.java" -d "$bin_dir"
