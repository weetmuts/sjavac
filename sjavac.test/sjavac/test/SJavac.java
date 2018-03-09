/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
/* Contains sources copyright Fredrik Öhrström 2014, 
 * licensed from Fredrik to you under the above license. */

package sjavac.test;
/*
 * @test
 * @summary Test all aspects of sjavac.
 * @bug 8004658 8042441 8042699 8054461 8054474 8054465
 *
 * @build Wrapper
 * @run main Wrapper SJavac
 */

import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.*;

import com.sun.tools.sjavac.Main;

public class SJavac {

    public static void main(String... args) throws Exception {
        try {
            SJavac s = new SJavac();
            s.test();
        } finally {
            System.out.println("\ntest complete\n");
        }
    }

    FileSystem defaultfs = FileSystems.getDefault();
    String serverArg = "-server:"
            + "portfile=testportfile,"
            + "background=false";

    // Where to put generated sources that will
    // test aspects of sjavac, ie JTWork/scratch/gensrc
    Path gensrc;
    // More gensrc dirs are used to test merging of serveral source roots.
    Path gensrc2;
    Path gensrc3;

    // Where to put compiled classes.
    Path bin;
    Path bin2;
    // Where to put c-header files.
    Path headers;

    // The sjavac compiler.
    Main main = new Main();

    // Remember the previous bin and headers state here.
    Map<String,Long> previous_bin_state;
    Map<String,Long> previous_bin2_state;
    Map<String,Long> previous_headers_state;

    public void test() throws Exception {
        gensrc = defaultfs.getPath("gensrc");
        gensrc2 = defaultfs.getPath("gensrc2");
        gensrc3 = defaultfs.getPath("gensrc3");
        bin = defaultfs.getPath("bin");
        bin2 = defaultfs.getPath("bin2");
        headers = defaultfs.getPath("headers");

        Files.createDirectory(gensrc);
        Files.createDirectory(gensrc2);
        Files.createDirectory(gensrc3);
        Files.createDirectory(bin);
        Files.createDirectory(bin2);
        Files.createDirectory(headers);

        initialCompile();
        incrementalCompileNoChanges();
        incrementalCompileDroppingClasses();
        incrementalCompileWithChange();
        incrementalCompileDropAllNatives();
        incrementalCompileAddNative();
        incrementalCompileChangeNative();
        compileWithOverrideSource();
        compileWithInvisibleSources();
        compileCircularSources();
        compileExcludingDependency();
        incrementalCompileTestFullyQualifiedRef();
        compileTestingClasspathPubapis();
        compileWithAtFile();
        testStateDir();
        testServerDir();
        testPermittedArtifact();
        incrementalCompileTestSourceRootChange();
        testCopy();
        testCompileProperties();

        delete(gensrc);
        delete(gensrc2);
        delete(gensrc3);
        delete(bin);
        delete(headers);
    }

    void initialCompile() throws Exception {
        System.out.println("\nInitial compile of gensrc.");
        System.out.println("----------------------------");
        populate(gensrc,
            "alfa/omega/AINT.java",
            "package alfa.omega; public interface AINT { void aint(); }",

            "alfa/omega/A.java",
            "package alfa.omega; public class A implements AINT { "+
                 "public final static int DEFINITION = 17; public void aint() { } }",

            "alfa/omega/AA.java",
            "package alfa.omega;"+
            "// A package private class, not contributing to the public api.\n"+
            "class AA {"+
            "   // A properly nested static inner class.\n"+
            "    static class AAA { }\n"+
            "    // A properly nested inner class.\n"+
            "    class AAAA { }\n"+
            "    Runnable foo() {\n"+
            "        // A proper anonymous class.\n"+
            "        return new Runnable() { public void run() { } };\n"+
            "    }\n"+
            "    AAA aaa;\n"+
            "    AAAA aaaa;\n"+
            "    AAAAA aaaaa;\n"+
            "}\n"+
            "class AAAAA {\n"+
            "    // A bad auxiliary class, but no one is referencing it\n"+
            "    // from outside of this source file, therefore it is ok.\n"+
            "}\n",

            "beta/BINT.java",
            "package beta;public interface BINT { void foo(); }",

            "beta/B.java",
            "package beta; import alfa.omega.A; public class B {"+
            "private int b() { return A.DEFINITION; } native void foo(); }");

        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        previous_bin_state = collectState(bin);
        previous_headers_state = collectState(headers);
    }

