# The Smart Javac Wrapper JDK8 Backport with Extras!

1) Have you ever wondered why you can't point javac to a source
directory and automatically have it compile all sources found below?

2) Have you ever wondered why Eclipse and other IDEs have all the fun
with an up to date database of all your source code enabling fast
incremental compies?

3) Have you ever wondered why you can't write a proper makefile for
javac because you cannot predict what its going to output?

4) Have you ever wondered why javac is not multi-core?

Well, I have. When I designed the new build system for the OpenJDK a
couple of years ago I created the Smart Javac Wrapper, that would
enable all of the above. 1-3 are now more or less solved, whereas for
4 there is a proof of concept, but alas it will require much more work
within javac itself to actually show a noticeable speed difference.

Lets say you have all your sources below src, and you want your compiled classes below bin:

```
sjavac src -d bin
```

The sources will be found and compiled and put into bin. A second
directory bin_state will also be created to hold the database file
javac_state. If you run the same command again, it will quickly do
nothing. Now touch a source file, run the command again, the package
containing the updated source, will be recompiled. Now add a public
variable, run the command again, now any other package that is
dependent on your recompiled package, will also get recompiled. Notice
that only changes to the public api of a package will trigger
recompiles. Neat! (1 and 2 is done.)

Take a look at the contents of bin_state/javac_state file. It will
contain a database of your source code. What artifacts are created,
what dependencies there are both to other sources and to alread
compiled classes. The timestamp of the javac_state file will only be
updated when something has been compiled. Thus we can use the
javac_state file as the goal for a Makefile rule:

```
SRCS=$(shell find src -name "*.java")
bin_state/javac_state : $(SRCS)
      sjavac src -d bin
```

This simple Makefile will give you both fast detection that nothing
has to be done, and a proper incremental compile, if something needs
to be done. (3 is done)

When you compile, the default behaviour for sjavac is to spawn a
server in the background. This server will do the actual compilation
and you can follow its progress through bin_state/javac_log. If you do
multiple compiles after another, then the JVM will be warmed up and
succesive compiles will go a lot faster. The default keepalive is 120
seconds, ie if there is no compilation activity for that time, it will
shut down. You can increase the keepalive with:

```
sjavac -server:keepalive=1200 src1 src2 src3 -d bin
```

(Here we compile all sources found below src1, src2 and src3.) When
sjavac runs you can see how sjavac groups your source code, in an
effort to split the work on different cpus. If you are lucky, you
might get a speedup (4 is on its way, sort of). You can play for
example by adding: -j 5 -jj 3, to set the number of cores to 5 and the
maximum number of source groups to 3. You might also want to increase
the heap size of the server:

```
sjavac -server:vmargs=-verbose:gc%20-Xmx10G,keepalive=1200 mysrcs -d bin
```

(Look in javac_outerr for the verbose gc log. You can stop the server
by deleting the javac_port file, or touching javac_port.stop on
Windows, or "sjavac -stopserver -d bin")

To do proper incremental compiles, sjavac considers the output
directory to be its own and will forcibly prune it from unknown
artifacts! Thus if you want to put other stuff in the output
directory, you will have to use several sources and the copy rule. For
example:

```
sjavac srcs datasrcs -copy .xml -d bin
```

Any xml file found below srcs or datasrcs will be copied over to
bin. You can also do a properties transform, but then you need to set
"-s". For those working on the OpenJDK, suddenly its trivial to
compile the JDK9 javac langtools repository (even with its new modular
structure):

```
sjavac src/*/share/classes -tr .properties=sjavac.transforms.CompileProperties -d bin -s gensrc
```

There is even an rudimentary ant adapter, thus if you drop sjavac.jar
into /usr/shar/ant/lib, then you can run:

```
ant -Dbuild.compiler=sjavac.ant.Compiler build
```

and ant will then use sjavac to compile. Consider this more of a proof
of concept, the incremental aspect of ant does not currently play that
well with sjavac and the forced pruning, might confuse the
output. However, ant itself (apache-ant-1.9.3) can be compiled thus.

You must set JAVA_HOME to a jdk8 sdk before you can build it or use it!

# sjavac, the server

If you have a larger build made up of several modules, you probably
want to reuse the server between the modules. To do so, you have to
set the -server-dir: argument.

```
sjavac -server-dir:/tmp/myserver src1 -d bin1
```

This will still create the bin_state directory, but it will only
contain the javac_state file. The javac_port file, log and outerr file
will end up in /tmp/myserver. If you compile a separate set of
sources:

```
sjavac -server-dir:/tmp/myserver src2 -d bin2
```

it will use the same server for the second compile. You can easily see how the JVM warms up for your compiles if you compile the same source to different targets.

```
sjavac -server:id=run1 -server-dir:/tmp/myserver src -d bin1
sjavac -server:id=run2 -server-dir:/tmp/myserver src -d bin2
sjavac -server:id=run3 -server-dir:/tmp/myserver src -d bin3
sjavac -server:id=run4 -server-dir:/tmp/myserver src -d bin4
cat /tmp/myserver/javac_log

Sjavac server started. Accepting connections...
    port: 51132
        time: Sat Aug 30 07:28:47 CEST 2014
            poolsize: 4
            PooledSjavac-3 run1-1 compiled 16 sources in 2648ms giving 6.0 sources/s
            PooledSjavac-2 run1-0 compiled 18 sources in 3100ms giving 6.0 sources/s
            PooledSjavac-4 run1-2 compiled 21 sources in 3164ms giving 7.0 sources/s
            PooledSjavac-4 run2-1 compiled 16 sources in 967ms giving 17.0 sources/s
            PooledSjavac-2 run2-0 compiled 18 sources in 1235ms giving 15.0 sources/s
            PooledSjavac-3 run2-2 compiled 21 sources in 1340ms giving 16.0 sources/s
            PooledSjavac-4 run3-1 compiled 16 sources in 928ms giving 17.0 sources/s
            PooledSjavac-2 run3-0 compiled 18 sources in 1132ms giving 16.0 sources/s
            PooledSjavac-3 run3-2 compiled 21 sources in 1215ms giving 17.0 sources/s
            PooledSjavac-2 run4-1 compiled 16 sources in 710ms giving 23.0 sources/s
            PooledSjavac-4 run4-0 compiled 18 sources in 940ms giving 19.0 sources/s
            PooledSjavac-3 run4-2 compiled 21 sources in 1063ms giving 20.0 sources/s
```

You can see how the compilation speed increases significantly. Imagine
how much compilation time you waste when you invoke a cold external
javac again and again from make. Also note that a run is split into
three source chunks, trying to make use of the 4 cores.
