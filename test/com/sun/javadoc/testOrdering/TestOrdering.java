/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8039410 8042601 8042829 8049393 8050031
 * @summary test to determine if members are ordered correctly
 * @author ksrini
 * @library ../lib/
 * @build JavadocTester
 * @run main TestOrdering
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static java.nio.file.StandardOpenOption.*;

public class TestOrdering extends JavadocTester {

    public static void main(String[] args) throws Exception {
        TestOrdering tester = new TestOrdering();
        tester.runTests();
    }

    @Test
    void testUnnamedPackagesForClassUse() {
        javadoc("-d", "out",
                "-sourcepath", testSrc,
                "-use",
                testSrc("C.java"), testSrc("UsedInC.java"));
        checkExit(Exit.OK);
        checkExecutableMemberOrdering("class-use/UsedInC.html");
    }

    @Test
    void testNamedPackagesForClassUse() {
        javadoc("-d", "out-1",
                "-sourcepath", testSrc,
                "-use",
                "pkg1");
        checkExit(Exit.OK);
        checkClassUseOrdering("pkg1/class-use/UsedClass.html");
        checkOrder("pkg1/class-use/UsedClass.html", expectedClassUseMethodOrdering);
        checkOrder("pkg1/class-use/UsedClass.html", expectedClassUseWithTypeParams);
        checkOrder("pkg1/class-use/UsedClass.html", expectedInnerClassContructors);
    }

    enum ListOrder { NONE, REVERSE, SHUFFLE };
    /*
     * By default we do not shuffle the input list, in order to keep the list deterministic,
     * and the test predictable. However, we can turn on the stress mode, by setting the following
     * property if required.
     */
    static final ListOrder STRESS_MODE = Boolean.getBoolean("TestOrder.STRESS")
            ? ListOrder.SHUFFLE
            : ListOrder.REVERSE;

    /*
     * Controls the number of sibling packages,  pkg0, pkg1, pkg2, .....
     */
    static final int MAX_PACKAGES = 4;

    /*
     * Controls the number of children packages, pkg0, pkg0.pkg, pkg0.pkg.pkg, .....
     * Note: having too long a depth (> 256 chars on Windows), will likely lead to
     * cause problems with automated build and test systems.
     */
    static final int MAX_SUBPACKAGES_DEPTH = 4;
    @Test
    void testIndexOrdering() throws IOException {
        final String clsname = "Add";
        List<String> cmdArgs = new ArrayList();
        cmdArgs.add("-d");
        cmdArgs.add("out-2");
        cmdArgs.add("-sourcepath");
        cmdArgs.add("src");
        cmdArgs.add("-package");
        System.out.println("STRESS_MODE: " + STRESS_MODE);
        emitFile(null, clsname, STRESS_MODE);
        for (int width = 0 ; width < MAX_PACKAGES ; width++) {
            String wpkgname = "add" + width;
            String dpkgname = wpkgname;
            emitFile(wpkgname, clsname, ListOrder.NONE); // list as-is
            cmdArgs.add(wpkgname);
            for (int depth = 1 ; depth < MAX_SUBPACKAGES_DEPTH ; depth++) {
                dpkgname = dpkgname + ".add";
                emitFile(dpkgname, clsname, STRESS_MODE);
                cmdArgs.add(dpkgname);
            }
        }
        File srcDir = new File(new File("."), "src");
        cmdArgs.add(new File(srcDir, clsname + ".java").getPath());
        javadoc(cmdArgs.toArray(new String[cmdArgs.size()]));
        checkExit(Exit.OK);
        checkOrder("index-all.html", composeTestVectors());
        checkOrder("add0/add/package-tree.html", expectedPackageTreeOrdering);
        checkOrder("overview-tree.html", expectedOverviewOrdering);
    }

