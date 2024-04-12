/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static org.dspace.app.rest.matcher.NotifyServiceMatcher.matchNotifyService;
import static org.dspace.app.rest.matcher.NotifyServiceMatcher.matchNotifyServicePattern;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.core.MediaType;
import org.apache.commons.collections4.CollectionUtils;
import org.dspace.app.ldn.NotifyServiceEntity;
import org.dspace.app.ldn.service.NotifyService;
import org.dspace.app.ldn.service.NotifyServiceInboundPatternService;
import org.dspace.app.rest.model.patch.AddOperation;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.patch.RemoveOperation;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.builder.NotifyServiceBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;


public class NotifyServiceInboundPatternTestIT extends AbstractControllerIntegrationTest {

    @Autowired
    private NotifyService notifyService;

    @Autowired
    private NotifyServiceInboundPatternService inboundPatternService;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        context.turnOffAuthorisationSystem();
        List<NotifyServiceEntity> notifyServiceEntities = notifyService.findAll(context);
        if (CollectionUtils.isNotEmpty(notifyServiceEntities)) {
            notifyServiceEntities.forEach(notifyServiceEntity -> {
                try {
                    notifyServiceEntity.getInboundPatterns().forEach(inbound -> {
                        try {
                            inboundPatternService.delete(context, inbound);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    notifyService.delete(context, notifyServiceEntity);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            context.commit();
        }
        context.restoreAuthSystemState();
    }

    @Override
    @After
    public void destroy() throws Exception {
        context.turnOffAuthorisationSystem();
        List<NotifyServiceEntity> notifyServiceEntities = notifyService.findAll(context);
        if (CollectionUtils.isNotEmpty(notifyServiceEntities)) {
            notifyServiceEntities.forEach(notifyServiceEntity -> {
                try {
                    notifyServiceEntity.getInboundPatterns().forEach(inbound -> {
                        try {
                            inboundPatternService.delete(context, inbound);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    notifyService.delete(context, notifyServiceEntity);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        context.commit();
        context.restoreAuthSystemState();
        super.destroy();
    }
    @Test
    public void NotifyServiceInboundPatternConstraintRemoveOperationTest() throws Exception {

        context.turnOffAuthorisationSystem();

        NotifyServiceEntity notifyServiceEntity =
                NotifyServiceBuilder.createNotifyServiceBuilder(context, "service name")
                        .withDescription("service description")
                        .withUrl("https://service.ldn.org/about")
                        .withLdnUrl("https://service.ldn.org/inbox")
                        .build();

        context.restoreAuthSystemState();

        List<Operation> ops = new ArrayList<Operation>();
        AddOperation inboundAddOperationOne = new AddOperation("notifyServiceInboundPatterns/-",
                "{\"pattern\":\"patternA\",\"constraint\":\"itemFilterA\",\"automatic\":\"false\"}");

        AddOperation inboundAddOperationTwo = new AddOperation("notifyServiceInboundPatterns/-",
                "{\"pattern\":\"patternB\",\"constraint\":\"itemFilterB\",\"automatic\":\"true\"}");

        ops.add(inboundAddOperationOne);
        ops.add(inboundAddOperationTwo);
        String patchBody = getPatchContent(ops);

        String authToken = getAuthToken(admin.getEmail(), password);
        getClient(authToken)
                .perform(patch("/api/ldn/ldnservices/" + notifyServiceEntity.getID())
                        .content(patchBody)
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyServiceInboundPatterns", hasSize(2)))
                .andExpect(jsonPath("$",
                        allOf(
                                matchNotifyService(notifyServiceEntity.getID(), "service name", "service description",
                                        "https://service.ldn.org/about", "https://service.ldn.org/inbox"),
                                hasJsonPath("$.notifyServiceInboundPatterns", containsInAnyOrder(
                                        matchNotifyServicePattern("patternA", "itemFilterA", false),
                                        matchNotifyServicePattern("patternB", "itemFilterB", true)
                                ))
                        )));

        RemoveOperation inboundRemoveOperation = new RemoveOperation("notifyServiceInboundPatterns[1]/constraint");
        ops = new ArrayList<Operation>();
        ops.add(inboundRemoveOperation);
        patchBody = getPatchContent(ops);

        getClient(authToken)
                .perform(patch("/api/ldn/ldnservices/" + notifyServiceEntity.getID())
                        .content(patchBody)
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyServiceInboundPatterns", hasSize(2)))
                .andExpect(jsonPath("$",
                        allOf(
                                matchNotifyService(notifyServiceEntity.getID(), "service name", "service description",
                                        "https://service.ldn.org/about", "https://service.ldn.org/inbox"),
                                hasJsonPath("$.notifyServiceInboundPatterns", containsInAnyOrder(
                                        matchNotifyServicePattern("patternA", "itemFilterA", false),
                                        matchNotifyServicePattern("patternB", null, true)
                                ))
                        )));
    }

    @Test
    public void NotifyServiceInboundPatternConstraintRemoveOperationBadRequestTest() throws Exception {

        context.turnOffAuthorisationSystem();
        List<NotifyServiceEntity> notifyServiceEntities = notifyService.findAll(context);
        if (CollectionUtils.isNotEmpty(notifyServiceEntities)) {
            notifyServiceEntities.forEach(notifyServiceEntity -> {
                try {
                    notifyServiceEntity.getInboundPatterns().forEach(inbound -> {
                        try {
                            inboundPatternService.delete(context, inbound);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    notifyService.delete(context, notifyServiceEntity);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            context.commit();
        }

        NotifyServiceEntity notifyServiceEntity =
                NotifyServiceBuilder.createNotifyServiceBuilder(context, "service name")
                        .withDescription("service description")
                        .withUrl("https://service.ldn.org/about")
                        .withLdnUrl("https://service.ldn.org/inbox")
                        .build();

        context.restoreAuthSystemState();

        List<Operation> ops = new ArrayList<Operation>();
        AddOperation inboundAddOperation = new AddOperation("notifyServiceInboundPatterns/-",
                "{\"pattern\":\"patternA\",\"constraint\":\"itemFilterA\",\"automatic\":\"false\"}");

        ops.add(inboundAddOperation);
        String patchBody = getPatchContent(ops);

        String authToken = getAuthToken(admin.getEmail(), password);
        getClient(authToken)
                .perform(patch("/api/ldn/ldnservices/" + notifyServiceEntity.getID())
                        .content(patchBody)
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyServiceInboundPatterns", hasSize(1)))
                .andExpect(jsonPath("$",
                        allOf(
                                matchNotifyService(notifyServiceEntity.getID(), "service name", "service description",
                                        "https://service.ldn.org/about", "https://service.ldn.org/inbox"),
                                hasJsonPath("$.notifyServiceInboundPatterns", containsInAnyOrder(
                                        matchNotifyServicePattern("patternA", "itemFilterA", false)
                                ))
                        )));

        // index out of the range
        RemoveOperation inboundRemoveOperation = new RemoveOperation("notifyServiceInboundPatterns[1]/constraint");
        ops = new ArrayList<Operation>();
        ops.add(inboundRemoveOperation);
        patchBody = getPatchContent(ops);

        getClient(authToken)
                .perform(patch("/api/ldn/ldnservices/" + notifyServiceEntity.getID())
                        .content(patchBody)
                        .contentType(MediaType.APPLICATION_JSON_PATCH_JSON))
                .andExpect(status().isBadRequest());
    }

}
