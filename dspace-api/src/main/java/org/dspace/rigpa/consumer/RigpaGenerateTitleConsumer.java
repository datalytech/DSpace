/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.consumer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
import org.dspace.kernel.ServiceManager;
import org.dspace.rigpa.generator.GeneratorOfTitle;
import org.dspace.rigpa.generator.SimpleGeneratorTitle;
import org.dspace.utils.DSpace;

/**
 * The consumer is used to generate custom titles according to configurations of the generators
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk@4science.com)
 */
public class RigpaGenerateTitleConsumer implements Consumer {

    private Set<UUID> itemsAlreadyProcessed = new HashSet<UUID>();

    private List<GeneratorOfTitle> generetors = new ArrayList<>();

    private ItemService itemService;

    @Override
    public void initialize() throws Exception {
        ServiceManager serviceManager = new DSpace().getServiceManager();
        this.itemService = ContentServiceFactory.getInstance().getItemService();
        var simpleGenerator = serviceManager.getServiceByName(SimpleGeneratorTitle.class.getName(),
                                                              SimpleGeneratorTitle.class);
        generetors.add(simpleGenerator);
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

        var entityType = itemService.getMetadataFirstValue(item, "dspace","entity","type", null);

        GeneratorOfTitle generator = getGenerator(entityType);

        if (Objects.nonNull(generator)) {
            MetadataFieldConfig targetMetadataField = generator.getTargetMetadataField();
            var generatedValue = generator.generateTitle(context, item);
            if (StringUtils.isNotBlank(generatedValue)) {
                // remove current target value
                itemService.clearMetadata(context, item, targetMetadataField.getSchema(),
                                                         targetMetadataField.getQualifier(),
                                                         targetMetadataField.getElement(), null);
                // add new generated value
                itemService.addMetadata(context, item, targetMetadataField.getSchema(),
                                                         targetMetadataField.getQualifier(),
                                                         targetMetadataField.getElement(), null, generatedValue);
            }
        }

    }

    private GeneratorOfTitle getGenerator(String entityType) {
        if (StringUtils.isBlank(entityType)) {
            return null;
        }

        for (GeneratorOfTitle generator : generetors) {
            if (StringUtils.equals(generator.getSupportedType(), entityType)) {
                return generator;
            }
        }
        return null;
    }

    @Override
    public void end(Context context) throws Exception {
        this.itemsAlreadyProcessed.clear();
    }

    @Override
    public void finish(Context context) throws Exception { }

}
