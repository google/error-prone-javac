/*
 * Copyright (c) 2003, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.comp;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.tools.JavaFileObject;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Scope.ImportFilter;
import com.sun.tools.javac.code.Scope.NamedImportScope;
import com.sun.tools.javac.code.Scope.StarImportScope;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.DefinedBy.Api;

import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.tree.JCTree.*;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Flags.ANNOTATION;
import static com.sun.tools.javac.code.Kinds.*;
import static com.sun.tools.javac.code.Scope.LookupKind.NON_RECURSIVE;
import static com.sun.tools.javac.code.TypeTag.CLASS;
import static com.sun.tools.javac.code.TypeTag.ERROR;
import static com.sun.tools.javac.code.TypeTag.TYPEVAR;
import static com.sun.tools.javac.tree.JCTree.Tag.*;

import com.sun.tools.javac.util.Dependencies.AttributionKind;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

/** This is the second phase of Enter, in which classes are completed
 *  by entering their members into the class scope using
 *  MemberEnter.complete().  See Enter for an overview.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class MemberEnter extends JCTree.Visitor implements Completer {
    protected static final Context.Key<MemberEnter> memberEnterKey = new Context.Key<>();

    /** A switch to determine whether we check for package/class conflicts
     */
    final static boolean checkClash = true;

    private final Names names;
    private final Enter enter;
    private final Log log;
    private final Check chk;
    private final Attr attr;
    private final Symtab syms;
    private final TreeMaker make;
    private final Todo todo;
    private final Annotate annotate;
    private final TypeAnnotations typeAnnotations;
    private final Types types;
    private final JCDiagnostic.Factory diags;
    private final Source source;
    private final Target target;
    private final DeferredLintHandler deferredLintHandler;
    private final Lint lint;
    private final TypeEnvs typeEnvs;
    private final Dependencies dependencies;

    public static MemberEnter instance(Context context) {
        MemberEnter instance = context.get(memberEnterKey);
        if (instance == null)
            instance = new MemberEnter(context);
        return instance;
    }

    protected MemberEnter(Context context) {
        context.put(memberEnterKey, this);
        names = Names.instance(context);
        enter = Enter.instance(context);
        log = Log.instance(context);
        chk = Check.instance(context);
        attr = Attr.instance(context);
        syms = Symtab.instance(context);
        make = TreeMaker.instance(context);
        todo = Todo.instance(context);
        annotate = Annotate.instance(context);
        typeAnnotations = TypeAnnotations.instance(context);
        types = Types.instance(context);
        diags = JCDiagnostic.Factory.instance(context);
        source = Source.instance(context);
        target = Target.instance(context);
        deferredLintHandler = DeferredLintHandler.instance(context);
        lint = Lint.instance(context);
        typeEnvs = TypeEnvs.instance(context);
        dependencies = Dependencies.instance(context);
        allowTypeAnnos = source.allowTypeAnnotations();
    }

    /** Switch: support type annotations.
     */
    boolean allowTypeAnnos;

    /** A queue for classes whose members still need to be entered into the
     *  symbol table.
     */
    ListBuffer<Env<AttrContext>> halfcompleted = new ListBuffer<>();

    /** Set to true only when the first of a set of classes is
     *  processed from the half completed queue.
     */
    boolean isFirst = true;

    /** A flag to disable completion from time to time during member
     *  enter, as we only need to look up types.  This avoids
     *  unnecessarily deep recursion.
     */
    boolean completionEnabled = true;

    /* ---------- Processing import clauses ----------------
     */

    /** Import all classes of a class or package on demand.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class or package the members of which are imported.
     *  @param env           The env in which the imported classes will be entered.
     */
    private void importAll(int pos,
                           final TypeSymbol tsym,
                           Env<AttrContext> env) {
        // Check that packages imported from exist (JLS ???).
        if (tsym.kind == PCK && tsym.members().isEmpty() && !tsym.exists()) {
            // If we can't find java.lang, exit immediately.
            if (((PackageSymbol)tsym).fullname.equals(names.java_lang)) {
                JCDiagnostic msg = diags.fragment("fatal.err.no.java.lang");
                throw new FatalError(msg);
            } else {
                log.error(DiagnosticFlag.RESOLVE_ERROR, pos, "doesnt.exist", tsym);
            }
        }
        env.toplevel.starImportScope.importAll(tsym.members(), tsym.members(), typeImportFilter, false);
    }

    /** Import all static members of a class or package on demand.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class or package the members of which are imported.
     *  @param env           The env in which the imported classes will be entered.
     */
    private void importStaticAll(int pos,
                                 final TypeSymbol tsym,
                                 Env<AttrContext> env) {
        final StarImportScope toScope = env.toplevel.starImportScope;
        final PackageSymbol packge = env.toplevel.packge;
        final TypeSymbol origin = tsym;

        // enter imported types immediately
        new SymbolImporter() {
            void doImport(TypeSymbol tsym) {
                toScope.importAll(tsym.members(), origin.members(), staticImportFilter, true);
            }
        }.importFrom(tsym);
    }

    /** Import statics types of a given name.  Non-types are handled in Attr.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class from which the name is imported.
     *  @param name          The (simple) name being imported.
     *  @param env           The environment containing the named import
     *                  scope to add to.
     */
    private void importNamedStatic(final DiagnosticPosition pos,
                                   final TypeSymbol tsym,
                                   final Name name,
                                   final Env<AttrContext> env) {
        if (tsym.kind != TYP) {
            log.error(DiagnosticFlag.RECOVERABLE, pos, "static.imp.only.classes.and.interfaces");
            return;
        }

        final NamedImportScope toScope = env.toplevel.namedImportScope;
        final Scope originMembers = tsym.members();

        // enter imported types immediately
        new SymbolImporter() {
            void doImport(TypeSymbol tsym) {
                Set<Symbol> maskedOut = null;
                for (Symbol sym : tsym.members().getSymbolsByName(name)) {
                    if (sym.kind == TYP &&
                        staticImportFilter.accepts(originMembers, sym) &&
                        !chk.checkUniqueStaticImport(pos, env.toplevel, sym)) {
                        if (maskedOut == null)
                            maskedOut = Collections.newSetFromMap(new IdentityHashMap<Symbol, Boolean>());
                        maskedOut.add(sym);
                    }
                }
                ImportFilter importFilter = maskedOut != null ?
                        new MaskedImportFilter(staticImportFilter, maskedOut) :
                        staticImportFilter;
                toScope.importByName(tsym.members(), originMembers, name, importFilter);
            }
        }.importFrom(tsym);
    }
    //where:
        class MaskedImportFilter implements ImportFilter {

            private final ImportFilter delegate;
            private final Set<Symbol> maskedOut;

            public MaskedImportFilter(ImportFilter delegate, Set<Symbol> maskedOut) {
                this.delegate = delegate;
                this.maskedOut = maskedOut;
            }

            @Override
            public boolean accepts(Scope origin, Symbol sym) {
                return !maskedOut.contains(sym) && delegate.accepts(origin, sym);
            }
        }
        abstract class SymbolImporter {
            Set<Symbol> processed = new HashSet<>();
            void importFrom(TypeSymbol tsym) {
                if (tsym == null || !processed.add(tsym))
                    return;

                // also import inherited names
                importFrom(types.supertype(tsym.type).tsym);
                for (Type t : types.interfaces(tsym.type))
                    importFrom(t.tsym);

                doImport(tsym);
            }
            abstract void doImport(TypeSymbol tsym);
        }

    /** Import given class.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class to be imported.
     *  @param env           The environment containing the named import
     *                  scope to add to.
     */
    private void importNamed(DiagnosticPosition pos, final Symbol tsym, Env<AttrContext> env) {
        if (tsym.kind == TYP &&
            chk.checkUniqueImport(pos, env.toplevel, tsym))
            env.toplevel.namedImportScope.importType(tsym.owner.members(), tsym.owner.members(), tsym);
    }

    /** Construct method type from method signature.
     *  @param typarams    The method's type parameters.
     *  @param params      The method's value parameters.
     *  @param res             The method's result type,
     *                 null if it is a constructor.
     *  @param recvparam       The method's receiver parameter,
     *                 null if none given; TODO: or already set here?
     *  @param thrown      The method's thrown exceptions.
     *  @param env             The method's (local) environment.
     */
    Type signature(MethodSymbol msym,
                   List<JCTypeParameter> typarams,
                   List<JCVariableDecl> params,
                   JCTree res,
                   JCVariableDecl recvparam,
                   List<JCExpression> thrown,
                   Env<AttrContext> env) {

        // Enter and attribute type parameters.
        List<Type> tvars = enter.classEnter(typarams, env);
        attr.attribTypeVariables(typarams, env);

        // Enter and attribute value parameters.
        ListBuffer<Type> argbuf = new ListBuffer<>();
        for (List<JCVariableDecl> l = params; l.nonEmpty(); l = l.tail) {
            memberEnter(l.head, env);
            argbuf.append(l.head.vartype.type);
        }

        // Attribute result type, if one is given.
        Type restype = res == null ? syms.voidType : attr.attribType(res, env);

        // Attribute receiver type, if one is given.
        Type recvtype;
        if (recvparam!=null) {
            memberEnter(recvparam, env);
            recvtype = recvparam.vartype.type;
        } else {
            recvtype = null;
        }

        // Attribute thrown exceptions.
        ListBuffer<Type> thrownbuf = new ListBuffer<>();
        for (List<JCExpression> l = thrown; l.nonEmpty(); l = l.tail) {
            Type exc = attr.attribType(l.head, env);
            if (!exc.hasTag(TYPEVAR)) {
                exc = chk.checkClassType(l.head.pos(), exc);
            } else if (exc.tsym.owner == msym) {
                //mark inference variables in 'throws' clause
                exc.tsym.flags_field |= THROWS;
            }
            thrownbuf.append(exc);
        }
        MethodType mtype = new MethodType(argbuf.toList(),
                                    restype,
                                    thrownbuf.toList(),
                                    syms.methodClass);
        mtype.recvtype = recvtype;

        return tvars.isEmpty() ? mtype : new ForAll(tvars, mtype);
    }

