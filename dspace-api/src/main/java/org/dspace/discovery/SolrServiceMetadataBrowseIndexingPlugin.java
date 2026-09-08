/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.discovery;

import static org.dspace.discovery.SolrServiceImpl.SOLR_FIELD_SUFFIX_FACET_PREFIXES;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.common.SolrInputDocument;
import org.dspace.browse.BrowseException;
import org.dspace.browse.BrowseIndex;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.authority.service.ChoiceAuthorityService;
import org.dspace.content.authority.service.MetadataAuthorityService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.discovery.indexobject.IndexableItem;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.dspace.sort.OrderFormat;
import org.dspace.sort.SortException;
import org.dspace.sort.SortOption;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A Solr Indexing plugin for the "metadata" browse index type.
 * <p>
 * For Example:
 * webui.browse.index.2 = author:metadata:dc.contributor.*\,dc.creator:text
 * OR
 * webui.browse.index.4 = subject:metadata:dc.subject.*:text
 * <p>
 * This plugin was based heavily on the old (DSpace 5.x or below), SolrBrowseCreateDAO
 * class, specifically its "additionalIndex()" method, which used to perform this function.
 *
 * @author Tim Donohue
 */
public class SolrServiceMetadataBrowseIndexingPlugin implements SolrServiceIndexPlugin {

    private static final Logger log = LogManager.getLogger(SolrServiceMetadataBrowseIndexingPlugin.class);

    /**
     * Data type of a browse index that lists the year of a date field instead of
     * the full date, i.e. webui.browse.index.n = year:metadata:dc.date.issued:year
     */
    private static final String YEAR_DATA_TYPE = "year";

    /**
     * The leading year of a date, as an ISO-8601 date starts with it.
     */
    private static final Pattern YEAR_PATTERN = Pattern.compile("^\\s*(-?\\d{4})");

    @Autowired(required = true)
    protected ItemService itemService;

    @Autowired(required = true)
    protected MetadataAuthorityService metadataAuthorityService;

    @Autowired(required = true)
    protected ChoiceAuthorityService choiceAuthorityService;

