/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.audit;

import org.apache.commons.cli.Options;
import org.dspace.scripts.configuration.ScriptConfiguration;

/**
 * The {@link ScriptConfiguration} for the {@link AuditExport} script.
 * <p>
 * No {@link #isAllowedToExecute} override is provided: the base
 * {@link ScriptConfiguration} restricts every script to repository administrators unless a
 * subclass widens that, which is exactly the access this export needs.
 *
 * @author Claude Sonnet 5
 */
public class AuditExportScriptConfiguration<T extends AuditExport> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableClass;

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

    @Override
    public Options getOptions() {
        if (options == null) {
            Options options = new Options();

            options.addOption("o", "object", true,
                    "Limit to events on this object (or where it is the subject), by UUID");
            options.addOption("p", "eperson", true, "Limit to events performed by this EPerson, by UUID");
            options.addOption("t", "type", true,
                    "Limit to this event type: one of CREATE, MODIFY, MODIFY_METADATA, ADD, REMOVE, "
                            + "DELETE, INSTALL");
            options.addOption("f", "from", true, "Limit to events on or after this date (yyyy-MM-dd)");
            options.addOption("u", "until", true,
                    "Limit to events on or before this date (yyyy-MM-dd), the whole day is included");
            options.addOption("h", "help", false, "help");

            super.options = options;
        }
        return options;
    }

}
