#!/bin/sh
set -eu
exec java -XX:MaxRAMPercentage=65 -cp 'classes:lib/*' WebServer --hosted
