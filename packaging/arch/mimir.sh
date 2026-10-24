#!/bin/sh
export _JAVA_AWT_WM_NONREPARENTING=1
exec java -jar /usr/share/java/mimir/mimir.jar "$@"
