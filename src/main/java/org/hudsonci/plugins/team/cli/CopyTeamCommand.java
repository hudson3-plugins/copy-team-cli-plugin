/*
 * Copyright (c) 2013 Hudson.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Hudson - initial API and implementation and/or initial documentation
 */

package org.hudsonci.plugins.team.cli;

import hudson.Extension;
import hudson.XmlFile;
import hudson.cli.CLICommand;
import hudson.model.Failure;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.eclipse.hudson.security.team.Team;
import org.eclipse.hudson.security.team.TeamManager;
import org.eclipse.hudson.security.team.TeamManager.TeamAlreadyExistsException;
import org.eclipse.hudson.security.team.TeamManager.TeamNotFoundException;
import org.eclipse.hudson.security.team.TeamNode;
import org.eclipse.hudson.security.team.TeamView;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Copy a team and its jobs from the command line. User must be sys admin.
 * <h3>EMAIL
 * Email <recipients> are replaced with contents of email argument.
 * <pre>
 * <project>
 *   <project-properties class="java.util.concurrent.ConcurrentHashMap">
 *     <entry>
 *       <string>hudson-tasks-Mailer</string>
 *       <external-property>
 *         <originalValue class="hudson.tasks.Mailer">
 *           <recipients>${email}</recipients>
 *           <dontNotifyEveryUnstableBuild>false</dontNotifyEveryUnstableBuild>
 *           <sendToIndividuals>false</sendToIndividuals>
 *         </originalValue>
 *         <propertyOverridden>false</propertyOverridden>
 *         <modified>true</modified>
 *       </external-property>
 *     </entry>
 * </pre>
 * If email not specified, replace entire entry with:
 * <pre>
 * <entry>
 *   <string>hudson-tasks-Mailer</string>
 *   <external-property>
 *     <propertyOverridden>false</propertyOverridden>
 *     <modified>false</modified>
 *   </external-property>
 * </entry>
 * </pre>
 * Cascading project names qualified with the old team name are requalified
 * with the new team name. E.g., for copy-team Team1 TeamX
 * <pre>
 * <project>
 *   <cascadingProjectName>Team1.JobBill2</cascadingProjectName>
 *   <cascadingChildrenNames class="java.util.concurrent.CopyOnWriteArraySet">
 *     <string>Team1.JobBill5</string>
 *   </cascadingChildrenNames>
 * </pre>
 * Is converted to:
 * <pre>
 * <project>
 *   <cascadingProjectName>TeamX.JobBill2</cascadingProjectName>
 *   <cascadingChildrenNames class="java.util.concurrent.CopyOnWriteArraySet">
 *     <string>TeamX.JobBill5</string>
 *   </cascadingChildrenNames>
 * </pre>
 * If build trigger is specified, replace old team name with new team name, e.g.,
 * <pre>
 * <project>
 *   <project-properties class="java.util.concurrent.ConcurrentHashMap">
 *     <entry>
 *       <string>hudson-tasks-BuildTrigger</string>
 *       <external-property>
 *         <originalValue class="hudson.tasks.BuildTrigger">
 *           <childProjects>Team1.JobBill4, Team1.JobBill5</childProjects>
 * </pre>
 * Is converted to:
 * <pre>
 * <project>
 *   <project-properties class="java.util.concurrent.ConcurrentHashMap">
 *     <entry>
 *       <string>hudson-tasks-BuildTrigger</string>
 *       <external-property>
 *         <originalValue class="hudson.tasks.BuildTrigger">
 *           <childProjects>TeamX.JobBill4, TeamX.JobBill5</childProjects>
 * </pre>
 * Note that project names that are not qualified with the old team name
 * are untouched.
 * <h3>-nodes option
 * <table>
 * <tr>
 * <th>Value</th>
 * <th>Meaning</th>
 * </tr>
 * <tr>
 * <td>move</td>
 * <td>Move nodes owned by the from team to the to team.</td>
 * <td>visible</td>
 * <td>Make nodes owned by the from team visible to the to team</td>
 * <td>ignore</td>
 * <td>Do nothing about nodes owned by the from team (default)</td>
 * </tr>
 * </table>
 * <table>
 * <h3>-views option
 * <tr>
 * <th>Value</th>
 * <th>Meaning</th>
 * </tr>
 * <tr>
 * <td>move</td>
 * <td>Move views owned by the from team to the to team.</td>
 * <td>visible</td>
 * <td>Make views owned by the from team visible to the to team</td>
 * <td>ignore</td>
 * <td>Do nothing about views owned by the from team (default)</td>
 * </tr>
 * </table>
 * @author Bob Foster
 */
@Extension
public class CopyTeamCommand extends CLICommand {