    @Test
    void testIndexTypeClustering() {
        javadoc("-d", "out-3",
                "-sourcepath", testSrc("src-2"),
                "-use",
                "a",
                "b",
                "e",
                "something");
        checkOrder("index-all.html", typeTestVectors);
        checkExit(Exit.OK);
    }
    String[] typeTestVectors = {
        "something</a> - package something</dt>",
        "something</span></a> - Class in",
        "something</span></a> - Enum in",
        "something</span></a> - Interface in",
        "something</span></a> - Annotation Type in",
        "something</a></span> - Variable in class",
        "something()</a></span> - Constructor",
        "something()</a></span> - Method in class a.<a href=\"a/A.html\"",
        "something()</a></span> - Method in class a.<a href=\"a/something.html\"",
        "something()</a></span> - Method in class something.<a href=\"something/J.html\""
    };
    String[] composeTestVectors() {
        List<String> testList = new ArrayList<>();

        for (String x : expectedMethodOrdering) {
            testList.add(x);
            for (int i = 0; i < MAX_PACKAGES; i++) {
                String wpkg = "add" + i;
                testList.add(wpkg + "/" + x);
                String dpkg = wpkg;
                for (int j = 1; j < MAX_SUBPACKAGES_DEPTH; j++) {
                    dpkg = dpkg + "/" + "add";
                    testList.add(dpkg + "/" + x);
                }
            }
        }
        for (String x : expectedEnumOrdering) {
            testList.add(x.replace("REPLACE_ME", "&lt;Unnamed&gt;"));
            for (int i = 0; i < MAX_PACKAGES; i++) {
                String wpkg = "add" + i;
                testList.add(wpkg + "/" + x.replace("REPLACE_ME", wpkg));
                String dpkg = wpkg;
                for (int j = 1; j < MAX_SUBPACKAGES_DEPTH; j++) {
                    dpkg = dpkg + "/" + "add";
                    testList.add(dpkg + "/" + x.replace("REPLACE_ME", pathToPackage(dpkg)));
                }
            }
        }
        testList.addAll(Arrays.asList(expectedFieldOrdering));
        return testList.toArray(new String[testList.size()]);
    }
    void checkExecutableMemberOrdering(String usePage) {
        String contents = readFile(usePage);
        // check constructors
        checking("constructors");
        int idx1 = contents.indexOf("C.html#C-UsedInC");
        int idx2 = contents.indexOf("C.html#C-UsedInC-int");
        int idx3 = contents.indexOf("C.html#C-UsedInC-java.lang.String");
        if (idx1 == -1 || idx2 == -1 || idx3 == -1) {
            failed("ctor strings not found");
        } else if (idx1 > idx2 || idx2 > idx3 || idx1 > idx3) {
            failed("ctor strings are out of order");
        } else
            passed("ctor strings are in order");

        // check methods
        checking("methods");
        idx1 = contents.indexOf("C.html#ymethod-int");
        idx2 = contents.indexOf("C.html#ymethod-java.lang.String");
        if (idx1 == -1 || idx2 == -1) {
            failed("#ymethod strings not found");
        } else if (idx1 > idx2) {
            failed("#ymethod strings are out of order");
        } else
            passed("Executable Member Ordering: OK");
    }

    void checkClassUseOrdering(String usePage) {
        checkClassUseOrdering(usePage, "pkg1/C#ITERATION#.html#zfield");
        checkClassUseOrdering(usePage, "pkg1/C#ITERATION#.html#fieldInC#ITERATION#");
        checkClassUseOrdering(usePage, "pkg1/C#ITERATION#.html#zmethod-pkg1.UsedClass");
        checkClassUseOrdering(usePage, "pkg1/C#ITERATION#.html#methodInC#ITERATION#");
    }

