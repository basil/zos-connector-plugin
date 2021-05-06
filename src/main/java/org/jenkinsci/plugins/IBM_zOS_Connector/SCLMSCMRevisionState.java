package org.jenkinsci.plugins.IBM_zOS_Connector;

import hudson.scm.EditType;
import hudson.scm.SCMRevisionState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>SCLMSCMRevisionState</h2>
 * Represents revision of SCLM.
 *
 * @author <a href="mailto:candiduslynx@gmail.com">Alexander Shcherbakov</a>
 * @version 1.0
 * @see SCLMFileState
 */
public class SCLMSCMRevisionState extends SCMRevisionState {
    /**
     * DBUTIL format string. Passed to FLMCMD for report generation.
     */
    private final static String DBUTILFormat = "@@FLMCLV.@@FLMTYP(@@FLMMBR) <@@FLMCD4 @@FLMCTM> @@FLMCUS @@FLMMVR";
    /**
     * DBUTIL pattern. User for DBUTIL report parsing.
     */
    private final static Pattern DBUTILPattern = Pattern.compile(".*?(\\S+)\\s*\\.(\\S+)\\s*\\((\\S+)\\s*\\)\\s+<(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2})>\\s+(\\S+)\\s+(\\S+).*");

    /**
     * List of current files under interest.
     */
    private final LinkedList<SCLMFileState> files;
    /**
     * Types of files under interest.
     */
    private final LinkedList<String> types;

