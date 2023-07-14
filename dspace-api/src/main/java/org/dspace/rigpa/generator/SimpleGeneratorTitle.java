/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.generator;

import java.util.List;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang.StringUtils;
import org.dspace.content.Item;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.importer.external.metadatamapping.MetadataFieldConfig;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Simplest implementation of a {@link GeneratorOfTitle} bean,
 * that will generate a title using a list of configured  {@link SimpleGeneratorTitle#fieldsToUseForGenerationOfTitle}:
 * <ul>
 *  <li> separated by a {@link SimpleGeneratorTitle#separator} and</li>
 *  <li> optionally prefixed with {@link SimpleGeneratorTitle#titlePrefix}.</li>
 * </ul>
 * Once generated the value can be placed inside the {@link SimpleGeneratorTitle#targetMetadataFiled}.
 * <br/>
 * <br/>
 * <b>You should have only one {@link SimpleGeneratorTitle} for each {@link SimpleGeneratorTitle#entityType}!</b>
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk@4science.com)
 */
public class SimpleGeneratorTitle implements GeneratorOfTitle {

    @NotNull
    private String entityType;
    @NotNull
    private MetadataFieldConfig targetMetadataFiled;

    private String separator;
    private String titlePrefix;
    private List<MetadataFieldConfig> fieldsToUseForGenerationOfTitle;

    @Autowired
    private ItemService itemService;

    public SimpleGeneratorTitle() {
        super();
        this.titlePrefix = getDefaultTitlePrefix();
        this.separator = getDefaultSeparator();
    }

    @Override
    public String generateTitle(Context context, Item item) {
        return fieldsToUseForGenerationOfTitle
            .stream()
            .map(md -> getMetadataFirstValue(item, md))
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.joining(separator, titlePrefix, ""));
    }

    protected String getMetadataFirstValue(Item item, MetadataFieldConfig metadataFiled) {
        return itemService.getMetadataFirstValue(
            item, metadataFiled.getSchema(),
            metadataFiled.getElement(),
            metadataFiled.getQualifier(), Item.ANY
        );
    }

    @Override
    public String getSupportedType() {
        return this.entityType;
    }

    @Override
    public MetadataFieldConfig getTargetMetadataField() {
        return this.targetMetadataFiled;
    }

    public void setSeparator(String separator) {
        this.separator = separator;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public void setTitlePrefix(String titlePrefix) {
        this.titlePrefix = titlePrefix;
    }

    public void setTargetMetadataFiled(MetadataFieldConfig targetMetadataFiled) {
        this.targetMetadataFiled = targetMetadataFiled;
    }

    public void setFieldsToUseForGenerationOfTitle(List<MetadataFieldConfig> fieldsToUseForGenerationOfTitle) {
        this.fieldsToUseForGenerationOfTitle = fieldsToUseForGenerationOfTitle;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (entityType == null ? 0 : entityType.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        SimpleGeneratorTitle other = (SimpleGeneratorTitle) obj;
        if (entityType == null) {
            if (other.entityType != null) {
                return false;
            }
        } else if (!entityType.equals(other.entityType)) {
            return false;
        }
        return true;
    }

}
