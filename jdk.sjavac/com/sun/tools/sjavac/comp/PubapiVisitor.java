/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementScanner8;

/** Utility class that constructs a textual representation
 * of the public api of a class.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class PubapiVisitor extends ElementScanner8<Void, Void> {

    // The pubapi is stored here.
    List<String> api = new LinkedList<String>();
    // The hash of the pubapi (excluding paths to jars/classes and their timestamp)
    int hash;
    // Indentation level.
    int indent = 0;
    // The class location info is for example:
    // ZipFileIndexFileObject[/home/fredrik/bin/jdk1.8.0/lib/tools.jar(com/sun/tools/javac/comp/Resolve.class) 1396702502000
    // ie the jfileobj.toString() + " " + jfileobj.getLastModified()
    String class_loc_info = "";

    // If true, then store full public api information, not just the hash.
    // Takes a lot of space in the javac_state file, but makes it much easier 
    // to debug any public api bugs.
    boolean debugPubapi = false;

    String depth(int l) {
        return "________________________________".substring(0, l);
    }

    public void classLocInfo(String s) {
        class_loc_info = " "+s;
    }

    public void construct(TypeElement e) {
        visit(e);
        hash = 0;
        List<String> sorted_api = new ArrayList<>();
        sorted_api.addAll(api);
        // Why sort here? Because we want the same pubapi hash to be generated
        // for both a source compile and a classpath extraction.
        // For xor it might not matter, but perhaps a better function is used in the future.
        Collections.sort(sorted_api);

        for (String s : sorted_api) {
            hash ^= s.hashCode();
        }
        api = new LinkedList<String>();
        api.add(0, "PUBAPI "+e.getQualifiedName()+" "+Integer.toString(Math.abs(hash),16)+class_loc_info);
        if (debugPubapi) {
            api.addAll(sorted_api);
        }
    }

    @Override
    public Void visitType(TypeElement e, Void p) {
        if (e.getModifiers().contains(Modifier.PUBLIC)
            || e.getModifiers().contains(Modifier.PROTECTED))
        {
            api.add(depth(indent) + "!TYPE " + e.getQualifiedName());
            indent += 2;
            Void v = super.visitType(e, p);
            indent -= 2;
            return v;
        }
        return null;
    }

    @Override
    public Void visitVariable(VariableElement e, Void p) {
        if (e.getModifiers().contains(Modifier.PUBLIC)
            || e.getModifiers().contains(Modifier.PROTECTED)) {
            api.add(depth(indent)+"VAR "+makeVariableString(e));
        }
        // Safe to not recurse here, because the only thing
        // to visit here is the constructor of a variable declaration.
        // If it happens to contain an anonymous inner class (which it might)
        // then this class is never visible outside of the package anyway, so
        // we are allowed to ignore it here.
        return null;
    }

    @Override
    public Void visitExecutable(ExecutableElement e, Void p) {
        if (e.getModifiers().contains(Modifier.PUBLIC)
            || e.getModifiers().contains(Modifier.PROTECTED)) {
            api.add(depth(indent)+"METHOD "+makeMethodString(e));
        }
        return null;
    }

    /**
     * Creates a String representation of a method element with everything
     * necessary to track all public aspects of it in an API.
     * @param e Element to create String for.
     * @return String representation of element.
     */
    protected String makeMethodString(ExecutableElement e) {
        StringBuilder result = new StringBuilder();
        for (Modifier modifier : e.getModifiers()) {
            result.append(modifier.toString());
            result.append(" ");
        }
        result.append(e.getReturnType().toString());
        result.append(" ");
        result.append(e.toString());

        List<? extends TypeMirror> thrownTypes = e.getThrownTypes();
        if (!thrownTypes.isEmpty()) {
            result.append(" throws ");
            for (Iterator<? extends TypeMirror> iterator = thrownTypes
                    .iterator(); iterator.hasNext();) {
                TypeMirror typeMirror = iterator.next();
                result.append(typeMirror.toString());
                if (iterator.hasNext()) {
                    result.append(", ");
                }
            }
        }
        return result.toString();
    }

    /**
     * Creates a String representation of a variable element with everything
     * necessary to track all public aspects of it in an API.
     * @param e Element to create String for.
     * @return String representation of element.
     */
    protected String makeVariableString(VariableElement e) {
        StringBuilder result = new StringBuilder();
        for (Modifier modifier : e.getModifiers()) {
            result.append(modifier.toString());
            result.append(" ");
        }
        result.append(e.asType().toString());
        result.append(" ");
        result.append(e.toString());
        Object value = e.getConstantValue();
        if (value != null) {
            result.append(" = ");
            if (e.asType().toString().equals("char")) {
                int v = (int)value.toString().charAt(0);
                result.append("'\\u"+Integer.toString(v,16)+"'");
            } else {
                result.append(value.toString());
            }
        }
        return result.toString();
    }
}