/* ********************************************************************
 * Visitor methods for member enter
 *********************************************************************/

    ImportFilter staticImportFilter;
    ImportFilter typeImportFilter = new ImportFilter() {
        @Override
        public boolean accepts(Scope origin, Symbol t) {
            return t.kind == Kinds.TYP;
        }
    };

    protected void memberEnter(JCCompilationUnit tree, Env<AttrContext> env) {
        ImportFilter prevStaticImportFilter = staticImportFilter;
        try {
            final PackageSymbol packge = env.toplevel.packge;
            this.staticImportFilter = new ImportFilter() {
                @Override
                public boolean accepts(Scope origin, Symbol sym) {
                    return sym.isStatic() &&
                           chk.staticImportAccessible(sym, packge) &&
                           sym.isMemberOf((TypeSymbol) origin.owner, types);
                }
            };
            memberEnter((JCTree) tree, env);
        } finally {
            this.staticImportFilter = prevStaticImportFilter;
        }
    }

    /** Visitor argument: the current environment
     */
    protected Env<AttrContext> env;

    /** Enter field and method definitions and process import
     *  clauses, catching any completion failure exceptions.
     */
    protected void memberEnter(JCTree tree, Env<AttrContext> env) {
        Env<AttrContext> prevEnv = this.env;
        try {
            this.env = env;
            tree.accept(this);
        }  catch (CompletionFailure ex) {
            chk.completionError(tree.pos(), ex);
        } finally {
            this.env = prevEnv;
        }
    }

    /** Enter members from a list of trees.
     */
    void memberEnter(List<? extends JCTree> trees, Env<AttrContext> env) {
        for (List<? extends JCTree> l = trees; l.nonEmpty(); l = l.tail)
            memberEnter(l.head, env);
    }

    /** Enter members for a class.
     */
    void finishClass(JCClassDecl tree, Env<AttrContext> env) {
        if ((tree.mods.flags & Flags.ENUM) != 0 &&
            (types.supertype(tree.sym.type).tsym.flags() & Flags.ENUM) == 0) {
            addEnumMembers(tree, env);
        }
        memberEnter(tree.defs, env);
    }

    /** Add the implicit members for an enum type
     *  to the symbol table.
     */
    private void addEnumMembers(JCClassDecl tree, Env<AttrContext> env) {
        JCExpression valuesType = make.Type(new ArrayType(tree.sym.type, syms.arrayClass,
                                                          Type.noAnnotations));

        // public static T[] values() { return ???; }
        JCMethodDecl values = make.
            MethodDef(make.Modifiers(Flags.PUBLIC|Flags.STATIC),
                      names.values,
                      valuesType,
                      List.<JCTypeParameter>nil(),
                      List.<JCVariableDecl>nil(),
                      List.<JCExpression>nil(), // thrown
                      null, //make.Block(0, Tree.emptyList.prepend(make.Return(make.Ident(names._null)))),
                      null);
        memberEnter(values, env);

        // public static T valueOf(String name) { return ???; }
        JCMethodDecl valueOf = make.
            MethodDef(make.Modifiers(Flags.PUBLIC|Flags.STATIC),
                      names.valueOf,
                      make.Type(tree.sym.type),
                      List.<JCTypeParameter>nil(),
                      List.of(make.VarDef(make.Modifiers(Flags.PARAMETER |
                                                         Flags.MANDATED),
                                            names.fromString("name"),
                                            make.Type(syms.stringType), null)),
                      List.<JCExpression>nil(), // thrown
                      null, //make.Block(0, Tree.emptyList.prepend(make.Return(make.Ident(names._null)))),
                      null);
        memberEnter(valueOf, env);
    }

    public void visitTopLevel(JCCompilationUnit tree) {
        if (!tree.starImportScope.isEmpty()) {
            // we must have already processed this toplevel
            return;
        }

        DiagnosticPosition prevLintPos = deferredLintHandler.immediate();
        Lint prevLint = chk.setLint(lint);

        try {
            // Import-on-demand java.lang.
            importAll(tree.pos, syms.enterPackage(names.java_lang), env);

            // Process the package def and all import clauses.
            memberEnter(tree.defs, env);
        } finally {
            chk.setLint(prevLint);
            deferredLintHandler.setPos(prevLintPos);
        }
    }

    public void visitPackageDef(JCPackageDecl tree) {
        // check that no class exists with same fully qualified name as
        // toplevel package
        if (checkClash && tree.pid != null) {
            Symbol p = env.toplevel.packge;
            while (p.owner != syms.rootPackage) {
                p.owner.complete(); // enter all class members of p
                if (syms.classes.get(p.getQualifiedName()) != null) {
                    log.error(tree.pos,
                              "pkg.clashes.with.class.of.same.name",
                              p);
                }
                p = p.owner;
            }
        }
        // process package annotations
        annotate.annotateLater(tree.annotations, env, env.toplevel.packge, null);
    }

    // process the non-static imports and the static imports of types.
    public void visitImport(JCImport tree) {
        dependencies.push(AttributionKind.IMPORT, tree);
        JCFieldAccess imp = (JCFieldAccess)tree.qualid;
        Name name = TreeInfo.name(imp);

        // Create a local environment pointing to this tree to disable
        // effects of other imports in Resolve.findGlobalType
        Env<AttrContext> localEnv = env.dup(tree);

        TypeSymbol p = attr.attribImportQualifier(tree, localEnv).tsym;
        if (name == names.asterisk) {
            // Import on demand.
            chk.checkCanonical(imp.selected);
            if (tree.staticImport)
                importStaticAll(tree.pos, p, env);
            else
                importAll(tree.pos, p, env);
        } else {
            // Named type import.
            if (tree.staticImport) {
                importNamedStatic(tree.pos(), p, name, localEnv);
                chk.checkCanonical(imp.selected);
            } else {
                TypeSymbol c = attribImportType(imp, localEnv).tsym;
                chk.checkCanonical(imp);
                importNamed(tree.pos(), c, env);
            }
        }
        dependencies.pop();
    }

    public void visitMethodDef(JCMethodDecl tree) {
        WriteableScope enclScope = enter.enterScope(env);
        MethodSymbol m = new MethodSymbol(0, tree.name, null, enclScope.owner);
        m.flags_field = chk.checkFlags(tree.pos(), tree.mods.flags, m, tree);
        tree.sym = m;

        //if this is a default method, add the DEFAULT flag to the enclosing interface
        if ((tree.mods.flags & DEFAULT) != 0) {
            m.enclClass().flags_field |= DEFAULT;
        }

        Env<AttrContext> localEnv = methodEnv(tree, env);

        annotate.enterStart();
        try {
            DiagnosticPosition prevLintPos = deferredLintHandler.setPos(tree.pos());
            try {
                // Compute the method type
                m.type = signature(m, tree.typarams, tree.params,
                                   tree.restype, tree.recvparam,
                                   tree.thrown,
                                   localEnv);
            } finally {
                deferredLintHandler.setPos(prevLintPos);
            }

            if (types.isSignaturePolymorphic(m)) {
                m.flags_field |= SIGNATURE_POLYMORPHIC;
            }

            // Set m.params
            ListBuffer<VarSymbol> params = new ListBuffer<>();
            JCVariableDecl lastParam = null;
            for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail) {
                JCVariableDecl param = lastParam = l.head;
                params.append(Assert.checkNonNull(param.sym));
            }
            m.params = params.toList();

            // mark the method varargs, if necessary
            if (lastParam != null && (lastParam.mods.flags & Flags.VARARGS) != 0)
                m.flags_field |= Flags.VARARGS;

            localEnv.info.scope.leave();
            if (chk.checkUnique(tree.pos(), m, enclScope)) {
            enclScope.enter(m);
            }

            annotate.annotateLater(tree.mods.annotations, localEnv, m, tree.pos());
            // Visit the signature of the method. Note that
            // TypeAnnotate doesn't descend into the body.
            annotate.annotateTypeLater(tree, localEnv, m, tree.pos());

            if (tree.defaultValue != null)
                annotateDefaultValueLater(tree.defaultValue, localEnv, m);
        } finally {
            annotate.enterDone();
        }
    }

    /** Create a fresh environment for method bodies.
     *  @param tree     The method definition.
     *  @param env      The environment current outside of the method definition.
     */
    Env<AttrContext> methodEnv(JCMethodDecl tree, Env<AttrContext> env) {
        Env<AttrContext> localEnv =
            env.dup(tree, env.info.dup(env.info.scope.dupUnshared(tree.sym)));
        localEnv.enclMethod = tree;
        if (tree.sym.type != null) {
            //when this is called in the enter stage, there's no type to be set
            localEnv.info.returnResult = attr.new ResultInfo(VAL, tree.sym.type.getReturnType());
        }
        if ((tree.mods.flags & STATIC) != 0) localEnv.info.staticLevel++;
        return localEnv;
    }

    public void visitVarDef(JCVariableDecl tree) {
        Env<AttrContext> localEnv = env;
        if ((tree.mods.flags & STATIC) != 0 ||
            (env.info.scope.owner.flags() & INTERFACE) != 0) {
            localEnv = env.dup(tree, env.info.dup());
            localEnv.info.staticLevel++;
        }
        DiagnosticPosition prevLintPos = deferredLintHandler.setPos(tree.pos());
        annotate.enterStart();
        try {
            try {
                if (TreeInfo.isEnumInit(tree)) {
                    attr.attribIdentAsEnumType(localEnv, (JCIdent)tree.vartype);
                } else {
                    attr.attribType(tree.vartype, localEnv);
                    if (TreeInfo.isReceiverParam(tree))
                        checkReceiver(tree, localEnv);
                }
            } finally {
                deferredLintHandler.setPos(prevLintPos);
            }

            if ((tree.mods.flags & VARARGS) != 0) {
                //if we are entering a varargs parameter, we need to
                //replace its type (a plain array type) with the more
                //precise VarargsType --- we need to do it this way
                //because varargs is represented in the tree as a
                //modifier on the parameter declaration, and not as a
                //distinct type of array node.
                ArrayType atype = (ArrayType)tree.vartype.type;
                tree.vartype.type = atype.makeVarargs();
            }
            WriteableScope enclScope = enter.enterScope(env);
            VarSymbol v =
                new VarSymbol(0, tree.name, tree.vartype.type, enclScope.owner);
            v.flags_field = chk.checkFlags(tree.pos(), tree.mods.flags, v, tree);
            tree.sym = v;
            if (tree.init != null) {
                v.flags_field |= HASINIT;
                if ((v.flags_field & FINAL) != 0 &&
                    needsLazyConstValue(tree.init)) {
                    Env<AttrContext> initEnv = getInitEnv(tree, env);
                    initEnv.info.enclVar = v;
                    v.setLazyConstValue(initEnv(tree, initEnv), attr, tree);
                }
            }
            if (chk.checkUnique(tree.pos(), v, enclScope)) {
                chk.checkTransparentVar(tree.pos(), v, enclScope);
                enclScope.enter(v);
            }

            annotate.annotateLater(tree.mods.annotations, localEnv, v, tree.pos());
            annotate.annotateTypeLater(tree.vartype, localEnv, v, tree.pos());

            v.pos = tree.pos;
        } finally {
            annotate.enterDone();
        }
    }
    // where
    void checkType(JCTree tree, Type type, String diag) {
        if (!tree.type.isErroneous() && !types.isSameType(tree.type, type)) {
            log.error(tree, diag, type, tree.type);
        }
    }
    void checkReceiver(JCVariableDecl tree, Env<AttrContext> localEnv) {
        attr.attribExpr(tree.nameexpr, localEnv);
        MethodSymbol m = localEnv.enclMethod.sym;
        if (m.isConstructor()) {
            Type outertype = m.owner.owner.type;
            if (outertype.hasTag(TypeTag.METHOD)) {
                // we have a local inner class
                outertype = m.owner.owner.owner.type;
            }
            if (outertype.hasTag(TypeTag.CLASS)) {
                checkType(tree.vartype, outertype, "incorrect.constructor.receiver.type");
                checkType(tree.nameexpr, outertype, "incorrect.constructor.receiver.name");
            } else {
                log.error(tree, "receiver.parameter.not.applicable.constructor.toplevel.class");
            }
        } else {
            checkType(tree.vartype, m.owner.type, "incorrect.receiver.type");
            checkType(tree.nameexpr, m.owner.type, "incorrect.receiver.name");
        }
    }

    public boolean needsLazyConstValue(JCTree tree) {
        InitTreeVisitor initTreeVisitor = new InitTreeVisitor();
        tree.accept(initTreeVisitor);
        return initTreeVisitor.result;
    }

    /** Visitor class for expressions which might be constant expressions.
     */
    static class InitTreeVisitor extends JCTree.Visitor {

        private boolean result = true;

        @Override
        public void visitTree(JCTree tree) {}

        @Override
        public void visitNewClass(JCNewClass that) {
            result = false;
        }

        @Override
        public void visitNewArray(JCNewArray that) {
            result = false;
        }

        @Override
        public void visitLambda(JCLambda that) {
            result = false;
        }

        @Override
        public void visitReference(JCMemberReference that) {
            result = false;
        }

        @Override
        public void visitApply(JCMethodInvocation that) {
            result = false;
        }

        @Override
        public void visitSelect(JCFieldAccess tree) {
            tree.selected.accept(this);
        }

        @Override
        public void visitConditional(JCConditional tree) {
            tree.cond.accept(this);
            tree.truepart.accept(this);
            tree.falsepart.accept(this);
        }

        @Override
        public void visitParens(JCParens tree) {
            tree.expr.accept(this);
        }

        @Override
        public void visitTypeCast(JCTypeCast tree) {
            tree.expr.accept(this);
        }
    }

    /** Create a fresh environment for a variable's initializer.
     *  If the variable is a field, the owner of the environment's scope
     *  is be the variable itself, otherwise the owner is the method
     *  enclosing the variable definition.
     *
     *  @param tree     The variable definition.
     *  @param env      The environment current outside of the variable definition.
     */
    Env<AttrContext> initEnv(JCVariableDecl tree, Env<AttrContext> env) {
        Env<AttrContext> localEnv = env.dupto(new AttrContextEnv(tree, env.info.dup()));
        if (tree.sym.owner.kind == TYP) {
            localEnv.info.scope = env.info.scope.dupUnshared(tree.sym);
        }
        if ((tree.mods.flags & STATIC) != 0 ||
                ((env.enclClass.sym.flags() & INTERFACE) != 0 && env.enclMethod == null))
            localEnv.info.staticLevel++;
        return localEnv;
    }

    /** Default member enter visitor method: do nothing
     */
    public void visitTree(JCTree tree) {
    }

    public void visitErroneous(JCErroneous tree) {
        if (tree.errs != null)
            memberEnter(tree.errs, env);
    }

    public Env<AttrContext> getMethodEnv(JCMethodDecl tree, Env<AttrContext> env) {
        Env<AttrContext> mEnv = methodEnv(tree, env);
        mEnv.info.lint = mEnv.info.lint.augment(tree.sym);
        for (List<JCTypeParameter> l = tree.typarams; l.nonEmpty(); l = l.tail)
            mEnv.info.scope.enterIfAbsent(l.head.type.tsym);
        for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail)
            mEnv.info.scope.enterIfAbsent(l.head.sym);
        return mEnv;
    }

    public Env<AttrContext> getInitEnv(JCVariableDecl tree, Env<AttrContext> env) {
        Env<AttrContext> iEnv = initEnv(tree, env);
        return iEnv;
    }

