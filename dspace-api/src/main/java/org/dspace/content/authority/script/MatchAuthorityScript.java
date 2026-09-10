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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataField;
import org.dspace.content.MetadataValue;
import org.dspace.content.authority.Choice;
import org.dspace.content.authority.Choices;
import org.dspace.content.authority.factory.ContentAuthorityServiceFactory;
import org.dspace.content.authority.service.ChoiceAuthorityService;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.content.service.MetadataFieldService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.exception.SQLRuntimeException;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.util.UUIDUtils;
import org.dspace.utils.DSpace;

/**
 * Links the text values of authority controlled metadata to the entity they
 * name, filling in the authority (and the confidence) that the submission form
 * would have stored if the value had been picked from the authority lookup.
 * <p>
 * It is meant for the data that reaches the repository without going through
 * the form - CERIF, CSV or SQL imports, values typed before the authority was
 * configured - where the item shows the right name but has no link at all: the
 * project page does not resolve the investigator, the person page does not list
 * the project, the authority driven facets and the browse indexes fall back to
 * the plain text and the {@code cris.virtual.*} metadata stays empty.
 * <p>
 * Each value is looked up with the very same authority the form uses for the
 * field, that is the plugin configured by {@code choices.plugin.<field>}
 * (honouring any per collection override), and its best match is accepted only
 * when it points at a local item of the expected entity type whose name really
 * is the value at hand:
 * <ul>
 * <li>{@code EXACT} - the value equals one of the names of the matched entity,
 * ignoring case, accents and punctuation. The name can be an alternative name
 * ({@code crisrp.name.variant} and friends), which is how a person is matched
 * even when the project spells them differently.</li>
 * <li>{@code TOKENS} - same words in a different order or with a different
 * separator, i.e. <em>Papadopoulos, Georgios</em> against <em>Georgios
 * Papadopoulos</em>.</li>
 * <li>{@code BEST} - the authority returned a single candidate but its names
 * do not match the value. Reported, and applied only with -l.</li>
 * </ul>
 * The text value is never rewritten unless the field is listed in -u, and for
 * {@code crispj.investigator} it is never rewritten at all: an investigator is
 * matched through the alternative names of the profile, so replacing the value
 * with the primary name of the person would drop the form in which the project
 * itself credits them. Only the authority is added.
 * <p>
 * By default the script scans {@code crispj.investigator},
 * {@code crispj.investigator.department} and
 * {@code crispj.investigator.faculty}, and only reports what it would do: use
 * -f to write the authorities.
 * <p>
 * An authority declaring its entity type - {@code OrgUnitAuthority} does,
 * through {@code cris.ItemAuthority.OrgUnitAuthority.entityType} - searches the
 * entities of that type only. {@code AuthorAuthority} declares none in the
 * default configuration, so it searches every item by title: the expected
 * entity type is then taken from {@code authority.match.entity-type.<field>},
 * from -t, or from the defaults below, and the candidates of another type are
 * discarded. Declaring {@code cris.ItemAuthority.AuthorAuthority.entityType =
 * Person} narrows the search itself, for the submission form as well.
 * <p>
 * Once the authorities are written, run {@code ./dspace item-enhancer -f} and
 * {@code ./dspace index-discovery -b}: the links live in solr and in the
 * enhanced metadata, not only in the authority column.
 *
 * @author Claude Code
 */
