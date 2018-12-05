/*
 * Copyright (c) 2010, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 6604599
 * @summary ToolProvider should be less compiler-specific
 * @library /tools/lib
 * @build ToolBox
 * @run main ToolProviderTest2
 */

import javax.tools.ToolProvider;
import java.util.List;

// control for ToolProviderTest1 -- verify that using ToolProvider to
// access the compiler does trigger loading com.sun.tools.javac.*
public class ToolProviderTest2 {
    public static void main(String... args) throws Exception {
        if (args.length > 0) {
            System.err.println(ToolProvider.getSystemJavaCompiler());
            return;
        }

        new ToolProviderTest2().run();
    }

    void run() throws Exception {
        ToolBox tb = new ToolBox();
        String classpath = System.getProperty("java.class.path");

        List<String> lines = tb.new JavaTask()
                .vmOptions("-verbose:class")
                .classpath(classpath)
                .className(getClass().getName())
                .classArgs("javax.tools.ToolProvider")
                .run()
                .getOutputLines(ToolBox.OutputKind.STDOUT);

        boolean found = false;
        for (String line : lines) {
            System.err.println(line);
            if (line.contains("com.sun.tools.javac."))
                found = true;
        }

        if (!found)
            error("expected class name not found");

        if (errors > 0)
            throw new Exception(errors + " errors occurred");
    }

    void error(String msg) {
        System.err.println(msg);
        errors++;
    }

    int errors;
}
