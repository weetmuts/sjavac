// Copyright 2014 by Fredrik Öhrström and licensed to you 
// under the GPLv2 with the classpath exception.
package com.sun.tools.sjavac.client;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.io.File;
import java.io.FileNotFoundException;

public class ForkCommand {

    /** Figure out how our process was started, then we can use this to start the server. */
    public static String getCommandLine() {
        StringBuilder commandline = new StringBuilder();
        
        File launcher = getLauncher();
        if (launcher == null) return null;
        commandline.append(launcher.toString());

        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMxBean.getInputArguments();
        for (String s: arguments) {
            commandline.append(" \""+s+"\"");
        }
        
        commandline.append(" \"-Xbootclasspath:"+runtimeMxBean.getBootClassPath()+"\"");
        commandline.append(" -classpath \""+runtimeMxBean.getClassPath()+"\"");
        commandline.append(" \"-Djava.library.path="+runtimeMxBean.getLibraryPath()+"\"");

        return commandline.toString();
    }
    
    public static File getLauncher() {
        try {
            String javahome = System.getProperty("java.home");
            if (javahome == null) {
                throw new IllegalStateException("java.home");
            }
            File exe;
            if (File.separatorChar == '\\') {
                exe = new File(javahome, "bin/javaw.exe");
            } else {
                exe = new File(javahome, "bin/java");
            }
            if (!exe.isFile()) {
                throw new FileNotFoundException(exe.toString());
            }
            if (!exe.canExecute()) {
                throw new IllegalStateException("Not executable "+exe.toString());
            }
            return exe;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return null;
        }
    }
}

