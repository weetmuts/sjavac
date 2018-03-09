#!/bin/sh
HERE=$(pwd)
rm -rf /tmp/test/*
mkdir -p /tmp/test
cd /tmp/test
java -ea -classpath $HERE/bin:$HERE/sjavac.jar:$JAVA_HOME/lib/tools.jar sjavac.test.SJavac