    @Override
    public String getShortDescription() {
        return "Copy a team and its jobs to a newly created team";
    }
    @Argument(metaVar = "FROM", usage = "Team name to copy (required)", required=true, index=0)
    public String from;
    @Argument(metaVar = "TO", usage = "Team name to create (required)", required=true, index=1)
    public String to;
    @Argument(metaVar = "EMAIL", usage = "Email recipients separated by commas (optional); if not specified, recipients will be removed", required=false, index=2)
    public String email;
    @Option(name = "-n", aliases="--nodes", usage = "MOVE (move nodes to new team), VISIBLE (make nodes visible to new team), IGNORE (ignore nodes - default), ")
    public String nodes;
    @Option(name = "-v", aliases="--views", usage = "MOVE (move views to new team), VISIBLE (make views visible to new team), IGNORE (ignore views - default), ")
    public String views;
    
    
    
    protected int run() throws Exception {
        Hudson h = Hudson.getInstance();
        
        if (!h.isTeamManagementEnabled()) {
            stderr.println("Team management is not enabled");
            return -1;
        }
        
        TeamManager teamManager = h.getTeamManager();
        
        if (!teamManager.isCurrentUserSysAdmin()) {
            stderr.println("User not authorized to create team");
            return -1;
        }
        
        Team fromTeam;
        try {
            fromTeam = teamManager.findTeam(from);
        } catch (TeamNotFoundException e) {
            stderr.println("From team "+from+" not found");
            return -1;
        }
        
        if (nodes != null && !("move".equalsIgnoreCase(nodes) || "visible".equalsIgnoreCase(nodes) || "ignore".equalsIgnoreCase(nodes))) {
            stderr.println("nodes must be one of move, visible or ignore");
        }
        
        if (views != null && !("move".equalsIgnoreCase(views) || "visible".equalsIgnoreCase(views) || "ignore".equalsIgnoreCase(views))) {
            stderr.println("views must be one of move, visible or ignore");
        }
        
        Team toTeam = null;
        try {
            toTeam = teamManager.createTeam(to);
        } catch (IOException ex) {
            stderr.println(ex.getMessage());
            return -1;
        } catch (TeamAlreadyExistsException ex) {
            stderr.println("To team "+to+" already exists");
            return -1;
        }
        
        Set<String> jobNames = fromTeam.getJobNames();
        for (String jobName : jobNames) {
            String unqualifiedName = teamManager.getUnqualifiedJobName(jobName);
            TopLevelItem item = h.getItem(jobName);
            if (item instanceof Job) {
                Job job = (Job) item;
                XmlFile file = job.getConfigFile();
                InputStream in;
                try {
                    in = fixConfigFile(file, from, to, email);
                } catch (Failure ex) {
                    stderr.println("Error reading config.xml for job "+jobName);
                    stderr.println(ex.getMessage());
                    return -1;
                }
                h.createProjectFromXML(unqualifiedName, to, in);
            }
        }
        
        if (nodes != null) {
            if ("move".equalsIgnoreCase(nodes)) {
                for (String nodeName : fromTeam.getNodeNames()) {
                    teamManager.moveNode(fromTeam, toTeam, nodeName);
                }
            } else if ("visible".equalsIgnoreCase(nodes)) {
                for (TeamNode teamNode : fromTeam.getNodes()) {
                    teamManager.addNodeVisibility(teamNode, toTeam.getName());
                    // Assume target team wants to be able to use the node.
                    teamManager.setNodeEnabled(teamNode.getId(), toTeam, true);
                }
            }
        }
        
        if (views != null) {
            if ("move".equalsIgnoreCase(views)) {
                for (String viewName : fromTeam.getViewNames()) {
                    teamManager.moveView(fromTeam, toTeam, viewName);
                }
            } else if ("visible".equalsIgnoreCase(nodes)) {
                for (TeamView teamView : fromTeam.getViews()) {
                    teamManager.addViewVisibility(teamView, toTeam.getName());
                }
            }
        }
        
        return 0;
    }