    @Override
    public void additionalIndex(Context context, IndexableObject indexableObject, SolrInputDocument document) {
        // Only works for Items
        if (!(indexableObject instanceof IndexableItem)) {
            return;
        }
        Item item = ((IndexableItem) indexableObject).getIndexedObject();
        Collection collection = item.getOwningCollection();

        // Get the currently configured browse indexes
        BrowseIndex[] bis;
        try {
            bis = BrowseIndex.getBrowseIndices();
        } catch (BrowseException e) {
            log.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }

        // Faceting for metadata browsing. It is different than search facet
        // because if there are authority with variants support we want all the
        // variants to go in the facet... they are sorted by count so just the
        // prefered label is relevant
        for (BrowseIndex bi : bis) {
            log.debug("Indexing for item " + item.getID() + ", for index: "
                          + bi.getTableName());

            // ONLY perform indexing for "metadata" type indices
            if (bi.isMetadataIndex()) {
                // Generate our bits of metadata (so getMdBits() can be used below)
                bi.generateMdBits();

                // values to show in the browse list
                Set<String> distFValues = new HashSet<>();
                // value for lookup without authority
                Set<String> distFVal = new HashSet<>();
                // value for lookup with authority
                Set<String> distFAuths = new HashSet<>();
                // value for lookup when partial search (the item mapper tool use it)
                Set<String> distValuesForAC = new HashSet<>();

                // now index the new details - but only if it's archived or
                // withdrawn
                if (item.isArchived() || item.isWithdrawn()) {
                    // get the metadata from the item
                    for (int mdIdx = 0; mdIdx < bi.getMetadataCount(); mdIdx++) {
                        String[] md = bi.getMdBits(mdIdx);
                        List<MetadataValue> values = itemService.getMetadata(item, md[0], md[1],
                                                                             md[2], Item.ANY);

                        // if we have values to index on, then do so
                        if (values != null && values.size() > 0) {
                            int minConfidence = metadataAuthorityService
                                .getMinConfidence(values.get(0).getMetadataField());
                            boolean ignoreAuthority =
                                DSpaceServicesFactory
                                    .getInstance()
                                    .getConfigurationService()
                                    .getPropertyAsType("discovery.browse.authority.ignore." + bi.getName(),
                                                       DSpaceServicesFactory.getInstance()
                                                                            .getConfigurationService()
                                                                            .getPropertyAsType(
                                                                                    "discovery.browse.authority.ignore",
                                                                                    Boolean.FALSE),
                                                       true);

                            for (int x = 0; x < values.size(); x++) {
                                MetadataValue val = values.get(x);
                                boolean hasChoiceAuthority = choiceAuthorityService
                                        .isChoicesConfigured(metadataAuthorityService
                                                .makeFieldKey(val.getSchema(), val.getElement(), val.getQualifier())
                                                .toString(), item.getType(), collection);

                                // Ensure that there is a value to index before
                                // inserting it
                                if (StringUtils.isEmpty(val.getValue())) {
                                    log.error("Null metadata value for item "
                                                  + item.getID()
                                                  + ", field: "
                                                  + val.getMetadataField().toString()
                                    );
                                } else {
                                    // what the browse list shows and is looked up by, which is
                                    // the metadata value itself unless the index asks for less
                                    String browseValue = getBrowseValue(bi, val);
                                    if (browseValue == null) {
                                        log.debug("Skipping item=" + item.getID()
                                                      + ", field=" + val.getMetadataField().toString()
                                                      + ", value=" + val.getValue()
                                                      + " (NO " + bi.getDataType() + " TO INDEX)");
                                        continue;
                                    }
                                    if (bi.isAuthorityIndex()
                                            && (val.getAuthority() == null || val.getConfidence() < minConfidence)) {
                                        // if we have an authority index only
                                        // authored metadata will go here!
                                        log.debug("Skipping item="
                                                      + item.getID() + ", field="
                                                      + val.getMetadataField().toString()
                                                      + ", value=" + val.getValue()
                                                      + ", authority="
                                                      + val.getAuthority()
                                                      + ", confidence="
                                                      + val.getConfidence()
                                                      + " (BAD AUTHORITY)");
                                        continue;
                                    }

                                    // is there any valid (with appropriate
                                    // confidence) authority key?
                                    if ((ignoreAuthority && !bi.isAuthorityIndex())
                                            || (val.getAuthority() != null && val.getConfidence() >= minConfidence)) {
                                        distFAuths.add(val.getAuthority());
                                        distValuesForAC.add(browseValue);

                                        String preferedLabel = null;
                                        Boolean generalSetting = DSpaceServicesFactory.getInstance()
                                                .getConfigurationService().getPropertyAsType(
                                                        "discovery.browse.authority.ignore-preferred",
                                                        Boolean.FALSE);
                                        boolean ignorePrefered = DSpaceServicesFactory.getInstance()
                                                .getConfigurationService().getPropertyAsType(
                                                        "discovery.browse.authority.ignore-preferred." + bi.getName(),
                                                        generalSetting, true);
                                        if (!ignorePrefered && hasChoiceAuthority) {
                                            try {
                                                preferedLabel = choiceAuthorityService
                                                        .getLabel(val, Constants.ITEM, collection,
                                                                val.getLanguage());
                                            } catch (Exception e) {
                                                log.warn("Failed to get preferred label for "
                                                             + val.getMetadataField().toString('.'), e);
                                            }
                                            // ChoiceAuthority implementations fall back to returning the
                                            // authority key itself when it cannot be resolved (i.e. the
                                            // linked entity has been deleted or was never created). Such a
                                            // key is not a label: indexing it would make the browse list
                                            // show a bare uuid instead of the name. Discard it so that the
                                            // metadata value stored on the item is used instead.
                                            if (StringUtils.equals(preferedLabel, val.getAuthority())) {
                                                log.warn("Unresolvable authority " + val.getAuthority()
                                                             + " for " + val.getMetadataField().toString('.')
                                                             + " on item " + item.getID()
                                                             + ": falling back to the metadata value");
                                                preferedLabel = null;
                                            }
                                        }
                                        List<String> variants = null;

                                        boolean ignoreVariants =
                                            DSpaceServicesFactory
                                                .getInstance()
                                                .getConfigurationService()
                                                .getPropertyAsType("discovery.browse.authority.ignore-variants."
                                                                       + bi.getName(),
                                                                   DSpaceServicesFactory
                                                                       .getInstance()
                                                                       .getConfigurationService()
                                                                       .getPropertyAsType(
                                                                           "discovery.browse.authority.ignore-variants",
                                                                           Boolean.FALSE),
                                                                   true);
                                        if (!ignoreVariants && hasChoiceAuthority) {
                                            try {
                                                variants = choiceAuthorityService
                                                    .getVariants(val, Constants.ITEM, collection);
                                            } catch (Exception e) {
                                                log.warn("Failed to get variants for "
                                                             + val.getMetadataField().toString(), e);
                                            }
                                        }

                                        if (StringUtils
                                            .isNotBlank(preferedLabel)) {
                                            String nLabel = OrderFormat
                                                .makeSortString(
                                                    preferedLabel,
                                                    val.getLanguage(),
                                                    bi.getDataType());
                                            distFValues
                                                .add(nLabel
                                                         + SearchUtils.FILTER_SEPARATOR
                                                         + preferedLabel
                                                         + SearchUtils.AUTHORITY_SEPARATOR
                                                         + val.getAuthority());
                                            distValuesForAC.add(preferedLabel);
                                        } else {
                                            String nVal = OrderFormat.makeSortString(browseValue,
                                                    val.getLanguage(), bi.getDataType());
                                            distFValues.add(nVal
                                                     + SearchUtils.FILTER_SEPARATOR
                                                     + browseValue
                                                     + SearchUtils.AUTHORITY_SEPARATOR
                                                     + val.getAuthority());
                                            distValuesForAC.add(nVal);
                                        }

                                        if (variants != null) {
                                            for (String var : variants) {
                                                String nVal = OrderFormat
                                                    .makeSortString(
                                                        var,
                                                        val.getLanguage(),
                                                        bi.getDataType());
                                                distFValues
                                                    .add(nVal
                                                             + SearchUtils.FILTER_SEPARATOR
                                                             + var
                                                             + SearchUtils.AUTHORITY_SEPARATOR
                                                             + val.getAuthority());
                                                distValuesForAC.add(var);
                                            }
                                        }
                                    } else {
                                        // put it in the browse index as if it
                                        // hasn't have an authority key

                                        // get the normalised version of the value
                                        String nVal = OrderFormat
                                            .makeSortString(
                                                browseValue,
                                                val.getLanguage(),
                                                bi.getDataType());
                                        distFValues
                                            .add(nVal
                                                     + SearchUtils.FILTER_SEPARATOR
                                                     + browseValue);
                                        distFVal.add(browseValue);
                                        distValuesForAC.add(browseValue);
                                    }
                                }
                            }
                        }
                    }
                }
                for (String facet : distFValues) {
                    document.addField(bi.getDistinctTableName() + "_filter", facet);
                    document.addField(bi.getDistinctTableName() + SOLR_FIELD_SUFFIX_FACET_PREFIXES, facet);
                }
                for (String facet : distFAuths) {
                    document.addField(bi.getDistinctTableName() + "_authority_filter", facet);
                }
                for (String facet : distValuesForAC) {
                    document.addField(bi.getDistinctTableName() + "_partial", facet);
                }
                for (String facet : distFVal) {
                    document.addField(bi.getDistinctTableName() + "_value_filter", facet);
                }
            }
        }

        // Add sorting options as configurated for the browse system
        try {
            for (SortOption so : SortOption.getSortOptions()) {
                List<MetadataValue> dcvalue = itemService.getMetadataByMetadataString(item, so.getMetadata());
                if (dcvalue != null && dcvalue.size() > 0) {
                    String nValue = OrderFormat
                        .makeSortString(dcvalue.get(0).getValue(),
                                        dcvalue.get(0).getLanguage(), so.getType());
                    document.addField("bi_sort_" + so.getNumber() + "_sort", nValue);
                }
            }
        } catch (SortException e) {
            // we can't solve it so rethrow as runtime exception
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * The value a metadata value contributes to the given browse index.
     * <p>
     * Every index browses by the metadata value itself, except an index declared
     * with the "year" data type: that one keeps only the leading year of the date,
     * so that 2019-05-12 and 2019-11-03 end up under the same browse entry.
     *
     * @param bi  the browse index being populated
     * @param val the metadata value to index
     * @return the value to index, or null when this value has nothing to
     *         contribute to the index
     */
    private String getBrowseValue(BrowseIndex bi, MetadataValue val) {
        if (!YEAR_DATA_TYPE.equalsIgnoreCase(bi.getDataType())) {
            return val.getValue();
        }
        Matcher matcher = YEAR_PATTERN.matcher(val.getValue());
        return matcher.find() ? matcher.group(1) : null;
    }

}
