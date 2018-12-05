/*
 * Copyright (c) 2004, 2014, Oracle and/or its affiliates. All rights reserved.
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
 * @bug      4515705 4804296 4702454 4697036 8025633
 * @summary  Make sure that first sentence warning only appears once.
 *           Make sure that only warnings/errors are printed when quiet is used.
 *           Make sure that links to private/unincluded methods do not cause
 *           a "link unresolved" warning.
 *           Make sure error message starts with "error -".
 * @author   jamieh
 * @library  ../lib
 * @build    JavadocTester
 * @run main TestWarnings
 */

public class TestWarnings extends JavadocTester {
    public static void main(String... args) throws Exception  {
        TestWarnings tester = new TestWarnings();
        tester.runTests();
    }

    @Test
    void testDefault() {
        javadoc("-Xdoclint:none",
                "-d", "out-default",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.FAILED);  // TODO: investigate; suspect bad input HTML

        checkOutput(Output.WARNING, true,
                "X.java:11: warning - Missing closing '}' character for inline tag");
        checkOutput(Output.ERROR, true,
                "package.html: error - Body tag missing from HTML");

        checkOutput("pkg/X.html", false,
                "can't find m()");
        checkOutput("pkg/X.html", false,
                "can't find X()");
        checkOutput("pkg/X.html", false,
                "can't find f");
    }

    @Test
    void testPrivate() {
        javadoc("-Xdoclint:none",
                "-d", "out-private",
                "-private",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.FAILED);  // TODO: investigate; suspect bad input HTML

        checkOutput("pkg/X.html", true,
            "<a href=\"../pkg/X.html#m--\"><code>m()</code></a><br/>",
            "<a href=\"../pkg/X.html#X--\"><code>X()</code></a><br/>",
            "<a href=\"../pkg/X.html#f\"><code>f</code></a><br/>");
    }
}