/* ********************************************************************
 * Type completion
 *********************************************************************/

    Type attribImportType(JCTree tree, Env<AttrContext> env) {
        Assert.check(completionEnabled);
        try {
            // To prevent deep recursion, suppress completion of some
            // types.
            completionEnabled = false;
            return attr.attribType(tree, env);
        } finally {
            completionEnabled = true;
        }
    }

    /**
     * Check if a list of annotations contains a reference to
     * java.lang.Deprecated.
     **/
    private boolean hasDeprecatedAnnotation(List<JCAnnotation> annotations) {
        for (List<JCAnnotation> al = annotations; !al.isEmpty(); al = al.tail) {
            JCAnnotation a = al.head;
            if (a.annotationType.type == syms.deprecatedType && a.args.isEmpty())
                return true;
        }
        return false;
    }

    /** Queue processing of an attribute default value. */
    void annotateDefaultValueLater(final JCExpression defaultValue,
                                   final Env<AttrContext> localEnv,
                                   final MethodSymbol m) {
        annotate.normal(new Annotate.Worker() {
                @Override
                public String toString() {
                    return "annotate " + m.owner + "." +
                        m + " default " + defaultValue;
                }

                @Override
                public void run() {
                    JavaFileObject prev = log.useSource(localEnv.toplevel.sourcefile);
                    try {
                        enterDefaultValue(defaultValue, localEnv, m);
                    } finally {
                        log.useSource(prev);
                    }
                }
            });
        annotate.validate(new Annotate.Worker() { //validate annotations
            @Override
            public void run() {
                JavaFileObject prev = log.useSource(localEnv.toplevel.sourcefile);
                try {
                    // if default value is an annotation, check it is a well-formed
                    // annotation value (e.g. no duplicate values, no missing values, etc.)
                    chk.validateAnnotationTree(defaultValue);
                } finally {
                    log.useSource(prev);
                }
            }
        });
    }

    /** Enter a default value for an attribute method. */
    private void enterDefaultValue(final JCExpression defaultValue,
                                   final Env<AttrContext> localEnv,
                                   final MethodSymbol m) {
        m.defaultValue = annotate.enterAttributeValue(m.type.getReturnType(),
                                                      defaultValue,
                                                      localEnv);
    }