    void checkClassUseOrdering(String usePage, String searchString) {
        String contents = readFile(usePage);
        int lastidx = 0;
        System.out.println("testing for " + searchString);
        for (int i = 1; i < 5; i++) {
            String s = searchString.replaceAll("#ITERATION#", Integer.toString(i));
            checking(s);
            int idx = contents.indexOf(s);
            if (idx < lastidx) {
                failed(s + ", member ordering error, last:" + lastidx + ", got:" + idx);
            } else {
                passed("\tlast: " + lastidx + " got:" + idx);
            }
            lastidx = idx;
        }
    }

    static String[] contents = {
        "public add ADDADD;",
        "public add AddAdd;",
        "public add addadd;",
        "public enum add {add, ADD, addd, ADDD};",
        "public enum ADD {ADD, add, addd, ADDD};",
        "public void   add(){}",
        "public void   add(double d){}",
        "public void   add(int i, float f){}",
        "public void   add(float f, int i){}",
        "public void   add(double d, byte b){}",
        "public Double add(Double d) {return (double) 22/7;}",
        "public double add(double d1, double d2) {return d1 + d2;}",
        "public double add(double d1, Double  d2) {return d1 + d2;}",
        "public Float  add(float f) {return (float) 22/7;}",
        "public void   add(int i){}",
        "public int    add(Integer i) {return 0;}"
    };

    void emitFile(String pkgname, String clsname, ListOrder order) throws IOException {
        File srcDir = new File("src");
        File outDir = pkgname == null
            ? srcDir
            : new File(srcDir, pkgname.replace(".", File.separator));
        File outFile = new File(outDir, clsname + ".java");
        outDir.mkdirs();
        List<String> scratch = new ArrayList<>(Arrays.asList(contents));
        switch (order) {
            case SHUFFLE:
                Collections.shuffle(scratch);
                break;
            case REVERSE:
                Collections.reverse(scratch);
                break;
            default:
                // leave list as-is
        }
        // insert the header
        scratch.add(0, "public class " + clsname + " {");
        if (pkgname != null) {
            scratch.add(0, "package " + pkgname + ";");
        }
        // append the footer
        scratch.add("}");
        Files.write(outFile.toPath(), scratch, CREATE, TRUNCATE_EXISTING);
    }

    String pathToPackage(String in) {
        return in.replace("/", ".");
    }

    final String expectedInnerClassContructors[] = {
        "../../pkg1/A.html#A-pkg1.UsedClass-",
        "../../pkg1/B.A.html#A-pkg1.UsedClass-",
        "../../pkg1/B.html#B-pkg1.UsedClass-",
        "../../pkg1/A.C.html#C-pkg1.UsedClass-java.lang.Object:A-",
        "../../pkg1/A.C.html#C-pkg1.UsedClass-java.util.Collection-",
        "../../pkg1/A.C.html#C-pkg1.UsedClass-java.util.List-"
    };

