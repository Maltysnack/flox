#!/usr/bin/env bash
# Compile the Flox interpreter into ./build/
set -e
rm -rf build
mkdir -p build
javac -d build src/flox/*.java
echo "Built. Run with: java -cp build flox.Flox examples/<name>.flox"
