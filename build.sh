echo Compiling
if [ ! "$ANT_HOME" = "" ]; then
    ANTJAR=:$ANT_HOME/lib/ant.jar
    ANTSRC=$(echo sjavac.ant/sjavac/ant/*.java)
fi

rm -rf /tmp/bin/*
mkdir -p /tmp/bin 
$JAVA_HOME/bin/javac -cp $JAVA_HOME/lib/tools.jar$ANTJAR -implicit:none -XDignore.symbol.file=true \
-d /tmp/bin \
jdk.sjavac/com/sun/tools/sjavac/*.java \
jdk.sjavac/com/sun/tools/sjavac/*/*.java \
jdk.sjavac/com/sun/tools/javac/util/StringUtils.java \
sjavac.transforms/sjavac/transforms/*.java \
sjavac.test/sjavac/test/*.java \
sjavac.test/sjavac/test/util/*.java \
$ANTSRC

echo Creating jar
rm -f sjavac.jar
(cd /tmp/bin; find . -type f > ../files; jar cf sjavac.jar @../files; rm ../files)
mv /tmp/bin/sjavac.jar .