    void incrementalCompileNoChanges() throws Exception {
        System.out.println("\nTesting that no change in sources implies no change in binaries.");
        System.out.println("------------------------------------------------------------------");
        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyEqual(new_bin_state, previous_bin_state);
        Map<String,Long> new_headers_state = collectState(headers);
        verifyEqual(previous_headers_state, new_headers_state);
    }

    void incrementalCompileDroppingClasses() throws Exception {
        System.out.println("\nTesting that deleting AA.java deletes all");
        System.out.println("generated inner class as well as AA.class");
        System.out.println("-----------------------------------------");
        removeFrom(gensrc, "alfa/omega/AA.java");
        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenRemoved(previous_bin_state, new_bin_state,
                                       "bin/alfa/omega/AA$1.class",
                                       "bin/alfa/omega/AA$AAAA.class",
                                       "bin/alfa/omega/AA$AAA.class",
                                       "bin/alfa/omega/AAAAA.class",
                                       "bin/alfa/omega/AA.class");

        previous_bin_state = new_bin_state;
        Map<String,Long> new_headers_state = collectState(headers);
        verifyEqual(previous_headers_state, new_headers_state);
    }

    void incrementalCompileWithChange() throws Exception {
        System.out.println("\nNow update the A.java file with a new timestamps and");
        System.out.println("new final static definition. This should trigger a recompile,");
        System.out.println("not only of alfa, but also beta.");
        System.out.println("But check that the generated native header was not updated!");
        System.out.println("Since we did not modify the native api of B.");
        System.out.println("-------------------------------------------------------------");

        populate(gensrc,"alfa/omega/A.java",
                       "package alfa.omega; public class A implements AINT { "+
                 "public final static int DEFINITION = 18; public void aint() { } private void foo() { } }");

        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);

        verifyNewerFiles(previous_bin_state, new_bin_state,
                         "bin/alfa/omega/A.class",
                         "bin/alfa/omega/AINT.class",
                         "bin/beta/B.class",
                         "bin/beta/BINT.class",
                         "bin/javac_state");
        previous_bin_state = new_bin_state;

