/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.sun.tools.sjavac.comp;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.ZipArchive.ZipFileObject;
import com.sun.tools.javac.file.ZipFileIndexArchive.ZipFileIndexFileObject;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Options;
import com.sun.tools.sjavac.comp.JavaCompilerWithDeps;
import com.sun.tools.sjavac.server.CompilationResult;
import com.sun.tools.sjavac.server.PublicApiResult;
import com.sun.tools.sjavac.server.Sjavac;
import com.sun.tools.sjavac.server.SysInfo;
import com.sun.tools.sjavac.server.SjavacServer;

/**
 * The sjavac implementation that interacts with javac and performs the actual
 * compilation.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class SjavacImpl implements Sjavac {

    com.sun.tools.javac.util.Context context;
    javax.tools.JavaCompiler.CompilationTask task;

    /**
     * Setup a compilation context, used for reading public apis of classes on the classpath
     * as well as annotation processors.
     */
    public SjavacImpl(com.sun.tools.sjavac.options.Options options) {
        com.sun.tools.javac.api.JavacTool compiler = com.sun.tools.javac.api.JavacTool.create();
        javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        context = new com.sun.tools.javac.util.Context();
        String[] args = options.prepJavacArgs();
        task = compiler.getTask(new PrintWriter(System.err), 
                                fileManager, 
                                null, Arrays.asList(args), 
                                null, null, context);
        // Trigger a creation of the JavaCompiler, necessary to get a sourceCompleter for ClassFinder.
        // The sourceCompleter is used for build situations where a classpath class references other classes
        // that happens to be on the sourcepath.
        com.sun.tools.javac.main.JavaCompiler.instance(context);
    }

    public SjavacImpl() {
    }

    @Override
    public SysInfo getSysInfo() {
        return new SysInfo(Runtime.getRuntime().availableProcessors(),
                           Runtime.getRuntime().maxMemory());
    }

    @Override
    public CompilationResult compile(String protocolId,
                                     String invocationId,
                                     String[] args,
                                     List<File> explicitSources,
                                     Set<URI> sourcesToCompile,
                                     Set<URI> visibleSources) {
        final AtomicBoolean forcedExit = new AtomicBoolean();

        long start = System.currentTimeMillis();
        JavacTool compiler = JavacTool.create();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        SmartFileManager smartFileManager = new SmartFileManager(fileManager);
        Context context = new Context();
        ResolveWithDeps.preRegister(context);
        AttrWithDeps.preRegister(context);
        JavaCompilerWithDeps.preRegister(context, new SjavacErrorHandler() {
            @Override
            public void logError(String msg) {
                forcedExit.set(true);
            }
        });

        // Now setup the actual compilation....
        CompilationResult compilationResult = new CompilationResult(0);

        // First deal with explicit source files on cmdline and in at file.
        ListBuffer<JavaFileObject> compilationUnits = new ListBuffer<>();
        for (JavaFileObject i : fileManager.getJavaFileObjectsFromFiles(explicitSources)) {
            compilationUnits.append(i);
        }
        // Now deal with sources supplied as source_to_compile.
        ListBuffer<File> sourcesToCompileFiles = new ListBuffer<>();
        for (URI u : sourcesToCompile) {
            sourcesToCompileFiles.append(new File(u));
        }
        for (JavaFileObject i : fileManager.getJavaFileObjectsFromFiles(sourcesToCompileFiles)) {
            compilationUnits.append(i);
        }
        forcedExit.set(false);
        // Create a new logger.
        StringWriter stdoutLog = new StringWriter();
        StringWriter stderrLog = new StringWriter();
        PrintWriter stdout = new PrintWriter(stdoutLog);
        PrintWriter stderr = new PrintWriter(stderrLog);
        com.sun.tools.javac.main.Main.Result rc = com.sun.tools.javac.main.Main.Result.OK;
        try {
            if (compilationUnits.size() > 0) {
                smartFileManager.setVisibleSources(visibleSources);
                smartFileManager.cleanArtifacts();
                smartFileManager.setLog(stdout);

                // Do the compilation!
                CompilationTask task = compiler.getTask(stderr,
                                                        smartFileManager,
                                                        null,
                                                        Arrays.asList(args),
                                                        null,
                                                        compilationUnits,
                                                        context);
                smartFileManager.setSymbolFileEnabled(!Options.instance(context).isSet("ignore.symbol.file"));
                rc = ((JavacTaskImpl) task).doCall();
                smartFileManager.flush();
            }
        } catch (Exception e) {
            stderrLog.append(e.getMessage());
            forcedExit.set(true);
        }

        compilationResult.packageArtifacts = smartFileManager.getPackageArtifacts();

        Dependencies deps = Dependencies.instance(context);
        compilationResult.packageDependencies = deps.getSourcefileDependencies();
        compilationResult.classpathPackageDependencies = deps.getClasspathDependencies();
        compilationResult.packagePublicApis = deps.getPublicApis();

        compilationResult.stdout = stdoutLog.toString();
        compilationResult.stderr = stderrLog.toString();

        compilationResult.returnCode = rc.exitCode == 0 && forcedExit.get() ? -1 : rc.exitCode;

        long stop = System.currentTimeMillis();
        float secs = (float)(stop-start) / (float)1000.0;
        float srcpersec = Math.round(((float)compilationUnits.size()) / secs);
        SjavacServer.log(Thread.currentThread().getName()+" "+invocationId+" compiled "+
                         +compilationUnits.size()+" sources in "+(stop-start)+"ms giving "+srcpersec+" sources/s");
        return compilationResult;
    }

    @Override
    public void shutdown() {
        // Nothing to clean up
        // ... maybe we should wait for any current request to finish?
    }


    @Override
    public String serverSettings() {
        return "";
    }

    /**
     * Return the class location string for class c.
     */
    @Override
    public String getClassLoc(String c) {
        try {
            com.sun.tools.javac.jvm.ClassReader cr = com.sun.tools.javac.jvm.ClassReader.instance(context);
            com.sun.tools.javac.util.Names ns = com.sun.tools.javac.util.Names.instance(context);
            com.sun.tools.javac.util.Name n = ns.fromString(c);
            com.sun.tools.javac.code.Symbol.ClassSymbol cs = cr.loadClass(n);
            return cs.classfile.toString()+" "+cs.classfile.getLastModified();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return "";
    }

    /**
     * Extract the pubapi for a class c. If the class was fetched from an archive, 
     * store the archive in the set archives.
     */
    @Override
    public PublicApiResult getPublicApi(String c) {
        PublicApiResult result = new PublicApiResult();
        result.api = new LinkedList<>();
        result.archives = new HashSet<String>();
        try {
            com.sun.tools.javac.jvm.ClassReader cr = com.sun.tools.javac.jvm.ClassReader.instance(context);
            com.sun.tools.javac.util.Names ns = com.sun.tools.javac.util.Names.instance(context);
            com.sun.tools.javac.util.Name n = ns.fromString(c);
            com.sun.tools.javac.code.Symbol.ClassSymbol cs = cr.loadClass(n);
            if (cs.classfile instanceof ZipFileIndexFileObject) {
                String s = cs.classfile.toString();
                int p = s.indexOf('[');
                int pp = s.indexOf('(', p+1);
                result.archives.add(s.substring(p+1,pp));
            }

            if (cs.classfile != null) {
                result.api = com.sun.tools.sjavac.comp.Dependencies.constructPubapi(cs, 
                                                                                    cs.classfile.toString()+" "+cs.classfile.getLastModified());
            }
            else {
                System.err.println("Classfile is null for "+c);
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return result;
    }

}
