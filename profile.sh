rm -rf /tmp/ban*
mkdir -p /tmp/ban 
echo Plain javac
time $JAVA_HOME/bin/javac -cp $JAVA_HOME/lib/tools.jar -implicit:none -XDignore.symbol.file=true \
-d /tmp/bin \
jdk.sjavac/com/sun/tools/sjavac/*.java \
jdk.sjavac/com/sun/tools/sjavac/*/*.java \
jdk.sjavac/com/sun/tools/javac/util/StringUtils.java \
sjavac.transforms/sjavac/transforms/*.java 

rm -rf /tmp/bon*
mkdir -p /tmp/bon
echo With Smart Javac Wrapper
time sjavac -cp $JAVA_HOME/lib/tools.jar jdk.sjavac sjavac.transforms -d /tmp/bon -log:timing

diff -rq /tmp/ban /tmp/bon
