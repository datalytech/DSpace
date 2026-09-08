/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.authority.script;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.authority.Choices;
import org.dspace.content.authority.factory.ContentAuthorityServiceFactory;
import org.dspace.content.authority.service.ChoiceAuthorityService;
import org.dspace.content.authority.service.MetadataAuthorityService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.exception.SQLRuntimeException;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.util.UUIDUtils;
import org.dspace.utils.DSpace;

/**
 * Finds - and optionally repairs - metadata values whose authority is the uuid
 * of an item that does not exist (any more).
 * <p>
 * Such "dangling" authorities are invisible on the item page, because the item
 * still carries the plain text value, but they break everything that resolves
 * the authority to the linked entity: the browse indexes fall back to showing
 * the bare uuid instead of the name, the cris.virtual.* fields are filled with
 * placeholders and the entity relations are lost.
 * <p>
 * By default the script only reports what it finds, use -f to repair.
 *
 * @author Claude Code
 */
public class DanglingAuthorityScript
    extends DSpaceRunnable<DanglingAuthorityScriptConfiguration<DanglingAuthorityScript>> {

    /**
     * The repair strategies supported by the -f option.
     */
    public enum FixMode {
        /**
         * Drop the authority and its confidence, keeping the text value.
         */
        CLEAR,
        /**
         * Look the text value up again with the authority plugin configured for
         * the field and re-point the authority to the item that is found. Values
         * without exactly one matching item are left untouched.
         */
        RELINK;
    }

    private static final int PAGE_SIZE = 100;

    private static final String CSV_NAME = "dangling-authorities.csv";

    private static final String ACTION_REPORTED = "reported";

    /**
     * The maximum number of findings detailed in the log, to keep the report
     * readable on repositories with a lot of broken references.
     */
    private static final int MAX_DETAILED_FINDINGS = 50;

    /**
     * Metadata recalculated by the item enhancer. It is reported but never
     * touched: repairing the metadata it derives from is what fixes it.
     */
    private static final String DERIVED_SCHEMA = "cris";

    private static final Set<String> DERIVED_ELEMENTS = Set.of("virtual", "virtualsource");

    private ItemService itemService;

    private ChoiceAuthorityService choiceAuthorityService;

    private MetadataAuthorityService metadataAuthorityService;

    private Context context;

    private FixMode fixMode;

    private Set<String> onlyFields;

    private boolean csv;

    /**
     * Which authority uuids exist, so that each uuid is looked up only once.
     */
    private final Map<String, Boolean> authorityExists = new HashMap<>();

    /**
     * The outcome of the relink lookups, keyed by field, collection and value.
     */
    private final Map<String, String> relinkCandidates = new HashMap<>();

    /**
     * The findings, keyed by the dangling authority.
     */
    private final Map<String, Finding> findings = new TreeMap<>();

    private int scannedItems = 0;

    private int affectedItems = 0;

    private int touchedItems = 0;

    private File csvFile;

    private PrintWriter csvWriter;

    @Override
    public void setup() throws ParseException {

        itemService = ContentServiceFactory.getInstance().getItemService();
        choiceAuthorityService = ContentAuthorityServiceFactory.getInstance().getChoiceAuthorityService();
        metadataAuthorityService = ContentAuthorityServiceFactory.getInstance().getMetadataAuthorityService();

        csv = commandLine.hasOption('c');

        onlyFields = new HashSet<>();
        String[] fields = commandLine.getOptionValues('m');
        if (fields != null) {
            for (String field : fields) {
                for (String single : StringUtils.split(field, ',')) {
                    onlyFields.add(StringUtils.trim(single).replace('.', '_'));
                }
            }
        }

        String fix = commandLine.getOptionValue('f');
        if (fix != null) {
            try {
                fixMode = FixMode.valueOf(StringUtils.upperCase(StringUtils.trim(fix)));
            } catch (IllegalArgumentException e) {
                throw new ParseException("Unknown value for -f: " + fix + ". Expected clear or relink");
            }
        }
    }

    @Override
    public void internalRun() throws Exception {

        context = new Context();
        assignCurrentUserInContext();
        assignSpecialGroupsInContext();
        context.turnOffAuthorisationSystem();

        if (fixMode == null) {
            handler.logInfo("Running in report mode, no item will be modified. Use -f to repair.");
        } else {
            handler.logInfo("Running in " + StringUtils.lowerCase(fixMode.name()) + " repair mode.");
        }
        if (!onlyFields.isEmpty()) {
            handler.logInfo("Restricted to: " + String.join(", ", sortedFieldNames(onlyFields)));
        }

        try {
            openCsv();
            scanItems();
            closeCsv();
            report();
            writeCsv();
            context.complete();
        } catch (Exception e) {
            closeCsv();
            handler.handleException("An error occurred while checking the authorities. The process is aborted", e);
            context.abort();
        } finally {
            deleteCsv();
            context.restoreAuthSystemState();
        }
    }

    private void scanItems() throws SQLException {
        int total = itemService.countTotal(context);
        handler.logInfo("Checking the authorities of " + total + " items");
        for (int offset = 0; offset < total; offset += PAGE_SIZE) {
            Iterator<Item> items = itemService.findAll(context, PAGE_SIZE, offset);
            while (items.hasNext()) {
                scanItem(items.next());
            }
            context.commit();
            context.clear();
        }
        handler.logInfo("Checked " + scannedItems + " items");
    }

    private void scanItem(Item item) {

        boolean found = false;
        boolean modified = false;

        for (MetadataValue value : item.getMetadata()) {
            String action = scanMetadataValue(item, value);
            if (action != null) {
                found = true;
                modified = modified || !ACTION_REPORTED.equals(action);
            }
        }

        if (found) {
            affectedItems++;
        }
        if (modified) {
            updateItem(item);
        }

        scannedItems++;
        uncacheItem(item);
    }

    /**
     * @return the action taken on the given metadata value, or null when its
     *         authority is fine (or out of scope) and nothing was done
     */
    private String scanMetadataValue(Item item, MetadataValue value) {

        String authority = value.getAuthority();
        if (StringUtils.isBlank(authority)) {
            return null;
        }

        String fieldKey = metadataAuthorityService.makeFieldKey(value.getMetadataField());
        if (!onlyFields.isEmpty() && !onlyFields.contains(fieldKey)) {
            return null;
        }

        // an authority that is not a uuid is a reference to an external source
        // (i.e. will be referenced::ORCID::0000-0002-...) which is resolved by
        // other means, so there is nothing to check here
        UUID uuid = UUIDUtils.fromString(authority);
        if (uuid == null || itemExists(uuid)) {
            return null;
        }

        String action = repair(item, value, authority);
        writeCsvRow(item, value, authority, action);
        return action;
    }

    private String repair(Item item, MetadataValue value, String authority) {

        Finding finding = findings.computeIfAbsent(authority, Finding::new);
        finding.add(value.getMetadataField().toString('.'), value.getValue(), item.getID());

        if (fixMode == null || isDerived(value)) {
            return ACTION_REPORTED;
        }

        if (fixMode == FixMode.RELINK) {
            String replacement = findReplacement(item, value);
            if (replacement == null) {
                finding.relinkFailures++;
                return ACTION_REPORTED;
            }
            value.setAuthority(replacement);
            value.setConfidence(Choices.CF_ACCEPTED);
            finding.relinkedTo = replacement;
            finding.repaired++;
            return "relinked to " + replacement;
        }

        value.setAuthority(null);
        value.setConfidence(Choices.CF_UNSET);
        finding.repaired++;
        return "cleared";
    }

    /**
     * Metadata generated by the item enhancer is rebuilt from the metadata it
     * derives from, so it must not be repaired here.
     */
    private boolean isDerived(MetadataValue value) {
        return DERIVED_SCHEMA.equals(value.getMetadataField().getMetadataSchema().getName())
            && DERIVED_ELEMENTS.contains(value.getMetadataField().getElement());
    }

    /**
     * Ask the authority configured for the field to match the text value again,
     * and return the uuid of the single item it points at, if any.
     */
    private String findReplacement(Item item, MetadataValue value) {

        Collection collection = item.getOwningCollection();
        if (collection == null || StringUtils.isBlank(value.getValue())) {
            return null;
        }

        String fieldKey = metadataAuthorityService.makeFieldKey(value.getMetadataField());
        String cacheKey = fieldKey + ' ' + collection.getID() + ' ' + value.getValue();
        if (relinkCandidates.containsKey(cacheKey)) {
            return relinkCandidates.get(cacheKey);
        }

        String replacement = null;
        try {
            Choices choices = choiceAuthorityService.getBestMatch(fieldKey, value.getValue(), Constants.ITEM,
                collection, value.getLanguage());
            if (choices != null && choices.values != null && choices.values.length == 1) {
                // an authority key that is not a uuid points at an external
                // source (ORCID, ROR, ...) rather than at an item of ours
                String candidate = choices.values[0].authority;
                replacement = UUIDUtils.fromString(candidate) != null ? candidate : null;
            }
        } catch (Exception e) {
            handler.logWarning("Could not look up " + value.getValue() + " for "
                + value.getMetadataField().toString('.') + ": " + e.getMessage());
        }

        relinkCandidates.put(cacheKey, replacement);
        return replacement;
    }

    private boolean itemExists(UUID uuid) {
        return authorityExists.computeIfAbsent(uuid.toString(), key -> {
            try {
                return itemService.find(context, uuid) != null;
            } catch (SQLException e) {
                throw new SQLRuntimeException(e);
            }
        });
    }

    private void updateItem(Item item) {
        try {
            itemService.setMetadataModified(item);
            itemService.update(context, item);
            touchedItems++;
        } catch (SQLException | AuthorizeException e) {
            throw new IllegalStateException("Could not update item " + item.getID(), e);
        }
    }

    private void report() {

        if (findings.isEmpty()) {
            handler.logInfo("No dangling authority found");
            return;
        }

        List<Finding> sorted = new ArrayList<>(findings.values());
        sorted.sort(Comparator.comparingInt((Finding finding) -> finding.occurrences).reversed());

        int occurrences = sorted.stream().mapToInt(finding -> finding.occurrences).sum();
        handler.logInfo("Found " + findings.size() + " dangling authorities, used "
            + occurrences + " times on " + affectedItems + " items");

        Map<String, Integer> perField = new TreeMap<>();
        for (Finding finding : sorted) {
            finding.fields.forEach((field, count) -> perField.merge(field, count, Integer::sum));
        }
        perField.forEach((field, count) -> handler.logInfo("  " + field + ": " + count));

        for (Finding finding : sorted.subList(0, Math.min(sorted.size(), MAX_DETAILED_FINDINGS))) {
            handler.logInfo(finding.describe());
        }
        if (sorted.size() > MAX_DETAILED_FINDINGS) {
            handler.logInfo("... and " + (sorted.size() - MAX_DETAILED_FINDINGS)
                + " more, use -c to get the full list as a csv");
        }

        if (fixMode == null) {
            handler.logInfo("Nothing was modified. Re-run with -f clear or -f relink to repair.");
            return;
        }

        int repaired = sorted.stream().mapToInt(finding -> finding.repaired).sum();
        handler.logInfo("Repaired " + repaired + " metadata values on " + touchedItems + " items");

        int failures = sorted.stream().mapToInt(finding -> finding.relinkFailures).sum();
        if (failures > 0) {
            handler.logInfo("Left untouched " + failures + " values with no single matching item. "
                + "Create the missing entities, or re-run with -f clear to drop their authority.");
        }
        if (repaired > 0) {
            handler.logInfo("Now run ./dspace item-enhancer -f to rebuild the cris.virtual.* metadata, "
                + "then ./dspace index-discovery -b to rebuild the browse indexes.");
        }
    }

    private void openCsv() throws IOException {
        if (!csv) {
            return;
        }
        csvFile = File.createTempFile("dangling-authorities", ".csv");
        csvWriter = new PrintWriter(csvFile, StandardCharsets.UTF_8.name());
        csvWriter.println("authority,metadata_field,item_uuid,value,action");
    }

    private void writeCsvRow(Item item, MetadataValue value, String authority, String action) {
        if (csvWriter == null) {
            return;
        }
        csvWriter.println(String.join(",",
            quote(authority),
            quote(value.getMetadataField().toString('.')),
            quote(item.getID().toString()),
            quote(value.getValue()),
            quote(action)));
    }

    private String quote(String value) {
        return '"' + StringUtils.replace(StringUtils.defaultString(value), "\"", "\"\"") + '"';
    }

    private void closeCsv() {
        if (csvWriter != null) {
            csvWriter.close();
            csvWriter = null;
        }
    }

    private void writeCsv() throws IOException, SQLException, AuthorizeException {
        if (csvFile == null || findings.isEmpty()) {
            return;
        }
        try (InputStream input = new FileInputStream(csvFile)) {
            handler.writeFilestream(context, CSV_NAME, input, "text/csv");
        }
        handler.logInfo("Report written to " + CSV_NAME);
    }

    private void deleteCsv() {
        if (csvFile != null && !csvFile.delete()) {
            csvFile.deleteOnExit();
        }
    }

    private List<String> sortedFieldNames(Set<String> fieldKeys) {
        List<String> names = new ArrayList<>();
        fieldKeys.forEach(fieldKey -> names.add(fieldKey.replace('_', '.')));
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private void uncacheItem(Item item) {
        try {
            context.uncacheEntity(item);
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
    }

    private void assignCurrentUserInContext() throws SQLException {
        UUID uuid = getEpersonIdentifier();
        if (uuid != null) {
            EPerson ePerson = EPersonServiceFactory.getInstance().getEPersonService().find(context, uuid);
            context.setCurrentUser(ePerson);
        }
    }

    private void assignSpecialGroupsInContext() throws SQLException {
        for (UUID uuid : handler.getSpecialGroups()) {
            context.setSpecialGroup(uuid);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public DanglingAuthorityScriptConfiguration<DanglingAuthorityScript> getScriptConfiguration() {
        return new DSpace().getServiceManager()
            .getServiceByName("dangling-authority", DanglingAuthorityScriptConfiguration.class);
    }

    /**
     * Everything that has been seen for one dangling authority.
     */
    private static final class Finding {

        private static final int MAX_SAMPLES = 5;

        private final String authority;

        private final Map<String, Integer> fields = new TreeMap<>();

        private final Set<String> values = new LinkedHashSet<>();

        private final Set<UUID> items = new LinkedHashSet<>();

        private int occurrences = 0;

        private int repaired = 0;

        private int relinkFailures = 0;

        private String relinkedTo;

        private Finding(String authority) {
            this.authority = authority;
        }

        private void add(String field, String value, UUID itemId) {
            occurrences++;
            fields.merge(field, 1, Integer::sum);
            items.add(itemId);
            if (values.size() < MAX_SAMPLES) {
                values.add(value);
            }
        }

        private String describe() {
            StringBuilder description = new StringBuilder(authority)
                .append(" (").append(occurrences).append(occurrences == 1 ? " value on " : " values on ")
                .append(items.size()).append(items.size() == 1 ? " item)" : " items)")
                .append(" fields=").append(fields)
                .append(" values=").append(values);
            if (relinkedTo != null) {
                description.append(" -> relinked to ").append(relinkedTo);
            }
            return description.toString();
        }
    }

}
