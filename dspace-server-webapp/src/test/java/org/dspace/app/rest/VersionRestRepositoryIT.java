/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;
import static com.jayway.jsonpath.JsonPath.read;
import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.dspace.app.rest.matcher.ItemMatcher;
import org.dspace.app.rest.matcher.VersionMatcher;
import org.dspace.app.rest.model.patch.AddOperation;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.RemoveOperation;
import org.dspace.app.rest.model.patch.ReplaceOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.EPersonBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.VersionBuilder;
import org.dspace.builder.WorkflowItemBuilder;
import org.dspace.builder.WorkspaceItemBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.InstallItemService;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.eperson.EPerson;
import org.dspace.services.ConfigurationService;
import org.dspace.versioning.Version;
import org.dspace.versioning.VersionHistory;
import org.dspace.versioning.service.VersionHistoryService;
import org.dspace.xmlworkflow.storedcomponents.XmlWorkflowItem;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.rest.webmvc.RestMediaTypes;
import org.springframework.http.MediaType;

/**
 * Integration test class for the version endpoint.
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk at 4science.it)
 */
public class VersionRestRepositoryIT extends AbstractControllerIntegrationTest {

    private Item item;

    private Version version;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private InstallItemService installItemService;

    @Autowired
    private WorkspaceItemService workspaceItemService;

    @Autowired
    private VersionHistoryService versionHistoryService;

    @Before
    public void setup() throws SQLException, AuthorizeException {
        context.turnOffAuthorisationSystem();

        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. Three public items that are readable by Anonymous with different subjects
        item = ItemBuilder.createItem(context, col1)
                          .withTitle("Public item 1")
                          .withIssueDate("2017-10-17")
                          .withAuthor("Smith, Donald").withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        version = VersionBuilder.createVersion(context, item, "Fixing some typos").build();
        context.restoreAuthSystemState();
    }

