/*
 * Copyright (c) 1997, 2017, Oracle and/or its affiliates. All rights reserved.
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

package jdk.javadoc.internal.doclets.formats.html;

import java.util.*;

import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

import com.sun.source.doctree.DocTree;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlConstants;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlStyle;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTag;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree;
import jdk.javadoc.internal.doclets.formats.html.markup.RawHtml;
import jdk.javadoc.internal.doclets.formats.html.markup.StringContent;
import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.PackageSummaryWriter;
import jdk.javadoc.internal.doclets.toolkit.util.CommentHelper;
import jdk.javadoc.internal.doclets.toolkit.util.DocFileIOException;
import jdk.javadoc.internal.doclets.toolkit.util.DocPath;
import jdk.javadoc.internal.doclets.toolkit.util.DocPaths;

/**
 * Class to generate file for each package contents in the right-hand
 * frame. This will list all the Class Kinds in the package. A click on any
 * class-kind will update the frame with the clicked class-kind page.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Atul M Dambalkar
 * @author Bhavesh Patel (Modified)
 */
public class PackageWriterImpl extends HtmlDocletWriter
    implements PackageSummaryWriter {

    /**
     * The prev package name in the alpha-order list.
     */
    protected PackageElement prev;

    /**
     * The next package name in the alpha-order list.
     */
    protected PackageElement next;

    /**
     * The package being documented.
     */
    protected PackageElement packageElement;

    /**
     * The HTML tree for main tag.
     */
    protected HtmlTree mainTree = HtmlTree.MAIN();

    /**
     * The HTML tree for section tag.
     */
    protected HtmlTree sectionTree = HtmlTree.SECTION();

    /**
     * Constructor to construct PackageWriter object and to generate
     * "package-summary.html" file in the respective package directory.
     * For example for package "java.lang" this will generate file
     * "package-summary.html" file in the "java/lang" directory. It will also
     * create "java/lang" directory in the current or the destination directory
     * if it doesn't exist.
     *
     * @param configuration the configuration of the doclet.
     * @param packageElement    PackageElement under consideration.
     * @param prev          Previous package in the sorted array.
     * @param next            Next package in the sorted array.
     */
    public PackageWriterImpl(HtmlConfiguration configuration,
            PackageElement packageElement, PackageElement prev, PackageElement next) {
        super(configuration, DocPath
                .forPackage(packageElement)
                .resolve(DocPaths.PACKAGE_SUMMARY));
        this.prev = prev;
        this.next = next;
        this.packageElement = packageElement;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getPackageHeader(String heading) {
        HtmlTree bodyTree = getBody(true, getWindowTitle(utils.getPackageName(packageElement)));
        HtmlTree htmlTree = (configuration.allowTag(HtmlTag.HEADER))
                ? HtmlTree.HEADER()
                : bodyTree;
        addTop(htmlTree);
        addNavLinks(true, htmlTree);
        if (configuration.allowTag(HtmlTag.HEADER)) {
            bodyTree.addContent(htmlTree);
        }
        HtmlTree div = new HtmlTree(HtmlTag.DIV);
        div.addStyle(HtmlStyle.header);
        if (configuration.showModules) {
            ModuleElement mdle = configuration.docEnv.getElementUtils().getModuleOf(packageElement);
            Content classModuleLabel = HtmlTree.SPAN(HtmlStyle.moduleLabelInPackage, contents.moduleLabel);
            Content moduleNameDiv = HtmlTree.DIV(HtmlStyle.subTitle, classModuleLabel);
            moduleNameDiv.addContent(Contents.SPACE);
            moduleNameDiv.addContent(getModuleLink(mdle,
                    new StringContent(mdle.getQualifiedName().toString())));
            div.addContent(moduleNameDiv);
        }
        Content annotationContent = new HtmlTree(HtmlTag.P);
        addAnnotationInfo(packageElement, annotationContent);
        div.addContent(annotationContent);
        Content tHeading = HtmlTree.HEADING(HtmlConstants.TITLE_HEADING, true,
                HtmlStyle.title, contents.packageLabel);
        tHeading.addContent(Contents.SPACE);
        Content packageHead = new StringContent(heading);
        tHeading.addContent(packageHead);
        div.addContent(tHeading);
        if (configuration.allowTag(HtmlTag.MAIN)) {
            mainTree.addContent(div);
        } else {
            bodyTree.addContent(div);
        }
        return bodyTree;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getContentHeader() {
        HtmlTree div = new HtmlTree(HtmlTag.DIV);
        div.addStyle(HtmlStyle.contentContainer);
        return div;
    }

    /**
     * Add the package deprecation information to the documentation tree.
     *
     * @param div the content tree to which the deprecation information will be added
     */
    public void addDeprecationInfo(Content div) {
        List<? extends DocTree> deprs = utils.getBlockTags(packageElement, DocTree.Kind.DEPRECATED);
        if (utils.isDeprecated(packageElement)) {
            CommentHelper ch = utils.getCommentHelper(packageElement);
            HtmlTree deprDiv = new HtmlTree(HtmlTag.DIV);
            deprDiv.addStyle(HtmlStyle.deprecatedContent);
            Content deprPhrase = HtmlTree.SPAN(HtmlStyle.deprecatedLabel, getDeprecatedPhrase(packageElement));
            deprDiv.addContent(deprPhrase);
            if (!deprs.isEmpty()) {
                List<? extends DocTree> commentTags = ch.getDescription(configuration, deprs.get(0));
                if (!commentTags.isEmpty()) {
                    addInlineDeprecatedComment(packageElement, deprs.get(0), deprDiv);
                }
            }
            div.addContent(deprDiv);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Content getSummaryHeader() {
        HtmlTree ul = new HtmlTree(HtmlTag.UL);
        ul.addStyle(HtmlStyle.blockList);
        return ul;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addClassesSummary(SortedSet<TypeElement> classes, String label,
            String tableSummary, List<String> tableHeader, Content summaryContentTree) {
        if(!classes.isEmpty()) {
            Content caption = getTableCaption(new RawHtml(label));
            Content table = (configuration.isOutputHtml5())
                    ? HtmlTree.TABLE(HtmlStyle.typeSummary, caption)
                    : HtmlTree.TABLE(HtmlStyle.typeSummary, tableSummary, caption);
            table.addContent(getSummaryTableHeader(tableHeader, "col"));
            Content tbody = new HtmlTree(HtmlTag.TBODY);
            boolean altColor = false;
            for (TypeElement klass : classes) {
                altColor = !altColor;
                if (!utils.isCoreClass(klass) ||
                    !configuration.isGeneratedDoc(klass)) {
                    continue;
                }
                Content classContent = getLink(new LinkInfoImpl(
                        configuration, LinkInfoImpl.Kind.PACKAGE, klass));
                Content thClass = HtmlTree.TH_ROW_SCOPE(HtmlStyle.colFirst, classContent);
                HtmlTree tr = HtmlTree.TR(thClass);
                tr.addStyle(altColor ? HtmlStyle.altColor : HtmlStyle.rowColor);

                HtmlTree tdClassDescription = new HtmlTree(HtmlTag.TD);
                tdClassDescription.addStyle(HtmlStyle.colLast);
                if (utils.isDeprecated(klass)) {
                    tdClassDescription.addContent(getDeprecatedPhrase(klass));
                    List<? extends DocTree> tags = utils.getDeprecatedTrees(klass);
                    if (!tags.isEmpty()) {
                        addSummaryDeprecatedComment(klass, tags.get(0), tdClassDescription);
                    }
                } else {
                    addSummaryComment(klass, tdClassDescription);
                }
                tr.addContent(tdClassDescription);
                tbody.addContent(tr);
            }
            table.addContent(tbody);
            Content li = HtmlTree.LI(HtmlStyle.blockList, table);
            summaryContentTree.addContent(li);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addPackageDescription(Content packageContentTree) {
        if (!utils.getBody(packageElement).isEmpty()) {
            Content tree = configuration.allowTag(HtmlTag.SECTION) ? sectionTree : packageContentTree;
            addDeprecationInfo(tree);
            addInlineComment(packageElement, tree);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addPackageTags(Content packageContentTree) {
        Content htmlTree = (configuration.allowTag(HtmlTag.SECTION))
                ? sectionTree
                : packageContentTree;
        addTagsInfo(packageElement, htmlTree);
        if (configuration.allowTag(HtmlTag.SECTION)) {
            packageContentTree.addContent(sectionTree);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addPackageContent(Content contentTree, Content packageContentTree) {
        if (configuration.allowTag(HtmlTag.MAIN)) {
            mainTree.addContent(packageContentTree);
            contentTree.addContent(mainTree);
        } else {
            contentTree.addContent(packageContentTree);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addPackageFooter(Content contentTree) {
        Content htmlTree = (configuration.allowTag(HtmlTag.FOOTER))
                ? HtmlTree.FOOTER()
                : contentTree;
        addNavLinks(false, htmlTree);
        addBottom(htmlTree);
        if (configuration.allowTag(HtmlTag.FOOTER)) {
            contentTree.addContent(htmlTree);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void printDocument(Content contentTree) throws DocFileIOException {
        printHtmlDocument(configuration.metakeywords.getMetaKeywords(packageElement),
                true, contentTree);
    }

    /**
     * Get "Use" link for this pacakge in the navigation bar.
     *
     * @return a content tree for the class use link
     */
    @Override
    protected Content getNavLinkClassUse() {
        Content useLink = getHyperLink(DocPaths.PACKAGE_USE,
                contents.useLabel, "", "");
        Content li = HtmlTree.LI(useLink);
        return li;
    }

    /**
     * Get "PREV PACKAGE" link in the navigation bar.
     *
     * @return a content tree for the previous link
     */
    @Override
    public Content getNavLinkPrevious() {
        Content li;
        if (prev == null) {
            li = HtmlTree.LI(contents.prevPackageLabel);
        } else {
            DocPath path = DocPath.relativePath(packageElement, prev);
            li = HtmlTree.LI(getHyperLink(path.resolve(DocPaths.PACKAGE_SUMMARY),
                contents.prevPackageLabel, "", ""));
        }
        return li;
    }

    /**
     * Get "NEXT PACKAGE" link in the navigation bar.
     *
     * @return a content tree for the next link
     */
    @Override
    public Content getNavLinkNext() {
        Content li;
        if (next == null) {
            li = HtmlTree.LI(contents.nextPackageLabel);
        } else {
            DocPath path = DocPath.relativePath(packageElement, next);
            li = HtmlTree.LI(getHyperLink(path.resolve(DocPaths.PACKAGE_SUMMARY),
                contents.nextPackageLabel, "", ""));
        }
        return li;
    }

    /**
     * Get "Tree" link in the navigation bar. This will be link to the package
     * tree file.
     *
     * @return a content tree for the tree link
     */
    @Override
    protected Content getNavLinkTree() {
        Content useLink = getHyperLink(DocPaths.PACKAGE_TREE,
                contents.treeLabel, "", "");
        Content li = HtmlTree.LI(useLink);
        return li;
    }

    /**
     * Get the module link.
     *
     * @return a content tree for the module link
     */
    @Override
    protected Content getNavLinkModule() {
        Content linkContent = getModuleLink(utils.elementUtils.getModuleOf(packageElement),
                contents.moduleLabel);
        Content li = HtmlTree.LI(linkContent);
        return li;
    }

    /**
     * Highlight "Package" in the navigation bar, as this is the package page.
     *
     * @return a content tree for the package link
     */
    @Override
    protected Content getNavLinkPackage() {
        Content li = HtmlTree.LI(HtmlStyle.navBarCell1Rev, contents.packageLabel);
        return li;
    }
}
