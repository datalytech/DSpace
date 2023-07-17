/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static org.dspace.app.rest.matcher.VocabularyMatcher.matchVocabularyEntry;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.commons.lang3.ArrayUtils;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.authority.factory.ContentAuthorityServiceFactory;
import org.dspace.core.factory.CoreServiceFactory;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Integration tests for {@link org.dspace.content.authority.SolrSuggestAuthority}
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk@4science.com)
 */
public class SolrSuggestAuthorityIT extends AbstractControllerIntegrationTest {

    private static String[] defaultChoices;
    private static String choicesPluginDcSubject;
    private static String choicesPresentationDcSubject;
    private static String authorityControlledDcSubject;
    private static String solrSuggestFacetName;

    @BeforeClass
    public static void build() {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();

        defaultChoices =
            configurationService.getArrayProperty("plugin.named.org.dspace.content.authority.ChoiceAuthority");
        choicesPluginDcSubject = configurationService.getProperty("choices.plugin.dc.subject");
        choicesPresentationDcSubject = configurationService.getProperty("choices.presentation.dc.subject");
        authorityControlledDcSubject = configurationService.getProperty("authority.controlled.dc.subject");
        solrSuggestFacetName = configurationService.getProperty("SolrSuggestAuthority.dc_subject.facetname");

        configurationService.setProperty(
            "plugin.named.org.dspace.content.authority.ChoiceAuthority",
            ArrayUtils.add(defaultChoices, "org.dspace.content.authority.SolrSuggestAuthority = SolrSuggestAuthority")
        );
        configurationService.setProperty("choices.plugin.dc.subject", "SolrSuggestAuthority");
        configurationService.setProperty("choices.presentation.dc.subject", "suggest");
        configurationService.setProperty("authority.controlled.dc.subject", "true");
        configurationService.setProperty("SolrSuggestAuthority.dc_subject.facetname", "subject");
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();
        ContentAuthorityServiceFactory.getInstance().getChoiceAuthorityService().clearCache();
    }

    @AfterClass
    public static void tearDown() {
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty(
            "plugin.named.org.dspace.content.authority.ChoiceAuthority",
            defaultChoices
        );
        configurationService.setProperty("choices.plugin.dc.subject", choicesPluginDcSubject);
        configurationService.setProperty("choices.presentation.dc.subject", choicesPresentationDcSubject);
        configurationService.setProperty("authority.controlled.dc.subject", authorityControlledDcSubject);
        configurationService.setProperty("SolrSuggestAuthority.dc_subject.facetname", solrSuggestFacetName);
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();
        ContentAuthorityServiceFactory.getInstance().getChoiceAuthorityService().clearCache();
    }

    @Before
    public void before() {
        context.turnOffAuthorisationSystem();
        ConfigurationService configurationService = DSpaceServicesFactory.getInstance().getConfigurationService();
        configurationService.setProperty(
            "plugin.named.org.dspace.content.authority.ChoiceAuthority",
            ArrayUtils.add(defaultChoices, "org.dspace.content.authority.SolrSuggestAuthority = SolrSuggestAuthority")
        );
        configurationService.setProperty("choices.plugin.dc.subject", "SolrSuggestAuthority");
        configurationService.setProperty("choices.presentation.dc.subject", "suggest");
        configurationService.setProperty("authority.controlled.dc.subject", "true");
        configurationService.setProperty("SolrSuggestAuthority.dc_subject.facetname", "subject");
        CoreServiceFactory.getInstance().getPluginService().clearNamedPluginClasses();
        ContentAuthorityServiceFactory.getInstance().getChoiceAuthorityService().clearCache();
        context.restoreAuthSystemState();
    }

    @Test
    public void solrSuggestAuthorityNoSuggestionsTest() throws Exception {
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "test"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.entries").isEmpty())
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(0)));
    }

    @Test
    public void solrSuggestAuthorityTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                                           .withName("Test collection")
                                           .withEntityType("Publication")
                                           .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 1")
                   .withSubject("test subject")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 2")
                   .withSubject("subject test")
                   .build();

        context.restoreAuthSystemState();

        // expect to get only one value, because the control is based on the prefix
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "test"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.entries", contains(
                                matchVocabularyEntry("test subject", "test subject", "vocabularyEntry"))))
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(1)));
    }

    @Test
    public void solrSuggestAuthorityIsNotPrefixTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                                           .withName("Test collection")
                                           .withEntityType("Publication")
                                           .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 1")
                   .withSubject("first test subject")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 2")
                   .withSubject("second subject test")
                   .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "test"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.entries").isEmpty())
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(0)));
    }

    @Test
    public void solrSuggestAuthorityPartOfPrefixTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                                           .withName("Test collection")
                                           .withEntityType("Publication")
                                           .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 1")
                   .withSubject("coaching")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 2")
                   .withSubject("committed relationships")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 3")
                   .withSubject("community support")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 4")
                   .withSubject("Completed Project")
                   .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "comm"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.entries", containsInAnyOrder(
                          matchVocabularyEntry("committed relationships", "committed relationships", "vocabularyEntry"),
                          matchVocabularyEntry("community support", "community support", "vocabularyEntry")
                          )))
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(2)));

        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "co"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.entries", containsInAnyOrder(
                          matchVocabularyEntry("coaching", "coaching", "vocabularyEntry"),
                          matchVocabularyEntry("Completed Project", "Completed Project", "vocabularyEntry"),
                          matchVocabularyEntry("community support", "community support", "vocabularyEntry"),
                          matchVocabularyEntry("committed relationships", "committed relationships", "vocabularyEntry")
                          )))
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(4)));
    }

    @Test
    public void solrSuggestAuthorityUnprocessableEntityTest() throws Exception {
        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", ""))
                        .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void solrSuggestAuthorityExactFilterTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context).build();
        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                                           .withName("Test collection")
                                           .withEntityType("Publication")
                                           .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 1")
                   .withSubject("coaching")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 2")
                   .withSubject("committed relationships")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 3")
                   .withSubject("community support")
                   .build();

        ItemBuilder.createItem(context, col1)
                   .withTitle("Publication title 4")
                   .withSubject("Completed Project")
                   .build();

        context.restoreAuthSystemState();

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "comm")
                        .param("exact", "true"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(0)));

        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "committed")
                        .param("exact", "true"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(0)));

        getClient(token).perform(get("/api/submission/vocabularies/SolrSuggestAuthority/entries")
                        .param("filter", "coaching")
                        .param("exact", "true"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$._embedded.entries", contains(
                                   matchVocabularyEntry("coaching", "coaching", "vocabularyEntry")
                                   )))
                        .andExpect(jsonPath("$.page.totalElements", Matchers.is(1)));
    }

}
