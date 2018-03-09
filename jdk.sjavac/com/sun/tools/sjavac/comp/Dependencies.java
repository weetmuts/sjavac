/*
 * Copyright (c) 1999, 2014, Oracle and/or its affiliates. All rights reserved.
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

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;

/** Utility class containing dependency information between packages
 *  and the pubapi for a package.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Dependencies {
    protected static final Context.Key<Dependencies> dependenciesKey = new Context.Key<>();

    // The log to be used for error reporting.
    protected Log log;
    // Map from package name to packages that the package depends upon.
    protected Map<Name,Set<Name>> deps;
    // Map from package name to non-sourcefile classes that the package depends upon.
    protected Map<Name,Set<ClassSymbol>> classDeps;
    // This is the set of all packages that are supplied
    // through the java files at the command line.
    protected Set<Name> explicitPackages;

    // This is the set of all classes found outside of the source, ie the classpath.
    protected Set<Name> classpathClasses;

    // Map from a class name to its public api.
    protected Map<Name,List<String>> publicApiPerClass;

    public static Dependencies instance(Context context) {
        Dependencies instance = context.get(dependenciesKey);
        if (instance == null)
            instance = new Dependencies(context);
        return instance;
    }

    private Dependencies(Context context) {
        context.put(dependenciesKey, this);
        log = Log.instance(context);
        deps = new HashMap<>();
        classDeps = new HashMap<>();
        explicitPackages = new HashSet<>();
        publicApiPerClass = new HashMap<>();
    }

    /**
     * Fetch the set of dependencies that are relevant to the compile
     * that has just been performed. I.e. we are only interested in
     * dependencies for classes that were explicitly compiled.
     * @return
     */
    public Map<String,Set<String>> getSourcefileDependencies() {
        Map<String,Set<String>> new_deps = new HashMap<>();
        for (Name pkg : explicitPackages) {
            Set<Name> set = deps.get(pkg);
            if (set != null) {
                String pkg_name = pkg.toString();
                Set<String> new_set = new_deps.get(pkg_name);
                if (new_set == null) {
                    new_set = new HashSet<>();
                    // Modules beware....
                    new_deps.put(":"+pkg_name, new_set);
                }
                for (Name d : set) {
                    new_set.add(":"+d.toString());
                }
            }
        }
        return new_deps;
    }

    /**
     * Fetch the set of classpath dependencies that our sources depend upon.
     * @return
     */
    public Map<String,Set<String>> getClasspathDependencies() {
        Map<String,Set<String>> new_deps = new HashMap<>();

        for (Name pkg : classDeps.keySet()) {
            if (explicitPackages.contains(pkg)) {
                continue;
            }
            Set<ClassSymbol> set = classDeps.get(pkg);
            String pkg_name = pkg.toString();
            Set<String> new_set = new_deps.get(pkg_name);
            if (new_set == null) {
                new_set = new HashSet<>();
                // Modules beware....
                new_deps.put(":"+pkg_name, new_set);
            }
            for (ClassSymbol c : set) {
                new_set.add(""+c.fullname);
            }
        }
        return new_deps;
    }

    /**
     * Convert the map from class names to their pubapi to a map
     * from package names to their pubapi (which is the sorted concatenation
     * of all the class pubapis)
     */
    public Map<String,List<String>> getPublicApis() {
        // The result map, to be returned.
        Map<String,List<String>> publicApiPerPackage = new HashMap<>();
        // Remember the Name for the sortable String version of the name.
        // I.e. where the dot (.) before the class name is replaced with bang (!).
        Map<String,Name> backToName = new HashMap<>();
        // Sort all the classes on their fullname that includes the package path.
        // Thus all classes belonging to the same package will be in consecutive order.
        Name[] names = publicApiPerClass.keySet().toArray(new Name[0]);
        List<String> fullnames = new ArrayList<>();
        for (Name n : names) {
            String tmp = n.toString();
            int p = tmp.lastIndexOf('.');
            String s = tmp.substring(0,p)+"!"+tmp.substring(p+1);
            fullnames.add(s);
            backToName.put(s, n);
        }
        String[] sorted_fullnames = fullnames.toArray(new String[0]);
        Arrays.sort(sorted_fullnames);
        // Now sorted_fullnames has a list of classes sorted, but with all classes inside
        // a package grouped together. This would not happen if we did not use !.
        String currPkg = "";
        List<String> currPublicApi = null;

        for (String n : sorted_fullnames) {
            int lastBang = n.lastIndexOf('!');
            assert(lastBang != -1);
            String pkgName = n.substring(0, lastBang);
            if (!pkgName.equals(currPkg)) {
                if (!currPkg.equals("")) {
                    // Add default module name ":"
                    publicApiPerPackage.put(":"+currPkg, currPublicApi);
                }
                currPublicApi = new LinkedList<>();
                currPkg = pkgName;
             }
             currPublicApi.addAll(publicApiPerClass.get(backToName.get(n)));
        }
        if (currPkg != "" && currPublicApi != null) {
            publicApiPerPackage.put(":"+currPkg, currPublicApi);
        }
        return publicApiPerPackage;
     }

     /**
      * Visit the api of a source class and construct a pubapi string and
      * store it into the pubapi_perclass map.
      */
    public void visitPubapiOfSource(TypeElement e) {
        visitPubapi(e);
        Name p = ((ClassSymbol)e).packge().fullname;
        explicitPackages.add(p);
    }

    /**
     * Visit the api of a classpath class and construct a pubapi string and
     * store it into the pubapi_perclass map.
     */
    public void visitPubapiOfClasspath(TypeElement e) {
        visitPubapi(e);
    }

    /**
     * Visit the api of a class and construct a list of api strings and
     * store it into the pubapi_perclass map.
     */
    private void visitPubapi(TypeElement e) {
        Name n = ((ClassSymbol)e).fullname;
        assert(publicApiPerClass.get(n) == null);

        PubapiVisitor v = new PubapiVisitor();
        v.construct(e);
        publicApiPerClass.put(n, v.api);
    }
    
    /**
     * Visit the api of a class and return the constructed pubapi string.
     */
    public static List<String> constructPubapi(TypeElement e, String class_loc_info) {

        PubapiVisitor v = new PubapiVisitor();
        if (class_loc_info != null) {
            v.classLocInfo(class_loc_info);
        }
        v.construct(e);
        return v.api;
    }

    /**
     * Collect a package dependency. currPkg is marked as depending on depPkg.
     */
    public void reportPackageDep(Name currPkg, Name depPkg) {
        if (!currPkg.equals(depPkg)) {
            Set<Name> theset = deps.get(currPkg);
            if (theset==null) {
                theset = new HashSet<>();
                deps.put(currPkg, theset);
            }
            theset.add(depPkg);
        }
    }

    /**
     * Collect a classpath class dependency. currPkg is marked as depending on depCls.
     */
    public void reportClassDep(ClassSymbol depCls) {
        String s = depCls.classfile != null ? depCls.classfile.toString() : ""; 
        if (s.startsWith("RegularFileObject[") && 
            s.endsWith(".java]")) {
            // This was a sourcepath dependency, ignore it.
            return;
        }
        Name pkg = depCls.packge().fullname;
        Set<ClassSymbol> theset = classDeps.get(pkg);
        if (theset==null) {
            theset = new HashSet<>();
            classDeps.put(pkg, theset);
        }
        theset.add(depCls);
    }

}
