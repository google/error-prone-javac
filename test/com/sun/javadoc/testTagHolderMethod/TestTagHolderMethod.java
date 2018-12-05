/*
 * Copyright (c) 2002, 2014, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 4706525
 * @summary Determine if the new Tag.holder() method works properly.
 * @author jamieh
 * @library ../lib
 * @build JavadocTester
 * @build TestTagHolderMethod
 * @run main TestTagHolderMethod
 */

import com.sun.javadoc.*;

public class TestTagHolderMethod extends JavadocTester {

    /**
     * Doclet entry point.
     */
    public static boolean start(RootDoc root) throws Exception {
        ClassDoc[] classes = root.classes();
        for (int i = 0; i < classes.length; i++) {
            checkHolders(classes[i].fields());
            checkHolders(classes[i].constructors());
            checkHolders(classes[i].methods());
            checkHolders(classes[i].innerClasses());
        }
        return true;
    }

    private static void checkHolders(Doc[] holders) throws Exception {
        for (Doc holder : holders) {
            Tag[] tags = holder.tags();
            for (Tag tag : tags) {
                if (!tag.holder().name().equals(holder.name())) {
                    throw new Exception("The holder method does not return the correct Doc object.");
                } else {
                    System.out.println(tag.name() + " is held by " + holder.name());
                }
            }
        }
    }

    /**
     * The entry point of the test.
     * @param args the array of command line arguments.
     * @throws Exception if the test fails
     */
    public static void main(String... args) throws Exception {
        JavadocTester tester = new TestTagHolderMethod();
        tester.runTests();
    }

    @Test
    void test() {
        javadoc("-docletpath", testSrc, // unlikely to be effective
                "-doclet", "TestTagHolderMethod",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.OK);
    }
}