/* ********************************************************************
 * Source completer
 *********************************************************************/

    /** Complete entering a class.
     *  @param sym         The symbol of the class to be completed.
     */
    public void complete(Symbol sym) throws CompletionFailure {
        // Suppress some (recursive) MemberEnter invocations
        if (!completionEnabled) {
            // Re-install same completer for next time around and return.
            Assert.check((sym.flags() & Flags.COMPOUND) == 0);
            sym.completer = this;
            return;
        }

        ClassSymbol c = (ClassSymbol)sym;
        ClassType ct = (ClassType)c.type;
        Env<AttrContext> env = typeEnvs.get(c);
        JCClassDecl tree = (JCClassDecl)env.tree;
        boolean wasFirst = isFirst;
        isFirst = false;

        JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
        DiagnosticPosition prevLintPos = deferredLintHandler.setPos(tree.pos());
        try {
            dependencies.push(c);

            // Save class environment for later member enter (2) processing.
            halfcompleted.append(env);

            // Mark class as not yet attributed.
            c.flags_field |= UNATTRIBUTED;

            // If this is a toplevel-class, make sure any preceding import
            // clauses have been seen.
            if (c.owner.kind == PCK) {
                memberEnter(env.toplevel, env.enclosing(TOPLEVEL));
                todo.append(env);
            }

            if (c.owner.kind == TYP)
                c.owner.complete();

            // create an environment for evaluating the base clauses
            Env<AttrContext> baseEnv = baseEnv(tree, env);

            if (tree.extending != null)
                annotate.annotateTypeLater(tree.extending, baseEnv, sym, tree.pos());
            for (JCExpression impl : tree.implementing)
                annotate.annotateTypeLater(impl, baseEnv, sym, tree.pos());
            annotate.flush();

            // Determine supertype.
            Type supertype;
            if (tree.extending != null) {
                dependencies.push(AttributionKind.EXTENDS, tree.extending);
                try {
                    supertype = attr.attribBase(tree.extending, baseEnv,
                            true, false, true);
                } finally {
                    dependencies.pop();
                }
            } else {
                supertype = ((tree.mods.flags & Flags.ENUM) != 0)
                ? attr.attribBase(enumBase(tree.pos, c), baseEnv,
                                  true, false, false)
                : (c.fullname == names.java_lang_Object)
                ? Type.noType
                : syms.objectType;
            }
            ct.supertype_field = modelMissingTypes(supertype, tree.extending, false);

            // Determine interfaces.
            ListBuffer<Type> interfaces = new ListBuffer<>();
            ListBuffer<Type> all_interfaces = null; // lazy init
            Set<Type> interfaceSet = new HashSet<>();
            List<JCExpression> interfaceTrees = tree.implementing;
            for (JCExpression iface : interfaceTrees) {
                dependencies.push(AttributionKind.IMPLEMENTS, iface);
                try {
                    Type it = attr.attribBase(iface, baseEnv, false, true, true);
                    if (it.hasTag(CLASS)) {
                        interfaces.append(it);
                        if (all_interfaces != null) all_interfaces.append(it);
                        chk.checkNotRepeated(iface.pos(), types.erasure(it), interfaceSet);
                    } else {
                        if (all_interfaces == null)
                            all_interfaces = new ListBuffer<Type>().appendList(interfaces);
                        all_interfaces.append(modelMissingTypes(it, iface, true));
                    }
                } finally {
                    dependencies.pop();
                }
            }

            if ((c.flags_field & ANNOTATION) != 0) {
                ct.interfaces_field = List.of(syms.annotationType);
                ct.all_interfaces_field = ct.interfaces_field;
            }  else {
                ct.interfaces_field = interfaces.toList();
                ct.all_interfaces_field = (all_interfaces == null)
                        ? ct.interfaces_field : all_interfaces.toList();
            }

            if (c.fullname == names.java_lang_Object) {
                if (tree.extending != null) {
                    chk.checkNonCyclic(tree.extending.pos(),
                                       supertype);
                    ct.supertype_field = Type.noType;
                }
                else if (tree.implementing.nonEmpty()) {
                    chk.checkNonCyclic(tree.implementing.head.pos(),
                                       ct.interfaces_field.head);
                    ct.interfaces_field = List.nil();
                }
            }

            // Annotations.
            // In general, we cannot fully process annotations yet,  but we
            // can attribute the annotation types and then check to see if the
            // @Deprecated annotation is present.
            attr.attribAnnotationTypes(tree.mods.annotations, baseEnv);
            if (hasDeprecatedAnnotation(tree.mods.annotations))
                c.flags_field |= DEPRECATED;
            annotate.annotateLater(tree.mods.annotations, baseEnv,
                        c, tree.pos());

            chk.checkNonCyclicDecl(tree);

            // class type parameters use baseEnv but everything uses env
            attr.attribTypeVariables(tree.typarams, baseEnv);
            for (JCTypeParameter tp : tree.typarams)
                annotate.annotateTypeLater(tp, baseEnv, sym, tree.pos());

            // Add default constructor if needed.
            if ((c.flags() & INTERFACE) == 0 &&
                !TreeInfo.hasConstructors(tree.defs)) {
                List<Type> argtypes = List.nil();
                List<Type> typarams = List.nil();
                List<Type> thrown = List.nil();
                long ctorFlags = 0;
                boolean based = false;
                boolean addConstructor = true;
                JCNewClass nc = null;
                if (c.name.isEmpty()) {
                    nc = (JCNewClass)env.next.tree;
                    if (nc.constructor != null) {
                        addConstructor = nc.constructor.kind != ERR;
                        Type superConstrType = types.memberType(c.type,
                                                                nc.constructor);
                        argtypes = superConstrType.getParameterTypes();
                        typarams = superConstrType.getTypeArguments();
                        ctorFlags = nc.constructor.flags() & VARARGS;
                        if (nc.encl != null) {
                            argtypes = argtypes.prepend(nc.encl.type);
                            based = true;
                        }
                        thrown = superConstrType.getThrownTypes();
                    }
                }
                if (addConstructor) {
                    MethodSymbol basedConstructor = nc != null ?
                            (MethodSymbol)nc.constructor : null;
                    JCTree constrDef = DefaultConstructor(make.at(tree.pos), c,
                                                        basedConstructor,
                                                        typarams, argtypes, thrown,
                                                        ctorFlags, based);
                    tree.defs = tree.defs.prepend(constrDef);
                }
            }

            // enter symbols for 'this' into current scope.
            VarSymbol thisSym =
                new VarSymbol(FINAL | HASINIT, names._this, c.type, c);
            thisSym.pos = Position.FIRSTPOS;
            env.info.scope.enter(thisSym);
            // if this is a class, enter symbol for 'super' into current scope.
            if ((c.flags_field & INTERFACE) == 0 &&
                    ct.supertype_field.hasTag(CLASS)) {
                VarSymbol superSym =
                    new VarSymbol(FINAL | HASINIT, names._super,
                                  ct.supertype_field, c);
                superSym.pos = Position.FIRSTPOS;
                env.info.scope.enter(superSym);
            }

            // check that no package exists with same fully qualified name,
            // but admit classes in the unnamed package which have the same
            // name as a top-level package.
            if (checkClash &&
                c.owner.kind == PCK && c.owner != syms.unnamedPackage &&
                syms.packageExists(c.fullname)) {
                log.error(tree.pos, "clash.with.pkg.of.same.name", Kinds.kindName(sym), c);
            }
            if (c.owner.kind == PCK && (c.flags_field & PUBLIC) == 0 &&
                !env.toplevel.sourcefile.isNameCompatible(c.name.toString(),JavaFileObject.Kind.SOURCE)) {
                c.flags_field |= AUXILIARY;
            }
        } catch (CompletionFailure ex) {
            chk.completionError(tree.pos(), ex);
        } finally {
            deferredLintHandler.setPos(prevLintPos);
            log.useSource(prev);
            dependencies.pop();
        }

        // Enter all member fields and methods of a set of half completed
        // classes in a second phase.
        if (wasFirst) {
            Set<JCCompilationUnit> topLevels = new HashSet<>();
            try {
                while (halfcompleted.nonEmpty()) {
                    Env<AttrContext> toFinish = halfcompleted.next();
                    topLevels.add(toFinish.toplevel);
                    finish(toFinish);
                    if (allowTypeAnnos) {
                        typeAnnotations.organizeTypeAnnotationsSignatures(toFinish, (JCClassDecl)toFinish.tree);
                        typeAnnotations.validateTypeAnnotationsSignatures(toFinish, (JCClassDecl)toFinish.tree);
                    }
                }
            } finally {
                isFirst = true;
            }

            for (JCCompilationUnit toplevel : topLevels) {
                chk.checkImportsResolvable(toplevel);
            }

        }
    }

    private Env<AttrContext> baseEnv(JCClassDecl tree, Env<AttrContext> env) {
        WriteableScope baseScope = WriteableScope.create(tree.sym);
        //import already entered local classes into base scope
        for (Symbol sym : env.outer.info.scope.getSymbols(NON_RECURSIVE)) {
            if (sym.isLocal()) {
                baseScope.enter(sym);
            }
        }
        //import current type-parameters into base scope
        if (tree.typarams != null)
            for (List<JCTypeParameter> typarams = tree.typarams;
                 typarams.nonEmpty();
                 typarams = typarams.tail)
                baseScope.enter(typarams.head.type.tsym);
        Env<AttrContext> outer = env.outer; // the base clause can't see members of this class
        Env<AttrContext> localEnv = outer.dup(tree, outer.info.dup(baseScope));
        localEnv.baseClause = true;
        localEnv.outer = outer;
        localEnv.info.isSelfCall = false;
        return localEnv;
    }

    /** Enter member fields and methods of a class
     *  @param env        the environment current for the class block.
     */
    private void finish(Env<AttrContext> env) {
        JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
        try {
            JCClassDecl tree = (JCClassDecl)env.tree;
            finishClass(tree, env);
        } finally {
            log.useSource(prev);
        }
    }

    /** Generate a base clause for an enum type.
     *  @param pos              The position for trees and diagnostics, if any
     *  @param c                The class symbol of the enum
     */
    private JCExpression enumBase(int pos, ClassSymbol c) {
        JCExpression result = make.at(pos).
            TypeApply(make.QualIdent(syms.enumSym),
                      List.<JCExpression>of(make.Type(c.type)));
        return result;
    }

    Type modelMissingTypes(Type t, final JCExpression tree, final boolean interfaceExpected) {
        if (!t.hasTag(ERROR))
            return t;

        return new ErrorType(t.getOriginalType(), t.tsym) {
            private Type modelType;

            @Override
            public Type getModelType() {
                if (modelType == null)
                    modelType = new Synthesizer(getOriginalType(), interfaceExpected).visit(tree);
                return modelType;
            }
        };
    }
    // where
    private class Synthesizer extends JCTree.Visitor {
        Type originalType;
        boolean interfaceExpected;
        List<ClassSymbol> synthesizedSymbols = List.nil();
        Type result;

        Synthesizer(Type originalType, boolean interfaceExpected) {
            this.originalType = originalType;
            this.interfaceExpected = interfaceExpected;
        }

        Type visit(JCTree tree) {
            tree.accept(this);
            return result;
        }

        List<Type> visit(List<? extends JCTree> trees) {
            ListBuffer<Type> lb = new ListBuffer<>();
            for (JCTree t: trees)
                lb.append(visit(t));
            return lb.toList();
        }

        @Override
        public void visitTree(JCTree tree) {
            result = syms.errType;
        }

        @Override
        public void visitIdent(JCIdent tree) {
            if (!tree.type.hasTag(ERROR)) {
                result = tree.type;
            } else {
                result = synthesizeClass(tree.name, syms.unnamedPackage).type;
            }
        }

        @Override
        public void visitSelect(JCFieldAccess tree) {
            if (!tree.type.hasTag(ERROR)) {
                result = tree.type;
            } else {
                Type selectedType;
                boolean prev = interfaceExpected;
                try {
                    interfaceExpected = false;
                    selectedType = visit(tree.selected);
                } finally {
                    interfaceExpected = prev;
                }
                ClassSymbol c = synthesizeClass(tree.name, selectedType.tsym);
                result = c.type;
            }
        }

        @Override
        public void visitTypeApply(JCTypeApply tree) {
            if (!tree.type.hasTag(ERROR)) {
                result = tree.type;
            } else {
                ClassType clazzType = (ClassType) visit(tree.clazz);
                if (synthesizedSymbols.contains(clazzType.tsym))
                    synthesizeTyparams((ClassSymbol) clazzType.tsym, tree.arguments.size());
                final List<Type> actuals = visit(tree.arguments);
                result = new ErrorType(tree.type, clazzType.tsym) {
                    @Override @DefinedBy(Api.LANGUAGE_MODEL)
                    public List<Type> getTypeArguments() {
                        return actuals;
                    }
                };
            }
        }

        ClassSymbol synthesizeClass(Name name, Symbol owner) {
            int flags = interfaceExpected ? INTERFACE : 0;
            ClassSymbol c = new ClassSymbol(flags, name, owner);
            c.members_field = new Scope.ErrorScope(c);
            c.type = new ErrorType(originalType, c) {
                @Override @DefinedBy(Api.LANGUAGE_MODEL)
                public List<Type> getTypeArguments() {
                    return typarams_field;
                }
            };
            synthesizedSymbols = synthesizedSymbols.prepend(c);
            return c;
        }

        void synthesizeTyparams(ClassSymbol sym, int n) {
            ClassType ct = (ClassType) sym.type;
            Assert.check(ct.typarams_field.isEmpty());
            if (n == 1) {
                TypeVar v = new TypeVar(names.fromString("T"), sym, syms.botType,
                                        Type.noAnnotations);
                ct.typarams_field = ct.typarams_field.prepend(v);
            } else {
                for (int i = n; i > 0; i--) {
                    TypeVar v = new TypeVar(names.fromString("T" + i), sym,
                                            syms.botType, Type.noAnnotations);
                    ct.typarams_field = ct.typarams_field.prepend(v);
                }
            }
        }
    }


