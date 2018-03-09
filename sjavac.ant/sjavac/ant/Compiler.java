// Copyright 2014 by Fredrik Öhrström and licensed to you 
// under the Apache version 2 license.
package sjavac.ant;

import org.apache.tools.ant.taskdefs.compilers.DefaultCompilerAdapter;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.Javac;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.Commandline;
import org.apache.tools.ant.types.Path;

import java.util.Set;
import java.util.HashSet;
import java.util.StringTokenizer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import org.apache.tools.ant.util.StringUtils;

public class Compiler extends DefaultCompilerAdapter {

    /**
     * Execute sjavac externally. 
     *
     * @return true if the compiler ran with a zero exit result (ok)
     * @exception BuildException if the compilation has problems.
     */
    public boolean execute() throws BuildException {
        attributes.log("Using sjavac compiler", Project.MSG_VERBOSE);

        File stateDir = new File(destDir.toString()+"_state");
        Commandline cmd = new Commandline();
        String exec = "sjavac";
        cmd.setExecutable(exec);

        if (!stateDir.exists()) {
            attributes.log("Created dir: "+stateDir);
            stateDir.mkdir();
        }
        if (compileClasspath != null) {
            cmd.createArgument().setValue("-classpath");
            cmd.createArgument().setPath(compileClasspath);
        }
        
        if (compileSourcepath != null) {
            cmd.createArgument().setValue("-sourceath");
            cmd.createArgument().setPath(compileSourcepath);
        }

        if (debug) {
            cmd.createArgument().setValue("-g");
        }
        if (optimize) {
            cmd.createArgument().setValue("-O");
        }
        if (verbose) {
            cmd.createArgument().setValue("-verbose");
        }
        //        cmd.createArgument().setValue("--log=debug");

        addAtFile(cmd, stateDir);
        cmd.createArgument().setValue("-state-dir:"+stateDir.getAbsolutePath().toString());
        cmd.createArgument().setPath(src);

        cmd.createArgument().setValue("-d");
        cmd.createArgument().setFile(destDir);

        addCurrentCompilerArgs(cmd);

        int rc = executeExternalCompile(cmd.getCommandline(), -1, false);
        
        return rc == 0;        
    }

    /**
     * Create a javac_list file containing a list of all sources to be compiled
     * and supply it to sjavac using -if. Also make sure that javac_list is not
     * deleted if it is located inside destDir.
     */
    protected void addAtFile(Commandline cmd, File stateDir) {
        Set<String> sources = new HashSet<>();
        for (File f : compileList) {
            sources.add(f.getAbsolutePath());
        }

        // Load the previous javac_list compile
        File listFile = new File(stateDir.toString()+File.separatorChar+"javac_list");
        if (listFile.exists()) {
            Set<String> list = loadSet(listFile);
            // SJavac expects to receive the same list of source files during each compile
            // for example through -i -x -if -xf. However ant already has a rudimentary
            // incremental support, that support will send only a subset that have changed.
            // Thus if ant has decided that only a single file needs to be recompiled, then 
            // sources.size()==1. Feeding that single file using -if
            // will trigger sjavac to delete all class files for all other classes.
            // To avoid this we re-add all previously compiled files here and add them as well.
            // sjavac will still do the correct incremental compile. It would be convenient
            // if we could turn off the incremental support in ant when using sjavac.
            for (String s : list) {
                sources.add(s.substring(4));
            }
        }

        try {
            PrintWriter pw = new PrintWriter(new FileWriter(listFile));
            for (String s : sources) {
                pw.print("-if "+s+StringUtils.LINE_SEP);
            }
            pw.close();
        } catch (java.io.IOException e) {
            e.printStackTrace(System.err);
        }

        cmd.createArgument().setValue("@"+listFile);
    }

    public Set<String> loadSet(File list) {
        Set<String> set = new HashSet<>();
        try {
            BufferedReader in = new BufferedReader(new FileReader(list));
            for (;;) {
                String l = in.readLine();
                if (l==null) break;
                l = l.trim();
                if (l.length() > 0) {
                    set.add(l);
                }
            }
        } catch (FileNotFoundException e) {
            attributes.log("Could not open "+list, Project.MSG_VERBOSE);
            return null;
        } catch (IOException e) {
            attributes.log("Could not read "+list, Project.MSG_VERBOSE);
            return null;
        }
        return set;
    }

}
