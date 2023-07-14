/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.consumer;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.event.Consumer;
import org.dspace.event.Event;
import org.dspace.importer.external.metadatamapping.MetadataFieldConfig;
import org.dspace.rigpa.generator.service.factory.RigpaGeneratorConfigFactory;

/**
 * The consumer is used to generate custom titles according to configurations of the generators
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk@4science.com)
 */
public class RigpaGenerateTitleConsumer implements Consumer {

    public static final String CONSUMER_NAME = "generateTitle";

    private Set<UUID> itemsAlreadyProcessed = new HashSet<UUID>();

    private ItemService itemService;

    private RigpaGeneratorConfigFactory generatorConfigFactory;

    @Override
    public void initialize() throws Exception {
        itemService = ContentServiceFactory.getInstance().getItemService();
        generatorConfigFactory = RigpaGeneratorConfigFactory.getInstance();
    }

    @Override
    public void consume(Context context, Event event) throws Exception {
        // only items & only install events
        if (event.getSubjectType() != Constants.ITEM || event.getEventType() != Event.INSTALL
            || itemsAlreadyProcessed.contains(event.getSubjectID())) {
            return;
        }

        // get the item (should be archived)
        Item item = (Item) event.getSubject(context);
        if (Objects.isNull(item) || !item.isArchived()) {
            return;
        }

        String entityType = itemService.getMetadataFirstValue(item, "dspace","entity","type", Item.ANY);

        Optional.ofNullable(
                generatorConfigFactory.getGeneratorFor(entityType)
            )
            .ifPresent(g ->
                processGeneratedValue(
                    context, item, g.generateTitle(context, item), g.getTargetMetadataField()
                )
            );

        this.itemsAlreadyProcessed.add(item.getID());
    }

    protected void processGeneratedValue(
        Context context, Item item, String generatedValue, MetadataFieldConfig metadataField
    ) {
        if (StringUtils.isNotBlank(generatedValue)) {
            try {
             // remove current target value
                itemService.clearMetadata(
                    context, item,
                    metadataField.getSchema(),
                    metadataField.getElement(),
                    metadataField.getQualifier(),
                    Item.ANY
                );
                // add new generated value
                itemService.addMetadata(
                    context, item,
                    metadataField.getSchema(),
                    metadataField.getElement(),
                    metadataField.getQualifier(),
                    Item.ANY, generatedValue
                );
                itemService.update(context, item);
            } catch (Exception e) {
                throw new RuntimeException(
                    "Cannot process generated title in " + RigpaGenerateTitleConsumer.class + " consumer!",
                    e
                );
            }
        }
    }

    @Override
    public void end(Context context) throws Exception {
        this.itemsAlreadyProcessed.clear();
    }

    @Override
    public void finish(Context context) throws Exception { }

}
