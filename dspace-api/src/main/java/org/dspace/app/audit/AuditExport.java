/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.audit;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.opencsv.CSVWriter;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.dspace.app.util.factory.UtilServiceFactory;
import org.dspace.app.util.service.DSpaceObjectUtils;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.utils.DSpace;

/**
 * Export audit events matching an optional combination of object, eperson, event type and date
 * range filters to a CSV file, either the whole audit history (no filters) or a filtered subset -
 * the same filters exposed by the {@code /auditlogs} admin screen and its
 * {@code search/byFilters} REST search method.
 * <p>
 * Reads the audit core in batches through
 * {@link AuditService#forEachEvent(Context, UUID, UUID, String, Date, Date, int, java.util.function.Consumer)}
 * rather than loading every matching event into memory at once, since the unfiltered export can
 * cover the entire history of a repository that has been running {@code event.consumer.audit
 * .filters = All+All} for years.
 *
 * @author Claude Sonnet 5
 */
public class AuditExport extends DSpaceRunnable<AuditExportScriptConfiguration> {

    private static final String DATE_PATTERN = "yyyy-MM-dd";

    private static final int BATCH_SIZE = 500;

    private static final String[] CSV_HEADER = {
        "id", "eventType", "timeStamp",
        "subjectUUID", "subjectType", "subjectName",
        "objectUUID", "objectType", "objectName",
        "epersonUUID", "epersonEmail",
        "detail"
    };

    private boolean help = false;
    private UUID objectUuid;
    private UUID epersonUuid;
    private String eventType;
    private Date from;
    private Date to;

    private final AuditService auditService = new DSpace().getSingletonService(AuditService.class);
    private final EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();
    private final DSpaceObjectUtils dSpaceObjectUtils = UtilServiceFactory.getInstance().getDSpaceObjectUtils();

    @Override
    public void setup() throws ParseException {
        if (commandLine.hasOption('h')) {
            help = true;
            return;
        }

        objectUuid = parseUuid('o', "object");
        epersonUuid = parseUuid('p', "eperson");
        eventType = commandLine.getOptionValue('t');
        from = parseDate('f', "from", false);
        to = parseDate('u', "until", true);
    }

    private UUID parseUuid(char opt, String label) throws ParseException {
        String value = commandLine.getOptionValue(opt);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ParseException("The " + label + " option is not a valid UUID: " + value);
        }
    }

    /**
     * @param endOfDay when true, advance the parsed date to the last instant of that day, so an
     *                 "until" filter includes the whole day rather than excluding everything
     *                 after its first millisecond
     */
    private Date parseDate(char opt, String label, boolean endOfDay) throws ParseException {
        String value = commandLine.getOptionValue(opt);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        Date parsed;
        try {
            parsed = new SimpleDateFormat(DATE_PATTERN).parse(value.trim());
        } catch (java.text.ParseException e) {
            throw new ParseException(
                    "The " + label + " option is not a valid date, expected " + DATE_PATTERN + ": " + value);
        }
        if (!endOfDay) {
            return parsed;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(parsed);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    @Override
    public void internalRun() throws Exception {
        if (help) {
            printHelp();
            return;
        }

        Context context = new Context();
        context.turnOffAuthorisationSystem();
        try {
            context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));
        } catch (SQLException e) {
            handler.handleException(e);
        }

        // Names are resolved once per UUID and cached, since the same object or eperson is
        // typically referenced by many events
        Map<UUID, String> objectNames = new HashMap<>();
        Map<UUID, String> epersonEmails = new HashMap<>();

        Path tempFile = Files.createTempFile("audit-export", ".csv");
        try {
            long[] rowCount = {0};
            try (CSVWriter csvWriter = new CSVWriter(new FileWriter(tempFile.toFile()))) {
                csvWriter.writeNext(CSV_HEADER);
                auditService.forEachEvent(context, objectUuid, epersonUuid, eventType, from, to, BATCH_SIZE,
                    auditEvent -> {
                        csvWriter.writeNext(toCsvRow(context, auditEvent, objectNames, epersonEmails));
                        rowCount[0]++;
                    });
            }
            handler.logInfo("Exported " + rowCount[0] + " audit event(s).");

            try (FileInputStream fileInputStream = new FileInputStream(tempFile.toFile())) {
                handler.writeFilestream(context, buildFileName(), fileInputStream, "auditExportCSV");
            }
        } finally {
            Files.deleteIfExists(tempFile);
            context.restoreAuthSystemState();
            context.complete();
        }
    }

    private String[] toCsvRow(Context context, AuditEvent event, Map<UUID, String> objectNames,
            Map<UUID, String> epersonEmails) {
        return new String[] {
            event.getUuid() != null ? event.getUuid().toString() : "",
            StringUtils.defaultString(event.getEventType()),
            event.getDatetime() != null ? event.getDatetime().toInstant().toString() : "",
            event.getSubjectUUID() != null ? event.getSubjectUUID().toString() : "",
            StringUtils.defaultString(event.getSubjectType()),
            resolveObjectName(context, event.getSubjectUUID(), objectNames),
            event.getObjectUUID() != null ? event.getObjectUUID().toString() : "",
            StringUtils.defaultString(event.getObjectType()),
            resolveObjectName(context, event.getObjectUUID(), objectNames),
            event.getEpersonUUID() != null ? event.getEpersonUUID().toString() : "",
            resolveEpersonEmail(context, event.getEpersonUUID(), epersonEmails),
            StringUtils.defaultString(event.getDetail())
        };
    }

    private String resolveObjectName(Context context, UUID uuid, Map<UUID, String> cache) {
        if (uuid == null) {
            return "";
        }
        return cache.computeIfAbsent(uuid, id -> {
            try {
                DSpaceObject dso = dSpaceObjectUtils.findDSpaceObject(context, id);
                return dso != null ? dso.getName() : "";
            } catch (SQLException e) {
                return "";
            }
        });
    }

    private String resolveEpersonEmail(Context context, UUID uuid, Map<UUID, String> cache) {
        if (uuid == null) {
            return "";
        }
        return cache.computeIfAbsent(uuid, id -> {
            try {
                EPerson eperson = ePersonService.find(context, id);
                return eperson != null ? eperson.getEmail() : "";
            } catch (SQLException e) {
                return "";
            }
        });
    }

    private String buildFileName() {
        StringBuilder name = new StringBuilder("audit-export");
        if (objectUuid != null) {
            name.append("-object-").append(objectUuid);
        }
        if (epersonUuid != null) {
            name.append("-eperson-").append(epersonUuid);
        }
        if (StringUtils.isNotBlank(eventType)) {
            name.append("-").append(eventType.toLowerCase());
        }
        name.append("-").append(System.currentTimeMillis()).append(".csv");
        return name.toString();
    }

    @Override
    public AuditExportScriptConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("audit-export",
                AuditExportScriptConfiguration.class);
    }
}
