#!/bin/bash
# Build (and optionally run) the SupervisorCoderSystem multi-agent orchestrator.
#   ./build.sh              -> compile to build/
#   ./build.sh run DIR MODE -> compile, then run the supervisor on DIR (CODING|PENTEST)
set -e
cd "$(dirname "$0")"
find src -name '*.java' > sources.txt
rm -rf build && mkdir build
javac -d build @sources.txt
echo "compiled OK -> build/"
if [ "${1:-}" = "run" ]; then shift; java -cp build com.hackerai.supervisor.Main "$@"; fi