    /**
     * Fetch new remote revision.
     *
     * @param project       SCLM Project Name;
     * @param alternate     SCLM Alternate Project Definition.
     * @param group         SCLM Group.
     * @param types         List of types under interest.
     * @param SCLMJob       Prefix pf job to be invoked for DBUTIL report.
     * @param ZFTPConnector Connector initialized with basic information like server and user.
     * @param baseline      Previous revision state.
     * @see ZFTPConnector
     */
    SCLMSCMRevisionState(String project, String alternate, String group, LinkedList<String> types, String SCLMJob, ZFTPConnector ZFTPConnector, SCLMSCMRevisionState baseline) {
        // Copy types.
        this.types = types;

        // Format the job.
        String actualJob = SCLMJob + "\n" +
                "//SYSTSIN  DD *\n" +
                "  ISPSTART CMD(FLMCMD FILE,DBUWORK)\n" +
                "/*\n" +
                "//MSGS     DD SYSOUT=*\n" +
                "//REPT     DD SYSOUT=*\n" +
                "//TAIL     DD SYSOUT=*\n" +
                "//DBUWORK  DD *\n" +
                "DBUTIL,\n" +
                "+" + project + ",\n" +
                "+" + alternate + ",\n" +
                "+" + group + ",,,,,,\n" +
                "+*,*,*,*,*,*,*,YES,*,*,,,,NORMAL,N,N,,MSGS,REPT,TAIL,\n+" + SCLMSCMRevisionState.DBUTILFormat + "\n/*";

        // Create temp variables.
        LinkedList<SCLMFileState> remote = new LinkedList<>();
        InputStream inputStream = new ByteArrayInputStream(actualJob.getBytes(Charset.defaultCharset()));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Submit the job for the DBUTIL report and build remote file list.
        if (ZFTPConnector.submit(inputStream, true, 0, outputStream, true)) {
            String out = "";
            try {
                out = outputStream.toString("UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            String[] outParts = out.split("!! END OF JES SPOOL FILE !!(\\r\\n|\\r|\\n)");
            for (String spool : outParts) {
                if (this.isChangeLog(spool)) {
                    remote = this.constructChanges(spool, project, alternate, group);
                    break;
                }
            }
        }

        this.files = new LinkedList<>();
        LinkedList<SCLMFileState> common;
        LinkedList<SCLMFileState> added;
        LinkedList<SCLMFileState> deleted;

        // Prepare 'Added' files list
        added = new LinkedList<>(remote);
        if (baseline != null)
            added.removeAll(baseline.getFiles());

        for (SCLMFileState f : added)
            f.editType = EditType.ADD;
        this.files.addAll(added);

        if (baseline != null) {
            // Prepare 'Edited' files list
            common = new LinkedList<>(remote);
            common.removeAll(added);
            LinkedList<SCLMFileState> base = baseline.getFiles();
            for (SCLMFileState f : common) {
                boolean edit = true;
                for (SCLMFileState fileInBase : base) {
                    if (fileInBase.compareTo(f) == 0) {
                        edit = false;
                        break;
                    }
                }
                if (edit) {
                    f.editType = EditType.EDIT;
                } else {
                    f.editType = null;
                }
            }
            this.files.addAll(common);

            // Prepare 'Deleted' files list
            deleted = new LinkedList<>(baseline.getFiles());
            deleted.removeAll(common);
            for (SCLMFileState f : deleted)
                f.editType = EditType.DELETE;
            this.files.addAll(deleted);
        }
        this.files.sort(SCLMFileState.changeComparator);
    }

    /**
     * Get files from revision.
     *
     * @return Lst of files in revision.
     */
    private LinkedList<SCLMFileState> getFiles() {
        return this.files;
    }

    /**
     * Get name of ion file for the revision.
     *
     * @return <code>Null</code>.
     */
    @Override
    public String getIconFileName() {
        return null;
    }

    /**
     * Get URL for the revision.
     *
     * @return <code>Null</code>.
     */
    @Override
    public String getUrlName() {
        return null;
    }

    /**
     * Get printable name.
     *
     * @return Printable name.
     */
    @Override
    public String getDisplayName() {
        return "SCLM Revision State";
    }

    /**
     * Check whether a spool file is DBUTIL report.
     *
     * @param s Spool file.
     * @return Whether input is DBUTIL report.
     */
    private boolean isChangeLog(String s) {
        // Check all lines
        for (String g : s.split("\\r\\n|\\r|\\n")) {
            Matcher matcher = SCLMSCMRevisionState.DBUTILPattern.matcher(g);
            // Only not empty lines
            if (!g.matches("\\s*")) {
                // If line not matches it isn't DBUTIL report.
                if (!matcher.matches()) {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Construct SCLM file list from DBUTIL report.
     *
     * @param log       DBUTIL report.
     * @param project   SCLM Project Name.
     * @param alternate SCLM Alternate Project Definition.
     * @param group     SCLM Group.
     * @return SCLM file list.
     */
    private LinkedList<SCLMFileState> constructChanges(String log, String project, String alternate, String group) {
        // Result.
        LinkedList<SCLMFileState> res = new LinkedList<>();

        for (String g : log.split("\\r\\n|\\r|\\n")) {
            Matcher matcher = SCLMSCMRevisionState.DBUTILPattern.matcher(g);
            // Only non-empty lines.
            if (!g.matches("\\s*")) {
                if (matcher.matches()) {
                    // Parse
                    try {
                        String changeGroup = matcher.group(1);
                        String type = matcher.group(2);
                        String member = matcher.group(3);
                        SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                        Date date = df.parse(matcher.group(4));
                        String userID = matcher.group(5);
                        long version = Long.parseLong(matcher.group(6));
                        SCLMFileState file = new SCLMFileState(project,
                                alternate,
                                group,
                                type,
                                member,
                                version,
                                date,
                                userID,
                                changeGroup);
                        // Decide if we are adding this file.
                        if (this.types != null && !this.types.isEmpty()) {
                            if (this.types.contains(type)) {
                                res.add(file);
                            }
                        } else {
                            res.add(file);
                        }
                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // Sort result and return it.
        res.sort(SCLMFileState.changeComparator);
        return res;
    }

    /**
     * Get only changed files.
     *
     * @return List of changed SCLM files.
     */
    public LinkedList<SCLMFileState> getChangedOnly() {
        LinkedList<SCLMFileState> result = new LinkedList<>(this.files);
        result.removeIf(sclmFileState -> sclmFileState.editType == null);
        return result;
    }

    /**
     * Remove SCLM files marked as 'DELETED'.
     */
    public void removeDeleted() {
        this.files.removeIf(temp -> temp.editType == EditType.DELETE);
    }
}
