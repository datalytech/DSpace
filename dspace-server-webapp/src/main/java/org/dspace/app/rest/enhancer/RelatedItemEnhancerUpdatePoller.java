/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.enhancer;

import java.sql.SQLException;
import java.util.UUID;

import org.dspace.content.Item;
import org.dspace.content.enhancer.service.ItemEnhancerService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RelatedItemEnhancerUpdatePoller {
    private static final Logger log = LoggerFactory.getLogger(RelatedItemEnhancerUpdatePoller.class);
    @Autowired
    private ItemEnhancerService itemEnhancerService;

    @Autowired
    private ItemService itemService;

    @Scheduled(cron = "${related-item-enhancer-poller.cron:-}")
    public void pollItemToUpdateAndProcess() {
        try {
            Context context = new Context();
            context.turnOffAuthorisationSystem();
            UUID extractedUuid;
            while ((extractedUuid = itemEnhancerService.pollItemToUpdate(context)) != null) {
                Item item = itemService.find(context, extractedUuid);
                if (item != null) {
                    itemEnhancerService.enhance(context, item, true);
                }
                context.commit();
            }
            context.restoreAuthSystemState();
            context.complete();
        } catch (SQLException e) {
            log.error("Error polling items to update for metadata enrichment", e);
        }
    }

    public void setItemEnhancerService(ItemEnhancerService itemEnhancerService) {
        this.itemEnhancerService = itemEnhancerService;
    }

    public ItemEnhancerService getItemEnhancerService() {
        return itemEnhancerService;
    }

    public void setItemService(ItemService itemService) {
        this.itemService = itemService;
    }
}
