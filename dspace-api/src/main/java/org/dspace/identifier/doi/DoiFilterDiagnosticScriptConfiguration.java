/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier.doi;

import org.apache.commons.cli.Options;
import org.dspace.scripts.configuration.ScriptConfiguration;

/**
 * The {@link ScriptConfiguration} for the {@link DoiFilterDiagnostic} script.
 * <p>
 * No {@link #isAllowedToExecute} override is provided: the base {@link ScriptConfiguration}
 * restricts every script to repository administrators unless a subclass widens that, which is
 * exactly the access this diagnostic needs.
 *
 * @author Claude Sonnet 5
 */
public class DoiFilterDiagnosticScriptConfiguration<T extends DoiFilterDiagnostic> extends ScriptConfiguration<T> {

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

            options.addOption("i", "uuid", true,
                    "Check only this item, by UUID. Repeatable (-i uuid1 -i uuid2 ...).");
            options.addOption("f", "file", true,
                    "Path to a text file with one item UUID per line (blank lines and lines "
                            + "starting with # are ignored). Can be combined with -i.");
            options.addOption("a", "all", false,
                    "Check every item in the repository instead of a specific list. Cannot be "
                            + "combined with -i or -f. May take a long time on a large repository.");
            options.addOption("h", "help", false, "help");

            super.options = options;
        }
        return options;
    }

}
