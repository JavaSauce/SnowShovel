#!/bin/bash

set -euo pipefail

latest=$(curl -L https://maven.covers1624.net/net/javasauce/SnowShovel/maven-metadata.xml | grep -Po '(?<=<latest>)[^<]+(?=</latest>)')

mkdir -p bin
curl -LJ -o "bin/SnowShovel-$latest.zip" "https://maven.covers1624.net/net/javasauce/SnowShovel/$latest/SnowShovel-$latest.zip"
unzip "bin/SnowShovel-$latest.zip" -d bin/

exec java -jar "bin/SnowShovel-$latest.jar" "$@"
