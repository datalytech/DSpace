/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.template;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.template.generator.TemplateValueGenerator;
import org.dspace.core.Context;
import org.junit.Test;

/**
 * @author Corrado Lombardi (corrado.lombardi at 4science.it)
 */
public class PlaceholderTemplateItemValueTest {

    @Test
    public void valueWithoutPlaceholderDoesNotApply() {
        final boolean appliesTo = new PlaceholderTemplateItemValue(Collections.emptyMap()).appliesTo("Simple value");

        assertThat(appliesTo, is(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void tryingToGetValueForASimpleMetadataThrowsException() {

        new PlaceholderTemplateItemValue(Collections.emptyMap()).value(
            mock(Context.class),
            mock(Item.class),
            mock(Item.class),
            metadataValue("Simple value"));
    }

    @Test(expected = RuntimeException.class)
    public void generatorNotFoundThrowsException() {

        final Context context = mock(Context.class);
        final Item item = mock(Item.class);
        final Item templateItem = mock(Item.class);
        final MetadataValue metadataValue = metadataValue("###PLACEHOLDER###");

        final PlaceholderTemplateItemValue templateItemValue = new PlaceholderTemplateItemValue(
            Collections.singletonMap("dummy", generator(metadataValue("returned value")))
        );

        templateItemValue.value(context, item, templateItem, metadataValue);
    }

    @Test
    public void valueFromGeneratorIsReturned() {
        final Context context = mock(Context.class);
        final Item item = mock(Item.class);
        final Item templateItem = mock(Item.class);
        final MetadataValue metadataValue = metadataValue("###PLACEHOLDER.doSomething###");

        final Map<String, TemplateValueGenerator> generatorMap = new HashMap<>();
        generatorMap.put("dummy", generator(metadataValue("from dummy")));
        generatorMap.put("placeholder", generator(metadataValue("something done")));


        final PlaceholderTemplateItemValue templateItemValue = new PlaceholderTemplateItemValue(generatorMap);

        final boolean appliesTo = templateItemValue.appliesTo(metadataValue.getValue());
        final MetadataValue actualValue = templateItemValue.value(context, item, templateItem, metadataValue);

        assertThat(appliesTo, is(true));
        assertThat(actualValue.getValue(), is("something done"));
    }

    private MetadataValue metadataValue(final String value) {
        final MetadataValue metadataValue = mock(MetadataValue.class);
        when(metadataValue.getValue()).thenReturn(value);
        return metadataValue;
    }

    private TemplateValueGenerator generator(final MetadataValue expectedMetadataValue) {

        return (context, item, templateItem, metadataValue, extraParams) -> expectedMetadataValue;
    }
}
