/*
 * Copyright (c) 2005, 2014, Oracle and/or its affiliates. All rights reserved.
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
 * @bug      4494033 7028815 7052425 8007338 8023608 8008164 8016549
 * @summary  Run tests on doclet stylesheet.
 * @author   jamieh
 * @library  ../lib
 * @build    JavadocTester
 * @run main TestStylesheet
 */

public class TestStylesheet extends JavadocTester {

    public static void main(String... args) throws Exception {
        TestStylesheet tester = new TestStylesheet();
        tester.runTests();
    }

    @Test
    void test() {
        javadoc("-d", "out",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.OK);

        // TODO: most of this test seems a bit silly, since javadoc is simply
        // copying in the stylesheet from the source directory
        checkOutput("stylesheet.css", true,
                "/* Javadoc style sheet */",
                "/*\n"
                + "Overall document style\n"
                + "*/",
                "/*\n"
                + "Heading styles\n"
                + "*/",
                "/*\n"
                + "Navigation bar styles\n"
                + "*/",
                "body {\n"
                + "    background-color:#ffffff;\n"
                + "    color:#353833;\n"
                + "    font-family:'DejaVu Sans', Arial, Helvetica, sans-serif;\n"
                + "    font-size:14px;\n"
                + "    margin:0;\n"
                + "}",
                "ul {\n"
                + "    list-style-type:disc;\n"
                + "}",
                ".overviewSummary caption, .memberSummary caption, .typeSummary caption,\n"
                + ".useSummary caption, .constantsSummary caption, .deprecatedSummary caption {\n"
                + "    position:relative;\n"
                + "    text-align:left;\n"
                + "    background-repeat:no-repeat;\n"
                + "    color:#253441;\n"
                + "    font-weight:bold;\n"
                + "    clear:none;\n"
                + "    overflow:hidden;\n"
                + "    padding:0px;\n"
                + "    padding-top:10px;\n"
                + "    padding-left:1px;\n"
                + "    margin:0px;\n"
                + "    white-space:pre;\n"
                + "}",
                ".overviewSummary caption span, .memberSummary caption span, .typeSummary caption span,\n"
                + ".useSummary caption span, .constantsSummary caption span, .deprecatedSummary caption span {\n"
                + "    white-space:nowrap;\n"
                + "    padding-top:5px;\n"
                + "    padding-left:12px;\n"
                + "    padding-right:12px;\n"
                + "    padding-bottom:7px;\n"
                + "    display:inline-block;\n"
                + "    float:left;\n"
                + "    background-color:#F8981D;\n"
                + "    border: none;\n"
                + "    height:16px;\n"
                + "}",
                ".memberSummary caption span.activeTableTab span {\n"
                + "    white-space:nowrap;\n"
                + "    padding-top:5px;\n"
                + "    padding-left:12px;\n"
                + "    padding-right:12px;\n"
                + "    margin-right:3px;\n"
                + "    display:inline-block;\n"
                + "    float:left;\n"
                + "    background-color:#F8981D;\n"
                + "    height:16px;\n"
                + "}",
                ".memberSummary caption span.tableTab span {\n"
                + "    white-space:nowrap;\n"
                + "    padding-top:5px;\n"
                + "    padding-left:12px;\n"
                + "    padding-right:12px;\n"
                + "    margin-right:3px;\n"
                + "    display:inline-block;\n"
                + "    float:left;\n"
                + "    background-color:#4D7A97;\n"
                + "    height:16px;\n"
                + "}",
                ".memberSummary caption span.tableTab, .memberSummary caption span.activeTableTab {\n"
                + "    padding-top:0px;\n"
                + "    padding-left:0px;\n"
                + "    padding-right:0px;\n"
                + "    background-image:none;\n"
                + "    float:none;\n"
                + "    display:inline;\n"
                + "}",
                "@import url('resources/fonts/dejavu.css');");

        // Test whether a link to the stylesheet file is inserted properly
        // in the class documentation.
        checkOutput("pkg/A.html", true,
                "<link rel=\"stylesheet\" type=\"text/css\" "
                + "href=\"../stylesheet.css\" title=\"Style\">");

        checkOutput("stylesheet.css", false,
                "* {\n"
                + "    margin:0;\n"
                + "    padding:0;\n"
                + "}");
    }
}
