/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.generator;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.dspace.content.Item;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.importer.external.metadatamapping.MetadataFieldConfig;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author Mykhaylo Boychuk (mykhaylo.boychuk@4science.com)
 */
public class SimpleGeneratorTitle implements GeneratorOfTitle {

    private String separetor;
    private String entityType;
    private String titlePrefix;
    private MetadataFieldConfig targetMetadataFiled;
    private List<MetadataFieldConfig> fieldsToUseForGenerationOfTitle;

    @Autowired
    private ItemService itemService;

    @Override
    public String generateTitle(Context context, Item item) {
        StringBuilder titleBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(this.titlePrefix)) {
            titleBuilder.append(this.titlePrefix);
        }

        if (StringUtils.isBlank(this.separetor)) {
            this.separetor = getDefaultSeparetor();
        }

        for (MetadataFieldConfig metadataFiled : fieldsToUseForGenerationOfTitle) {
            var value = itemService.getMetadataFirstValue(item, metadataFiled.getSchema(),
                                                                metadataFiled.getElement(),
                                                                metadataFiled.getQualifier(), null);
            if (StringUtils.isNotBlank(value)) {
                if (StringUtils.isNotBlank(titleBuilder.toString())) {
                    titleBuilder.append(this.separetor);
                }
                titleBuilder.append(value);
            }
        }
        return titleBuilder.toString();
    }

    @Override
    public String getSupportedType() {
        return this.entityType;
    }

    @Override
    public MetadataFieldConfig getTargetMetadataField() {
        return this.targetMetadataFiled;
    }

    public void setSeparetor(String separetor) {
        this.separetor = separetor;
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

}
