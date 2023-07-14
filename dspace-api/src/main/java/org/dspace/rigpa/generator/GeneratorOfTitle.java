/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.generator;

import org.apache.commons.lang3.StringUtils;
import org.dspace.content.Item;
import org.dspace.core.Context;
import org.dspace.importer.external.metadatamapping.MetadataFieldConfig;
import org.dspace.rigpa.consumer.RigpaGenerateTitleConsumer;

/**
 * This interface declares all the methods needed to generate
 * a title with the consumer {@link RigpaGenerateTitleConsumer}
 * 
 * @author Mykhaylo Boychuk (mykhaylo.boychuk@4science.com)
 */
public interface GeneratorOfTitle {

    public default String getDefaultSeparator() {
        return StringUtils.SPACE;
    }

    public default String getDefaultTitlePrefix() {
        return "";
    }

    public String generateTitle(Context context, Item item);

    public MetadataFieldConfig getTargetMetadataField();

    public String getSupportedType();

}
