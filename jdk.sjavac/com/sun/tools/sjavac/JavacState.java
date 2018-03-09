/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.tools.sjavac;

import java.io.*;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.text.SimpleDateFormat;
import java.net.URI;
import java.util.*;

import com.sun.tools.sjavac.comp.SjavacImpl;
import com.sun.tools.sjavac.options.Options;
import com.sun.tools.sjavac.options.SourceLocation;
import com.sun.tools.sjavac.server.Sjavac;
import com.sun.tools.sjavac.server.PublicApiResult;

/**
 * The javac state class maintains the previous (prev) and the current (now)
 * build states and everything else that goes into the javac_state file.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class JavacState {
    // The arguments to the compile. If not identical, then it cannot
    // be an incremental build!
    String theArgs;
    // The number of cores limits how many threads are used for heavy concurrent work.
    int numCores;

    // The bin_dir/javac_state
    private File javacState;

    // The previous build state is loaded from javac_state
    private BuildState prev;
    // The current build state is constructed during the build,
    // then saved as the new javac_state.
    private BuildState now;

    // Something has changed in the javac_state. It needs to be saved!
    private boolean needsSaving;
    // If this is a new javac_state file, then do not print unnecessary messages.
    private boolean newJavacState;

    // These are packages where something has changed and the package
    // needs to be recompiled. Actions that trigger recompilation:
    // * source belonging to the package has changed
    // * artifact belonging to the package is lost, or its timestamp has been changed.
    // * an unknown artifact has appeared, we simply delete it, but we also trigger a recompilation.
    // * a package that is tainted, taints all packages that depend on it.
    private Set<String> taintedPackages;
    // After a compile, the pubapis are compared with the pubapis stored in the javac state file.
    // Any packages where the pubapi differ are added to this set.
    // Later we use this set and the dependency information to taint dependent packages.
    private Set<String> packagesWithChangedPublicApis;
    // When a module-info.java file is changed, taint the module,
    // then taint all modules that depend on that that module.
    // A module dependency can occur directly through a require, or
    // indirectly through a module that does a public export for the first tainted module.
    // When all modules are tainted, then taint all packages belonging to these modules.
    // Then rebuild. It is perhaps possible (and valuable?) to do a more finegrained examination of the
    // change in module-info.java, but that will have to wait.
    private Set<String> taintedModules;
    // The set of all packages that has been recompiled.
    // Copy over the javac_state for the packages that did not need recompilation,
    // verbatim from the previous (prev) to the new (now) build state.
    private Set<String> recompiledPackages;
    // The set of all classpath packages and their classes, 
    // for which either the timestamps or the pubapi have changed. 
    private Map<String,Set<String>> changedClasspathPackages;

    // The output directories filled with tasty artifacts.
    private File binDir, gensrcDir, headerDir, stateDir;

    // The current status of the file system.
    private Set<File> binArtifacts;
    private Set<File> gensrcArtifacts;
    private Set<File> headerArtifacts;

    // The status of the sources.
    Set<Source> removedSources = null;
    Set<Source> addedSources = null;
    Set<Source> modifiedSources = null;

    // Visible sources for linking. These are the only
    // ones that -sourcepath is allowed to see.
    Set<URI> visibleSrcs;

    // Visible classes for linking. These are the only
    // ones that -classpath is allowed to see.
    // It maps from a classpath root to the set of visible classes for that root.
    // If the set is empty, then all classes are visible for that root.
    // It can also map from a jar file to the set of visible classes for that jar file.
    Map<URI,Set<String>> visibleClasses;

    // Setup transform that always exist.
    private CompileJavaPackages compileJavaPackages = new CompileJavaPackages();

    // Where to send stdout and stderr.
    private PrintStream out, err;

    // Command line options.
    private Options options;

    JavacState(Options op, boolean removeJavacState, PrintStream o, PrintStream e) {
        options = op;
        out = o;
        err = e;
        numCores = options.getNumCores();
        theArgs = options.getStateArgsString();
        binDir = Util.pathToFile(options.getDestDir());
        gensrcDir = Util.pathToFile(options.getGenSrcDir());
        headerDir = Util.pathToFile(options.getHeaderDir());
        stateDir = Util.pathToFile(options.getStateDir());
        javacState = new File(stateDir, "javac_state");
        if (removeJavacState && javacState.exists()) {
            javacState.delete();
        }
        newJavacState = false;
        if (!javacState.exists()) {
            newJavacState = true;
            // If there is no javac_state then delete the contents of all the artifact dirs!
            // We do not want to risk building a broken incremental build.
            // BUT since the makefiles still copy things straight into the bin_dir et al,
            // we avoid deleting files here, if the option --permit-unidentified-classes was supplied.
            if (!options.areUnidentifiedArtifactsPermitted()) {
                deleteContents(binDir);
                deleteContents(gensrcDir);
                deleteContents(headerDir);
            }
            needsSaving = true;
        }
        prev = new BuildState();
        now = new BuildState();
        taintedPackages = new HashSet<>();
        recompiledPackages = new HashSet<>();
        changedClasspathPackages = new HashMap<>();
        packagesWithChangedPublicApis = new HashSet<>();
    }

    public BuildState prev() { return prev; }
    public BuildState now() { return now; }

    /**
     * Remove args not affecting the state.
     */
    static String[] removeArgsNotAffectingState(String[] args) {
        String[] out = new String[args.length];
        int j = 0;
        for (int i = 0; i<args.length; ++i) {
            if (args[i].equals("-j")) {
                // Just skip it and skip following value
                i++;
            } else if (args[i].startsWith("--server:")) {
                // Just skip it.
            } else if (args[i].startsWith("--log=")) {
                // Just skip it.
            } else if (args[i].equals("--compare-found-sources")) {
                // Just skip it and skip verify file name
                i++;
            } else {
                // Copy argument.
                out[j] = args[i];
                j++;
            }
        }
        String[] ret = new String[j];
        System.arraycopy(out, 0, ret, 0, j);
        return ret;
    }

    /**
     * Specify which sources are visible to the compiler through -sourcepath.
     */
    public void setVisibleSources(Map<String,Source> vs) {
        visibleSrcs = new HashSet<>();
        for (String s : vs.keySet()) {
            Source src = vs.get(s);
            visibleSrcs.add(src.file().toURI());
        }
    }

    /**
     * Specify which classes are visible to the compiler through -classpath.
     */
    public void setVisibleClasses(Map<String,Source> vs) {
        visibleSrcs = new HashSet<>();
        for (String s : vs.keySet()) {
            Source src = vs.get(s);
            visibleSrcs.add(src.file().toURI());
        }
    }
    /**
     * Returns true if this is an incremental build.
     */
    public boolean isIncremental() {
        return !prev.sources().isEmpty();
    }

    /**
     * Find all artifacts that exists on disk.
     */
    public void findAllArtifacts() {
        binArtifacts = findAllFiles(binDir);
        gensrcArtifacts = findAllFiles(gensrcDir);
        headerArtifacts = findAllFiles(headerDir);
    }

    /**
     * Lookup the artifacts generated for this package in the previous build.
     */
    private Map<String,File> fetchPrevArtifacts(String pkg) {
        Package p = prev.packages().get(pkg);
        if (p != null) {
            return p.artifacts();
        }
        return new HashMap<>();
    }

    /**
     * Delete all prev artifacts in the currently tainted packages.
     */
    public void deleteClassArtifactsInTaintedPackages() {
        for (String pkg : taintedPackages) {
            Map<String,File> arts = fetchPrevArtifacts(pkg);
            for (File f : arts.values()) {
                if (f.exists() && f.getName().endsWith(".class")) {
                    f.delete();
                }
            }
        }
    }

    /**
     * Mark the javac_state file to be in need of saving and as a side effect,
     * it gets a new timestamp.
     */
    private void needsSaving() {
        needsSaving = true;
    }

    /**
     * Save the javac_state file.
     */
    public void save() throws IOException {
        if (!needsSaving) {
            Log.debug("No changes detected in sources, javac_state was not touched.");
            return;
        }
        Log.debug("Saving the javac_state file.");
        try (FileWriter out = new FileWriter(javacState)) {
            StringBuilder b = new StringBuilder();
            long millisNow = System.currentTimeMillis();
            Date d = new Date(millisNow);
            SimpleDateFormat df =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS");
            b.append("# javac_state ver 0.4 generated "+millisNow+" "+df.format(d)+"\n");
            b.append("# This format might change at any time. Please do not depend on it.\n");
            b.append("# M module\n");
            b.append("# P package\n");
            b.append("# S C source_tobe_compiled timestamp\n");
            b.append("# S L link_only_source timestamp\n");
            b.append("# G C generated_source timestamp\n");
            b.append("# A artifact timestamp\n");
            b.append("# D dependency\n");
            b.append("# I C pubapi when compiled from source\n");
            // The pubapi of compiled source is extracted almost for free when
            // the compilation is done.
            // 
            // Should we have pubapi when linked from source?
            // No, because a linked source might not be entirely compiled because of
            // performance reasons, thus the full pubapi might not be available for free.
            // Instead, monitor the timestamp of linked sources, when the timestamp change
            // always force a recompile of dependents even though it might not be necessary.
            b.append("# I Z pubapi when linked as classes\n");
            // The pubapi of linked classes can easily be constructed from the referenced classes.
            // However this pubapi contains only a subset of the classes actually public in the package.
            // Because: 1) we cannot easily find all classes 2) we do not want to, we are satisfied in
            // only tracking the actually referred classes.
            b.append("# Z archive timestamp\n");
            // When referred classes are stored in a jar/zip, use this timestamp to shortcut
            // and avoid testing all internal classes in the jar, if the timestamp of the jar itself
            // is unchanged.
            b.append("# R arguments\n");
            b.append("R ").append(theArgs).append("\n");

            // Copy over the javac_state for the packages that did not need recompilation.
            now.copyPackagesExcept(prev, recompiledPackages, new HashSet<String>());
            // Recreate pubapi:s and timestamps for classpath packages that have changed.
            long start = System.currentTimeMillis();
            addToClasspathPubapis(changedClasspathPackages);
            long stop = System.currentTimeMillis();
            Log.timing("Extracting classpath public apis took "+(stop-start)+"ms");
            // Save the packages, ie package names, dependencies, pubapis and artifacts! I.e. the lot.
            Module.saveModules(now.modules(), b);
            // Save the archive timestamps.
            now.saveArchiveTimestamps(b);

            String s = b.toString();
            out.write(s, 0, s.length());
        }
    }

    /**
     * Load a javac_state file.
     */
    public static JavacState load(Options options, PrintStream out, PrintStream err) {
        JavacState db = new JavacState(options, false, out, err);
        Module  lastModule = null;
        Package lastPackage = null;
        Source  lastSource = null;
        boolean noFileFound = false;
        boolean foundCorrectVerNr = false;
        boolean newCommandLine = false;
        boolean syntaxError = false;

        try (BufferedReader in = new BufferedReader(new FileReader(db.javacState))) {
            for (;;) {
                String l = in.readLine();
                if (l==null) break;
                if (l.length()>=3 && l.charAt(1) == ' ') {
                    char c = l.charAt(0);
                    if (c == 'M') {
                        lastModule = db.prev.loadModule(l);
                    } else
                    if (c == 'P') {
                        if (lastModule == null) { syntaxError = true; break; }
                        lastPackage = db.prev.loadPackage(lastModule, l);
                    } else
                    if (c == 'D') {
                        if (lastModule == null || lastPackage == null) { syntaxError = true; break; }
                        lastPackage.loadDependency(l);
                    } else
                    if (c == 'I') {
                        if (lastModule == null || lastPackage == null) { syntaxError = true; break; }
                        lastPackage.loadPubapi(l);
                    } else
                    if (c == 'Z') {
                        db.prev.loadArchiveTimestamp(l);
                    } else
                    if (c == 'A') {
                        if (lastModule == null || lastPackage == null) { syntaxError = true; break; }
                        lastPackage.loadArtifact(l);
                    } else
                    if (c == 'S') {
                        if (lastModule == null || lastPackage == null) { syntaxError = true; break; }
                        lastSource = db.prev.loadSource(lastPackage, l, false);
                    } else
                    if (c == 'G') {
                        if (lastModule == null || lastPackage == null) { syntaxError = true; break; }
                        lastSource = db.prev.loadSource(lastPackage, l, true);
                    } else
                    if (c == 'R') {
                        String ncmdl = "R "+db.theArgs;
                        if (!l.equals(ncmdl)) {
                            newCommandLine = true;
                        }
                    } else
                         if (c == '#') {
                        if (l.startsWith("# javac_state ver ")) {
                            int sp = l.indexOf(" ", 18);
                            if (sp != -1) {
                                String ver = l.substring(18,sp);
                                if (!ver.equals("0.4")) {
                    break;
                                 }
                foundCorrectVerNr = true;
                            }
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Silently create a new javac_state file.
            noFileFound = true;
        } catch (IOException e) {
            Log.info("Dropping old javac_state because of errors when reading it.");
            db = new JavacState(options, true, out, err);
            foundCorrectVerNr = true;
            newCommandLine = false;
            syntaxError = false;
    }
        if (foundCorrectVerNr == false && !noFileFound) {
            Log.info("Dropping old javac_state since it is of an old version.");
            db = new JavacState(options, true, out, err);
        } else
        if (newCommandLine == true && !noFileFound) {
            Log.info("Dropping old javac_state since a new command line is used!");
            db = new JavacState(options, true, out, err);
        } else
        if (syntaxError == true) {
            Log.info("Dropping old javac_state since it contains syntax errors.");
            db = new JavacState(options, true, out, err);
        }
        db.prev.calculateDependents();
        return db;
    }

    /**
     * Mark a java package as tainted, ie it needs recompilation.
     */
    public void taintPackage(String name, String because) {
        if (!taintedPackages.contains(name)) {
            if (because != null) Log.debug("Tainting "+Util.justPackageName(name)+" because "+because);
            // It has not been tainted before.
            taintedPackages.add(name);
            needsSaving();
            Package nowp = now.packages().get(name);
            if (nowp != null) {
                for (String d : nowp.dependents()) {
                    taintPackage(d, because);
                }
            }
        }
    }

    /**
     * These packages need recompilation.
     */
    public Set<String> taintedPackages() {
        return taintedPackages;
    }

    /**
     * Clean out the tainted package set, used after the first round of compiles,
     * prior to propagating dependencies.
     */
    public void clearTaintedPackages() {
        taintedPackages = new HashSet<>();
    }

    /**
     * Go through all sources and check which have been removed, added or modified
     * and taint the corresponding packages.
     */
    public void checkSourceStatus(boolean check_gensrc) {
        removedSources = calculateRemovedSources();
        for (Source s : removedSources) {
            if (!s.isGenerated() || check_gensrc) {
                taintPackage(s.pkg().name(), "source "+s.name()+" was removed");
            }
        }

        addedSources = calculateAddedSources();
        for (Source s : addedSources) {
            String msg = null;
            if (isIncremental()) {
                // When building from scratch, there is no point
                // printing "was added" for every file since all files are added.
                // However for an incremental build it makes sense.
                msg = "source "+s.name()+" was added";
            }
            if (!s.isGenerated() || check_gensrc) {
                taintPackage(s.pkg().name(), msg);
            }
        }

        modifiedSources = calculateModifiedSources();
        for (Source s : modifiedSources) {
            if (!s.isGenerated() || check_gensrc) {
                taintPackage(s.pkg().name(), "source "+s.name()+" was modified");
            }
        }
    }

    /**
     * Acquire the compile_java_packages suffix rule for .java files.
     */
    public Map<String,Transformer> getJavaSuffixRule() {
        Map<String,Transformer> sr = new HashMap<>();
        sr.put(".java", compileJavaPackages);
        return sr;
    }


    /**
     * If artifacts have gone missing, force a recompile of the packages
     * they belong to.
     */
    public void taintPackagesThatMissArtifacts() {
        for (Package pkg : prev.packages().values()) {
            for (File f : pkg.artifacts().values()) {
                if (!f.exists()) {
                    // Hmm, the artifact on disk does not exist! Someone has removed it....
                    // Lets rebuild the package.
                    taintPackage(pkg.name(), ""+f+" is missing.");
                }
            }
        }
    }

    /**
     * Propagate recompilation through the dependency chains.
     * Avoid re-tainting packages that have already been compiled.
     */
    public void taintPackagesDependingOnChangedPackages(Set<String> pkgs, Set<String> recentlyCompiled) {
        for (Package pkg : prev.packages().values()) {
            for (String dep : pkg.dependencies()) {
                if (pkgs.contains(dep) && !recentlyCompiled.contains(pkg.name())) {
                    taintPackage(pkg.name(), " its depending on "+dep);
                }
            }
        }
    }

    /**
     * Compare the javac_state recorded public apis of packages on the classpath
     * with the actual public apis on the classpath.
     */
    public void taintPackagesDependingOnChangedClasspathPackages() {
        Sjavac comp = new SjavacImpl(options);
        Set<String> tainteds = new HashSet<String>();
        
        for (Package pkg : prev.packages().values()) {
            List<String> current = new ArrayList<String>();
            Iterator<String> i = current.iterator();
            boolean tainted = false;
            boolean skip = false;
            for (String s : pkg.pubapiForLinkedClasses()) {
                if (skip && !s.startsWith("PUBAPI ")) {
                    // Skipping, because timestamp or hash checked out ok. Assume no change here!
                    continue;
                }
                skip = false;
                // Check if we have found a new class.
                if (s.startsWith("PUBAPI ")) {
                    if (i.hasNext()) {
                        // Previous api had not ended! Change is detected!
                        tainted = true;
                        break;
                    }
                    // Extract the class name, hash, file and timestamp from the PUBAPI line
                    int p = s.indexOf(' ', 7);
                    int pp = s.indexOf(' ', p+1);
                    String cln = s.substring(7, p);
                    String hash = s.substring(p+1, pp);
                    String loc = s.substring(pp+1); // loc == file and timestamp
                    String archive = Util.extractArchive(loc);
                    if (archive != null && prev.archives().contains(archive)) {
                        // If it existed, then the timestamp for the archive
                        // is unchanged. Lets skip testing this class inside the archive!
                        Log.debug("Assume "+cln+" unchanged since "+archive+" is unchanged");
                        skip = true;
                        current = new ArrayList<String>();
                        i = current.iterator();
                        continue;
                    }
                    // The archive timestamp has changed, or is new.
                    // Compare the prev classLocInfo with the current classLocInfo
                    String cmp = comp.getClassLoc(cln);
                    if (cmp.equals(loc)) {
                        // Equal means that the come from the same class/zip file
                        // and the timestamp is the same. Assume equal!
                        Log.debug("Assume "+cln+" unchanged since "+loc+" is unchanged");
                        skip = true;
                        current = new ArrayList<String>();
                        i = current.iterator();
                        continue;
                    }
                    // The timestamps differ, lets check the pubapi.
                    Log.debug("Timestamp changed for "+cln+" now checking if pubapi is the same.");
                    // Add the package to changedClasspathPackages because this
                    // will trigger a regeneration the package information to javac_state
                    // thus updating the timestamps.
                    Util.addToMapSet(pkg.name(), cln, changedClasspathPackages);
                    needsSaving = true;
                    PublicApiResult r = comp.getPublicApi(cln);
                    current = r.api;
                    now.archives().addAll(r.archives);
                    i = current.iterator();
                }
                if (i.hasNext()) {
                    String ss = i.next();
                    if (s.startsWith("PUBAPI ") && ss.startsWith("PUBAPI ")) {
                        int p = s.indexOf(' ', 7);
                        int pp = s.indexOf(' ', p+1);
                        s = s.substring(0, pp);
                        ss = ss.substring(0, pp);
                        if (s.equals(ss)) {
                            // The pubapi of a class has identical hash!
                            // We assume it is equals!
                            Log.debug("Assume "+s.substring(0, pp)+" unchanged since its hash is unchanged");
                            skip = true;
                            current = new ArrayList<String>();
                            i = current.iterator();
                        } else {
                            // The pubapi hash is not identical! Change is detected!
                            tainted = true;
                        }
                    } 
                }
            }
            if (tainted) {
                Log.info("The pubapi of "+Util.justPackageName(pkg.name())+" has changed!");
                tainteds.add(pkg.name());
            } else if (pkg.pubapiForLinkedClasses().size() > 0) {
                Log.debug("The pubapi of "+Util.justPackageName(pkg.name())+" was unchanged!");
            }
        }
        taintPackagesDependingOnChangedPackages(tainteds, new HashSet<String>());
    }

    /**
     * Scan all output dirs for artifacts and remove those files (artifacts?)
     * that are not recognized as such, in the javac_state file.
     */
    public void removeUnidentifiedArtifacts() {
        Set<File> allKnownArtifacts = new HashSet<>();
        for (Package pkg : prev.packages().values()) {
            for (File f : pkg.artifacts().values()) {
                allKnownArtifacts.add(f);
            }
        }
        // Do not forget about javac_state....
        allKnownArtifacts.add(javacState);

        for (File f : binArtifacts) {
            if (!allKnownArtifacts.contains(f) &&
                !options.isUnidentifiedArtifactPermitted(f.getAbsolutePath())) {
                Log.debug("Removing "+f.getPath()+" since it is unknown to the javac_state.");
                f.delete();
            }
        }
        for (File f : headerArtifacts) {
            if (!allKnownArtifacts.contains(f)) {
                Log.debug("Removing "+f.getPath()+" since it is unknown to the javac_state.");
                f.delete();
            }
        }
        for (File f : gensrcArtifacts) {
            if (!allKnownArtifacts.contains(f)) {
                Log.debug("Removing "+f.getPath()+" since it is unknown to the javac_state.");
                f.delete();
            }
        }
    }

    /**
     * Remove artifacts that are no longer produced when compiling!
     */
    public void removeSuperfluousArtifacts(Set<String> recentlyCompiled) {
        // Nothing to do, if nothing was recompiled.
        if (recentlyCompiled.size() == 0) return;

        for (String pkg : now.packages().keySet()) {
            // If this package has not been recompiled, skip the check.
            if (!recentlyCompiled.contains(pkg)) continue;
            Collection<File> arts = now.artifacts().values();
            for (File f : fetchPrevArtifacts(pkg).values()) {
                if (!arts.contains(f)) {
                    Log.debug("Removing "+f.getPath()+" since it is now superfluous!");
                    if (f.exists()) f.delete();
                }
            }
        }
    }

    /**
     * Return those files belonging to prev, but not now.
     */
    private Set<Source> calculateRemovedSources() {
        Set<Source> removed = new HashSet<>();
        for (String src : prev.sources().keySet()) {
            if (now.sources().get(src) == null) {
                removed.add(prev.sources().get(src));
            }
        }
        return removed;
    }

    /**
     * Return those files belonging to now, but not prev.
     */
    private Set<Source> calculateAddedSources() {
        Set<Source> added = new HashSet<>();
        for (String src : now.sources().keySet()) {
            if (prev.sources().get(src) == null) {
                added.add(now.sources().get(src));
            }
        }
        return added;
    }

    /**
     * Return those files where the timestamp is newer.
     * If a source file timestamp suddenly is older than what is known
     * about it in javac_state, then consider it modified, but print
     * a warning!
     */
    private Set<Source> calculateModifiedSources() {
        Set<Source> modified = new HashSet<>();
        for (String src : now.sources().keySet()) {
            Source n = now.sources().get(src);
            Source t = prev.sources().get(src);
            if (prev.sources().get(src) != null) {
                if (t != null) {
                    if (n.lastModified() > t.lastModified()) {
                        modified.add(n);
                    } else if (n.lastModified() < t.lastModified()) {
                        modified.add(n);
                        Log.warn("The source file "+n.name()+" timestamp has moved backwards in time.");
                    }
                }
            }
        }
        return modified;
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteContents(File dir) {
        if (dir != null && dir.exists()) {
            for (File f : dir.listFiles()) {
                if (f.isDirectory()) {
                    deleteContents(f);
                }
                if (!options.isUnidentifiedArtifactPermitted(f.getAbsolutePath())) {
                    Log.debug("Removing "+f.getAbsolutePath());
                    f.delete();
                }
            }
        }
    }

    /**
     * Run the copy translator only.
     */
    public void performCopying(File binDir, Map<String,Transformer> suffixRules) {
        Map<String,Transformer> sr = new HashMap<>();
        for (Map.Entry<String,Transformer> e : suffixRules.entrySet()) {
            if (e.getValue().getClass().equals(CopyFile.class)) {
                sr.put(e.getKey(), e.getValue());
            }
        }
        perform(null, binDir, sr);
    }

    /**
     * Run all the translators that translate into java source code.
     * I.e. all translators that are not copy nor compile_java_source.
     */
    public void performTranslation(File gensrcDir, Map<String,Transformer> suffixRules) {
        Map<String,Transformer> sr = new HashMap<>();
        for (Map.Entry<String,Transformer> e : suffixRules.entrySet()) {
            Class<?> trClass = e.getValue().getClass();
            if (trClass == CompileJavaPackages.class || trClass == CopyFile.class)
                continue;

            sr.put(e.getKey(), e.getValue());
        }
        perform(null, gensrcDir, sr);
    }

    /**
     * Compile all the java sources. Return true, if it needs to be called again!
     */
    public boolean performJavaCompilations(Sjavac sjavac,
                                           Options args,
                                           Set<String> recentlyCompiled,
                                           boolean[] rcValue) {
        Map<String,Transformer> suffixRules = new HashMap<>();
        suffixRules.put(".java", compileJavaPackages);
        compileJavaPackages.setExtra(args);

        rcValue[0] = perform(sjavac, binDir, suffixRules);
        recentlyCompiled.addAll(taintedPackages());
        clearTaintedPackages();
        boolean again = !packagesWithChangedPublicApis.isEmpty();
        taintPackagesDependingOnChangedPackages(packagesWithChangedPublicApis, recentlyCompiled);
        packagesWithChangedPublicApis = new HashSet<>();
        return again && rcValue[0];
    }

    /**
     * Store the source into the set of sources belonging to the given transform.
     */
    private void addFileToTransform(Map<Transformer,Map<String,Set<URI>>> gs, Transformer t, Source s) {
        Map<String,Set<URI>> fs = gs.get(t);
        if (fs == null) {
            fs = new HashMap<>();
            gs.put(t, fs);
        }
        Set<URI> ss = fs.get(s.pkg().name());
        if (ss == null) {
            ss = new HashSet<>();
            fs.put(s.pkg().name(), ss);
        }
        ss.add(s.file().toURI());
    }

    /**
     * For all packages, find all sources belonging to the package, group the sources
     * based on their transformers and apply the transformers on each source code group.
     */
    private boolean perform(Sjavac sjavac,
                            File outputDir,
                            Map<String,Transformer> suffixRules) {
        boolean rc = true;
        // Group sources based on transforms. A source file can only belong to a single transform.
        Map<Transformer,Map<String,Set<URI>>> groupedSources = new HashMap<>();
        for (Source src : now.sources().values()) {
            Transformer t = suffixRules.get(src.suffix());
               if (t != null) {
                if (taintedPackages.contains(src.pkg().name()) && !src.isLinkedOnly()) {
                    addFileToTransform(groupedSources, t, src);
                }
            }
        }
        // Go through the transforms and transform them.
        for (Map.Entry<Transformer,Map<String,Set<URI>>> e : groupedSources.entrySet()) {
            Transformer t = e.getKey();
            Map<String,Set<URI>> srcs = e.getValue();
            // These maps need to be synchronized since multiple threads will be writing results into them.
            Map<String,Set<URI>> packageArtifacts =
                    Collections.synchronizedMap(new HashMap<String,Set<URI>>());
            Map<String,Set<String>> packageDependencies =
                    Collections.synchronizedMap(new HashMap<String,Set<String>>());
            Map<String,List<String>> packagePublicApis =
                    Collections.synchronizedMap(new HashMap<String, List<String>>());
            // Map from package name to set of classes. The classes are a subset of all classes 
            // within the package. The subset are those that our code has directly referenced.
            Map<String,Set<String>> classpathPackageDependencies =
                Collections.synchronizedMap(new HashMap<String, Set<String>>());

            boolean  r = t.transform(sjavac,
                                     srcs,
                                     visibleSrcs,
                                     visibleClasses,
                                     prev.dependents(),
                                     outputDir.toURI(),
                                     packageArtifacts,
                                     packageDependencies,
                                     packagePublicApis,
                                     classpathPackageDependencies,
                                     0,
                                     isIncremental(),
                                     numCores,
                                     out,
                                     err);
            if (!r) rc = false;

            for (String p : srcs.keySet()) {
                recompiledPackages.add(p);
            }
            // The transform is done! Extract all the artifacts and store the info into the Package objects.
            for (Map.Entry<String,Set<URI>> a : packageArtifacts.entrySet()) {
                Module mnow = now.findModuleFromPackageName(a.getKey());
                mnow.addArtifacts(a.getKey(), a.getValue());
            }
            // Extract all the dependencies and store the info into the Package objects.
            for (Map.Entry<String,Set<String>> a : packageDependencies.entrySet()) {
                Set<String> deps = a.getValue();
                Module mnow = now.findModuleFromPackageName(a.getKey());
                mnow.setDependencies(a.getKey(), deps);
            }
            // With two threads compiling our sources, sources compiled by a second thread, might look like 
            // classpath dependencies to the first thread or vice versa. We cannot remove such fake classpath dependencies 
            // until the end of the compilation since the knowledge of what is compiled does not exist until now.
            for (String pkg : packagePublicApis.keySet()) {
                classpathPackageDependencies.remove(pkg);
            }
            // Also, if we doing an incremental compile, then references outside of the small recompiled set,
            // will also look like classpath deps, lets remove them as well.
            for (String pkg : prev.packages().keySet()) {
                Package p = prev.packages().get(pkg);
                if (p.pubapiForLinkedClasses().size() == 0) {
                    classpathPackageDependencies.remove(pkg);
                }
            }
            // Extract all classpath package classes and store the public ap
            // into the Package object. 
            addToClasspathPubapis(classpathPackageDependencies);

            // Extract all the pubapis and store the info into the Package objects.
            for (Map.Entry<String,List<String>> a : packagePublicApis.entrySet()) {
                Module mprev = prev.findModuleFromPackageName(a.getKey());
                List<String> pubapi = a.getValue();
                Module mnow = now.findModuleFromPackageName(a.getKey());
                mnow.setPubapiForCompiledSources(a.getKey(), pubapi);
                if (mprev.hasPubapiForCompiledSourcesChanged(a.getKey(), pubapi)) {
                    // Aha! The pubapi of this package has changed!
                    // It can also be a new compile from scratch.
                    if (mprev.lookupPackage(a.getKey()).existsInJavacState()) {
                        // This is an incremental compile! The pubapi
                        // did change. Trigger recompilation of dependents.
                        packagesWithChangedPublicApis.add(a.getKey());
                        Log.info("The pubapi of "+Util.justPackageName(a.getKey())+" has changed!");
                    }
                }
            }
        }
        return rc;
    }

    /**
     * Utility method to recursively find all files below a directory.
     */
    private static Set<File> findAllFiles(File dir) {
        Set<File> foundFiles = new HashSet<>();
        if (dir == null) {
            return foundFiles;
        }
        recurse(dir, foundFiles);
        return foundFiles;
    }

    private static void recurse(File dir, Set<File> foundFiles) {
        for (File f : dir.listFiles()) {
            if (f.isFile()) {
                foundFiles.add(f);
            } else if (f.isDirectory()) {
                recurse(f, foundFiles);
            }
        }
    }

    /**
     * Compare the calculate source list, with an explicit list, usually supplied from the makefile.
     * Used to detect bugs where the makefile and sjavac have different opinions on which files
     * should be compiled.
     */
    public void compareWithMakefileList(File makefileSourceList) throws ProblemException {
        // If we are building on win32 using for example cygwin the paths in the makefile source list
        // might be /cygdrive/c/.... which does not match c:\....
        // We need to adjust our calculated sources to be identical, if necessary.
        boolean mightNeedRewriting = File.pathSeparatorChar == ';';

        if (makefileSourceList == null) return;

        Set<String> calculatedSources = new HashSet<>();
        Set<String> listedSources = SourceLocation.loadList(makefileSourceList);

        // Create a set of filenames with full paths.
        for (Source s : now.sources().values()) {
            // Don't include link only sources when comparing sources to compile
            if (!s.isLinkedOnly()) {
                String path = s.file().getPath();
                if (mightNeedRewriting)
                    path = Util.normalizeDriveLetter(path);
                calculatedSources.add(path);
            }
        }

        
        for (String s : listedSources) {
            if (!calculatedSources.contains(s)) {
                 throw new ProblemException("The makefile listed source "+s+" was not calculated by the smart javac wrapper!");
            }
        }

        for (String s : calculatedSources) {
            if (!listedSources.contains(s)) {
                throw new ProblemException("The smart javac wrapper calculated source "+s+" was not listed by the makefiles!");
            }
        }
    }

    /**
     * Add the classes in deps, to the pubapis of the Packages.
     * The pubapis are stored within the corresponding Package in now.
     */
    public void addToClasspathPubapis(Map<String, Set<String>> deps) {
        Sjavac comp = new SjavacImpl(options);
        // Extract all the pubapis of the classes inside deps and
        // store the info into the corresponding Package objects.
        for (Map.Entry<String,Set<String>> a : deps.entrySet()) {
            String pkg = a.getKey();
            Module mnow = now.findModuleFromPackageName(pkg);
            Set<String> classes = new HashSet<>();
            classes.addAll(a.getValue());
            classes.addAll(mnow.lookupPackage(pkg).getClassesFromClasspathPubapi());
            List<String> sorted_classes = new ArrayList<>();
            for (String key : classes) {
                sorted_classes.add(key);
            }
            Collections.sort(sorted_classes);

            List<String> pubapis = new ArrayList<>();
            for (String s : sorted_classes) {
                PublicApiResult r = comp.getPublicApi(s);
                pubapis.addAll(r.api);
                now.archives().addAll(r.archives);
            }
            mnow.setPubapiForLinkedClasses(a.getKey(), pubapis);                
        }
    }
}
