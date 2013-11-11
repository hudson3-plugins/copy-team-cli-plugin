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
import hudson.cli.CLICommand;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import java.util.Set;
import org.eclipse.hudson.security.team.Team;
import org.eclipse.hudson.security.team.TeamManager;
import org.eclipse.hudson.security.team.TeamManager.TeamNotFoundException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Delete a team from the command line. User must be sys admin.
 * @author Bob Foster
 */
@Extension
public class CopyTeamCommand extends CLICommand {

    @Override
    public String getShortDescription() {
        return "Copy a team";
    }
    @Argument(metaVar = "FROM", usage = "Team to copy", required=true)
    public String from;
    @Argument(metaVar = "TO", usage = "Team to create", required=true)
    public String to;
    @Option(name = "-d", aliases = {"--delete-email"}, usage = "Delete email notification")
    public boolean deleteEmail;

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
        
        Team targetTeam;
        try {
            targetTeam = teamManager.findTeam(from);
        } catch (TeamNotFoundException e) {
            stderr.println("From team "+team+" not found");
            return -1;
        }
        
        try {
            teamManager.findTeam(to);
            stderr.println("To team "+team+" already exists");
            return -1;
        } catch (TeamNotFoundException e) {
        }
        
        Set<String> jobNames = targetTeam.getJobNames();
        for (String job : jobNames) {
        }
        
        return 0;
    }
}
