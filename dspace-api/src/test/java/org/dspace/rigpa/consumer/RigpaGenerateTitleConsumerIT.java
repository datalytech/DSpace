/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.any;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.dspace.AbstractIntegrationTestWithDatabase;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.WorkflowItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@link RigpaGenerateTitleConsumer}.
 *
 * @author Vincenzo Mecca (vins01-4science - vincenzo.mecca at 4science.com)
 *
 */
public class RigpaGenerateTitleConsumerIT extends AbstractIntegrationTestWithDatabase {

    private static final String RP01 = "RP01";
    private static final String EN = "en";
    private static final String RP01_EN = "RP01 en";
    private static final String PREFIX_RIGPA_RP01_EN = "RIGPA:RP01-en";

    private Collection rigpaPublicationsCollection;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        context.turnOffAuthorisationSystem();

        parentCommunity =
            CommunityBuilder.createCommunity(context)
                .withName("Parent community")
                .build();

        rigpaPublicationsCollection = createCollection("Publications", "RigpaPublication");

        context.restoreAuthSystemState();
    }

    @Test
    public void testNotGenerateTitleForCustomEntity() throws Exception {

        context.turnOffAuthorisationSystem();

        Collection customCollection =
            CollectionBuilder.createCollection(context, parentCommunity)
                .withName("OrgUnits")
                .withEntityType("CustomEntity")
                .build();

        Item customItem =
            ItemBuilder.createItem(context, customCollection)
                .withIdentifier("Default Identifier")
                .withOtherIdentifier("Other Identifier")
                .withLanguage(EN)
                .build();

        XmlWorkflowItem workflowItem =
            WorkflowItemBuilder.createWorkflowItem(context, customCollection)
                .withAuthor("custom author")
                .build();

        Item item = workflowItem.getItem();

        context.restoreAuthSystemState();
        context.commit();

        customItem = context.reloadEntity(customItem);
        item = context.reloadEntity(item);

        assertThat(customItem, Matchers.notNullValue());

        List<MetadataValue> metadata = customItem.getMetadata();
        assertThat(
            metadata,
            not(
                hasMetadata("dc", "title")
            )
        );

        assertThat(item, Matchers.notNullValue());
        assertThat(item.getMetadata(), not(empty()));

        metadata = item.getMetadata();
        assertThat(
            metadata,
            not(
                hasMetadata("dc", "title")
            )
        );

    }

    @Test
    public void testGenerateTitleForRigpaPublication() throws Exception {

        context.turnOffAuthorisationSystem();

        Item rigpaPublication =
            ItemBuilder.createItem(context, rigpaPublicationsCollection)
                .withIdentifier("P001")
                .withOtherIdentifier(RP01)
                .withLanguage(EN)
                .withIssueDate("2023-07-14")
                .build();

        context.restoreAuthSystemState();
        context.commit();

        rigpaPublication = context.reloadEntity(rigpaPublication);

        assertThat(rigpaPublication, Matchers.notNullValue());

        List<MetadataValue> metadata = rigpaPublication.getMetadata();
        assertThat(
            metadata,
            hasMetadata("dc", "title")
        );
        assertThat(
            metadata,
            hasMetadataValue("dc", "title", RP01_EN)
        );

    }

    @Test
    public void testOverwriteTitleForRigpaPublication() throws Exception {

        context.turnOffAuthorisationSystem();

        Item rigpaPublication =
            ItemBuilder.createItem(context, rigpaPublicationsCollection)
                .withIdentifier("P001")
                .withOtherIdentifier(RP01)
                .withLanguage(EN)
                .withIssueDate("2023-07-14")
                .withTitle("Hello World!")
                .build();

        context.restoreAuthSystemState();
        context.commit();

        rigpaPublication = context.reloadEntity(rigpaPublication);

        assertThat(rigpaPublication, Matchers.notNullValue());

        List<MetadataValue> metadata = rigpaPublication.getMetadata();
        assertThat(
            metadata,
            hasMetadata("dc", "title")
        );
        assertThat(
            metadata,
            not(hasMetadataValue("dc", "title", "Hello World!"))
        );
        assertThat(
            metadata,
            hasMetadataValue("dc", "title", RP01_EN)
        );

    }

    @Test
    public void testGenerateTitleForRigpaPrefixPublication() throws Exception {

        context.turnOffAuthorisationSystem();

        rigpaPublicationsCollection = createCollection("Publications", "RigpaPrefixPublication");

        Item rigpaPublication =
            ItemBuilder.createItem(context, rigpaPublicationsCollection)
                .withIdentifier("P001")
                .withOtherIdentifier(RP01)
                .withLanguage(EN)
                .withIssueDate("2023-07-14")
                .build();

        context.restoreAuthSystemState();
        context.commit();

        rigpaPublication = context.reloadEntity(rigpaPublication);

        assertThat(rigpaPublication, Matchers.notNullValue());

        List<MetadataValue> metadata = rigpaPublication.getMetadata();
        assertThat(
            metadata,
            hasMetadata("dc", "title")
        );
        assertThat(
            metadata,
            not(hasMetadataValue("dc", "title", "Hello World!"))
        );
        assertThat(
            metadata,
            hasMetadataValue("dc", "title", PREFIX_RIGPA_RP01_EN)
        );

    }

    protected Matcher<Iterable<? super MetadataValue>> hasMetadata(
        String schema, String element
    ) {
        return hasItem(
            allOf(
                hasSchema(equalTo(schema)),
                hasElement(equalTo(element)),
                hasQualifier(nullValue()),
                hasValue(any(String.class))
            )
        );
    }

    protected Matcher<Iterable<? super MetadataValue>> hasMetadata(
        String schema, String element, String qualifier
    ) {
        return hasItem(
            allOf(
                hasSchema(equalTo(schema)),
                hasElement(equalTo(element)),
                hasQualifier(equalTo(qualifier)),
                hasValue(equalTo(any(String.class)))
            )
        );
    }

    protected Matcher<Iterable<? super MetadataValue>> hasMetadataValue(
        String schema, String element, String qualifier, String value
    ) {
        return hasItem(
            allOf(
                hasSchema(equalTo(schema)),
                hasElement(equalTo(element)),
                hasQualifier(equalTo(qualifier)),
                hasValue(equalTo(value))
            )
        );
    }

    protected Matcher<Iterable<? super MetadataValue>> hasMetadataValue(
        String schema, String element, String value
    ) {
        return hasItem(
            allOf(
                hasSchema(equalTo(schema)),
                hasElement(equalTo(element)),
                hasQualifier(nullValue()),
                hasValue(equalTo(value))
            )
        );
    }

    protected Matcher<Iterable<? super MetadataValue>> matchMetadataValue(
        Matcher<? super String> schemaMatcher, Matcher<? super String> elementMatcher,
        Matcher<? super String> qualifierMatcher, Matcher<? super String> valueMatcher
    ) {
        return hasItem(
            allOf(
                hasSchema(schemaMatcher),
                hasElement(elementMatcher),
                hasQualifier(qualifierMatcher),
                hasValue(valueMatcher)
            )
        );
    }

    protected Matcher<MetadataValue> hasSchema(Matcher<? super String> schemaMatcher) {
        return hasMetadataField(
            hasProperty(
                "metadataSchema",
                hasProperty(
                    "name",
                    schemaMatcher
                )
            )
        );
    }

    protected Matcher<MetadataValue> hasQualifier(Matcher<? super String> qualifierMatcher) {
        return hasMetadataField(
            hasProperty(
                "qualifier",
                qualifierMatcher
            )
        );
    }

    protected Matcher<MetadataValue> hasElement(Matcher<? super String> elementMatcher) {
        return hasMetadataField(
            hasProperty(
                "element",
                elementMatcher
            )
        );
    }

    protected Matcher<MetadataValue> hasMetadataField(Matcher<? super String> fieldMatcher) {
        return hasProperty(
            "metadataField",
            fieldMatcher
        );
    }

    protected Matcher<MetadataValue> hasValue(Matcher<? super String> valueMatcher) {
        return hasProperty(
            "value",
            valueMatcher
        );
    }

    private Collection createCollection(String name, String entityType) {
        return CollectionBuilder.createCollection(context, parentCommunity)
            .withName(name)
            .withEntityType(entityType)
            .build();
    }

}
