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
 * @run main HelloWorldTest
 */

import java.util.Arrays;

// verify that running a simple program, such as this one, does not trigger
// the loading of ToolProvider or any com.sun.tools.javac class
public class HelloWorldTest {
    public static void main(String... args) throws Exception {
        if (args.length > 0) {
            System.err.println(Arrays.toString(args));
            return;
        }

        new HelloWorldTest().run();
    }

    void run() throws Exception {
        ToolBox tb = new ToolBox();

        String classpath = System.getProperty("java.class.path");

        ToolBox.Result tr = tb.new JavaTask()
                .vmOptions("-verbose:class")
                .classpath(classpath)
                .className(HelloWorldTest.class.getName())
                .classArgs("Hello", "World")
                .run();

        if (tr.getOutput(ToolBox.OutputKind.STDOUT).contains("java.lang.Object")) {
            for (String line : tr.getOutputLines(ToolBox.OutputKind.STDOUT)) {
                System.err.println(line);
                if (line.contains("javax.tools.ToolProvider") || line.contains("com.sun.tools.javac."))
                    error(">>> " + line);
            }
        } else {
            tr.writeAll();
            error("verbose output not as expected");
        }

        if (errors > 0)
            throw new Exception(errors + " errors occurred");
    }

    void error(String msg) {
        System.err.println(msg);
        errors++;
    }

    int errors;
}
