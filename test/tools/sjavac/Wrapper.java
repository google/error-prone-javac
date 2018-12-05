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

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;


public class Wrapper {
    public static void main(String... args) throws Exception {
        if (!isSJavacOnClassPath()) {
            System.out.println("sjavac not available: pass by default");
            return;
        }

        String testClassName = args[0];
        String[] testArgs = Arrays.copyOfRange(args, 1, args.length);

        File srcDir = new File(System.getProperty("test.src"));
        File clsDir = new File(System.getProperty("test.classes"));

        File src = new File(srcDir, testClassName + ".java");
        File cls = new File(clsDir, testClassName + ".class");

        if (cls.lastModified() < src.lastModified()) {
            System.err.println("Recompiling test class...");
            String[] javacArgs = { "-d", clsDir.getPath(), src.getPath() };
            int rc = com.sun.tools.javac.Main.compile(javacArgs);
            if (rc != 0)
                throw new Exception("compilation failed");
        }

        Class<?> sjavac = Class.forName(testClassName);
        Method main = sjavac.getMethod("main", String[].class);
        main.invoke(null, new Object[] { testArgs });
    }

    private static boolean isSJavacOnClassPath() {
        String cls = "com/sun/tools/sjavac/Main.class";
        return Wrapper.class.getClassLoader().getResource(cls) != null;
    }
}
