if [ ! "$ANT_HOME" = "" ]; then
    ANTJAR=:$ANT_HOME/lib/ant.jar
    ANTSRC=sjavac.ant
fi
sjavac -cp $JAVA_HOME/lib/tools.jar$ANTJAR jdk.sjavac sjavac.transforms sjavac.test $ANTSRC -d bin "$@"
