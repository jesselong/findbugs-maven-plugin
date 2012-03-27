package net.rumati.maven.plugins.findbugs;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @goal findbugs
 * @execute phase="compile"
 * @requiresDependencyResolution compile
 * @requiresProject
 */
public class FindBugsReport
        extends AbstractMavenReport
{
    /**
     * @parameter default-value="${project.reporting.outputDirectory}"
     * @required
     * @readonly
     */
    private File outputDirectory;
    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;
    /**
     * @component
     */
    private Renderer siteRenderer;
    /**
     * @parameter
     */
    private String xrefPath;
    /**
     * @parameter default-value="medium"
     */
    private String threshold;
    /**
     * @parameter default-value="default"
     */
    private String effort;

    @Override
    protected Renderer getSiteRenderer()
    {
        return siteRenderer;
    }

    @Override
    protected String getOutputDirectory()
    {
        return outputDirectory.getAbsolutePath();
    }

    @Override
    protected MavenProject getProject()
    {
        return project;
    }

    private void writeFindBugsProjectFile(MavenProject project, File outputFile)
            throws ParserConfigurationException, TransformerException
    {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element projectElement = doc.createElement("Project");
        doc.appendChild(projectElement);
        projectElement.setAttribute("projectName", project.getName());

        Element jar = doc.createElement("Jar");
        jar.setTextContent(project.getBuild().getOutputDirectory());
        projectElement.appendChild(jar);

        for (Object o : project.getCompileSourceRoots()){
            Element el = doc.createElement("SrcDir");
            el.setTextContent(o.toString());
            projectElement.appendChild(el);
        }

        for (Object o : project.getArtifacts()){
            Artifact a = (Artifact)o;
            Element el = doc.createElement("AuxClasspathEntry");
            el.setTextContent(a.getFile().getAbsolutePath());
            projectElement.appendChild(el);
        }

        Transformer trans = TransformerFactory.newInstance().newTransformer();
        trans.setOutputProperty(OutputKeys.METHOD, "xml");
        trans.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        trans.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        trans.setOutputProperty(OutputKeys.INDENT, "yes");
        trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        trans.transform(new DOMSource(doc), new StreamResult(outputFile));
    }

    @Override
    protected void executeReport(Locale locale)
            throws MavenReportException
    {
        try{
            File tmpFile = File.createTempFile("findbugs", ".fbp");
            try{
                writeFindBugsProjectFile(project, tmpFile);
                File outputFile = File.createTempFile("findbug", ".xml");
                try{
                    String bugPriorityLevel = null;
                    if (threshold.toLowerCase().equals("low")){
                        bugPriorityLevel = "-low";
                    }else if (threshold.toLowerCase().equals("medium")){
                        bugPriorityLevel = "-medium";
                    }else if (threshold.toLowerCase().equals("high")){
                        bugPriorityLevel = "-high";
                    }else{
                        throw new MavenReportException("Unkown threshold: " + threshold);
                    }

                    String effortArg = null;
                    if (effort.toLowerCase().equals("min")){
                        effortArg = "-effort:min";
                    }else if (effort.toLowerCase().equals("less")){
                        effortArg = "-effort:less";
                    }else if (effort.toLowerCase().equals("default")){
                        effortArg = "-effort:default";
                    }else if (effort.toLowerCase().equals("more")){
                        effortArg = "-effort:more";
                    }else if (effort.toLowerCase().equals("max")){
                        effortArg = "-effort:max";
                    }
                    edu.umd.cs.findbugs.LaunchAppropriateUI.main(new String[]{ "-textui", effortArg, bugPriorityLevel, "-project",
                                                                               tmpFile.getAbsolutePath(), "-xml:withMessages",
                                                                               "-output", outputFile.getAbsolutePath() });
                    Document doc =
                            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(outputFile);
                    XPath xpath = XPathFactory.newInstance().newXPath();

                    Sink sink = getSink();

                    sink.head();
                    sink.title();
                    sink.text("FindBugs Report");
                    sink.title_();
                    sink.head_();

                    sink.body();

                    sink.section1();
                    sink.sectionTitle1();
                    sink.text("FindBugs Report");
                    sink.sectionTitle1_();

                    sink.paragraph();
                    sink.text("This is a report of possible bugs found by the ");
                    sink.link("http://findbugs.sourceforge.net/");
                    sink.text("FindBugs");
                    sink.link_();
                    sink.text(" program, which uses static analysis to find bugs in Java code. The report was generated with the following parameters:");
                    sink.paragraph_();

                    sink.text("FindBugs version: " + xpath.evaluate("/BugCollection/attribute::version", doc));
                    sink.lineBreak();
                    sink.text("Effort: " + effort.toLowerCase());
                    sink.lineBreak();
                    sink.text("Bug Priority Threshold: " + threshold.toLowerCase());

                    sink.section1_();

                    doSummary(sink, xpath, doc);

                    doBugsByClassReport(sink, doc, xpath);

                    doBugsByCategoryReport(sink, doc, xpath);

                    sink.body_();

                    sink.flush();
                    sink.close();
                }finally{
                    outputFile.delete();
                }
            }finally{
                tmpFile.delete();
            }
        }catch (Exception e){
            throw new MavenReportException("Error creating report", e);
        }
    }

    private boolean doSummary(Sink sink, XPath xpath, Document doc)
            throws XPathExpressionException
    {
        sink.section1();
        sink.sectionTitle1();
        sink.text("Summary");
        sink.sectionTitle1_();
        try{

            String totalBugs = xpath.evaluate("/BugCollection/FindBugsSummary/attribute::total_bugs", doc);
            if (totalBugs.equals("0")){
                sink.paragraph();
                sink.text("No bugs were found!");
                sink.paragraph_();
                return false;
            }

            String p1 = xpath.evaluate("/BugCollection/FindBugsSummary/attribute::priority_1", doc);
            String p2 = xpath.evaluate("/BugCollection/FindBugsSummary/attribute::priority_2", doc);
            String p3 = xpath.evaluate("/BugCollection/FindBugsSummary/attribute::priority_3", doc);
            boolean hasP1 = false;
            if (p1 != null && p1.length() > 0 && !p1.equals("0")){
                hasP1 = true;
            }
            boolean hasP2 = false;
            if (p2 != null && p2.length() > 0 && !p2.equals("0")){
                hasP2 = true;
            }
            boolean hasP3 = false;
            if (p3 != null && p3.length() > 0 && !p3.equals("0")){
                hasP3 = true;
            }
            sink.paragraph();
            sink.bold();
            sink.text(totalBugs);
            sink.bold_();
            sink.text(" bug");
            if (Integer.parseInt(totalBugs) > 1){
                sink.text("s");
            }
            if (hasP1){
                sink.text(", consisting of ");
                sink.bold();
                sink.text(p1);
                sink.bold_();
                sink.text(" ");
                sink.italic();
                sink.text("High");
                sink.italic_();
                sink.text(" priority bug");
                if (Integer.parseInt(p1) > 1){
                    sink.text("s");
                }
            }
            if (hasP2){
                if (!hasP1){
                    sink.text(", consisting of ");
                }else{
                    if (hasP3){
                        sink.text(", ");
                    }else{
                        sink.text(" and ");
                    }
                }
                sink.bold();
                sink.text(p2);
                sink.bold_();
                sink.text(" ");
                sink.italic();
                sink.text("Medium");
                sink.italic_();
                sink.text(" priority bug");
                if (Integer.parseInt(p2) > 1){
                    sink.text("s");
                }
            }
            if (p3 != null && p3.length() > 0 && !p3.equals("0")){
                if (hasP1 || hasP2){
                    sink.text(" and ");
                }else{
                    sink.text(", consisting of ");
                }
                sink.bold();
                sink.text(p3);
                sink.bold_();
                sink.text(" ");
                sink.italic();
                sink.text("Low");
                sink.italic_();
                sink.text(" priority bug");
                if (Integer.parseInt(p3) > 1){
                    sink.text("s");
                }
            }
            sink.text(" found in ");
            sink.bold();
            sink.text(xpath.evaluate("/BugCollection/FindBugsSummary/attribute::total_size", doc));
            sink.bold_();
            sink.text(" lines of code, in ");
            sink.bold();
            sink.text(xpath.evaluate("/BugCollection/FindBugsSummary/attribute::total_classes", doc));
            sink.bold_();
            sink.text(" classes, in ");
            sink.bold();
            sink.text(xpath.evaluate("/BugCollection/FindBugsSummary/attribute::num_packages", doc));
            sink.bold_();
            sink.text(" package(s).");
            sink.paragraph_();

            sink.paragraph();
            sink.text("Here are some entry points to the report:");
            sink.paragraph_();

            sink.list();
            sink.listItem();
            sink.link("#report.BugsByClass");
            sink.text("Bugs by class");
            sink.link_();
            sink.listItem_();
            sink.listItem();
            sink.link("#report.BugsByCategory");
            sink.text("Bugs by category");
            sink.link_();
            sink.listItem_();
            sink.list_();
        }finally{
            sink.section1_();
        }
        return true;
    }

    public String getOutputName()
    {
        return "findbugs";
    }

    public String getName(Locale locale)
    {
        return "FindBugs Report";
    }

    public String getDescription(Locale locale)
    {
        return "Source code static analysis and bug report";
    }

    private void doBugsByClassReport(Sink sink, Document doc, XPath xpath)
            throws XPathExpressionException, UnsupportedEncodingException
    {
        sink.section1();
        sink.sectionTitle1();
        sink.text("Bugs By Class");
        sink.anchor("report.BugsByClass");
        sink.sectionTitle1_();
        sink.paragraph();
        sink.text("This is a list of bugs, by class.");
        sink.paragraph_();

        sink.table();
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text("Package");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Classes");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Lines");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Bugs");
        sink.tableHeaderCell_();
        sink.tableRow_();

        NodeList packageNodes =
                (NodeList)xpath.evaluate("/BugCollection/FindBugsSummary/PackageStats[@total_bugs>0]", doc, XPathConstants.NODESET);
        for (int packageNo = 0; packageNo < packageNodes.getLength(); packageNo++){
            Node packageNode = packageNodes.item(packageNo);
            String packageName = xpath.evaluate("attribute::package", packageNode);
            sink.tableRow();
            sink.tableCell();
            sink.link("#package." + packageName);
            sink.text(packageName);
            sink.link_();
            sink.tableCell_();
            sink.tableCell();
            sink.text(xpath.evaluate("attribute::total_types", packageNode));
            sink.tableCell_();
            sink.tableCell();
            sink.text(xpath.evaluate("attribute::total_size", packageNode));
            sink.tableCell_();
            sink.tableCell();
            sink.text(xpath.evaluate("attribute::total_bugs", packageNode));
            sink.tableCell_();
            sink.tableRow_();
        }
        sink.table_();

        for (int packageNo = 0; packageNo < packageNodes.getLength(); packageNo++){
            Node packageNode = packageNodes.item(packageNo);
            String packageName = xpath.evaluate("attribute::package", packageNode);
            sink.section2();
            sink.sectionTitle2();
            sink.anchor("package." + packageName);
            sink.text("Package: " + packageName);
            sink.sectionTitle2_();

            sink.table();
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text("Class Name");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Lines");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Bugs");
            sink.tableHeaderCell_();
            sink.tableRow_();

            NodeList classNodes = (NodeList)xpath.evaluate("child::*[@bugs>0]", packageNode, XPathConstants.NODESET);
            for (int classNo = 0; classNo < classNodes.getLength(); classNo++){
                Node classNode = classNodes.item(classNo);
                String className = xpath.evaluate("attribute::class", classNode);
                String bugs = xpath.evaluate("attribute::bugs", classNode);
                String lines = xpath.evaluate("attribute::size", classNode);
                sink.tableRow();
                sink.tableCell();
                sink.link("#class." + className);
                sink.text(className);
                sink.link_();
                sink.tableCell_();
                sink.tableCell();
                sink.text(lines);
                sink.tableCell_();
                sink.tableCell();
                sink.text(bugs);
                sink.tableCell_();
                sink.tableRow_();
            }
            sink.table_();

            for (int classNo = 0; classNo < classNodes.getLength(); classNo++){
                Node classNode = classNodes.item(classNo);
                String className = xpath.evaluate("attribute::class", classNode);
                sink.section3();
                sink.sectionTitle3();
                sink.rawText("<a name=\"class." + className + "\"/>");
                sink.text("Class: " + className);
                sink.sectionTitle3_();

                sink.table();
                sink.tableRow();
                sink.tableHeaderCell();
                sink.text("Category");
                sink.tableHeaderCell_();
                sink.tableHeaderCell();
                sink.text("Lines");
                sink.tableHeaderCell_();
                sink.tableHeaderCell();
                sink.text("Bug");
                sink.tableHeaderCell_();
                sink.tableHeaderCell();
                sink.text("Details");
                sink.tableHeaderCell_();
                sink.tableHeaderCell();
                sink.text("Priority");
                sink.tableHeaderCell_();
                sink.tableRow_();

                NodeList bugNodes = (NodeList)xpath.evaluate("/BugCollection/BugInstance[child::Class/attribute::classname=\""
                        + className + "\" and child::Class/attribute::primary=\"true\"]", doc, XPathConstants.NODESET);
                for (int bugNo = 0; bugNo < bugNodes.getLength(); bugNo++){
                    Node bugNode = bugNodes.item(bugNo);
                    String categoryCode = xpath.evaluate("attribute::category", bugNode);
                    String categoryName = xpath.evaluate("/BugCollection/BugCategory[@category=\"" + categoryCode
                            + "\"]/Description", doc);
                    sink.tableRow();
                    sink.tableCell();
                    sink.text(categoryName);
                    sink.tableCell_();
                    sink.tableCell();
                    String start = xpath.evaluate("child::SourceLine/attribute::start", bugNode);
                    String end = xpath.evaluate("child::SourceLine/attribute::end", bugNode);
                    if (xrefPath != null){
                        sink.rawText(getXrefLink(className, start, end));
                    }else{
                        if (start.equals(end)){
                            sink.text(start);
                        }else{
                            sink.text(start + "-" + end);
                        }
                    }
                    sink.tableCell_();
                    sink.tableCell();
                    sink.text(xpath.evaluate("LongMessage", bugNode));
                    sink.tableCell_();
                    sink.tableCell();
                    sink.link("#type." + xpath.evaluate("attribute::type", bugNode));
                    sink.text("Details");
                    sink.link_();
                    sink.tableCell_();
                    sink.tableCell();
                    String priority = xpath.evaluate("attribute::priority", bugNode);
                    if (priority.equals("1")){
                        priority = "High";
                    }else if (priority.equals("2")){
                        priority = "Medium";
                    }else{
                        priority = "Low";
                    }
                    sink.text(priority);
                    sink.tableCell_();
                    sink.tableRow_();
                }
                sink.table_();

                sink.section3_();
            }

            sink.section2_();
        }

        sink.section1_();
    }

    private String getXrefLink(String className, String lineStart, String lineEnd)
    {
        if (!xrefPath.endsWith("/")){
            xrefPath = xrefPath + "/";
        }

        className = className.replace(".", "/");
        int idx = className.indexOf("$");
        if (idx >= 0){
            className = className.substring(0, idx);
        }
        String link = "<a href=\"" + xrefPath + className + ".html";
        link += "#" + lineStart;
        link += "\">" + lineStart;
        if (!lineStart.equals(lineEnd)){
            link += "-" + lineEnd;
        }
        link += "</a>";
        return link;
    }

    private void doBugsByCategoryReport(Sink sink, Document doc, XPath xpath)
            throws XPathExpressionException, UnsupportedEncodingException
    {
        sink.section1();
        sink.sectionTitle1();
        sink.text("Bugs By Category");
        sink.anchor("report.BugsByCategory");
        sink.sectionTitle1_();
        sink.paragraph();
        sink.text("This is a list of bugs, by category.");
        sink.paragraph_();

        sink.table();
        sink.tableRow();
        sink.tableHeaderCell();
        sink.text("Category / Bug Pattern");
        sink.tableHeaderCell_();
        sink.tableHeaderCell();
        sink.text("Bugs");
        sink.tableHeaderCell_();
        sink.tableRow_();
        NodeList categoryNodes = (NodeList)xpath.evaluate("/BugCollection/BugCategory", doc, XPathConstants.NODESET);
        for (int categoryNo = 0; categoryNo < categoryNodes.getLength(); categoryNo++){
            Node categoryNode = categoryNodes.item(categoryNo);
            String categoryCode = xpath.evaluate("attribute::category", categoryNode);
            String categoryDescription = xpath.evaluate("Description", categoryNode);
            sink.tableRow();
            sink.tableCell();
            sink.bold();
            sink.link("#category." + categoryCode);
            sink.text(categoryDescription);
            sink.link_();
            sink.bold_();
            sink.tableCell_();
            sink.tableCell();
            sink.bold();
            sink.text(""
                    + ((NodeList)xpath.evaluate("/BugCollection/BugInstance[@category=\"" + categoryCode + "\"]", doc, XPathConstants.NODESET)).getLength());
            sink.bold_();
            sink.tableCell_();
            sink.tableRow_();
            NodeList typeNodes =
                    (NodeList)xpath.evaluate("/BugCollection/BugPattern[@category=\"" + categoryCode + "\"]", doc, XPathConstants.NODESET);
            for (int typeNo = 0; typeNo < typeNodes.getLength(); typeNo++){
                Node typeNode = typeNodes.item(typeNo);
                String typeCode = xpath.evaluate("attribute::type", typeNode);
                String typeDescription = xpath.evaluate("ShortDescription", typeNode);
                sink.tableRow();
                sink.tableCell();
                sink.rawText("<ul style=\"margin-top: 0px; margin-bottom: 0px; padding-top: 0px; padding-bottom: 0px;\"><li>");
                sink.link("#type." + typeCode);
                sink.text(typeDescription);
                sink.link_();
                sink.rawText("</li></ul>");
                sink.listItem_();
                sink.list_();
                sink.tableCell_();
                sink.tableCell();
                sink.text(""
                        + ((NodeList)xpath.evaluate("/BugCollection/BugInstance[@type=\"" + typeCode + "\"]", doc, XPathConstants.NODESET)).getLength());
                sink.tableCell_();
                sink.tableRow_();
            }
        }
        sink.table_();

        for (int categoryNo = 0; categoryNo < categoryNodes.getLength(); categoryNo++){
            Node categoryNode = categoryNodes.item(categoryNo);
            String categoryCode = xpath.evaluate("attribute::category", categoryNode);
            String categoryDescription = xpath.evaluate("Description", categoryNode);

            sink.section2();
            sink.sectionTitle2();
            sink.text(categoryDescription);
            sink.anchor("category." + categoryCode);
            sink.sectionTitle2_();

            sink.table();
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text("Bug Pattern");
            sink.tableHeaderCell_();
            sink.tableHeaderCell();
            sink.text("Bugs");
            sink.tableHeaderCell_();
            sink.tableRow_();
            NodeList typeNodes =
                    (NodeList)xpath.evaluate("/BugCollection/BugPattern[@category=\"" + categoryCode + "\"]", doc, XPathConstants.NODESET);
            for (int typeNo = 0; typeNo < typeNodes.getLength(); typeNo++){
                Node typeNode = typeNodes.item(typeNo);
                String typeCode = xpath.evaluate("attribute::type", typeNode);
                String typeDescription = xpath.evaluate("ShortDescription", typeNode);
                NodeList bugsNodes =
                        (NodeList)xpath.evaluate("/BugCollection/BugInstance[@type=\"" + typeCode + "\"]", doc, XPathConstants.NODESET);
                sink.tableRow();
                sink.tableCell();
                sink.link("#type." + typeCode);
                sink.text(typeDescription);
                sink.link_();
                sink.tableCell_();
                sink.tableCell();
                sink.text("" + bugsNodes.getLength());
                sink.tableCell_();
                sink.tableRow_();
            }
            sink.table_();

            for (int typeNo = 0; typeNo < typeNodes.getLength(); typeNo++){
                Node typeNode = typeNodes.item(typeNo);
                String typeCode = xpath.evaluate("attribute::type", typeNode);
                String typeDescription = xpath.evaluate("ShortDescription", typeNode);
                String typeDetails = xpath.evaluate("Details", typeNode);
                sink.section3();
                sink.sectionTitle3();
                sink.text(typeDescription);
                sink.anchor("type." + typeCode);
                sink.sectionTitle3_();
                sink.rawText(typeDetails);

                sink.table();
                sink.tableRow();
                sink.tableHeaderCell();
                sink.text("Class");
                sink.tableHeaderCell_();
                sink.tableHeaderCell();
                sink.text("Lines");
                sink.tableHeaderCell_();
                sink.tableHeaderCell();
                sink.text("Details");
                sink.tableHeaderCell_();
                sink.tableRow_();

                SortedSet<String> bugTypeClasses = new TreeSet<String>();
                NodeList bugNodes = (NodeList)xpath.evaluate("/BugCollection/BugInstance[@type=\"" + typeCode
                        + "\"]/Class[@primary=\"true\"]", doc, XPathConstants.NODESET);
                for (int bugno = 0; bugno < bugNodes.getLength(); bugno++){
                    bugTypeClasses.add(xpath.evaluate("attribute::classname", bugNodes.item(bugno)));
                }

                for (String className : bugTypeClasses){
                    bugNodes = (NodeList)xpath.evaluate("/BugCollection/BugInstance[@type=\"" + typeCode + "\"]/Class[@classname=\""
                            + className + "\"]", doc, XPathConstants.NODESET);
                    for (int bugno = 0; bugno < bugNodes.getLength(); bugno++){
                        Node bugClassNode = bugNodes.item(bugno);
                        sink.tableRow();
                        sink.tableCell();
                        sink.link("#class." + className);
                        sink.text(className);
                        sink.link_();
                        sink.tableCell_();
                        String start = xpath.evaluate("../child::SourceLine/attribute::start", bugClassNode);
                        String end = xpath.evaluate("../child::SourceLine/attribute::end", bugClassNode);
                        String details = xpath.evaluate("../LongMessage", bugClassNode);
                        sink.tableCell();
                        if (xrefPath == null){
                            if (start.equals(end)){
                                sink.text(start);
                            }else{
                                sink.text(start + "-" + end);
                            }
                        }else{
                            sink.rawText(getXrefLink(className, start, end));
                        }
                        sink.tableCell_();
                        sink.tableCell();
                        sink.text(details);
                        sink.tableCell_();
                        sink.tableRow_();
                    }
                }

                sink.table_();
                sink.section3_();
            }

            sink.section2_();
        }

        sink.section1_();
    }
}