public class MatchAuthorityScript
    extends DSpaceRunnable<MatchAuthorityScriptConfiguration<MatchAuthorityScript>> {

    /**
     * The metadata scanned when -m is not given.
     */
    public static final List<String> DEFAULT_FIELDS = List.of(
        "crispj.investigator",
        "crispj.investigator.department",
        "crispj.investigator.faculty");

    /**
     * The fields whose text value is never rewritten, not even with -u, because
     * the value carries information that the name of the linked entity does not
     * (the form in which the project credits its investigator).
     */
    private static final Set<String> PRESERVED_VALUE_FIELDS = Set.of("crispj.investigator");

    /**
     * The entity type expected on the other side of the link, used when the
     * authority itself does not declare one (i.e. AuthorAuthority, which has no
     * {@code cris.ItemAuthority.AuthorAuthority.entityType} in the default
     * configuration and would therefore match any item by title).
     */
    private static final Map<String, String> DEFAULT_ENTITY_TYPES = Map.of(
        "crispj.investigator", "Person",
        "crispj.coinvestigators", "Person",
        "crisfund.investigators", "Person",
        "crisfund.coinvestigators", "Person",
        "dc.contributor.author", "Person",
        "dc.contributor.editor", "Person");

    /**
     * The metadata holding the names an entity can be matched through, per
     * entity type. Overridable with
     * {@code authority.match.name-fields.<EntityType>}.
     */
    private static final Map<String, List<String>> DEFAULT_NAME_FIELDS = Map.of(
        "Person", List.of("dc.title", "crisrp.name", "crisrp.name.variant", "crisrp.name.translated",
            "crisrp.name.alternative", "crisrp.fullName"),
        "OrgUnit", List.of("dc.title", "crisou.name", "crisou.name.variant", "organization.legalName"));

    private static final List<String> FALLBACK_NAME_FIELDS = List.of("dc.title");

    private static final String NAME_FIELDS_PREFIX = "authority.match.name-fields.";

    private static final String ENTITY_TYPE_PREFIX = "authority.match.entity-type.";

    private static final int BATCH_SIZE = 100;

    private static final int MAX_DETAILED_VALUES = 50;

    private static final String CSV_NAME = "authority-match.csv";

    /**
     * How well the text value and the names of the matched entity agree.
     */
    enum Quality {
        EXACT,
        TOKENS,
        BEST;
    }

    /**
     * What has been done - or would be done - with a single metadata value.
     */
    private enum Action {
        LINKED("authority added"),
        RELINKED("authority replaced"),
        ALREADY_LINKED("already linked"),
        NO_MATCH("no matching entity"),
        AMBIGUOUS("more than one matching entity"),
        UNVERIFIED("candidate rejected, the names do not match the value"),
        REFERENCE("unresolved reference, left to update-item-references"),
        LOOKUP_FAILED("the authority lookup failed");

        private final String description;

        Action(String description) {
            this.description = description;
        }
    }

    private ItemService itemService;

    private ChoiceAuthorityService choiceAuthorityService;

    private MetadataFieldService metadataFieldService;

    private ConfigurationService configurationService;

    private Context context;

    private boolean apply;

    private boolean recheck;

    private boolean loose;

    private boolean csv;

    private List<String> fieldNames;

    private Set<String> updateValueFields;

    private Map<String, String> entityTypeOverrides;

    private UUID onlyItem;

    private UUID onlyCollection;

    /**
     * The outcome of the lookups, keyed by field, collection and value, so that
     * a name repeated over many projects is searched only once.
     */
    private final Map<String, Match> matches = new HashMap<>();

    /**
     * The name fields to compare against, keyed by entity type.
     */
    private final Map<String, List<String>> nameFields = new HashMap<>();

    /**
     * How many times each action has been taken, keyed by field.
     */
    private final Map<String, Map<Action, Integer>> counters = new LinkedHashMap<>();

    /**
     * The values that could not be linked, keyed by field and value, to be
     * listed in the report: they are the ones needing a look by a human.
     */
    private final Map<String, Map<String, Integer>> pending = new LinkedHashMap<>();

    private int touchedItems = 0;

    private File csvFile;

    private PrintWriter csvWriter;

    @Override
    public void setup() throws ParseException {

        itemService = ContentServiceFactory.getInstance().getItemService();
        metadataFieldService = ContentServiceFactory.getInstance().getMetadataFieldService();
        choiceAuthorityService = ContentAuthorityServiceFactory.getInstance().getChoiceAuthorityService();
        configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

        apply = commandLine.hasOption('f');
        recheck = commandLine.hasOption('r');
        loose = commandLine.hasOption('l');
        csv = commandLine.hasOption('o');

        fieldNames = new ArrayList<>(splitValues(commandLine.getOptionValues('m')));
        if (fieldNames.isEmpty()) {
            fieldNames.addAll(DEFAULT_FIELDS);
        }

        updateValueFields = new LinkedHashSet<>(splitValues(commandLine.getOptionValues('u')));

        entityTypeOverrides = new HashMap<>();
        for (String override : splitValues(commandLine.getOptionValues('t'))) {
            String[] parts = StringUtils.split(override, '=');
            if (parts.length != 2) {
                throw new ParseException("Unknown value for -t: " + override + ". Expected field=EntityType");
            }
            entityTypeOverrides.put(StringUtils.trim(parts[0]), StringUtils.trim(parts[1]));
        }

        onlyItem = parseUUID(commandLine.getOptionValue('i'), "-i");
        onlyCollection = parseUUID(commandLine.getOptionValue('c'), "-c");
    }

    @Override
    public void internalRun() throws Exception {

        context = new Context();
        assignCurrentUserInContext();
        assignSpecialGroupsInContext();
        context.turnOffAuthorisationSystem();

        try {
            List<Target> targets = resolveTargets();
            describeRun(targets);
            openCsv();
            for (Target target : targets) {
                process(target);
            }
            closeCsv();
            report();
            writeCsv();
            context.complete();
        } catch (Exception e) {
            closeCsv();
            handler.handleException("An error occurred while matching the authorities. The process is aborted", e);
            context.abort();
        } finally {
            deleteCsv();
            context.restoreAuthSystemState();
        }
    }

    /**
     * Turn the requested field names into the metadata they refer to, dropping
     * the ones that are not in the registry or have no authority configured.
     */
    private List<Target> resolveTargets() throws SQLException {

        List<Target> targets = new ArrayList<>();

        for (String fieldName : fieldNames) {

            MetadataField metadataField = metadataFieldService.findByString(context, fieldName, '.');
            if (metadataField == null) {
                handler.logWarning("Skipping " + fieldName + ": there is no such metadata field");
                continue;
            }

            Target target = new Target(metadataField);

            if (!choiceAuthorityService.isChoicesConfigured(target.fieldKey, Constants.ITEM, (Collection) null)) {
                // the authority can still be configured on a single submission
                // form, in which case it is resolved per collection at lookup
                // time, so this is a warning and not a reason to skip the field
                handler.logWarning("No authority is configured globally for " + target.fieldName
                    + ": it will be looked up per collection, through the submission form of each item");
            }

            targets.add(target);
        }

        if (targets.isEmpty()) {
            throw new IllegalArgumentException("None of the requested fields is under authority control: "
                + String.join(", ", fieldNames));
        }

        return targets;
    }

    private void describeRun(List<Target> targets) {

        if (apply) {
            handler.logInfo("Writing the authorities found. Values already linked are "
                + (recheck ? "checked again." : "left untouched, use -r to check them again."));
        } else {
            handler.logInfo("Running in report mode, no item will be modified. Use -f to write the authorities.");
        }

        for (Target target : targets) {
            handler.logInfo(target.describe());
        }

        if (loose) {
            handler.logInfo("Accepting the best match even when the names of the entity do not match the value.");
        }
        if (onlyItem != null) {
            handler.logInfo("Restricted to item " + onlyItem);
        }
        if (onlyCollection != null) {
            handler.logInfo("Restricted to the items owned by collection " + onlyCollection);
        }
    }

    private void process(Target target) throws SQLException {

        List<UUID> items = collectItems(target);
        handler.logInfo("Checking " + items.size() + " items for " + target.fieldName);

        for (int offset = 0; offset < items.size(); offset += BATCH_SIZE) {
            List<UUID> batch = items.subList(offset, Math.min(offset + BATCH_SIZE, items.size()));
            for (UUID id : batch) {
                Item item = itemService.find(context, id);
                if (item != null) {
                    processItem(item, target);
                }
            }
            if (apply) {
                context.commit();
            }
            // the cached lookups only hold uuid and name, so they survive
            context.clear();
        }
    }

    /**
     * @return the uuid of the items to check, the ones carrying the field
     */
    private List<UUID> collectItems(Target target) throws SQLException {

        List<UUID> items = new ArrayList<>();

        if (onlyItem != null) {
            items.add(onlyItem);
            return items;
        }

        try {
            Iterator<Item> iterator = itemService.findArchivedByMetadataField(context,
                target.schema, target.element, target.qualifier, Item.ANY);
            while (iterator.hasNext()) {
                Item item = iterator.next();
                if (isInScope(item)) {
                    items.add(item.getID());
                }
                context.uncacheEntity(item);
            }
        } catch (AuthorizeException e) {
            throw new IllegalStateException("Could not list the items with " + target.fieldName, e);
        }

        return items;
    }

    private boolean isInScope(Item item) {
        if (onlyCollection == null) {
            return true;
        }
        Collection collection = item.getOwningCollection();
        return collection != null && onlyCollection.equals(collection.getID());
    }

    private void processItem(Item item, Target target) {

        boolean modified = false;

        for (MetadataValue value : itemService.getMetadataByMetadataString(item, target.fieldName)) {
            Action action = processValue(item, target, value);
            modified = modified || action == Action.LINKED || action == Action.RELINKED;
        }

        if (apply && modified) {
            updateItem(item);
        }
    }

    private Action processValue(Item item, Target target, MetadataValue value) {

        if (StringUtils.isBlank(value.getValue())) {
            return null;
        }

        // the value can be rewritten below, the report always shows the text
        // the match was made on
        String text = value.getValue();
        String authority = value.getAuthority();
        if (StringUtils.isNotBlank(authority)) {
            if (UUIDUtils.fromString(authority) == null) {
                // i.e. "will be referenced::ORCID::0000-0002-...", resolved by
                // the update-item-references script once the entity exists
                return record(item, target, text, value, Action.REFERENCE, null);
            }
            if (!recheck) {
                return record(item, target, text, value, Action.ALREADY_LINKED, null);
            }
            if (isLinkStillValid(target, value, authority)) {
                return record(item, target, text, value, Action.ALREADY_LINKED, null);
            }
        }

        Match match = lookup(item, target, value);

        if (match == null) {
            return record(item, target, text, value, Action.LOOKUP_FAILED, null);
        }
        if (match.ambiguous) {
            return record(item, target, text, value, Action.AMBIGUOUS, match);
        }
        if (match.uuid == null) {
            return record(item, target, text, value, Action.NO_MATCH, null);
        }
        if (match.quality == Quality.BEST && !loose) {
            return record(item, target, text, value, Action.UNVERIFIED, match);
        }
        if (StringUtils.equals(match.uuid, authority)) {
            return record(item, target, text, value, Action.ALREADY_LINKED, match);
        }

        Action action = StringUtils.isNotBlank(authority) ? Action.RELINKED : Action.LINKED;

        if (apply) {
            value.setAuthority(match.uuid);
            value.setConfidence(Choices.CF_ACCEPTED);
            if (target.updateValue && StringUtils.isNotBlank(match.name)) {
                value.setValue(match.name);
            }
        }

        return record(item, target, text, value, action, match);
    }

    /**
     * An authority set by a previous run - or by hand - is kept when it still
     * points at an entity of the expected type carrying the value as one of its
     * names, so that -r only rewrites what is really broken.
     */
    private boolean isLinkStillValid(Target target, MetadataValue value, String authority) {

        Item linked = findItem(authority);
        if (linked == null) {
            return false;
        }
        if (!hasExpectedEntityType(target, linked)) {
            return false;
        }
        return quality(value.getValue(), namesOf(linked, target)) != Quality.BEST;
    }

    /**
     * Ask the authority configured for the field to match the value, and verify
     * the candidates it returns.
     *
     * @return the match, its uuid being null when nothing was found, or null
     *         when the lookup itself failed
     */
    private Match lookup(Item item, Target target, MetadataValue value) {

        Collection collection = item.getOwningCollection();
        String cacheKey = target.fieldKey + '|' + (collection != null ? collection.getID() : "")
            + '|' + normalize(value.getValue());
        if (matches.containsKey(cacheKey)) {
            return matches.get(cacheKey);
        }

        Match match = search(target, collection, value);
        matches.put(cacheKey, match);
        return match;
    }

    private Match search(Target target, Collection collection, MetadataValue value) {

        Choices choices;
        try {
            choices = choiceAuthorityService.getBestMatch(target.fieldKey, value.getValue(), Constants.ITEM,
                collection, value.getLanguage());
        } catch (Exception e) {
            handler.logWarning("Could not look up " + value.getValue() + " for " + target.fieldName
                + ": " + e.getMessage());
            return null;
        }

        if (choices == null || ArrayUtils.isEmpty(choices.values)) {
            return new Match();
        }

        Match best = new Match();

        for (Choice choice : choices.values) {

            // an authority key that is not a uuid points at an external source
            // (ORCID, ROR, ...) rather than at an entity of ours
            if (UUIDUtils.fromString(choice.authority) == null) {
                continue;
            }

            Item candidate = findItem(choice.authority);
            if (candidate == null || !hasExpectedEntityType(target, candidate)) {
                continue;
            }

            Match current = new Match();
            current.uuid = choice.authority;
            current.name = itemService.getMetadata(candidate, "dc.title");
            current.quality = quality(value.getValue(), namesOf(candidate, target));

            if (best.uuid == null || current.quality.ordinal() < best.quality.ordinal()) {
                best = current;
            } else if (current.quality == best.quality && !StringUtils.equals(current.uuid, best.uuid)) {
                best.ambiguous = true;
            }
        }

        return best;
    }

    private boolean hasExpectedEntityType(Target target, Item candidate) {
        return target.entityType == null
            || StringUtils.equals(target.entityType, itemService.getEntityType(candidate));
    }

    /**
     * The names the given entity can be matched through: its title, the CRIS
     * name fields configured for its entity type and, for a person, the family
     * and given name in both orders.
     */
    private List<String> namesOf(Item candidate, Target target) {

        List<String> names = new ArrayList<>();

        for (String field : nameFieldsOf(target.entityType)) {
            itemService.getMetadataByMetadataString(candidate, field)
                .forEach(metadataValue -> names.add(metadataValue.getValue()));
        }

        String familyName = itemService.getMetadata(candidate, "person.familyName");
        String givenName = itemService.getMetadata(candidate, "person.givenName");
        if (StringUtils.isNotBlank(familyName) && StringUtils.isNotBlank(givenName)) {
            names.add(familyName + ", " + givenName);
        }

        return names;
    }

    private List<String> nameFieldsOf(String entityType) {

        if (entityType == null) {
            return FALLBACK_NAME_FIELDS;
        }

        return nameFields.computeIfAbsent(entityType, type -> {
            String[] configured = configurationService.getArrayProperty(NAME_FIELDS_PREFIX + type);
            if (ArrayUtils.isNotEmpty(configured)) {
                return splitValues(configured);
            }
            return DEFAULT_NAME_FIELDS.getOrDefault(type, FALLBACK_NAME_FIELDS);
        });
    }

    /**
     * Compare the value with the names of the matched entity.
     */
    static Quality quality(String value, List<String> names) {

        String normalized = normalize(value);
        List<String> tokens = tokens(normalized);
        boolean sameTokens = false;

        for (String name : names) {
            String candidate = normalize(name);
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equals(normalized)) {
                return Quality.EXACT;
            }
            if (!sameTokens && tokens(candidate).equals(tokens)) {
                sameTokens = true;
            }
        }

        return sameTokens ? Quality.TOKENS : Quality.BEST;
    }

    /**
     * Reduce a name to what two spellings of it have in common: no case, no
     * accents, no punctuation and single spaces. The greek final sigma is
     * folded onto the ordinary one, so that a name typed in capitals compares
     * equal to the same name in lower case.
     */
    static String normalize(String value) {

        if (StringUtils.isBlank(value)) {
            return "";
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase(Locale.ROOT)
            .replace('ς', 'σ')
            .replaceAll("[^\\p{L}\\p{N}]+", " ");

        return StringUtils.normalizeSpace(normalized);
    }

    /**
     * The words of a normalized name, sorted, so that two spellings differing
     * only in the order of the words compare equal.
     */
    private static List<String> tokens(String normalized) {
        List<String> tokens = new ArrayList<>(Arrays.asList(StringUtils.split(normalized, ' ')));
        tokens.sort(String::compareTo);
        return tokens;
    }

    private Item findItem(String uuid) {
        try {
            return itemService.find(context, UUIDUtils.fromString(uuid));
        } catch (SQLException e) {
            throw new SQLRuntimeException(e);
        }
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

    private Action record(Item item, Target target, String text, MetadataValue value, Action action, Match match) {

        counters.computeIfAbsent(target.fieldName, field -> new LinkedHashMap<>())
            .merge(action, 1, Integer::sum);

        if (action == Action.NO_MATCH || action == Action.AMBIGUOUS || action == Action.UNVERIFIED) {
            pending.computeIfAbsent(target.fieldName, field -> new TreeMap<>())
                .merge(text, 1, Integer::sum);
        }

        writeCsvRow(item, target, text, value, action, match);

        return action;
    }

    private void report() {

        if (counters.isEmpty()) {
            handler.logInfo("No value to check was found");
            return;
        }

        handler.logInfo("");

        for (Map.Entry<String, Map<Action, Integer>> entry : counters.entrySet()) {
            handler.logInfo(entry.getKey() + ":");
            for (Map.Entry<Action, Integer> count : entry.getValue().entrySet()) {
                handler.logInfo("  " + count.getValue() + " " + count.getKey().description);
            }
        }

        reportPendingValues();

        if (!apply) {
            handler.logInfo("");
            handler.logInfo("Nothing was modified. Re-run with -f to write the authorities.");
            return;
        }

        handler.logInfo("");
        handler.logInfo("Updated " + touchedItems + " items");
        if (touchedItems > 0) {
            handler.logInfo("Now run ./dspace item-enhancer -f to rebuild the cris.virtual.* metadata, "
                + "then ./dspace index-discovery -b so that the authority driven facets, the browse indexes "
                + "and the entity pages pick up the new links.");
        }
    }

    /**
     * The values left without a link are the ones a human has to look at, so
     * list them - most frequent first - instead of hiding them in a count.
     */
    private void reportPendingValues() {

        for (Map.Entry<String, Map<String, Integer>> entry : pending.entrySet()) {

            List<Map.Entry<String, Integer>> values = new ArrayList<>(entry.getValue().entrySet());
            values.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

            handler.logInfo("");
            handler.logInfo(values.size() + " distinct values of " + entry.getKey() + " are still not linked:");
            for (Map.Entry<String, Integer> value : values.subList(0, Math.min(values.size(), MAX_DETAILED_VALUES))) {
                handler.logInfo("  " + value.getKey() + " (" + value.getValue() + ")");
            }
            if (values.size() > MAX_DETAILED_VALUES) {
                handler.logInfo("  ... and " + (values.size() - MAX_DETAILED_VALUES)
                    + " more, use -o to get the full list as a csv");
            }
        }
    }

    private void openCsv() throws IOException {
        if (!csv) {
            return;
        }
        csvFile = File.createTempFile("authority-match", ".csv");
        csvWriter = new PrintWriter(csvFile, StandardCharsets.UTF_8.name());
        csvWriter.println("metadata_field,item_uuid,value,action,quality,authority,entity_name");
    }

    private void writeCsvRow(Item item, Target target, String text, MetadataValue value, Action action,
        Match match) {
        if (csvWriter == null) {
            return;
        }
        csvWriter.println(String.join(",",
            quote(target.fieldName),
            quote(item.getID().toString()),
            quote(text),
            quote(action.name().toLowerCase(Locale.ROOT)),
            quote(match != null && match.quality != null ? match.quality.name().toLowerCase(Locale.ROOT) : ""),
            quote(match != null ? match.uuid : value.getAuthority()),
            quote(match != null ? match.name : "")));
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
        if (csvFile == null) {
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

    private List<String> splitValues(String[] values) {
        List<String> split = new ArrayList<>();
        if (values == null) {
            return split;
        }
        for (String value : values) {
            for (String single : StringUtils.split(value, ',')) {
                if (StringUtils.isNotBlank(single)) {
                    split.add(StringUtils.trim(single));
                }
            }
        }
        return split;
    }

    private UUID parseUUID(String value, String option) throws ParseException {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        UUID uuid = UUIDUtils.fromString(StringUtils.trim(value));
        if (uuid == null) {
            throw new ParseException("Unknown value for " + option + ": " + value + ". Expected an uuid");
        }
        return uuid;
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
    public MatchAuthorityScriptConfiguration<MatchAuthorityScript> getScriptConfiguration() {
        return new DSpace().getServiceManager()
            .getServiceByName("match-authority", MatchAuthorityScriptConfiguration.class);
    }

    /**
     * One of the fields to check, with everything the check needs to know about
     * it.
     */
    private final class Target {

        private final String schema;

        private final String element;

        private final String qualifier;

        /**
         * The field as the authority configuration names it, i.e.
         * crispj_investigator_department.
         */
        private final String fieldKey;

        /**
         * The field as the metadata registry names it, i.e.
         * crispj.investigator.department.
         */
        private final String fieldName;

        private final String entityType;

        private final boolean updateValue;

        private Target(MetadataField metadataField) {
            this.schema = metadataField.getMetadataSchema().getName();
            this.element = metadataField.getElement();
            this.qualifier = metadataField.getQualifier();
            this.fieldKey = metadataField.toString('_');
            this.fieldName = metadataField.toString('.');
            this.entityType = resolveEntityType(this.fieldName, this.fieldKey);
            this.updateValue = updateValueFields.contains(this.fieldName)
                && !PRESERVED_VALUE_FIELDS.contains(this.fieldName);
        }

        private String resolveEntityType(String field, String key) {

            if (entityTypeOverrides.containsKey(field)) {
                return entityTypeOverrides.get(field);
            }

            String configured = configurationService.getProperty(ENTITY_TYPE_PREFIX + key);
            if (StringUtils.isNotBlank(configured)) {
                return StringUtils.trim(configured);
            }

            try {
                String declared = choiceAuthorityService.getLinkedEntityType(key);
                if (StringUtils.isNotBlank(declared)) {
                    return declared;
                }
            } catch (IllegalArgumentException e) {
                // configured on a submission form only, the entity type of the
                // authority is unknown here
            }

            return DEFAULT_ENTITY_TYPES.get(field);
        }

        private String describe() {
            StringBuilder description = new StringBuilder("Checking ").append(fieldName)
                .append(" against ").append(entityType != null ? entityType + " entities" : "any entity");
            if (updateValue) {
                description.append(", replacing the value with the name of the matched entity");
            } else if (PRESERVED_VALUE_FIELDS.contains(fieldName)) {
                description.append(", keeping the value as it is (it may credit the entity "
                    + "through one of its alternative names)");
            }
            return description.toString();
        }
    }

    /**
     * The entity a value has been matched to, if any.
     */
    private static final class Match {

        private String uuid;

        private String name;

        private Quality quality;

        private boolean ambiguous;

    }

}
