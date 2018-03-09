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
package com.sun.tools.sjavac.comp;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.util.Context;
import static com.sun.tools.javac.code.Kinds.*;
import java.lang.reflect.*;

/** Subclass to Attr that overrides collect.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b></p>
 */
public class AttrWithDeps extends Attr {

    /** The dependency database
     */
    protected Dependencies deps;
    Field env_field;

    protected AttrWithDeps(Context context) {
        super(context);
        deps = Dependencies.instance(context);
    }

    public static void preRegister(Context context) {
        context.put(attrKey, new Context.Factory<Attr>() {
            public Attr make(Context c) {
                AttrWithDeps instance = new AttrWithDeps(c);
                c.put(Attr.class, instance);
                try {
                    instance.env_field = instance.getClass().getSuperclass().getDeclaredField("env");
                    instance.env_field.setAccessible(true);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    throw new Error("Internal error! Cannot access env field inside Attr!");
                }
                return instance;
            }
        });
    }

    @SuppressWarnings("unchecked")    
    public void visitSelect(JCFieldAccess tree) {
        super.visitSelect(tree);
        if ((tree.sym.kind & TYP) != 0) {
            Env<AttrContext> env;
            try {
                env = (Env<AttrContext>)env_field.get(this);
            } catch (IllegalAccessException e) {
                e.printStackTrace(System.err);
                throw new Error("Internal error! Cannot access env field inside Attr!");
            }
            // Capture dependencies between the packages.
            //            deps.reportPackageDep(env.enclClass.sym.packge().fullname, tree.sym.packge().fullname);
            deps.reportPackageDep(env.enclClass.sym.packge().fullname, tree.sym.packge().fullname);
            // It would be convenient to check if to.outermost comes from source or classpath
            // and only report it, if its from the classpath. This would reduce the amount
            // of data sent over the wire to the sjavac client. Can this be done? All interesting
            // classes are private within javac/file or javac/jvm....
            deps.reportClassDep(tree.sym.outermostClass());
        }
    }
}