    @Test
    public void findOneTest() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))));
    }

    @Test
    public void findOneSubmitterNameVisisbleTest() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))))
                             .andExpect(jsonPath("$.submitterName", Matchers.is(version.getEPerson().getFullName())));
    }

    @Test
    public void findOneSubmitterNameConfigurationPropertyFalseAdminUserLinkVisibleTest() throws Exception {

        configurationService.setProperty("versioning.item.history.include.submitter", false);

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))))
                             .andExpect(jsonPath("$.submitterName", Matchers.is(version.getEPerson().getFullName())));

        configurationService.setProperty("versioning.item.history.include.submitter", true);

    }

    @Test
    public void findOneSubmitterNameConfigurationPropertyTrueNormalUserLinkVisibleTest() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))))
                             .andExpect(jsonPath("$.submitterName", Matchers.is(version.getEPerson().getFullName())));

    }

    @Test
    public void findOneSubmitterNameConfigurationPropertyTrueAnonUserLinkVisibleTest() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))))
                             .andExpect(jsonPath("$.submitterName", Matchers.is(version.getEPerson().getFullName())));

    }

    @Test
    public void findOneSubmitterNameConfigurationPropertyFalseNormalUserLinkInvisibleTest() throws Exception {

        configurationService.setProperty("versioning.item.history.include.submitter", false);

        String adminToken = getAuthToken(eperson.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))))
                             .andExpect(jsonPath("$.submitterName").doesNotExist());

        configurationService.setProperty("versioning.item.history.include.submitter", true);

    }
    @Test
    public void findOneUnauthorizedTest() throws Exception {

        getClient().perform(get("/api/versioning/versions/" + version.getID()))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void findOneForbiddenTest() throws Exception {

        configurationService.setProperty("versioning.item.history.view.admin", true);

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/versioning/versions/" + version.getID()))
                        .andExpect(status().isForbidden());
        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void versionForItemTest() throws Exception {

        context.turnOffAuthorisationSystem();
        WorkspaceItem workspaceItem = workspaceItemService.findByItem(context, version.getItem());
        installItemService.installItem(context, workspaceItem);
        context.restoreAuthSystemState();
        getClient().perform(get("/api/core/items/" + version.getItem().getID() + "/version"))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(version))));
    }

    @Test
    public void versionItemTest() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + version.getID() + "/item"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(ItemMatcher.matchItemProperties(version.getItem()))));
    }

    @Test
    public void versionItemTestWrongId() throws Exception {

        String adminToken = getAuthToken(admin.getEmail(), password);

        getClient(adminToken).perform(get("/api/versioning/versions/" + ((version.getID() + 5) * 57) + "/item"))
                             .andExpect(status().isNotFound());
    }

    @Test
    public void deleteVersionItemAdminTest() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(delete("/api/versioning/versions/" + version.getID()))
                             .andExpect(status().isNoContent());
    }

    @Test
    public void deleteVersionItemUnauthorizedTest() throws Exception {
        getClient().perform(delete("/api/versioning/versions/" + version.getID()))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void deleteVersionItemForbiddenTest() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(delete("/api/versioning/versions/" + version.getID()))
                               .andExpect(status().isForbidden());
    }

    @Test
    public void deleteVersionItemNotFoundTest() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(delete("/api/versioning/versions/" + Integer.MAX_VALUE))
                               .andExpect(status().isNotFound());
    }

    @Test
    public void deleteVersionByCollectionAdminTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson adminCol = EPersonBuilder.createEPerson(context)
                                         .withEmail("adminCol@mail.com")
                                         .withPassword(password)
                                         .build();

        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Collection col = CollectionBuilder.createCollection(context, rootCommunity)
                                          .withName("Collection 1")
                                          .withAdminGroup(adminCol)
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                                .withTitle("Public item")
                                .withIssueDate("2021-04-19")
                                .withAuthor("Doe, John")
                                .withSubject("ExtraEntry")
                                .build();

        VersionBuilder.createVersion(context, itemA, "Fixing some typos").build();
        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef = new AtomicReference<Integer>();

        String adminColToken = getAuthToken(adminCol.getEmail(), password);
        getClient(adminColToken).perform(get("/api/versioning/versions/search/findByItem")
                                .param("itemUuid", itemA.getID().toString()))
                                .andExpect(status().isOk())
                                .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));

        getClient(adminColToken).perform(delete("/api/versioning/versions/" + idRef.get()))
                                .andExpect(status().isNoContent());
    }

    @Test
    public void deleteVersionByCommunityAdminTest() throws Exception {
        context.turnOffAuthorisationSystem();
        EPerson adminCommA = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommA@mail.com")
                                           .withPassword(password)
                                           .build();

        EPerson adminCommB = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommB@mail.com")
                                           .withPassword(password)
                                           .build();

        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Community subCommunityA = CommunityBuilder.createSubCommunity(context, rootCommunity)
                                                  .withName("subCommunity A")
                                                  .withAdminGroup(adminCommA)
                                                  .build();

        CommunityBuilder.createSubCommunity(context, rootCommunity)
                        .withName("subCommunity B")
                        .withAdminGroup(adminCommB)
                        .build();

        Collection col = CollectionBuilder.createCollection(context, subCommunityA)
                                          .withName("Collection 1")
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                                .withTitle("Public item")
                                .withIssueDate("2021-04-19")
                                .withAuthor("Doe, John")
                                .withSubject("ExtraEntry")
                                .build();

        VersionBuilder.createVersion(context, itemA, "Fixing some typos").build();
        context.restoreAuthSystemState();

        String adminCommAToken = getAuthToken(adminCommA.getEmail(), password);
        String adminCommBToken = getAuthToken(adminCommB.getEmail(), password);

        AtomicReference<Integer> idRef = new AtomicReference<Integer>();
        getClient(adminCommAToken).perform(get("/api/versioning/versions/search/findByItem")
                                  .param("itemUuid", itemA.getID().toString()))
                                  .andExpect(status().isOk())
                                  .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));

        getClient(adminCommBToken).perform(delete("/api/versioning/versions/" + idRef.get()))
                                  .andExpect(status().isForbidden());

        getClient(adminCommAToken).perform(delete("/api/versioning/versions/" + idRef.get()))
                                  .andExpect(status().isNoContent());
    }

    @Test
    public void deleteVersionBySubmitterTest() throws Exception {
        context.turnOffAuthorisationSystem();

        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Community subCommunityA = CommunityBuilder.createSubCommunity(context, rootCommunity)
                                                  .withName("subCommunity A")
                                                  .build();

        Collection col = CollectionBuilder.createCollection(context, subCommunityA)
                                          .withName("Collection 1")
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                                .withTitle("Public item")
                                .withIssueDate("2021-04-19")
                                .withAuthor("Doe, John")
                                .withSubject("ExtraEntry")
                                .build();

        itemA.setSubmitter(eperson);

        VersionBuilder.createVersion(context, itemA, "Fixing some typos").build();
        context.restoreAuthSystemState();

        AtomicReference<Integer> idRef = new AtomicReference<Integer>();
        String submitterToken = getAuthToken(eperson.getEmail(), password);

        getClient(submitterToken).perform(get("/api/versioning/versions/search/findByItem")
                                 .param("itemUuid", itemA.getID().toString()))
                                 .andExpect(status().isOk())
                                 .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));

        getClient(submitterToken).perform(delete("/api/versioning/versions/" + idRef.get()))
                                 .andExpect(status().isForbidden());
    }

    @Test
    public void createFirstVersionItemTest() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(post("/api/versioning/versions")
                             .param("summary", "test summary!")
                             .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                             .content("/api/core/items/" + item.getID()))
                             .andExpect(status().isCreated())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(3)),
                                        hasJsonPath("$.summary", is("test summary!")),
                                        hasJsonPath("$.submitterName", is("first (admin) last (admin)")),
                                        hasJsonPath("$.type", is("version"))
                                        )));
    }

    @Test
    public void createFirstVersionItemWithSubmitterTest() throws Exception {
        configurationService.setProperty("versioning.submitterCanCreateNewVersion", true);
        context.turnOffAuthorisationSystem();
        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Collection col = CollectionBuilder.createCollection(context, rootCommunity)
                                          .withName("Collection 1")
                                          .withSubmitterGroup(eperson)
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                               .withTitle("Public item")
                               .withIssueDate("2021-04-19")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        itemA.setSubmitter(eperson);

        context.restoreAuthSystemState();

        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(post("/api/versioning/versions")
                               .param("summary", "test summary!")
                               .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                               .content("/api/core/items/" + itemA.getID()))
                               .andExpect(status().isCreated())
                               .andExpect(jsonPath("$", Matchers.allOf(
                                          hasJsonPath("$.version", is(2)),
                                          hasJsonPath("$.summary", is("test summary!")),
                                          hasJsonPath("$.type", is("version"))
                                          )));

        configurationService.setProperty("versioning.submitterCanCreateNewVersion", false);
    }

    @Test
    public void createFirstVersionItemWithSubmitterAndPropertyForSubmitterDisabledTest() throws Exception {
        configurationService.setProperty("versioning.submitterCanCreateNewVersion", false);
        context.turnOffAuthorisationSystem();
        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Collection col = CollectionBuilder.createCollection(context, rootCommunity)
                                          .withName("Collection 1")
                                          .withSubmitterGroup(eperson)
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                               .withTitle("Public item")
                               .withIssueDate("2021-04-19")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        itemA.setSubmitter(eperson);

        context.restoreAuthSystemState();

        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(post("/api/versioning/versions")
                               .param("summary", "test summary!")
                               .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                               .content("/api/core/items/" + itemA.getID()))
                               .andExpect(status().isForbidden());
    }

    @Test
    public void createFirstVersionItemBadRequestTest() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(post("/api/versioning/versions")
                             .param("summary", "test summary!")
                             .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                             .content("/api/core/test/" + UUID.randomUUID()))
                             .andExpect(status().isBadRequest());
    }

    @Test
    public void createFirstVersionItemForbiddenTest() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(post("/api/versioning/versions")
                               .param("summary", "test summary!")
                               .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                               .content("/api/core/items/" + item.getID()))
                               .andExpect(status().isForbidden());
    }

    @Test
    public void createFirstVersionItemUnauthorizedTest() throws Exception {
        getClient().perform(post("/api/versioning/versions")
                   .param("summary", "test summary!")
                   .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                   .content("/api/core/items/" + item.getID()))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void createVersionFromVersionedItemTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 10).build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(post("/api/versioning/versions")
                             .param("summary", "test summary.")
                             .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                             .content("/api/core/items/" + item.getID()))
                             .andExpect(status().isCreated())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(11)),
                                        hasJsonPath("$.summary", is("test summary.")),
                                        hasJsonPath("$.submitterName", is("first (admin) last (admin)")),
                                        hasJsonPath("$.type", is("version"))
                                        )));
    }

    @Test
    public void createVersionByPreviousVersionRespectCurrentVersionTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-03-20")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Item item2 = ItemBuilder.createItem(context, col)
                                .withTitle("Public test item 2")
                                .withIssueDate("2021-03-20")
                                .withAuthor("Doe, John")
                                .withSubject("ExtraEntry")
                                .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 1).build();
        VersionBuilder.createVersionWithVersionHistory(context, item2, "tes2", versionHistory, 2).build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(post("/api/versioning/versions")
                             .param("summary", "check first version")
                             .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                             .content("/api/core/items/" + item.getID()))
                             .andExpect(status().isCreated())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(3)),
                                        hasJsonPath("$.summary", is("check first version")),
                                        hasJsonPath("$.submitterName", is("first (admin) last (admin)")),
                                        hasJsonPath("$.type", is("version"))
                                        )));
    }

    @Test
    public void createVersionFromWorkflowItemTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withWorkflowGroup(1, admin)
                                          .withName("Collection test").build();

        XmlWorkflowItem workflowItem = WorkflowItemBuilder.createWorkflowItem(context, col)
                                                          .withTitle("Workflow Item 1")
                                                          .withIssueDate("2017-10-17")
                                                          .build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(post("/api/versioning/versions")
                             .param("summary", "fix workspaceitem")
                             .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                             .content("/api/core/items/" + workflowItem.getItem().getID()))
                             .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void createVersionFromWorkspaceItemTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        WorkspaceItem witem = WorkspaceItemBuilder.createWorkspaceItem(context, col)
                                                  .withTitle("Workspace Item 1")
                                                  .withIssueDate("2017-10-17")
                                                  .withAuthor("Smith, Donald").withAuthor("Doe, John")
                                                  .withSubject("ExtraEntry")
                                                  .build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(post("/api/versioning/versions")
                             .param("summary", "fix workspaceitem")
                             .contentType(MediaType.parseMediaType(RestMediaTypes.TEXT_URI_LIST_VALUE))
                             .content("/api/core/items/" + witem.getItem().getID()))
                             .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void findByItemAdminTest() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByItem")
                             .param("itemUuid", item.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(1)),
                                        hasJsonPath("$.summary", emptyString()),
                                        hasJsonPath("$.submitterName", is("first last")),
                                        hasJsonPath("$.type", is("version"))
                                        )));
    }

    @Test
    public void findByItemAdminAndVersioningIsNotPublicUnauthorizedTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        getClient().perform(get("/api/versioning/versions/search/findByItem")
                   .param("itemUuid", item.getID().toString()))
                   .andExpect(status().isUnauthorized());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByItemVersionAndVersioningIsPublicTest() throws Exception {
        getClient().perform(get("/api/versioning/versions/search/findByItem")
                   .param("itemUuid", item.getID().toString()))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", Matchers.allOf(
                           hasJsonPath("$.version", is(1)),
                           hasJsonPath("$.summary", emptyString()),
                           hasJsonPath("$.type", is("version"))
                           )));
    }

    @Test
    public void findByItemSubmitterAndVersioningIsNotPublicForbiddenTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        item.setSubmitter(eperson);
        String submitterToken = getAuthToken(eperson.getEmail(), password);
        getClient(submitterToken).perform(get("/api/versioning/versions/search/findByItem")
                                 .param("itemUuid", item.getID().toString()))
                                 .andExpect(status().isForbidden());

        item.setSubmitter(null);
        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByItemCommunityAndColAdminAndVersioningIsNotPubliTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        context.turnOffAuthorisationSystem();
        EPerson adminCommA = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommA@mail.com")
                                           .withPassword(password)
                                           .build();

        EPerson adminCommB = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommB@mail.com")
                                           .withPassword(password)
                                           .build();

        EPerson adminCol = EPersonBuilder.createEPerson(context)
                                         .withEmail("adminCol@mail.com")
                                         .withPassword(password)
                                         .build();

        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Community subCommunityA = CommunityBuilder.createSubCommunity(context, rootCommunity)
                                                 .withName("subCommunity A")
                                                 .withAdminGroup(adminCommA)
                                                 .build();

        CommunityBuilder.createSubCommunity(context, rootCommunity)
                        .withName("subCommunity B")
                        .withAdminGroup(adminCommB)
                        .build();

        Collection col = CollectionBuilder.createCollection(context, subCommunityA)
                                          .withName("Collection 1")
                                          .withAdminGroup(adminCol)
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                               .withTitle("Public item")
                               .withIssueDate("2021-04-19")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        VersionBuilder.createVersion(context, itemA, "Fixing some typos").build();
        context.restoreAuthSystemState();

        String adminCommAToken = getAuthToken(adminCommA.getEmail(), password);
        String adminCommBToken = getAuthToken(adminCommB.getEmail(), password);
        String adminColToken = getAuthToken(adminCol.getEmail(), password);

        getClient(adminCommAToken).perform(get("/api/versioning/versions/search/findByItem")
                                  .param("itemUuid", itemA.getID().toString()))
                                  .andExpect(status().isOk())
                                  .andExpect(jsonPath("$", Matchers.allOf(
                                          hasJsonPath("$.version", is(1)),
                                          hasJsonPath("$.summary", emptyString()),
                                          hasJsonPath("$.type", is("version"))
                                          )));

        getClient(adminColToken).perform(get("/api/versioning/versions/search/findByItem")
                                .param("itemUuid", itemA.getID().toString()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(1)),
                                        hasJsonPath("$.summary", emptyString()),
                                        hasJsonPath("$.type", is("version"))
                                        )));

        getClient(adminCommBToken).perform(get("/api/versioning/versions/search/findByItem")
                                  .param("itemUuid", itemA.getID().toString()))
                                  .andExpect(status().isForbidden());

        item.setSubmitter(null);
        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByItemNotYetVersionedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col1 = CollectionBuilder.createCollection(context, parentCommunity)
                                           .withName("Sample 1 collection")
                                           .withSubmitterGroup(eperson).build();

        Item publicItem = ItemBuilder.createItem(context, col1)
                                     .withTitle("Public item")
                                     .withAuthor("Smith, Maria")
                                     .withSubject("ExtraEntry")
                                     .build();

        context.restoreAuthSystemState();
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByItem")
                             .param("itemUuid", publicItem.getID().toString()))
                             .andExpect(status().isNoContent());
    }

    @Test
    public void findByItemAndVersioningIsNotPublicForbiddenTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(get("/api/versioning/versions/search/findByItem")
                             .param("itemUuid", item.getID().toString()))
                             .andExpect(status().isForbidden());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByItemAndVersioningIsPublicTest() throws Exception {
        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(get("/api/versioning/versions/search/findByItem")
                             .param("itemUuid", item.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                     hasJsonPath("$.version", is(1)),
                                     hasJsonPath("$.summary", emptyString()),
                                     hasJsonPath("$.type", is("version"))
                                     )));
    }

    @Test
    public void findByItemBadRequestTest() throws Exception {
        String adminToken = getAuthToken(admin.getEmail(), password);

        // missing to provid item uuid
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByItem"))
                             .andExpect(status().isBadRequest());

        // provided wrong uuid
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByItem")
                             .param("itemUuid", "wrong id"))
                             .andExpect(status().isBadRequest());
    }

    @Test
    public void findByHistoryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        Item item2 = ItemBuilder.createItem(context, col)
                                .withTitle("Public test item 2")
                                .withAuthor("Doe, John")
                                .withSubject("ExtraEntry")
                                .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();
        Version v98 = VersionBuilder.createVersionWithVersionHistory(context, item2, "test 2", versionHistory, 98)
                                    .build();
        Version v99 = VersionBuilder.createVersionWithVersionHistory(context, item2, "test 3", versionHistory, 0)
                                    .build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                             .param("historyId", versionHistory.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", Matchers.contains(
                                        VersionMatcher.matchEntry(v99),
                                        VersionMatcher.matchEntry(v98),
                                        VersionMatcher.matchEntry(v77)
                                        )))
                             .andExpect(jsonPath("$._links.self.href", Matchers.allOf(Matchers.containsString(
                              "api/versioning/versions/search/findByHistory?historyId=" + versionHistory.getID()))))
                             .andExpect(jsonPath("$.page.totalElements", is(3)))
                             .andExpect(jsonPath("$.page.totalPages", is(1)))
                             .andExpect(jsonPath("$.page.number", is(0)));
    }

    @Test
    public void findByHistoryPaginationTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        Item item2 = ItemBuilder.createItem(context, col)
                                .withTitle("Public test item 2")
                                .withAuthor("Doe, John")
                                .withSubject("ExtraEntry")
                                .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();
        Version v98 = VersionBuilder.createVersionWithVersionHistory(context, item2, "test 2", versionHistory, 98)
                                    .build();
        Version v99 = VersionBuilder.createVersionWithVersionHistory(context, item2, "test 3", versionHistory, 0)
                                    .build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                             .param("size", "1")
                             .param("historyId", versionHistory.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", Matchers.contains(
                                        VersionMatcher.matchEntry(v99)
                                        )))
                             .andExpect(jsonPath("$._links.self.href", Matchers.allOf(
                                     Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                                     Matchers.containsString("historyId=" + versionHistory.getID()),
                                     Matchers.containsString("size=1")
                                     )))
                             .andExpect(jsonPath("$._links.first.href", Matchers.allOf(
                                     Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                                     Matchers.containsString("historyId=" + versionHistory.getID()),
                                     Matchers.containsString("page=0"),
                                     Matchers.containsString("size=1")
                                     )))
                             .andExpect(jsonPath("$._links.next.href", Matchers.allOf(
                                     Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                                     Matchers.containsString("historyId=" + versionHistory.getID()),
                                     Matchers.containsString("page=1"),
                                     Matchers.containsString("size=1")
                                     )))
                             .andExpect(jsonPath("$._links.last.href", Matchers.allOf(
                                     Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                                     Matchers.containsString("historyId=" + versionHistory.getID()),
                                     Matchers.containsString("page=2"),
                                     Matchers.containsString("size=1")
                                     )))
                             .andExpect(jsonPath("$.page.totalElements", is(3)))
                             .andExpect(jsonPath("$.page.totalPages", is(3)))
                             .andExpect(jsonPath("$.page.number", is(0)));

        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                .param("page", "1")
                .param("size", "1")
                .param("historyId", versionHistory.getID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.versions", Matchers.contains(
                           VersionMatcher.matchEntry(v98)
                           )))
                .andExpect(jsonPath("$._links.self.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=1"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.first.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=0"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.prev.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=0"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.next.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=2"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.last.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=2"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$.page.totalElements", is(3)))
                .andExpect(jsonPath("$.page.totalPages", is(3)))
                .andExpect(jsonPath("$.page.number", is(1)));

        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                .param("page", "2")
                .param("size", "1")
                .param("historyId", versionHistory.getID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.versions", Matchers.contains(
                           VersionMatcher.matchEntry(v77)
                           )))
                .andExpect(jsonPath("$._links.self.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=2"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.first.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=0"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.prev.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=1"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$._links.last.href", Matchers.allOf(
                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                        Matchers.containsString("historyId=" + versionHistory.getID()),
                        Matchers.containsString("page=2"),
                        Matchers.containsString("size=1")
                        )))
                .andExpect(jsonPath("$.page.totalElements", is(3)))
                .andExpect(jsonPath("$.page.totalPages", is(3)))
                .andExpect(jsonPath("$.page.number", is(2)));
    }

    @Test
    public void findByHistoryPaginationEmptyResponseTest() throws Exception {
        context.turnOffAuthorisationSystem();
        VersionHistory versionHistory = versionHistoryService.create(context);

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                             .param("size", "1")
                             .param("historyId", versionHistory.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions").doesNotExist())
                             .andExpect(jsonPath("$._links.self.href", Matchers.allOf(
                                        Matchers.containsString("api/versioning/versions/search/findByHistory?"),
                                        Matchers.containsString("historyId=" + versionHistory.getID()),
                                        Matchers.containsString("size=1")
                                        )))
                             .andExpect(jsonPath("$.page.totalElements", is(0)))
                             .andExpect(jsonPath("$.page.totalPages", is(0)))
                             .andExpect(jsonPath("$.page.number", is(0)));
    }

    @Test
    public void findByHistoryPaginationBadRequestTest() throws Exception {
        Integer id = Integer.MAX_VALUE;
        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                             .param("size", "1")
                             .param("historyId", id.toString()))
                             .andExpect(status().isBadRequest());
    }

    @Test
    public void findByHistoryAndVersioningIsNotPublicUnauthorizedTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 8).build();

        context.restoreAuthSystemState();

        getClient().perform(get("/api/versioning/versions/search/findByHistory")
                   .param("historyId", versionHistory.getID().toString()))
                   .andExpect(status().isUnauthorized());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByHistoryAndVersioningIsPublicTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v8 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 8).build();

        context.restoreAuthSystemState();

        getClient().perform(get("/api/versioning/versions/search/findByHistory")
                   .param("historyId", versionHistory.getID().toString()))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$._embedded.versions", Matchers.contains(
                              VersionMatcher.matchEntry(v8))));
    }

    @Test
    public void findByHistoryAndVersioningIsNotPublicForbiddenTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 8).build();

        context.restoreAuthSystemState();

        String ePersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(ePersonToken).perform(get("/api/versioning/versions/search/findByHistory")
                               .param("historyId", versionHistory.getID().toString()))
                               .andExpect(status().isForbidden());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByHistorySubmitterAndVersioningIsNotPublicForbiddenTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        item.setSubmitter(eperson);
        VersionHistory versionHistory = versionHistoryService.create(context);
        VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 8).build();

        context.restoreAuthSystemState();

        String ePersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(ePersonToken).perform(get("/api/versioning/versions/search/findByHistory")
                               .param("historyId", versionHistory.getID().toString()))
                               .andExpect(status().isForbidden());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByHistoryAdminSubmitterAndVersioningIsNotPublicTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        item.setSubmitter(eperson);
        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v8 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 8).build();

        context.restoreAuthSystemState();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versions/search/findByHistory")
                             .param("historyId", versionHistory.getID().toString()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", Matchers.contains(
                                     VersionMatcher.matchEntry(v8))));

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findByHistoryCommunityAndColAdminAndVersioningIsNotPubliTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        context.turnOffAuthorisationSystem();
        EPerson adminCommA = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommA@mail.com")
                                           .withPassword(password)
                                           .build();

        EPerson adminCommB = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommB@mail.com")
                                           .withPassword(password)
                                           .build();

        EPerson adminCol = EPersonBuilder.createEPerson(context)
                                         .withEmail("adminCol@mail.com")
                                         .withPassword(password)
                                         .build();

        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Community subCommunityA = CommunityBuilder.createSubCommunity(context, rootCommunity)
                                                 .withName("subCommunity A")
                                                 .withAdminGroup(adminCommA)
                                                 .build();

        CommunityBuilder.createSubCommunity(context, rootCommunity)
                        .withName("subCommunity B")
                        .withAdminGroup(adminCommB)
                        .build();

        Collection col = CollectionBuilder.createCollection(context, subCommunityA)
                                          .withName("Collection 1")
                                          .withAdminGroup(adminCol)
                                          .build();

        Item itemA = ItemBuilder.createItem(context, col)
                               .withTitle("Public item")
                               .withIssueDate("2021-04-19")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v8 = VersionBuilder.createVersionWithVersionHistory(context, itemA, "test", versionHistory, 8).build();
        context.restoreAuthSystemState();

        String adminCommAToken = getAuthToken(adminCommA.getEmail(), password);
        String adminCommBToken = getAuthToken(adminCommB.getEmail(), password);
        String adminColToken = getAuthToken(adminCol.getEmail(), password);

        getClient(adminCommAToken).perform(get("/api/versioning/versions/search/findByHistory")
                                  .param("historyId", versionHistory.getID().toString()))
                                  .andExpect(status().isOk())
                                  .andExpect(jsonPath("$._embedded.versions",
                                             Matchers.contains(VersionMatcher.matchEntry(v8))));

        getClient(adminColToken).perform(get("/api/versioning/versions/search/findByHistory")
                                .param("historyId", versionHistory.getID().toString()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$._embedded.versions",
                                           Matchers.contains(VersionMatcher.matchEntry(v8))));

        getClient(adminCommBToken).perform(get("/api/versioning/versions/search/findByHistory")
                                  .param("historyId", versionHistory.getID().toString()))
                                  .andExpect(status().isForbidden());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void patchReplaceSummaryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();

        context.restoreAuthSystemState();

        String newSummary = "New Summary";
        List<Operation> ops = new ArrayList<Operation>();
        ReplaceOperation replaceOperation = new ReplaceOperation("/summary", newSummary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                             .content(patchBody)
                             .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                     hasJsonPath("$.version", is(v77.getVersionNumber())),
                                     hasJsonPath("$.summary", is(newSummary)),
                                     hasJsonPath("$.submitterName", is("first last")),
                                     hasJsonPath("$.type", is("version"))
                                     )))
                             .andExpect(jsonPath("$._links.self.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v77.getID())
                                                 )))
                             .andExpect(jsonPath("$._links.versionhistory.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v77.getID() + "/versionhistory"
                                                 ))))
                             .andExpect(jsonPath("$._links.item.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v77.getID() + "/item")
                                                 )));

    }

    @Test
    public void patchRemoveSummaryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();

        context.restoreAuthSystemState();

        List<Operation> ops = new ArrayList<Operation>();
        RemoveOperation replaceOperation = new RemoveOperation("/summary");
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                             .content(patchBody)
                             .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                     hasJsonPath("$.version", is(v77.getVersionNumber())),
                                     hasJsonPath("$.summary", emptyString()),
                                     hasJsonPath("$.submitterName", is("first last")),
                                     hasJsonPath("$.type", is("version"))
                                     )))
                           .andExpect(jsonPath("$._links.self.href", Matchers.allOf(Matchers.containsString(
                                               "api/versioning/versions/" + v77.getID())
                                               )))
                           .andExpect(jsonPath("$._links.versionhistory.href", Matchers.allOf(Matchers.containsString(
                                               "api/versioning/versions/" + v77.getID() + "/versionhistory"
                                               ))))
                           .andExpect(jsonPath("$._links.item.href", Matchers.allOf(Matchers.containsString(
                                               "api/versioning/versions/" + v77.getID() + "/item")
                                               )));

    }

    @Test
    public void patchAddSummaryTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "", versionHistory, 77)
                                    .build();

        context.restoreAuthSystemState();

        String summary = "First Summary!";
        List<Operation> ops = new ArrayList<Operation>();
        AddOperation replaceOperation = new AddOperation("/summary", summary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                             .content(patchBody)
                             .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(v77.getVersionNumber())),
                                        hasJsonPath("$.summary", is(summary)),
                                        hasJsonPath("$.submitterName", is("first last")),
                                        hasJsonPath("$.type", is("version"))
                                        )))
                             .andExpect(jsonPath("$._links.self.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v77.getID())
                                                 )))
                             .andExpect(jsonPath("$._links.versionhistory.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v77.getID() + "/versionhistory"
                                                 ))))
                             .andExpect(jsonPath("$._links.item.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v77.getID() + "/item")
                                                 )));
    }

    @Test
    public void patchAddSummaryBadRequestTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();

        context.restoreAuthSystemState();

        String summary = "First Summary!";
        List<Operation> ops = new ArrayList<Operation>();
        AddOperation replaceOperation = new AddOperation("/summary", summary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                             .content(patchBody)
                             .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                             .andExpect(status().isBadRequest());
    }

    @Test
    public void patchWrongPathUnprocessableEntityTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();

        context.restoreAuthSystemState();

        String summary = "First Summary!";
        List<Operation> ops = new ArrayList<Operation>();
        AddOperation replaceOperation = new AddOperation("/wrongPath", summary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                             .content(patchBody)
                             .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                             .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchReplaceVersionUnprocessableEntityTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77)
                                    .build();

        context.restoreAuthSystemState();

        String newVersion = "133";
        List<Operation> ops = new ArrayList<Operation>();
        ReplaceOperation replaceOperation = new ReplaceOperation("/version", newVersion);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                             .content(patchBody)
                             .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                             .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void patchReplaceSummaryUnauthorizedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77).build();

        context.restoreAuthSystemState();

        String newSummary = "New Summary";
        List<Operation> ops = new ArrayList<Operation>();
        ReplaceOperation replaceOperation = new ReplaceOperation("/summary", newSummary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        getClient().perform(patch("/api/versioning/versions/" + v77.getID())
                   .content(patchBody)
                   .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void patchRemoveSummaryUnauthorizedTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77).build();

        context.restoreAuthSystemState();

        List<Operation> ops = new ArrayList<Operation>();
        RemoveOperation replaceOperation = new RemoveOperation("/summary");
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        getClient().perform(patch("/api/versioning/versions/" + v77.getID())
                   .content(patchBody)
                   .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void patchReplaceSummaryForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77).build();

        context.restoreAuthSystemState();

        String newSummary = "New Summary";
        List<Operation> ops = new ArrayList<Operation>();
        ReplaceOperation replaceOperation = new ReplaceOperation("/summary", newSummary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(patch("/api/versioning/versions/" + v77.getID())
                               .content(patchBody)
                               .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                               .andExpect(status().isForbidden());
    }

    @Test
    public void patchRemoveSummaryForbiddenTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test").build();

        Item item = ItemBuilder.createItem(context, col)
                          .withTitle("Public test item")
                          .withIssueDate("2021-04-27")
                          .withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77).build();

        context.restoreAuthSystemState();

        List<Operation> ops = new ArrayList<Operation>();
        RemoveOperation replaceOperation = new RemoveOperation("/summary");
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String epersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(epersonToken).perform(patch("/api/versioning/versions/" + v77.getID())
                               .content(patchBody)
                               .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                               .andExpect(status().isForbidden());
    }

    @Test
    public void patchReplaceSummaryByCollectionAdminTest() throws Exception {
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection title")
                                          .withAdminGroup(eperson)
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-03-08")
                               .withSubject("ExtraEntry")
                               .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77).build();

        context.restoreAuthSystemState();

        String newSummary = "New Summary";
        List<Operation> ops = new ArrayList<Operation>();
        ReplaceOperation replaceOperation = new ReplaceOperation("/summary", newSummary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String colAdminToken = getAuthToken(eperson.getEmail(), password);
        getClient(colAdminToken).perform(patch("/api/versioning/versions/" + v77.getID())
                                .content(patchBody)
                                .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$", Matchers.allOf(
                                        hasJsonPath("$.version", is(v77.getVersionNumber())),
                                        hasJsonPath("$.summary", is(newSummary)),
                                        hasJsonPath("$.type", is("version"))
                                        )));
    }

    @Test
    public void patchReplaceSummaryByCommunityAdminTest() throws Exception {
        context.turnOffAuthorisationSystem();

        EPerson adminCommA = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommA@mail.com")
                                           .withPassword(password)
                                           .build();

        EPerson adminCommB = EPersonBuilder.createEPerson(context)
                                           .withEmail("adminCommB@mail.com")
                                           .withPassword(password)
                                           .build();

        Community rootCommunity = CommunityBuilder.createCommunity(context)
                                                  .withName("Parent Community")
                                                  .build();

        Community subCommunityA = CommunityBuilder.createSubCommunity(context, rootCommunity)
                                                  .withName("subCommunity A")
                                                  .withAdminGroup(adminCommA)
                                                  .build();

        CommunityBuilder.createSubCommunity(context, rootCommunity)
                        .withName("subCommunity B")
                        .withAdminGroup(adminCommB)
                        .build();

        Collection col = CollectionBuilder.createCollection(context, subCommunityA)
                                          .withName("Collection title")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-03-08")
                               .withSubject("ExtraEntry")
                               .build();

        VersionHistory versionHistory = versionHistoryService.create(context);
        Version v77 = VersionBuilder.createVersionWithVersionHistory(context, item, "test", versionHistory, 77).build();

        context.restoreAuthSystemState();

        String newSummary = "New Summary";
        List<Operation> ops = new ArrayList<Operation>();
        ReplaceOperation replaceOperation = new ReplaceOperation("/summary", newSummary);
        ops.add(replaceOperation);
        String patchBody = getPatchContent(ops);

        String adminCommAToken = getAuthToken(adminCommA.getEmail(), password);
        String adminCommBToken = getAuthToken(adminCommB.getEmail(), password);

        getClient(adminCommBToken).perform(patch("/api/versioning/versions/" + v77.getID())
                                  .content(patchBody)
                                  .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                                  .andExpect(status().isForbidden());

        getClient(adminCommAToken).perform(get("/api/versioning/versions/" + v77.getID()))
                                  .andExpect(status().isOk())
                                  .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(v77))));

        getClient(adminCommAToken).perform(patch("/api/versioning/versions/" + v77.getID())
                                  .content(patchBody)
                                  .contentType(javax.ws.rs.core.MediaType.APPLICATION_JSON_PATCH_JSON))
                                  .andExpect(status().isOk());

        getClient(adminCommAToken).perform(get("/api/versioning/versions/" + v77.getID()))
                                  .andExpect(status().isOk())
                                  .andExpect(jsonPath("$", Matchers.allOf(
                                          hasJsonPath("$.version", is(v77.getVersionNumber())),
                                          hasJsonPath("$.summary", is(newSummary)),
                                          hasJsonPath("$.type", is("version"))
                                          )));
    }

}