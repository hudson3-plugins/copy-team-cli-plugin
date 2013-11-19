copy-team-cli-plugin
====================

Create a new team and copy all jobs in an existing team to it.

    java -jar hudson-cli.jar copy-team args...
    Copy a team and its jobs to a newly created team
     FROM                : Team name to copy (required)
     TO                  : Team name to create (required)
     EMAIL               : Email recipients separated by commas (optional); if not
                           specified, recipients will be removed

The FROM team must exist and the TO team must not.

copy-team assumes the email recipients in the FROM team are not relevant to the new
team and, by default, removes them from the copied jobs. If the optional EMAIL
parameter is specified, it will replace any existing email recipients in copied jobs.
If this is not the desired effect, EMAIL should be omitted and the new job
recipients set by hand.

All jobs in the FROM team are copied to the TO team. Jobs retain their original
names with new team qualification.

In copied jobs, job references in build triggers, cascading parents and children
that refer to jobs in the FROM team are modified to refer to the same jobs in the TO team.
Job references outside the FROM team are not changed.

NB: Email addresses and job references are only looked for in well-known locations.
Addresses or references added by plugins are not affected.