        Map<String,Long> new_headers_state = collectState(headers);
        verifyEqual(new_headers_state, previous_headers_state);
    }

    void incrementalCompileDropAllNatives() throws Exception {
        System.out.println("\nNow update the B.java file with one less native method,");
        System.out.println("ie it has no longer any methods!");
        System.out.println("Verify that beta_B.h is removed!");
        System.out.println("---------------------------------------------------------");

        populate(gensrc,"beta/B.java",
                       "package beta; import alfa.omega.A; public class B {"+
                       "private int b() { return A.DEFINITION; } }");

        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyNewerFiles(previous_bin_state, new_bin_state,
                         "bin/beta/B.class",
                         "bin/beta/BINT.class",
                         "bin/javac_state");
        previous_bin_state = new_bin_state;

        Map<String,Long> new_headers_state = collectState(headers);
        verifyThatFilesHaveBeenRemoved(previous_headers_state, new_headers_state,
                                       "headers/beta_B.h");
        previous_headers_state = new_headers_state;
    }

    void incrementalCompileAddNative() throws Exception {
        System.out.println("\nNow update the B.java file with a final static annotated with @Native.");
        System.out.println("Verify that beta_B.h is added again!");
        System.out.println("------------------------------------------------------------------------");

        populate(gensrc,"beta/B.java",
                       "package beta; import alfa.omega.A; public class B {"+
                       "private int b() { return A.DEFINITION; } "+
                 "@java.lang.annotation.Native final static int alfa = 42; }");

        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyNewerFiles(previous_bin_state, new_bin_state,
                         "bin/beta/B.class",
                         "bin/beta/BINT.class",
                         "bin/javac_state");
        previous_bin_state = new_bin_state;

        Map<String,Long> new_headers_state = collectState(headers);
        verifyThatFilesHaveBeenAdded(previous_headers_state, new_headers_state,
                                     "headers/beta_B.h");
        previous_headers_state = new_headers_state;
    }

    void incrementalCompileChangeNative() throws Exception {
        System.out.println("\nNow update the B.java file with a new value for the final static"+
                           " annotated with @Native.");
        System.out.println("Verify that beta_B.h is rewritten again!");
        System.out.println("-------------------------------------------------------------------");

        populate(gensrc,"beta/B.java",
                       "package beta; import alfa.omega.A; public class B {"+
                       "private int b() { return A.DEFINITION; } "+
                 "@java.lang.annotation.Native final static int alfa = 43; }");

        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyNewerFiles(previous_bin_state, new_bin_state,
                         "bin/beta/B.class",
                         "bin/beta/BINT.class",
                         "bin/javac_state");
        previous_bin_state = new_bin_state;

        Map<String,Long> new_headers_state = collectState(headers);
        verifyNewerFiles(previous_headers_state, new_headers_state,
                         "headers/beta_B.h");
        previous_headers_state = new_headers_state;
    }

    void compileWithOverrideSource() throws Exception {
        System.out.println("\nNow verify that we can override sources to be compiled.");
        System.out.println("Compile gensrc and gensrc2. However do not compile broken beta.B in gensrc,");
        System.out.println("only compile ok beta.B in gensrc2.");
        System.out.println("---------------------------------------------------------------------------");

        delete(gensrc);
        delete(gensrc2);
        delete(bin);
        previous_bin_state = collectState(bin);

        populate(gensrc,"alfa/omega/A.java",
                 "package alfa.omega; import beta.B; import gamma.C; public class A { B b; C c; }",
                 "beta/B.java",
                 "package beta; public class B { broken",
                 "gamma/C.java",
                 "package gamma; public class C { }");

        populate(gensrc2,
                 "beta/B.java",
                 "package beta; public class B { }");

        compile("-x", "beta", "gensrc", "gensrc2", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg);
        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state, 
                                     "bin/alfa/omega/A.class",
                                     "bin/beta/B.class",
                                     "bin/gamma/C.class",
                                     "bin/javac_state");

        System.out.println("----- Compile with exluded beta went well!");
        delete(bin);
        compileExpectFailure("gensrc", "gensrc2", "-d", "bin", "-h", "headers", "-j", "1",
                             serverArg);

        System.out.println("----- Compile without exluded beta failed, as expected! Good!");
        delete(bin);
    }

    void compileWithInvisibleSources() throws Exception {
        System.out.println("\nNow verify that we can make sources invisible to linking (sourcepath).");
        System.out.println("Compile gensrc and link against gensrc2 and gensrc3, however");
        System.out.println("gensrc2 contains broken code in beta.B, thus we must exclude that package");
        System.out.println("fortunately gensrc3 contains a proper beta.B.");
        System.out.println("------------------------------------------------------------------------");

        // Start with a fresh gensrcs and bin.
        delete(gensrc);
        delete(gensrc2);
        delete(gensrc3);
        delete(bin);
        previous_bin_state = collectState(bin);

        populate(gensrc,"alfa/omega/A.java",
                 "package alfa.omega; import beta.B; import gamma.C; public class A { B b; C c; }");
        populate(gensrc2,"beta/B.java",
                 "package beta; public class B { broken",
                 "gamma/C.java",
                 "package gamma; public class C { }");
        populate(gensrc3, "beta/B.java",
                 "package beta; public class B { }");

        compile("gensrc", "-x", "beta", "-sourcepath", "gensrc2",
                "-sourcepath", "gensrc3", "-d", "bin", "-h", "headers", "-j", "1", "-state-dir:bin",
                serverArg);

        System.out.println("The first compile went well!");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                                     "bin/alfa/omega/A.class",
                                     "bin/javac_state");

        System.out.println("----- Compile with exluded beta went well!");
        delete(bin);
        compileExpectFailure("gensrc", "-sourcepath", "gensrc2", "-sourcepath", "gensrc3",
                             "-d", "bin", "-h", "headers", "-j", "1",
                             serverArg);

        System.out.println("----- Compile without exluded beta failed, as expected! Good!");
        delete(bin);
    }

    void compileCircularSources() throws Exception {
        System.out.println("\nNow verify that circular sources split on multiple cores can be compiled.");
        System.out.println("---------------------------------------------------------------------------");

        // Start with a fresh gensrcs and bin.
        delete(gensrc);
        delete(gensrc2);
        delete(gensrc3);
        delete(bin);
        previous_bin_state = collectState(bin);

        populate(gensrc,"alfa/omega/A.java",
                 "package alfa.omega; public class A { beta.B b; }",
                 "beta/B.java",
                 "package beta; public class B { gamma.C c; }",
                 "gamma/C.java",
                 "package gamma; public class C { alfa.omega.A a; }");

        compile("gensrc", "-d", "bin", "-h", "headers", "-j", "3", "-state-dir:bin",
                serverArg,"-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                                     "bin/alfa/omega/A.class",
                                     "bin/beta/B.class",
                                     "bin/gamma/C.class",
                                     "bin/javac_state");
        delete(bin);
    }

    /**
     * Tests compiling class A that depends on class B without compiling class B
     * @throws Exception If test fails
     */
    void compileExcludingDependency() throws Exception {
        System.out.println("\nVerify that excluding classes from compilation but not from linking works.");
        System.out.println("---------------------------------------------------------------------------");

        delete(gensrc);
        delete(bin);
        previous_bin_state = collectState(bin);

        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { beta.B b; }",
                 "beta/B.java",
                 "package beta; public class B { }");

        compile("-x", "beta", "-src", "gensrc", "-x", "alfa/omega", "-sourcepath", "gensrc", "-state-dir:bin",
                "-d", "bin", serverArg);

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                                     "bin/alfa/omega/A.class",
                                     "bin/javac_state");
    }

    void incrementalCompileTestFullyQualifiedRef() throws Exception {
        System.out.println("\nVerify that \"alfa.omega.A a;\" does create a proper dependency.");
        System.out.println("----------------------------------------------------------------");

        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { "+
                 "  public final static int DEFINITION = 18; "+
                 "  public void hello() { }"+
                 "}",
                 "beta/B.java",
                 "package beta; public class B { "+
                 "  public void world() { alfa.omega.A a; }"+
                 "}");

        compile("gensrc", "-d", "bin", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> previous_bin_state = collectState(bin);

        // Change pubapi of A, this should trigger a recompile of B.
        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { "+
                 "  public final static int DEFINITION = 19; "+
                 "  public void hello() { }"+
                 "}");

        compile("gensrc", "-d", "bin", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);

        verifyNewerFiles(previous_bin_state, new_bin_state,
                         "bin/alfa/omega/A.class",
                         "bin/beta/B.class",
                         "bin/javac_state");
    }

   /**
     * Tests that we track the pubapis of classes on the classpath.
     * @throws Exception If test fails
     */
    void compileTestingClasspathPubapis() throws Exception {
        System.out.println("\nTest pubapi changes of classes on the classpath.");
        System.out.println("--------------------------------------------------");

        delete(gensrc);
        delete(gensrc2);
        delete(bin);
        delete(bin2);

        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; import beta.B; public class A { B b; }",
                 "beta/B.java",
                 "package beta; public class B { }");

        populate(gensrc2,
                 "gamma/C.java",
                 "package gamma; import alfa.omega.A; public class C { A a; }");
        System.out.println("Compiling bin...");
        compile("-src", "gensrc", "-d", "bin", "-state-dir:bin", "-server:portfile=testserver,background=false");
        System.out.println("Compiling bin2...");
        compile("-classpath", "bin", "-src", "gensrc2", "-d", "bin2", "-state-dir:bin2", "-server:portfile=testserver,background=false");

        previous_bin2_state = collectState(bin2);
        populate(gensrc,
                 "alfa/omega/AA.java",
                 "package alfa.omega; public class AA { }");

        System.out.println("Compiling bin again...");
        compile("-src", "gensrc", "-d", "bin", "-state-dir:bin", "-server:portfile=testserver,background=false");
        System.out.println("Compiling bin2 again...");
        compile("-classpath", "bin", "-src", "gensrc2", "-d", "bin2", "-state-dir:bin2",
                "-server:portfile=testserver,background=false");

        Map<String,Long> new_bin2_state = collectState(bin2);
        // Adding the class AA to alfa.A does not change the pubapi of the classes in alfa.A that
        // is actually used. Thus there should be no change of bin2. If alfa.A had been inside a jar
        // file, then bin2/javac_state would have been updated with a new timestamp for the jar.
        verifyNewerFiles(previous_bin2_state, new_bin2_state,
                         "bin2/javac_state");

        // Now modify pubapi of A
        previous_bin2_state = collectState(bin2);
        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; import beta.B; public class A { B b; public int a; }");

        System.out.println("Compiling bin again...");
        compile("-src", "gensrc", "-d", "bin", "-state-dir:bin", "-server:portfile=testserver,background=false");
        System.out.println("Compiling bin2 again");
        compile("-classpath", "bin", "-src", "gensrc2", "-d", "bin2", "-state-dir:bin2",
                "-server:portfile=testserver,background=false");

        new_bin2_state = collectState(bin2);
        // Check that C was really recompiled due to the change in A:s pubapi.
        verifyNewerFiles(previous_bin2_state, new_bin2_state,
                         "bin2/gamma/C.class",
                         "bin2/javac_state");

    }

    /**
     * Tests @atfile
     * @throws Exception If test fails
     */
    void compileWithAtFile() throws Exception {
        System.out.println("\nTest @atfile with command line content.");
        System.out.println("---------------------------------------");

        delete(gensrc);
        delete(gensrc2);
        delete(bin);

        populate(gensrc,
                 "list.txt",
                 "-if */alfa/omega/A.java\n-if */beta/B.java\ngensrc\n-d bin\n",
                 "alfa/omega/A.java",
                 "package alfa.omega; import beta.B; public class A { B b; }",
                 "beta/B.java",
                 "package beta; public class B { }",
                 "beta/C.java",
                 "broken");
        previous_bin_state = collectState(bin);
        compile("@gensrc/list.txt", "-state-dir:bin", "-server:portfile=testserver,background=false");

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                         "bin/javac_state",
                         "bin/alfa/omega/A.class",
                         "bin/beta/B.class");
    }

    /**
     * Tests storing javac_state into another directory.
     * @throws Exception If test fails
     */
    void testStateDir() throws Exception {
        System.out.println("\nVerify that -state-dir:bar works.");
        System.out.println("----------------------------------");

        Path bar = defaultfs.getPath("bar");
        Files.createDirectory(bar);

        delete(gensrc);
        delete(bin);
        delete(bar);
        previous_bin_state = collectState(bin);
        Map<String,Long> previous_bar_state = collectState(bar);

        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { }");

        compile("-state-dir:bar", "-src", "gensrc", "-d", "bin", serverArg);

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                                     "bin/alfa/omega/A.class");
        Map<String,Long> new_bar_state = collectState(bar);
        verifyThatFilesHaveBeenAdded(previous_bar_state, new_bar_state,
                                     "bar/javac_state");
        delete(bar);
    }

    /**
     * Tests storing javac_portfile,log,outerr into another directory.
     * @throws Exception If test fails
     */
    void testServerDir() throws Exception {
        System.out.println("\nVerify that -server-dir:bar works, also tests background server spawn.");
        System.out.println("------------------------------------------------------------------------");

        Path bar = defaultfs.getPath("bar");
        if (!Files.exists(bar)) Files.createDirectory(bar);
        delete(bar);

        Path bur = defaultfs.getPath("bur");
        if (!Files.exists(bur)) Files.createDirectory(bur);
        delete(bur);

        delete(gensrc);
        delete(bin);
        previous_bin_state = collectState(bin);
        Map<String,Long> previous_bar_state = collectState(bar);
        Map<String,Long> previous_bur_state = collectState(bur);

        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { }");

        compile("-state-dir:bar", "-server-dir:bur", "-src", "gensrc", "-d", "bin");

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                                     "bin/alfa/omega/A.class");
        Map<String,Long> new_bar_state = collectState(bar);
        verifyThatFilesHaveBeenAdded(previous_bar_state, new_bar_state,
                                     "bar/javac_state");
        Map<String,Long> new_bur_state = collectState(bur);
        verifyThatFilesHaveBeenAdded(previous_bur_state, new_bur_state,
                                     "bur/javac_port",
                                     "bur/javac_log",
                                     "bur/javac_outerr");

        compile("-state-dir:bar", "-server-dir:bur", "-stopserver");
        delete(bar);
    }

    /**
     * Test white listing of external artifacts inside the destination dir.
     * @throws Exception If test fails
     */
    void testPermittedArtifact() throws Exception {
        System.out.println("\nVerify that -Xpermit-artifact:bar works.");
        System.out.println("-------------------------------------------");

        delete(gensrc);
        delete(bin);

        previous_bin_state = collectState(bin);

        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { }");

        populate(bin,
                 "alfa/omega/AA.class",
                 "Ugh, a messy build system (tobefixed) wrote this class file, sjavac must not delete it.");

        compile("-log:debug", "-Xpermit-artifact:bin/alfa/omega/AA.class", "-src", "gensrc", "-d", "bin", 
                "-state-dir:bin", serverArg);

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                                     "bin/alfa/omega/A.class",
                                     "bin/alfa/omega/AA.class",
                                     "bin/javac_state");
    }

    void incrementalCompileTestSourceRootChange() throws Exception {
        System.out.println("\nVerify that a command line change of source roots\ndoes not prevent an incremental compile.");
        System.out.println("-----------------------------------------------");

        delete(gensrc);
        delete(bin);
        populate(gensrc,
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { "+
                 "  public final static int DEFINITION = 18; "+
                 "  public void hello() { }"+
                 "}",
                 "beta/B.java",
                 "package beta; public class B { "+
                 "  public void world() { alfa.omega.A a; }"+
                 "}");

        compile("gensrc", "-d", "bin", "-j", "1", "-state-dir:bin", serverArg, "-log:debug");
        Map<String,Long> previous_bin_state = collectState(bin);

        System.out.println("-----------------------------------------------");
        
        // Now compile again, but exclude the non-existant gamma package, this should be an incremental
        // compile with no change of the destination dir at all.
        compile("-x", "gamma", "gensrc", "-d", "bin", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        Map<String,Long> new_bin_state = collectState(bin);

        verifyEqual(previous_bin_state, new_bin_state);

        System.out.println("-----------------------------------------------");

        // Now compile again, but exclude beta, this should be an incremental
        // compile where beta/B.class is removed.
        compile("-x", "beta", "gensrc", "-d", "bin", "-j", "1", "-state-dir:bin",
                serverArg, "-log:debug");
        new_bin_state = collectState(bin);
       
        verifyThatFilesHaveBeenRemoved(previous_bin_state, new_bin_state,
                                       "bin/beta/B.class");
    }

    /**
     * Test copy file.
     * @throws Exception If test fails
     */
    void testCopy() throws Exception {
        System.out.println("\nTest -copy");
        System.out.println("------------");

        delete(gensrc);
        delete(gensrc2);
        delete(bin);

        populate(gensrc,
                 "alfa/omega/info.txt",
                 "Hello",
                 "beta/info.txt",
                 "There");

        previous_bin_state = collectState(bin);
        compile("-copy", ".txt", "-x", "alfa/omega", "gensrc", "-s", "gensrc2", "-d", "bin", "-state-dir:bin", "-server:portfile=testserver,background=false");

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                         "bin/javac_state",
                         "bin/beta/info.txt");
    }

    /**
     * Test compileProperties file.
     * @throws Exception If test fails
     */
    void testCompileProperties() throws Exception {
        System.out.println("\nTest -tr");
        System.out.println("----------");

        delete(gensrc);
        delete(gensrc2);
        delete(bin);

        populate(gensrc,
                 "alfa/omega/info.properties",
                 "hello.there.foo=HelloThereFoo",
                 "alfa/omega/A.java",
                 "package alfa.omega; public class A { }");

        previous_bin_state = collectState(bin);
        compile("-tr", ".properties=sjavac.transforms.CompileProperties", "-s", "gensrc2", "gensrc", 
                "-d", "bin", "-state-dir:bin", "-server:portfile=testserver,background=false");

        Map<String,Long> new_bin_state = collectState(bin);
        verifyThatFilesHaveBeenAdded(previous_bin_state, new_bin_state,
                         "bin/javac_state",
                         "bin/alfa/omega/A.class",
                         "bin/alfa/omega/info.class");
    }

    void removeFrom(Path dir, String... args) throws IOException {
        for (String filename : args) {
            Path p = dir.resolve(filename);
            Files.delete(p);
        }
    }

    void populate(Path src, String... args) throws IOException {
        if (!Files.exists(src)) {
            Files.createDirectory(src);
        }
        String[] a = args;
        for (int i = 0; i<a.length; i+=2) {
            String filename = a[i];
            String content = a[i+1];
            Path p = src.resolve(filename);
            Files.createDirectories(p.getParent());
            PrintWriter out = new PrintWriter(Files.newBufferedWriter(p,
                                                                      Charset.defaultCharset()));
            out.println(content);
            out.close();
        }
    }

    void waitForRemoval(final Path file) throws IOException {
	int count = 10;
        while (Files.exists(file)) {
	    System.out.println("Waiting for "+file+" to disappear.");
	    try {
		Thread.sleep(1000);
	    } catch (InterruptedException e) {
	    }
	    count--;
	    if (count <= 0) {
		throw new IOException("Expected "+file+" to disappear, and it didn't!");
	    }
	} 
    }

    void delete(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                 @Override
                 public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                 {
                     Files.delete(file);
                     return FileVisitResult.CONTINUE;
                 }

                 @Override
                 public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException
                 {
                     if (e == null) {
                         if (!dir.equals(root)) Files.delete(dir);
                         return FileVisitResult.CONTINUE;
                     } else {
                         // directory iteration failed
                         throw e;
                     }
                 }
            });
    }

    void compile(String... args) throws Exception {
        int rc = main.go(args, System.out, System.err);
        if (rc != 0) throw new Exception("Error during compile!");

        // Wait a second, to get around the (temporary) problem with
        // second resolution in the Java file api. But do not do this
        // on windows where the timestamps work.
        long in_a_sec = System.currentTimeMillis()+1000;
        while (File.separatorChar == '/' && in_a_sec > System.currentTimeMillis()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
    }

    void compileExpectFailure(String... args) throws Exception {
        int rc = main.go(args, System.out, System.err);
        if (rc == 0) throw new Exception("Expected error during compile! Did not fail!");
    }

    Map<String,Long> collectState(Path dir) throws IOException {
        final Map<String,Long> files = new HashMap<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                 @Override
                 public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                   throws IOException
                 {
                     files.put(file.toString(),new Long(Files.getLastModifiedTime(file).toMillis()));
                     return FileVisitResult.CONTINUE;
                 }
            });
        return files;
    }

    void verifyThatFilesHaveBeenRemoved(Map<String,Long> from,
                                        Map<String,Long> to,
                                        String... args) throws Exception {

        Set<String> froms = from.keySet();
        Set<String> tos = to.keySet();

        if (froms.equals(tos)) {
            throw new Exception("Expected new state to have fewer files than previous state!");
        }

        for (String t : tos) {
            if (!froms.contains(t)) {
                throw new Exception("Expected "+t+" to exist in previous state!");
            }
        }

        for (String f : args) {
            f = f.replace("/", File.separator);
            if (!froms.contains(f)) {
                throw new Exception("Expected "+f+" to exist in previous state!");
            }
            if (tos.contains(f)) {
                throw new Exception("Expected "+f+" to have been removed from the new state!");
            }
        }

        if (froms.size() - args.length != tos.size()) {
            throw new Exception("There are more removed files than the expected list!");
        }
    }

    void verifyThatFilesHaveBeenAdded(Map<String,Long> from,
                                      Map<String,Long> to,
                                      String... args) throws Exception {

        Set<String> froms = from.keySet();
        Set<String> tos = to.keySet();

        if (froms.equals(tos)) {
            throw new Exception("Expected new state to have more files than previous state!");
        }

        for (String t : froms) {
            if (!tos.contains(t)) {
                throw new Exception("Expected "+t+" to exist in new state!");
            }
        }

        for (String f : args) {
            f = f.replace("/", File.separator);
            if (!tos.contains(f)) {
                throw new Exception("Expected "+f+" to have been added to new state!");
            }
            if (froms.contains(f)) {
                throw new Exception("Expected "+f+" to not exist in previous state!");
            }
        }

        if (froms.size() + args.length != tos.size()) {
            throw new Exception("There are more added files than the expected list!");
        }
    }

    void verifyNewerFiles(Map<String,Long> from,
                          Map<String,Long> to,
                          String... args) throws Exception {
        if (!from.keySet().equals(to.keySet())) {
            throw new Exception("Expected the set of files to be identical!");
        }
        Set<String> files = new HashSet<String>();
        for (String s : args) {
            files.add(s.replace("/", File.separator));
        }
        for (String fn : from.keySet()) {
            long f = from.get(fn);
            long t = to.get(fn);
            if (files.contains(fn)) {
                if (t <= f) {
                    throw new Exception("Expected "+fn+" to have a more recent timestamp!");
                }
            } else {
                if (t != f) {
                    throw new Exception("Expected "+fn+" to have the same timestamp!");
                }
            }
        }
    }

    String print(Map<String,Long> m) {
        StringBuilder b = new StringBuilder();
        Set<String> keys = m.keySet();
        for (String k : keys) {
            b.append(k+" "+m.get(k)+"\n");
        }
        return b.toString();
    }

    void verifyEqual(Map<String,Long> from, Map<String,Long> to) throws Exception {
        if (!from.equals(to)) {
            System.out.println("FROM---"+print(from));
            System.out.println("TO-----"+print(to));
            throw new Exception("The dir should not differ! But it does!");
        }
    }
}
