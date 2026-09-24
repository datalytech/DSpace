/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.audit;

import java.util.Calendar;
import java.util.Date;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.utils.DSpace;

/**
 * Command line tool to prune old entries from the audit Solr core, based on
 * {@code audit.retention.days} (see {@code [dspace]/config/modules/audit.cfg}).
 * <p>
 * Nothing prunes the audit core automatically: with
 * {@code event.consumer.audit.filters = All+All} it records every event on every object type and
 * grows without bound. Run this periodically (a nightly or weekly cron entry calling
 * {@code [dspace]/bin/dspace audit-cleanup} is the expected use) once a retention policy has been
 * agreed with the repository's administrators.
 *
 * @author Claude Sonnet 5
 */
public class AuditCleanup {

    private static final Logger log = LogManager.getLogger(AuditCleanup.class);

    private AuditCleanup() { }

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("h", "help", false, "Help");
        options.addOption("d", "days", true,
                "Delete audit events older than this many days. Defaults to 'audit.retention.days' "
                        + "(itself defaulting to 365) when not given.");
        options.addOption("n", "dry-run", false,
                "Only report how many events would be deleted, without deleting anything.");

        CommandLineParser parser = new DefaultParser();
        CommandLine line;
        try {
            line = parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            new HelpFormatter().printHelp("audit-cleanup", options);
            System.exit(1);
            return;
        }

        if (line.hasOption('h')) {
            new HelpFormatter().printHelp("audit-cleanup", options);
            return;
        }

        DSpace dspace = new DSpace();
        ConfigurationService configurationService = dspace.getConfigurationService();
        AuditService auditService = dspace.getSingletonService(AuditService.class);

        if (!configurationService.getBooleanProperty("audit.enabled", false)) {
            System.err.println("audit.enabled is false: there should be nothing to clean up.");
            System.exit(1);
            return;
        }

        int retentionDays = line.hasOption('d')
                ? Integer.parseInt(line.getOptionValue('d'))
                : configurationService.getIntProperty("audit.retention.days", 365);
        if (retentionDays <= 0) {
            System.err.println("The retention period must be a positive number of days, got: " + retentionDays);
            System.exit(1);
            return;
        }

        Calendar cutoffCalendar = Calendar.getInstance();
        cutoffCalendar.add(Calendar.DAY_OF_YEAR, -retentionDays);
        Date cutoff = cutoffCalendar.getTime();

        // context is only needed for the AuditService API shape; the audit core itself lives
        // outside the DSpace database and is not access-controlled through this context
        Context context = new Context();
        context.turnOffAuthorisationSystem();
        try {
            long toDelete = auditService.countEvents(context, null, cutoff, null);
            System.out.println("Retention period: " + retentionDays + " days (cutoff: " + cutoff + ")");
            System.out.println((line.hasOption('n') ? "Would delete " : "Deleting ") + toDelete
                    + " audit event(s) recorded before the cutoff.");

            if (toDelete == 0 || line.hasOption('n')) {
                return;
            }

            // "from" left null: everything up to and including the cutoff
            auditService.deleteEvents(context, null, cutoff);
            auditService.commit();
            System.out.println("Done.");
        } catch (RuntimeException e) {
            log.error("Failed to prune the audit core", e);
            System.err.println("Failed to prune the audit core: " + e.getMessage());
            System.exit(1);
        } finally {
            context.abort();
        }
    }
}