    /*
     * This method intentionally does not use XStream or any of the objects
     * associated with various elements in the config.xml file, dealing
     * instead with the "raw" XML, because the objects and their methods
     * have too many unforseeable side effects.
     */
    private InputStream fixConfigFile(XmlFile file, String oldTeam, String newTeam, String email) {
        InputStream in = null;
        String oldPrefix = oldTeam + TeamManager.TEAM_SEPARATOR;
        String newPrefix = newTeam + TeamManager.TEAM_SEPARATOR;
        try {
            in = new FileInputStream(file.getFile());
            SAXReader reader = new SAXReader();
            Document doc = reader.read(in);
            
            Element root = doc.getRootElement();
            // The root element name varies by project type, e.g.,
            // project, matrix-project, etc. Code assumes that
            // following elements are common to all project types.
            
            // Cascading
            Element cascadingParent = root.element("cascadingProjectName");
            if (cascadingParent != null) {
                fixTeamName(cascadingParent, oldPrefix, newPrefix);
            }
            Element cascadingChildren = root.element("cascadingChildrenNames");
            if (cascadingChildren != null) {
                for (Object elem : cascadingChildren.elements("string")) {
                    fixTeamName((Element) elem, oldPrefix, newPrefix);
                }
            }
            // Properties (open-ended problem)
            Element properties = root.element("project-properties");
            if (properties == null) {
                throw new Failure("Project has no <project-properties>");
            }
            List<Element> removeEntries = new ArrayList<Element>();
            for (Object ent : properties.elements("entry")) {
                Element entry = (Element) ent;
                Element extProp = entry.element("external-property");
                Element origValue = extProp != null ? extProp.element("originalValue") : null;
                Element str = entry.element("string");
                if (str != null) {
                    String propName = str.getTextTrim();
                    if ("hudson-tasks-Mailer".equals(propName)) {
                        // email
                        if (extProp != null) {
                            if (email == null) {
                                // A recent fix removes entries that are not specified
                                removeEntries.add(entry);
                                /*
                                // Replace entire entry
                                entry.remove(extProp);
                                extProp = entry.addElement("external-property");
                                Element propOver = extProp.addElement("propertyOverridden");
                                propOver.setText("false");
                                Element modified = extProp.addElement("modified");
                                modified.setText("false");
                                */
                            } else if (origValue != null) {
                                fixEmailProperty(origValue, email);
                            }
                        }
                    } else if ("hudson-tasks-BuildTrigger".equals(propName)) {
                        // trigger
                        if (origValue != null) {
                            fixTriggerProperty(origValue, oldPrefix, newPrefix);
                        }
                    } else if ("builders".equals(propName)) {
                        // can be many of these
                        Element describableList = entry.element("describable-list-property");
                        origValue = describableList != null ? describableList.element("originalValue") : null;
                        // copyartifact
                        List artifacts = origValue != null ? origValue.elements("hudson.plugins.copyartifact.CopyArtifact") : null;
                        if (artifacts != null) {
                            for (Object obj : artifacts) {
                                Element copyArtifact = (Element) obj;
                                Element projectName = copyArtifact.element("projectName");
                                fixTeamName(projectName, oldPrefix, newPrefix);
                            }
                        }
                        // multijob
                        List multiJob = origValue != null ? origValue.elements("com.tikal.jenkins.plugins.multijob.MultiJobBuilder") : null;
                        for (Object obj : multiJob) {
                            Element builder = (Element) obj;
                            List jobs = builder.elements("phaseJobs");
                            if (jobs != null) {
                                for (Object j : jobs) {
                                    Element job = (Element) j;
                                    List configs = job.elements("com.tikal.jenkins.plugins.multijob.PhaseJobsConfig");
                                    if (configs != null) {
                                        for (Object c : configs) {
                                            Element config = (Element) c;
                                            Element jobName = config.element("jobName");
                                            fixTeamName(jobName, oldPrefix, newPrefix);
                                        }
                                    }
                                }
                            }
                        }
                        
                    } else if ("hudson-plugins-redmine-RedmineProjectProperty".equals(propName)) {
                        Element baseProp = entry.element("base-property");
                        Element projectName = baseProp != null ? baseProp.element("projectName") : null;
                        if (projectName != null) {
                            fixTeamName(projectName, oldPrefix, newPrefix);
                        }
                    }
                }
            }
            for (Element entry : removeEntries) {
                properties.remove(entry);
            }
            
            StringWriter writer = new StringWriter();
            doc.write(writer);
            return new ByteArrayInputStream(writer.toString().getBytes("UTF-8"));
        } catch (FileNotFoundException ex) {
            throw new Failure("File not found");
        } catch (DocumentException ex) {
            throw new Failure("Unable to parse document");
        } catch (IOException ex) {
            throw new Failure("Document write failed");
        } finally {
            try {
                in.close();
            } catch (IOException ex) {
                ;
            }
        }
    }
    
    private void fixTeamName(Element element, String oldPrefix, String newPrefix) {
        String jobName = element.getTextTrim();
        if (jobName.startsWith(oldPrefix)) {
            element.setText(newPrefix+jobName.substring(oldPrefix.length()));
        }
    }

    private void fixTriggerProperty(Element origValue, String oldPrefix, String newPrefix) {
        Element cp = origValue.element("childProjects");
        String childProjects = cp.getTextTrim();
        List<String> children = new ArrayList<String>();
        StringTokenizer st = new StringTokenizer(childProjects, ", ");
        boolean changed = false;
        while (st.hasMoreTokens()) {
            String child = st.nextToken();
            if (child.startsWith(oldPrefix)) {
                changed = true;
                child = newPrefix + child.substring(oldPrefix.length());
            }
            children.add(child);
        }
        if (changed) {
            childProjects = StringUtils.join(children, ", ");
            cp.setText(childProjects);
        }
    }

    private void fixEmailProperty(Element origValue, String email) {
        Element recipients = origValue.element("recipients");
        if (recipients != null) {
            recipients.setText(email);
        }
    }
}