/* ***************************************************************************
 * tree building
 ****************************************************************************/

    /** Generate default constructor for given class. For classes different
     *  from java.lang.Object, this is:
     *
     *    c(argtype_0 x_0, ..., argtype_n x_n) throws thrown {
     *      super(x_0, ..., x_n)
     *    }
     *
     *  or, if based == true:
     *
     *    c(argtype_0 x_0, ..., argtype_n x_n) throws thrown {
     *      x_0.super(x_1, ..., x_n)
     *    }
     *
     *  @param make     The tree factory.
     *  @param c        The class owning the default constructor.
     *  @param argtypes The parameter types of the constructor.
     *  @param thrown   The thrown exceptions of the constructor.
     *  @param based    Is first parameter a this$n?
     */
    JCTree DefaultConstructor(TreeMaker make,
                            ClassSymbol c,
                            MethodSymbol baseInit,
                            List<Type> typarams,
                            List<Type> argtypes,
                            List<Type> thrown,
                            long flags,
                            boolean based) {
        JCTree result;
        if ((c.flags() & ENUM) != 0 &&
            (types.supertype(c.type).tsym == syms.enumSym)) {
            // constructors of true enums are private
            flags = (flags & ~AccessFlags) | PRIVATE | GENERATEDCONSTR;
        } else
            flags |= (c.flags() & AccessFlags) | GENERATEDCONSTR;
        if (c.name.isEmpty()) {
            flags |= ANONCONSTR;
        }
        Type mType = new MethodType(argtypes, null, thrown, c);
        Type initType = typarams.nonEmpty() ?
            new ForAll(typarams, mType) :
            mType;
        MethodSymbol init = new MethodSymbol(flags, names.init,
                initType, c);
        init.params = createDefaultConstructorParams(make, baseInit, init,
                argtypes, based);
        List<JCVariableDecl> params = make.Params(argtypes, init);
        List<JCStatement> stats = List.nil();
        if (c.type != syms.objectType) {
            stats = stats.prepend(SuperCall(make, typarams, params, based));
        }
        result = make.MethodDef(init, make.Block(0, stats));
        return result;
    }

    private List<VarSymbol> createDefaultConstructorParams(
            TreeMaker make,
            MethodSymbol baseInit,
            MethodSymbol init,
            List<Type> argtypes,
            boolean based) {
        List<VarSymbol> initParams = null;
        List<Type> argTypesList = argtypes;
        if (based) {
            /*  In this case argtypes will have an extra type, compared to baseInit,
             *  corresponding to the type of the enclosing instance i.e.:
             *
             *  Inner i = outer.new Inner(1){}
             *
             *  in the above example argtypes will be (Outer, int) and baseInit
             *  will have parameter's types (int). So in this case we have to add
             *  first the extra type in argtypes and then get the names of the
             *  parameters from baseInit.
             */
            initParams = List.nil();
            VarSymbol param = new VarSymbol(PARAMETER, make.paramName(0), argtypes.head, init);
            initParams = initParams.append(param);
            argTypesList = argTypesList.tail;
        }
        if (baseInit != null && baseInit.params != null &&
            baseInit.params.nonEmpty() && argTypesList.nonEmpty()) {
            initParams = (initParams == null) ? List.<VarSymbol>nil() : initParams;
            List<VarSymbol> baseInitParams = baseInit.params;
            while (baseInitParams.nonEmpty() && argTypesList.nonEmpty()) {
                VarSymbol param = new VarSymbol(baseInitParams.head.flags() | PARAMETER,
                        baseInitParams.head.name, argTypesList.head, init);
                initParams = initParams.append(param);
                baseInitParams = baseInitParams.tail;
                argTypesList = argTypesList.tail;
            }
        }
        return initParams;
    }

    /** Generate call to superclass constructor. This is:
     *
     *    super(id_0, ..., id_n)
     *
     * or, if based == true
     *
     *    id_0.super(id_1,...,id_n)
     *
     *  where id_0, ..., id_n are the names of the given parameters.
     *
     *  @param make    The tree factory
     *  @param params  The parameters that need to be passed to super
     *  @param typarams  The type parameters that need to be passed to super
     *  @param based   Is first parameter a this$n?
     */
    JCExpressionStatement SuperCall(TreeMaker make,
                   List<Type> typarams,
                   List<JCVariableDecl> params,
                   boolean based) {
        JCExpression meth;
        if (based) {
            meth = make.Select(make.Ident(params.head), names._super);
            params = params.tail;
        } else {
            meth = make.Ident(names._super);
        }
        List<JCExpression> typeargs = typarams.nonEmpty() ? make.Types(typarams) : null;
        return make.Exec(make.Apply(typeargs, meth, make.Idents(params)));
    }
}