    final String expectedClassUseMethodOrdering[] = {
        "../../pkg1/MethodOrder.html#m--",
        "../../pkg1/MethodOrder.html#m-byte:A-",
        "../../pkg1/MethodOrder.html#m-double-",
        "../../pkg1/MethodOrder.html#m-double-double-",
        "../../pkg1/MethodOrder.html#m-double-java.lang.Double-",
        "../../pkg1/MethodOrder.html#m-int-",
        "../../pkg1/MethodOrder.html#m-int-int-",
        "../../pkg1/MethodOrder.html#m-int-java.lang.Integer-",
        "../../pkg1/MethodOrder.html#m-java.lang.Double-",
        "../../pkg1/MethodOrder.html#m-java.lang.Double-double-",
        "../../pkg1/MethodOrder.html#m-java.lang.Double-java.lang.Double-",
        "../../pkg1/MethodOrder.html#m-java.lang.Integer-",
        "../../pkg1/MethodOrder.html#m-java.lang.Integer-int-",
        "../../pkg1/MethodOrder.html#m-java.lang.Integer-java.lang.Integer-",
        "../../pkg1/MethodOrder.html#m-java.lang.Object:A-",
        "../../pkg1/MethodOrder.html#m-java.util.ArrayList-",
        "../../pkg1/MethodOrder.html#m-java.util.Collection-",
        "../../pkg1/MethodOrder.html#m-java.util.List-"
    };
    final String expectedClassUseWithTypeParams[] = {
        "../../pkg1/MethodOrder.html#tpm-pkg1.UsedClass-",
        "../../pkg1/MethodOrder.html#tpm-pkg1.UsedClass-pkg1.UsedClass-",
        "../../pkg1/MethodOrder.html#tpm-pkg1.UsedClass-pkg1.UsedClass:A-",
        "../../pkg1/MethodOrder.html#tpm-pkg1.UsedClass-java.lang.String-"
    };
    final String expectedMethodOrdering[] = {
        "Add.html#add--",
        "Add.html#add-double-",
        "Add.html#add-double-byte-",
        "Add.html#add-double-double-",
        "Add.html#add-double-java.lang.Double-",
        "Add.html#add-float-",
        "Add.html#add-float-int-",
        "Add.html#add-int-",
        "Add.html#add-int-float-",
        "Add.html#add-java.lang.Double-",
        "Add.html#add-java.lang.Integer-"
    };
    final String expectedEnumOrdering[] = {
        "Add.add.html\" title=\"enum in REPLACE_ME\"",
        "Add.ADD.html\" title=\"enum in REPLACE_ME\""
    };
    final String expectedFieldOrdering[] = {
        "Add.html#addadd\"",
        "add0/add/add/add/Add.html#addadd\"",
        "add0/add/add/Add.html#addadd\"",
        "add0/add/Add.html#addadd\"",
        "add0/Add.html#addadd\"",
        "add1/add/add/add/Add.html#addadd\"",
        "add1/add/add/Add.html#addadd\"",
        "add1/add/Add.html#addadd\"",
        "add1/Add.html#addadd\"",
        "add2/add/add/add/Add.html#addadd\"",
        "add2/add/add/Add.html#addadd\"",
        "add2/add/Add.html#addadd\"",
        "add2/Add.html#addadd\"",
        "add3/add/add/add/Add.html#addadd\"",
        "add3/add/add/Add.html#addadd\"",
        "add3/add/Add.html#addadd\"",
        "add3/Add.html#addadd\"",
        "Add.html#AddAdd\"",
        "add0/add/add/add/Add.html#AddAdd\"",
        "add0/add/add/Add.html#AddAdd\"",
        "add0/add/Add.html#AddAdd\"",
        "add0/Add.html#AddAdd\"",
        "add1/add/add/add/Add.html#AddAdd\"",
        "add1/add/add/Add.html#AddAdd\"",
        "add1/add/Add.html#AddAdd\"",
        "add1/Add.html#AddAdd\"",
        "add2/add/add/add/Add.html#AddAdd\"",
        "add2/add/add/Add.html#AddAdd\"",
        "add2/add/Add.html#AddAdd\"",
        "add2/Add.html#AddAdd\"",
        "add3/add/add/add/Add.html#AddAdd\"",
        "add3/add/add/Add.html#AddAdd\"",
        "add3/add/Add.html#AddAdd\"",
        "add3/Add.html#AddAdd\"",
        "Add.html#ADDADD\"",
        "add0/add/add/add/Add.html#ADDADD\"",
        "add0/add/add/Add.html#ADDADD\"",
        "add0/add/Add.html#ADDADD\"",
        "add0/Add.html#ADDADD\"",
        "add1/add/add/add/Add.html#ADDADD\"",
        "add1/add/add/Add.html#ADDADD\"",
        "add1/add/Add.html#ADDADD\"",
        "add1/Add.html#ADDADD\"",
        "add2/add/add/add/Add.html#ADDADD\"",
        "add2/add/add/Add.html#ADDADD\"",
        "add2/add/Add.html#ADDADD\"",
        "add2/Add.html#ADDADD\"",
        "add3/add/add/add/Add.html#ADDADD\"",
        "add3/add/add/Add.html#ADDADD\"",
        "add3/add/Add.html#ADDADD\"",
        "add3/Add.html#ADDADD\""
    };
    final String expectedPackageTreeOrdering[] = {
        "<a href=\"../../add0/add/Add.add.html\" title=\"enum in add0.add\">",
        "<a href=\"../../add0/add/Add.ADD.html\" title=\"enum in add0.add\">"
    };
    final String expectedOverviewOrdering[] = {
        "<a href=\"Add.add.html\" title=\"enum in &lt;Unnamed&gt;\">",
        "<a href=\"add0/Add.add.html\" title=\"enum in add0\">",
        "<a href=\"add0/add/Add.add.html\" title=\"enum in add0.add\">",
        "<a href=\"add0/add/add/Add.add.html\" title=\"enum in add0.add.add\">",
        "<a href=\"add0/add/add/add/Add.add.html\" title=\"enum in add0.add.add.add\">",
        "<a href=\"add1/Add.add.html\" title=\"enum in add1\">",
        "<a href=\"add1/add/Add.add.html\" title=\"enum in add1.add\">",
        "<a href=\"add1/add/add/Add.add.html\" title=\"enum in add1.add.add\">",
        "<a href=\"add1/add/add/add/Add.add.html\" title=\"enum in add1.add.add.add\">",
        "<a href=\"add2/Add.add.html\" title=\"enum in add2\">",
        "<a href=\"add2/add/Add.add.html\" title=\"enum in add2.add\">",
        "<a href=\"add2/add/add/Add.add.html\" title=\"enum in add2.add.add\">",
        "<a href=\"add2/add/add/add/Add.add.html\" title=\"enum in add2.add.add.add\">",
        "<a href=\"add3/Add.add.html\" title=\"enum in add3\">",
        "<a href=\"add3/add/Add.add.html\" title=\"enum in add3.add\">",
        "<a href=\"add3/add/add/Add.add.html\" title=\"enum in add3.add.add\">",
        "<a href=\"add3/add/add/add/Add.add.html\" title=\"enum in add3.add.add.add\">",
        "<a href=\"Add.ADD.html\" title=\"enum in &lt;Unnamed&gt;\">",
        "<a href=\"add0/Add.ADD.html\" title=\"enum in add0\">",
        "<a href=\"add0/add/Add.ADD.html\" title=\"enum in add0.add\">",
        "<a href=\"add0/add/add/Add.ADD.html\" title=\"enum in add0.add.add\">",
        "<a href=\"add0/add/add/add/Add.ADD.html\" title=\"enum in add0.add.add.add\">",
        "<a href=\"add1/Add.ADD.html\" title=\"enum in add1\">",
        "<a href=\"add1/add/Add.ADD.html\" title=\"enum in add1.add\">",
        "<a href=\"add1/add/add/Add.ADD.html\" title=\"enum in add1.add.add\">",
        "<a href=\"add1/add/add/add/Add.ADD.html\" title=\"enum in add1.add.add.add\">",
        "<a href=\"add2/Add.ADD.html\" title=\"enum in add2\">",
        "<a href=\"add2/add/Add.ADD.html\" title=\"enum in add2.add\">",
        "<a href=\"add2/add/add/Add.ADD.html\" title=\"enum in add2.add.add\">",
        "<a href=\"add2/add/add/add/Add.ADD.html\" title=\"enum in add2.add.add.add\">",
        "<a href=\"add3/Add.ADD.html\" title=\"enum in add3\">",
        "<a href=\"add3/add/Add.ADD.html\" title=\"enum in add3.add\">",
        "<a href=\"add3/add/add/Add.ADD.html\" title=\"enum in add3.add.add\">",
        "<a href=\"add3/add/add/add/Add.ADD.html\" title=\"enum in add3.add.add.add\">",
    };
}
