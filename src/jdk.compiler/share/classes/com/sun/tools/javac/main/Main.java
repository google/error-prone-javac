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

package com.sun.tools.javac.main;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.Set;

import javax.tools.JavaFileManager;

import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.file.CacheFSInfo;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.processing.AnnotationProcessingError;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Log.PrefixKind;
import com.sun.tools.javac.util.Log.WriterKind;

/** This class provides a command line interface to the javac compiler.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Main {

    /** The name of the compiler, for use in diagnostics.
     */
    String ownName;

    /** The writer to use for diagnostic output.
     */
    PrintWriter out;

    /** The log to use for diagnostic output.
     */
    public Log log;

    /**
     * If true, certain errors will cause an exception, such as command line
     * arg errors, or exceptions in user provided code.
     */
    boolean apiMode;


    /** Result codes.
     */
    public enum Result {
        OK(0),        // Compilation completed with no errors.
        ERROR(1),     // Completed but reported errors.
        CMDERR(2),    // Bad command-line arguments
        SYSERR(3),    // System error or resource exhaustion.
        ABNORMAL(4);  // Compiler terminated abnormally

        Result(int exitCode) {
            this.exitCode = exitCode;
        }

        public boolean isOK() {
            return (exitCode == 0);
        }

        public final int exitCode;
    }

    /**
     * Construct a compiler instance.
     * @param name the name of this tool
     */
    public Main(String name) {
        this(name, new PrintWriter(System.err, true));
    }

    /**
     * Construct a compiler instance.
     * @param name the name of this tool
     * @param out a stream to which to write messages
     */
    public Main(String name, PrintWriter out) {
        this.ownName = name;
        this.out = out;
    }

    /** Report a usage error.
     */
    void error(String key, Object... args) {
        if (apiMode) {
            String msg = log.localize(PrefixKind.JAVAC, key, args);
            throw new PropagatedException(new IllegalStateException(msg));
        }
        warning(key, args);
        log.printLines(PrefixKind.JAVAC, "msg.usage", ownName);
    }

    /** Report a warning.
     */
    void warning(String key, Object... args) {
        log.printRawLines(ownName + ": " + log.localize(PrefixKind.JAVAC, key, args));
    }


    /**
     * Programmatic interface for main function.
     * @param args  the command line parameters
     * @return the result of the compilation
     */
    public Result compile(String[] args) {
        Context context = new Context();
        JavacFileManager.preRegister(context); // can't create it until Log has been set up
        Result result = compile(args, context);
        if (fileManager instanceof JavacFileManager) {
            // A fresh context was created above, so jfm must be a JavacFileManager
            ((JavacFileManager)fileManager).close();
        }
        return result;
    }

    /**
     * Internal version of compile, allowing context to be provided.
     * Note that the context needs to have a file manager set up.
     * @param argv  the command line parameters
     * @param context the context
     * @return the result of the compilation
     */
    public Result compile(String[] argv, Context context) {
        context.put(Log.outKey, out);
        log = Log.instance(context);

        if (argv.length == 0) {
            Option.HELP.process(new OptionHelper.GrumpyHelper(log) {
                @Override
                public String getOwnName() { return ownName; }
            }, "-help");
            return Result.CMDERR;
        }

        try {
            argv = CommandLine.parse(argv);
        } catch (FileNotFoundException e) {
            warning("err.file.not.found", e.getMessage());
            return Result.SYSERR;
        } catch (IOException ex) {
            log.printLines(PrefixKind.JAVAC, "msg.io");
            ex.printStackTrace(log.getWriter(WriterKind.NOTICE));
            return Result.SYSERR;
        }

        Arguments args = Arguments.instance(context);
        args.init(ownName, argv);

        if (log.nerrors > 0)
            return Result.CMDERR;

        Options options = Options.instance(context);

        // init Log
        boolean forceStdOut = options.isSet("stdout");
        if (forceStdOut) {
            log.flush();
            log.setWriters(new PrintWriter(System.out, true));
        }

        // init CacheFSInfo
        // allow System property in following line as a Mustang legacy
        boolean batchMode = (options.isUnset("nonBatchMode")
                    && System.getProperty("nonBatchMode") == null);
        if (batchMode)
            CacheFSInfo.preRegister(context);

        // init file manager
        fileManager = context.get(JavaFileManager.class);
        if (fileManager instanceof BaseFileManager) {
            ((BaseFileManager) fileManager).setContext(context); // reinit with options
            ((BaseFileManager) fileManager).handleOptions(args.getDeferredFileManagerOptions());
        }

        // handle this here so it works even if no other options given
        String showClass = options.get("showClass");
        if (showClass != null) {
            if (showClass.equals("showClass")) // no value given for option
                showClass = "com.sun.tools.javac.Main";
            showClass(showClass);
        }

        boolean ok = args.validate();
        if (!ok || log.nerrors > 0)
            return Result.CMDERR;

        if (args.isEmpty())
            return Result.OK;

        // init plugins
        Set<List<String>> pluginOpts = args.getPluginOpts();
        if (!pluginOpts.isEmpty()) {
            BasicJavacTask t = (BasicJavacTask) BasicJavacTask.instance(context);
            t.initPlugins(pluginOpts);
        }

        // init doclint
        List<String> docLintOpts = args.getDocLintOpts();
        if (!docLintOpts.isEmpty()) {
            BasicJavacTask t = (BasicJavacTask) BasicJavacTask.instance(context);
            t.initDocLint(docLintOpts);
        }

        // init Depeendencies
        if (options.isSet("completionDeps")) {
            Dependencies.GraphDependencies.preRegister(context);
        }

        // init JavaCompiler
        JavaCompiler comp = JavaCompiler.instance(context);
        if (options.get(Option.XSTDOUT) != null) {
            // Stdout reassigned - ask compiler to close it when it is done
            comp.closeables = comp.closeables.prepend(log.getWriter(WriterKind.NOTICE));
        }

        try {
            comp.compile(args.getFileObjects(), args.getClassNames(), null);

            if (log.expectDiagKeys != null) {
                if (log.expectDiagKeys.isEmpty()) {
                    log.printRawLines("all expected diagnostics found");
                    return Result.OK;
                } else {
                    log.printRawLines("expected diagnostic keys not found: " + log.expectDiagKeys);
                    return Result.ERROR;
                }
            }

            return (comp.errorCount() == 0) ? Result.OK : Result.ERROR;

        } catch (OutOfMemoryError | StackOverflowError ex) {
            resourceMessage(ex);
            return Result.SYSERR;
        } catch (FatalError ex) {
            feMessage(ex, options);
            return Result.SYSERR;
        } catch (AnnotationProcessingError ex) {
            apMessage(ex);
            return Result.SYSERR;
        } catch (PropagatedException ex) {
            // TODO: what about errors from plugins?   should not simply rethrow the error here
            throw ex.getCause();
        } catch (Throwable ex) {
            // Nasty.  If we've already reported an error, compensate
            // for buggy compiler error recovery by swallowing thrown
            // exceptions.
            if (comp == null || comp.errorCount() == 0 || options.isSet("dev"))
                bugMessage(ex);
            return Result.ABNORMAL;
        } finally {
            if (comp != null) {
                try {
                    comp.close();
                } catch (ClientCodeException ex) {
                    throw new RuntimeException(ex.getCause());
                }
            }
        }
    }

    /** Print a message reporting an internal error.
     */
    void bugMessage(Throwable ex) {
        log.printLines(PrefixKind.JAVAC, "msg.bug", JavaCompiler.version());
        ex.printStackTrace(log.getWriter(WriterKind.NOTICE));
    }

    /** Print a message reporting a fatal error.
     */
    void feMessage(Throwable ex, Options options) {
        log.printRawLines(ex.getMessage());
        if (ex.getCause() != null && options.isSet("dev")) {
            ex.getCause().printStackTrace(log.getWriter(WriterKind.NOTICE));
        }
    }

    /** Print a message reporting an input/output error.
     */
    void ioMessage(Throwable ex) {
        log.printLines(PrefixKind.JAVAC, "msg.io");
        ex.printStackTrace(log.getWriter(WriterKind.NOTICE));
    }

    /** Print a message reporting an out-of-resources error.
     */
    void resourceMessage(Throwable ex) {
        log.printLines(PrefixKind.JAVAC, "msg.resource");
        ex.printStackTrace(log.getWriter(WriterKind.NOTICE));
    }

    /** Print a message reporting an uncaught exception from an
     * annotation processor.
     */
    void apMessage(AnnotationProcessingError ex) {
        log.printLines(PrefixKind.JAVAC, "msg.proc.annotation.uncaught.exception");
        ex.getCause().printStackTrace(log.getWriter(WriterKind.NOTICE));
    }

    /** Print a message reporting an uncaught exception from an
     * annotation processor.
     */
    void pluginMessage(Throwable ex) {
        log.printLines(PrefixKind.JAVAC, "msg.plugin.uncaught.exception");
        ex.printStackTrace(log.getWriter(WriterKind.NOTICE));
    }

    /** Display the location and checksum of a class. */
    void showClass(String className) {
        PrintWriter pw = log.getWriter(WriterKind.NOTICE);
        pw.println("javac: show class: " + className);
        URL url = getClass().getResource('/' + className.replace('.', '/') + ".class");
        if (url == null)
            pw.println("  class not found");
        else {
            pw.println("  " + url);
            try {
                final String algorithm = "MD5";
                byte[] digest;
                MessageDigest md = MessageDigest.getInstance(algorithm);
                try (DigestInputStream in = new DigestInputStream(url.openStream(), md)) {
                    byte[] buf = new byte[8192];
                    int n;
                    do { n = in.read(buf); } while (n > 0);
                    digest = md.digest();
                }
                StringBuilder sb = new StringBuilder();
                for (byte b: digest)
                    sb.append(String.format("%02x", b));
                pw.println("  " + algorithm + " checksum: " + sb);
            } catch (NoSuchAlgorithmException | IOException e) {
                pw.println("  cannot compute digest: " + e);
            }
        }
    }

    // TODO: update this to JavacFileManager
    private JavaFileManager fileManager;

    /* ************************************************************************
     * Internationalization
     *************************************************************************/

    public static final String javacBundleName =
        "com.sun.tools.javac.resources.javac";
}
