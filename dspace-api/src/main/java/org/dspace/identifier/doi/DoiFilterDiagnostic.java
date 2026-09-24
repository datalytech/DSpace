/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier.doi;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import com.opencsv.CSVWriter;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.logic.Filter;
import org.dspace.content.logic.FilterUtils;
import org.dspace.content.logic.LogicalStatement;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.eperson.factory.EPersonServiceFactory;
import org.dspace.eperson.service.EPersonService;
import org.dspace.scripts.DSpaceRunnable;
import org.dspace.utils.DSpace;

/**
 * Diagnose, item by item, why a set of items did or did not pass the DOI minting filter
 * configured under {@code identifier.doi.filter} (in this deployment, {@code
 * uc-doctoral-thesis-doi_filter} - see {@code dspace/config/spring/api/item-filters.xml}).
 * <p>
 * Rather than re-implementing the filter's rules, this evaluates the exact same Spring beans the
 * filter itself is built from - the {@code is-archived_condition}, {@code is-withdrawn_condition},
 * {@code uc-type-is-doctoral-thesis_condition}, {@code uc-uhtype-is-doctoral-thesis_condition},
 * {@code uc-already-has-doi_condition} and {@code has-at-least-one-bitstream_condition} beans, plus
 * the configured filter itself for the overall result - so the report can never drift out of sync
 * with whatever {@code doi-organiser} actually decides. If those bean IDs change, the lookup fails
 * loudly instead of silently reporting a stale breakdown.
 * <p>
 * One CSV row is written per item, with one column per sub-condition, so a client can see exactly
 * which requirement a given item fails (e.g. it carries the readable "Doctoral Thesis" type but no
 * bitstream, or it is archived and typed correctly but was withdrawn).
 *
 * @author Claude Sonnet 5
 */
public class DoiFilterDiagnostic extends DSpaceRunnable<DoiFilterDiagnosticScriptConfiguration> {

    private static final String[] CSV_HEADER = {
        "uuid", "title",
        "archived", "notWithdrawn",
        "typeIsDoctoralThesis_dcType", "typeIsDoctoralThesis_uhType", "typeMatches",
        "doesNotAlreadyHaveDoi", "hasAtLeastOneBitstream",
        "passesFilter"
    };

    // Kept in sync by hand with the sub-statements of uc-doctoral-thesis-doi_filter in
    // dspace/config/spring/api/item-filters.xml - update these bean IDs (and CSV_HEADER above) if
    // that filter's composition ever changes.
    private static final String IS_ARCHIVED_CONDITION = "is-archived_condition";
    private static final String IS_WITHDRAWN_CONDITION = "is-withdrawn_condition";
    private static final String TYPE_IS_DOCTORAL_THESIS_DC_TYPE_CONDITION = "uc-type-is-doctoral-thesis_condition";
    private static final String TYPE_IS_DOCTORAL_THESIS_UH_TYPE_CONDITION = "uc-uhtype-is-doctoral-thesis_condition";
    private static final String ALREADY_HAS_DOI_CONDITION = "uc-already-has-doi_condition";
    private static final String HAS_BITSTREAM_CONDITION = "has-at-least-one-bitstream_condition";

    private static final String DOI_FILTER_CONFIGURATION_PROPERTY = "identifier.doi.filter";

    private boolean help = false;
    private boolean all = false;
    private List<String> uuidArgs = Collections.emptyList();
    private String file;

    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();
    private final EPersonService ePersonService = EPersonServiceFactory.getInstance().getEPersonService();

    private LogicalStatement isArchived;
    private LogicalStatement isWithdrawn;
    private LogicalStatement typeIsDoctoralThesisDcType;
    private LogicalStatement typeIsDoctoralThesisUhType;
    private LogicalStatement alreadyHasDoi;
    private LogicalStatement hasBitstream;
    private Filter doiFilter;

    @Override
    public void setup() throws ParseException {
        if (commandLine.hasOption('h')) {
            help = true;
            return;
        }

        all = commandLine.hasOption('a');
        String[] uuidValues = commandLine.getOptionValues('i');
        uuidArgs = uuidValues != null ? Arrays.asList(uuidValues) : Collections.emptyList();
        file = commandLine.getOptionValue('f');

        if (!all && uuidArgs.isEmpty() && StringUtils.isBlank(file)) {
            throw new ParseException("Specify at least one of -i/--uuid, -f/--file, or -a/--all");
        }
        if (all && (!uuidArgs.isEmpty() || StringUtils.isNotBlank(file))) {
            throw new ParseException("-a/--all cannot be combined with -i/--uuid or -f/--file");
        }
    }

