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


package com.sun.tools.javac.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;

import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.util.ClientCodeException;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DefinedBy;
import com.sun.tools.javac.util.DefinedBy.Api;
import com.sun.tools.javac.util.JCDiagnostic;

/**
 *  Wrap objects to enable unchecked exceptions to be caught and handled.
 *
 *  For each method, exceptions are handled as follows:
 *  <ul>
 *  <li>Checked exceptions are left alone and propogate upwards in the
 *      obvious way, since they are an expected aspect of the method's
 *      specification.
 *  <li>Unchecked exceptions which have already been caught and wrapped in
 *      ClientCodeException are left alone to continue propogating upwards.
 *  <li>All other unchecked exceptions (i.e. subtypes of RuntimeException
 *      and Error) and caught, and rethrown as a ClientCodeException with
 *      its cause set to the original exception.
 *  </ul>
 *
 *  The intent is that ClientCodeException can be caught at an appropriate point
 *  in the program and can be distinguished from any unanticipated unchecked
 *  exceptions arising in the main body of the code (i.e. bugs.) When the
 *  ClientCodeException has been caught, either a suitable message can be
 *  generated, or if appropriate, the original cause can be rethrown.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class ClientCodeWrapper {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Trusted { }

    public static ClientCodeWrapper instance(Context context) {
        ClientCodeWrapper instance = context.get(ClientCodeWrapper.class);
        if (instance == null)
            instance = new ClientCodeWrapper(context);
        return instance;
    }

    /**
     * A map to cache the results of whether or not a specific classes can
     * be "trusted", and thus does not need to be wrapped.
     */
    Map<Class<?>, Boolean> trustedClasses;

    protected ClientCodeWrapper(Context context) {
        trustedClasses = new HashMap<>();
    }

    public JavaFileManager wrap(JavaFileManager fm) {
        if (isTrusted(fm))
            return fm;
        return new WrappedJavaFileManager(fm);
    }

    public FileObject wrap(FileObject fo) {
        if (isTrusted(fo))
            return fo;
        return new WrappedFileObject(fo);
    }

    FileObject unwrap(FileObject fo) {
        if (fo instanceof WrappedFileObject)
            return ((WrappedFileObject) fo).clientFileObject;
        else
            return fo;
    }

    public JavaFileObject wrap(JavaFileObject fo) {
        if (isTrusted(fo))
            return fo;
        return new WrappedJavaFileObject(fo);
    }

    public Iterable<JavaFileObject> wrapJavaFileObjects(Iterable<? extends JavaFileObject> list) {
        List<JavaFileObject> wrapped = new ArrayList<>();
        for (JavaFileObject fo : list)
            wrapped.add(wrap(fo));
        return Collections.unmodifiableList(wrapped);
    }

    JavaFileObject unwrap(JavaFileObject fo) {
        if (fo instanceof WrappedJavaFileObject)
            return ((JavaFileObject) ((WrappedJavaFileObject) fo).clientFileObject);
        else
            return fo;
    }

    public <T /*super JavaFileOject*/> DiagnosticListener<T> wrap(DiagnosticListener<T> dl) {
        if (isTrusted(dl))
            return dl;
        return new WrappedDiagnosticListener<>(dl);
    }

    TaskListener wrap(TaskListener tl) {
        if (isTrusted(tl))
            return tl;
        return new WrappedTaskListener(tl);
    }

    TaskListener unwrap(TaskListener l) {
        if (l instanceof WrappedTaskListener)
            return ((WrappedTaskListener) l).clientTaskListener;
        else
            return l;
    }

    Collection<TaskListener> unwrap(Collection<? extends TaskListener> listeners) {
        Collection<TaskListener> c = new ArrayList<>(listeners.size());
        for (TaskListener l: listeners)
            c.add(unwrap(l));
        return c;
    }

    @SuppressWarnings("unchecked")
    private <T> Diagnostic<T> unwrap(final Diagnostic<T> diagnostic) {
        if (diagnostic instanceof JCDiagnostic) {
            JCDiagnostic d = (JCDiagnostic) diagnostic;
            return (Diagnostic<T>) new DiagnosticSourceUnwrapper(d);
        } else {
            return diagnostic;
        }
    }

    protected boolean isTrusted(Object o) {
        Class<?> c = o.getClass();
        Boolean trusted = trustedClasses.get(c);
        if (trusted == null) {
            trusted = c.getName().startsWith("com.sun.tools.javac.")
                    || c.isAnnotationPresent(Trusted.class);
            trustedClasses.put(c, trusted);
        }
        return trusted;
    }

    private String wrappedToString(Class<?> wrapperClass, Object wrapped) {
        return wrapperClass.getSimpleName() + "[" + wrapped + "]";
    }

    // <editor-fold defaultstate="collapsed" desc="Wrapper classes">

    protected class WrappedJavaFileManager implements JavaFileManager {
        protected JavaFileManager clientJavaFileManager;
        WrappedJavaFileManager(JavaFileManager clientJavaFileManager) {
            clientJavaFileManager.getClass(); // null check
            this.clientJavaFileManager = clientJavaFileManager;
        }

        @Override @DefinedBy(Api.COMPILER)
        public ClassLoader getClassLoader(Location location) {
            try {
                return clientJavaFileManager.getClassLoader(location);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
            try {
                return wrapJavaFileObjects(clientJavaFileManager.list(location, packageName, kinds, recurse));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public String inferBinaryName(Location location, JavaFileObject file) {
            try {
                return clientJavaFileManager.inferBinaryName(location, unwrap(file));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public boolean isSameFile(FileObject a, FileObject b) {
            try {
                return clientJavaFileManager.isSameFile(unwrap(a), unwrap(b));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public boolean handleOption(String current, Iterator<String> remaining) {
            try {
                return clientJavaFileManager.handleOption(current, remaining);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public boolean hasLocation(Location location) {
            try {
                return clientJavaFileManager.hasLocation(location);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind) throws IOException {
            try {
                return wrap(clientJavaFileManager.getJavaFileForInput(location, className, kind));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind, FileObject sibling) throws IOException {
            try {
                return wrap(clientJavaFileManager.getJavaFileForOutput(location, className, kind, unwrap(sibling)));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
            try {
                return wrap(clientJavaFileManager.getFileForInput(location, packageName, relativeName));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
            try {
                return wrap(clientJavaFileManager.getFileForOutput(location, packageName, relativeName, unwrap(sibling)));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public void flush() throws IOException {
            try {
                clientJavaFileManager.flush();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public void close() throws IOException {
            try {
                clientJavaFileManager.close();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public int isSupportedOption(String option) {
            try {
                return clientJavaFileManager.isSupportedOption(option);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override
        public String toString() {
            return wrappedToString(getClass(), clientJavaFileManager);
        }
    }

    protected class WrappedFileObject implements FileObject {
        protected FileObject clientFileObject;
        WrappedFileObject(FileObject clientFileObject) {
            clientFileObject.getClass(); // null check
            this.clientFileObject = clientFileObject;
        }

        @Override @DefinedBy(Api.COMPILER)
        public URI toUri() {
            try {
                return clientFileObject.toUri();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public String getName() {
            try {
                return clientFileObject.getName();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public InputStream openInputStream() throws IOException {
            try {
                return clientFileObject.openInputStream();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public OutputStream openOutputStream() throws IOException {
            try {
                return clientFileObject.openOutputStream();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            try {
                return clientFileObject.openReader(ignoreEncodingErrors);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            try {
                return clientFileObject.getCharContent(ignoreEncodingErrors);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public Writer openWriter() throws IOException {
            try {
                return clientFileObject.openWriter();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public long getLastModified() {
            try {
                return clientFileObject.getLastModified();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public boolean delete() {
            try {
                return clientFileObject.delete();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override
        public String toString() {
            return wrappedToString(getClass(), clientFileObject);
        }
    }

    protected class WrappedJavaFileObject extends WrappedFileObject implements JavaFileObject {
        WrappedJavaFileObject(JavaFileObject clientJavaFileObject) {
            super(clientJavaFileObject);
        }

        @Override @DefinedBy(Api.COMPILER)
        public Kind getKind() {
            try {
                return ((JavaFileObject)clientFileObject).getKind();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public boolean isNameCompatible(String simpleName, Kind kind) {
            try {
                return ((JavaFileObject)clientFileObject).isNameCompatible(simpleName, kind);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public NestingKind getNestingKind() {
            try {
                return ((JavaFileObject)clientFileObject).getNestingKind();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER)
        public Modifier getAccessLevel() {
            try {
                return ((JavaFileObject)clientFileObject).getAccessLevel();
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override
        public String toString() {
            return wrappedToString(getClass(), clientFileObject);
        }
    }

    protected class WrappedDiagnosticListener<T /*super JavaFileObject*/> implements DiagnosticListener<T> {
        protected DiagnosticListener<T> clientDiagnosticListener;
        WrappedDiagnosticListener(DiagnosticListener<T> clientDiagnosticListener) {
            clientDiagnosticListener.getClass(); // null check
            this.clientDiagnosticListener = clientDiagnosticListener;
        }

        @Override @DefinedBy(Api.COMPILER)
        public void report(Diagnostic<? extends T> diagnostic) {
            try {
                clientDiagnosticListener.report(unwrap(diagnostic));
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override
        public String toString() {
            return wrappedToString(getClass(), clientDiagnosticListener);
        }
    }

    public class DiagnosticSourceUnwrapper implements Diagnostic<JavaFileObject> {
        public final JCDiagnostic d;

        DiagnosticSourceUnwrapper(JCDiagnostic d) {
            this.d = d;
        }

        @DefinedBy(Api.COMPILER)
        public Diagnostic.Kind getKind() {
            return d.getKind();
        }

        @DefinedBy(Api.COMPILER)
        public JavaFileObject getSource() {
            return unwrap(d.getSource());
        }

        @DefinedBy(Api.COMPILER)
        public long getPosition() {
            return d.getPosition();
        }

        @DefinedBy(Api.COMPILER)
        public long getStartPosition() {
            return d.getStartPosition();
        }

        @DefinedBy(Api.COMPILER)
        public long getEndPosition() {
            return d.getEndPosition();
        }

        @DefinedBy(Api.COMPILER)
        public long getLineNumber() {
            return d.getLineNumber();
        }

        @DefinedBy(Api.COMPILER)
        public long getColumnNumber() {
            return d.getColumnNumber();
        }

        @DefinedBy(Api.COMPILER)
        public String getCode() {
            return d.getCode();
        }

        @DefinedBy(Api.COMPILER)
        public String getMessage(Locale locale) {
            return d.getMessage(locale);
        }

        @Override
        public String toString() {
            return d.toString();
        }
    }

    protected class WrappedTaskListener implements TaskListener {
        protected TaskListener clientTaskListener;
        WrappedTaskListener(TaskListener clientTaskListener) {
            clientTaskListener.getClass(); // null check
            this.clientTaskListener = clientTaskListener;
        }

        @Override @DefinedBy(Api.COMPILER_TREE)
        public void started(TaskEvent ev) {
            try {
                clientTaskListener.started(ev);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override @DefinedBy(Api.COMPILER_TREE)
        public void finished(TaskEvent ev) {
            try {
                clientTaskListener.finished(ev);
            } catch (ClientCodeException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                throw new ClientCodeException(e);
            }
        }

        @Override
        public String toString() {
            return wrappedToString(getClass(), clientTaskListener);
        }
    }

    // </editor-fold>
}
