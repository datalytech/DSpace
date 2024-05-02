/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.submit.service;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.dspace.app.util.SubmissionConfig;
import org.dspace.app.util.SubmissionConfigReader;
import org.dspace.app.util.SubmissionConfigReaderException;
import org.dspace.app.util.SubmissionStepConfig;
import org.dspace.content.Collection;
import org.dspace.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * An implementation for Submission Config service
 *
 * @author paulo.graca at fccn.pt
 */
public class SubmissionConfigServiceImpl implements SubmissionConfigService, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SubmissionConfigServiceImpl.class);

    protected SubmissionConfigReader submissionConfigReader;

    public SubmissionConfigServiceImpl () {
        initSubmissionConfigReader();
    }

    protected Optional<SubmissionConfigReader> getSubmissionConfigReader() {
        if (this.submissionConfigReader == null) {
            initSubmissionConfigReader();
        }
        return Optional.ofNullable(submissionConfigReader);
    }

    private void initSubmissionConfigReader() {
        try {
            submissionConfigReader = new SubmissionConfigReader();
        } catch (Exception e) {
            log.error("Cannot initialize SubmissionConfigReader!", e);
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.reload();
    }

    private void reload(SubmissionConfigReader submissionConfigReader1) {
        try {
            submissionConfigReader1.reload();
        } catch (SubmissionConfigReaderException e) {
            log.error("Cannot reload SubmissionConfigReader!", e);
        }
    }

    @Override
    public void reload() throws SubmissionConfigReaderException {
        getSubmissionConfigReader().ifPresent(this::reload);
    }

    @Override
    public String getDefaultSubmissionConfigName() {
        return getSubmissionConfigReader().map(s -> s.getDefaultSubmissionConfigName()).orElse(null);
    }

    @Override
    public List<SubmissionConfig> getAllSubmissionConfigs(Integer limit, Integer offset) {
        return getSubmissionConfigReader().map(s -> s.getAllSubmissionConfigs(limit, offset)).orElse(List.of());
    }

    @Override
    public int countSubmissionConfigs() {
        return getSubmissionConfigReader().map(s -> s.countSubmissionConfigs()).orElse(-1);
    }

    @Override
    public SubmissionConfig getSubmissionConfigByCollection(Collection collection) {
        return getSubmissionConfigReader().map(s -> s.getSubmissionConfigByCollection(collection)).orElse(null);
    }

    @Override
    public SubmissionConfig getSubmissionConfigByName(String submitName) {
        return getSubmissionConfigReader().map(s -> s.getSubmissionConfigByName(submitName)).orElse(null);
    }

    @Override
    public SubmissionStepConfig getStepConfig(String stepID) throws SubmissionConfigReaderException {
        return getSubmissionConfigReader().map(s -> {
            try {
                return s.getStepConfig(stepID);
            } catch (SubmissionConfigReaderException e) {
                throw new RuntimeException(e);
            }
        }).orElse(null);
    }

    @Override
    public List<Collection> getCollectionsBySubmissionConfig(Context context, String submitName)
            throws IllegalStateException, SQLException {
        return getSubmissionConfigReader().map(s -> {
            try {
                return getCollectionsBySubmissionConfig(context, submitName);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }).orElse(List.of());
    }

}