    @Override
    public void internalRun() throws Exception {
        if (help) {
            printHelp();
            return;
        }

        loadConditions();

        Context context = new Context();
        context.turnOffAuthorisationSystem();
        try {
            context.setCurrentUser(ePersonService.find(context, getEpersonIdentifier()));
        } catch (SQLException e) {
            handler.handleException(e);
        }

        Path tempFile = Files.createTempFile("doi-filter-diagnostic", ".csv");
        try {
            long rowCount = 0;
            long notFoundCount = 0;
            try (CSVWriter csvWriter = new CSVWriter(new FileWriter(tempFile.toFile()))) {
                csvWriter.writeNext(CSV_HEADER);
                if (all) {
                    handler.logInfo("Checking every item in the repository, this may take a while...");
                    Iterator<Item> items = itemService.findAll(context);
                    while (items.hasNext()) {
                        Item item = items.next();
                        writeRow(context, csvWriter, item);
                        rowCount++;
                        context.uncacheEntity(item);
                    }
                } else {
                    for (UUID uuid : resolveUuids()) {
                        Item item = itemService.find(context, uuid);
                        if (item == null) {
                            handler.logWarning("No item found for UUID " + uuid + ", skipping");
                            notFoundCount++;
                            continue;
                        }
                        writeRow(context, csvWriter, item);
                        rowCount++;
                        context.uncacheEntity(item);
                    }
                }
            }
            handler.logInfo("Wrote " + rowCount + " row(s)"
                    + (notFoundCount > 0 ? "; " + notFoundCount + " UUID(s) were not found and were skipped" : "")
                    + ".");

            try (FileInputStream fileInputStream = new FileInputStream(tempFile.toFile())) {
                handler.writeFilestream(context, buildFileName(), fileInputStream, "doiFilterDiagnosticCSV");
            }
        } finally {
            Files.deleteIfExists(tempFile);
            context.restoreAuthSystemState();
            context.complete();
        }
    }

    private void loadConditions() {
        isArchived = getBean(IS_ARCHIVED_CONDITION);
        isWithdrawn = getBean(IS_WITHDRAWN_CONDITION);
        typeIsDoctoralThesisDcType = getBean(TYPE_IS_DOCTORAL_THESIS_DC_TYPE_CONDITION);
        typeIsDoctoralThesisUhType = getBean(TYPE_IS_DOCTORAL_THESIS_UH_TYPE_CONDITION);
        alreadyHasDoi = getBean(ALREADY_HAS_DOI_CONDITION);
        hasBitstream = getBean(HAS_BITSTREAM_CONDITION);

        doiFilter = FilterUtils.getFilterFromConfiguration(DOI_FILTER_CONFIGURATION_PROPERTY);
        if (doiFilter == null) {
            throw new IllegalStateException(
                    "No filter is configured under '" + DOI_FILTER_CONFIGURATION_PROPERTY + "'");
        }
    }

    private LogicalStatement getBean(String beanId) {
        LogicalStatement statement =
                new DSpace().getServiceManager().getServiceByName(beanId, LogicalStatement.class);
        if (statement == null) {
            throw new IllegalStateException(
                    "No '" + beanId + "' bean found - has item-filters.xml changed?");
        }
        return statement;
    }

    private List<UUID> resolveUuids() throws Exception {
        LinkedHashSet<String> raw = new LinkedHashSet<>(uuidArgs);
        if (StringUtils.isNotBlank(file)) {
            for (String line : Files.readAllLines(Paths.get(file))) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                raw.add(trimmed);
            }
        }

        List<UUID> uuids = new ArrayList<>(raw.size());
        for (String value : raw) {
            try {
                uuids.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                handler.logWarning("Skipping value that is not a valid UUID: " + value);
            }
        }
        return uuids;
    }

    private void writeRow(Context context, CSVWriter csvWriter, Item item) throws Exception {
        boolean archived = isArchived.getResult(context, item);
        boolean notWithdrawn = !isWithdrawn.getResult(context, item);
        boolean dcTypeMatches = typeIsDoctoralThesisDcType.getResult(context, item);
        boolean uhTypeMatches = typeIsDoctoralThesisUhType.getResult(context, item);
        boolean typeMatches = dcTypeMatches || uhTypeMatches;
        boolean doesNotAlreadyHaveDoi = !alreadyHasDoi.getResult(context, item);
        boolean hasBitstreamResult = hasBitstream.getResult(context, item);
        boolean passesFilter = Boolean.TRUE.equals(doiFilter.getResult(context, item));

        csvWriter.writeNext(new String[] {
            item.getID().toString(),
            StringUtils.defaultString(item.getName()),
            Boolean.toString(archived),
            Boolean.toString(notWithdrawn),
            Boolean.toString(dcTypeMatches),
            Boolean.toString(uhTypeMatches),
            Boolean.toString(typeMatches),
            Boolean.toString(doesNotAlreadyHaveDoi),
            Boolean.toString(hasBitstreamResult),
            Boolean.toString(passesFilter)
        });
    }

    private String buildFileName() {
        return "doi-filter-diagnostic-" + System.currentTimeMillis() + ".csv";
    }

    @Override
    public DoiFilterDiagnosticScriptConfiguration getScriptConfiguration() {
        return new DSpace().getServiceManager().getServiceByName("doi-filter-diagnostic",
                DoiFilterDiagnosticScriptConfiguration.class);
    }

}
