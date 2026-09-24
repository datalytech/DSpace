/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.dspace.app.audit.AuditEvent;
import org.dspace.app.audit.AuditService;
import org.dspace.app.rest.Parameter;
import org.dspace.app.rest.SearchRestMethod;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.AuditEventRest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;


/**
 * This is the repository responsible to manage Audit Event Rest object
 *
 * @author Andrea Bollini (andrea.bollini at 4science.it)
 */

@Component(AuditEventRest.CATEGORY + "." + AuditEventRest.NAME)
public class AuditEventRestRepository extends DSpaceRestRepository<AuditEventRest, UUID> {

    private static final Logger log = LoggerFactory.getLogger(AuditEventRestRepository.class);

    // SimpleDateFormat is not thread-safe: a new instance is created per parse rather than shared,
    // even though it is more common in this codebase to share one as a static field (see
    // LoginStatisticsRestRepository.DATE_FORMATTER) - that pattern has the same latent bug, not
    // repeated here.
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    @Autowired
    private AuditService auditService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    protected ConverterService converter;

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public AuditEventRest findOne(Context context, UUID id) {
        returnNotFoundIfDisabled();
        AuditEvent audit = auditService.findEvent(context, id);
        return converter.toRest(audit, utils.obtainProjection());
    }


    @PreAuthorize("hasAuthority('ADMIN')")
    @SearchRestMethod(name = "findByObject")
    public Page<AuditEventRest> findByObject(@Parameter(value = "object", required = true) UUID uuid,
            Pageable pageable) throws AuthorizeException, SQLException {
        returnNotFoundIfDisabled();
        Context context = obtainContext();
        Sort sort = pageable.getSort();
        boolean asc = sort.isUnsorted() || (sort.isSorted() && sort.getOrderFor("timeStamp").isAscending());
        List<AuditEvent> events = auditService.findEvents(context, uuid, null, null, pageable.getPageSize(),
                (int) pageable.getOffset(), asc);
        long total = auditService.countEvents(context, uuid, null, null);
        return converter.toRestPage(events, pageable, total, utils.obtainProjection());

    }

    /**
     * Search audit events by any combination of object, eperson, event type and date range. Every
     * parameter is optional; parameters left out are not applied as a filter, so calling this with
     * none of them behaves like {@link #findAll(Context, Pageable)}.
     *
     * @param objectUuid  limit to events on this object (or where it is the subject), or null
     * @param epersonUuid limit to events performed by this EPerson, or null
     * @param eventType   limit to this event type (CREATE, MODIFY, MODIFY_METADATA, ADD, REMOVE,
     *                    DELETE or INSTALL - see {@link org.dspace.event.Event}), or null
     * @param startDate   limit to events on or after this date ({@code yyyy-MM-dd}), or null
     * @param endDate     limit to events on or before this date ({@code yyyy-MM-dd}, the whole day
     *                    is included), or null
     * @param pageable    pagination and sort (only sorting by timeStamp is honoured)
     * @return the matching audit events
     */
    @PreAuthorize("hasAuthority('ADMIN')")
    @SearchRestMethod(name = "byFilters")
    public Page<AuditEventRest> findByFilters(
            @Parameter(value = "object", required = false) UUID objectUuid,
            @Parameter(value = "eperson", required = false) UUID epersonUuid,
            @Parameter(value = "eventType", required = false) String eventType,
            @Parameter(value = "startDate", required = false) String startDate,
            @Parameter(value = "endDate", required = false) String endDate,
            Pageable pageable) throws AuthorizeException, SQLException {
        returnNotFoundIfDisabled();
        Context context = obtainContext();
        Date from = parseStartOfDay(startDate);
        Date to = parseEndOfDay(endDate);
        Sort sort = pageable.getSort();
        boolean asc = sort.isUnsorted() || (sort.isSorted() && sort.getOrderFor("timeStamp").isAscending());
        List<AuditEvent> events = auditService.findEvents(context, objectUuid, epersonUuid, eventType, from, to,
                pageable.getPageSize(), (int) pageable.getOffset(), asc);
        long total = auditService.countEvents(context, objectUuid, epersonUuid, eventType, from, to);
        return converter.toRestPage(events, pageable, total, utils.obtainProjection());
    }

    private Date parseStartOfDay(String date) {
        if (StringUtils.isBlank(date)) {
            return null;
        }
        try {
            return new SimpleDateFormat(DATE_PATTERN).parse(date);
        } catch (ParseException e) {
            throw new UnprocessableEntityException(
                    "The provided date has not a valid format. Expected: " + DATE_PATTERN, e);
        }
    }

    /**
     * Like {@link #parseStartOfDay(String)}, but advanced to the last instant of that day, so that
     * an end date filters inclusively through the whole day rather than excluding everything after
     * its first millisecond.
     */
    private Date parseEndOfDay(String date) {
        Date parsed = parseStartOfDay(date);
        if (parsed == null) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(parsed);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    @Override
    public Class<AuditEventRest> getDomainClass() {
        return AuditEventRest.class;
    }

    @Override
    @PreAuthorize("hasAuthority('ADMIN')")
    public Page<AuditEventRest> findAll(Context context, Pageable pageable) {
        returnNotFoundIfDisabled();
        Sort sort = pageable.getSort();
        boolean asc = sort.isUnsorted() || (sort.isSorted() && sort.getOrderFor("timeStamp").isAscending());
        List<AuditEvent> events = auditService.findAllEvents(context, pageable.getPageSize(),
                (int) pageable.getOffset(), asc);
        long total = auditService.countAllEvents(context);
        return converter.toRestPage(events, pageable, total, utils.obtainProjection());
    }


    private void returnNotFoundIfDisabled() {
        if (!configurationService.getBooleanProperty("audit.enabled")) {
            throw new ResourceNotFoundException("Audit service is disabled");
        }
    }
}
