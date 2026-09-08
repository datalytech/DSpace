/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content.authority.script;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.dspace.scripts.configuration.ScriptConfiguration;

/**
 * The {@link ScriptConfiguration} for the {@link DanglingAuthorityScript}.
 *
 * @author Claude Code
 */
public class DanglingAuthorityScriptConfiguration<T extends DanglingAuthorityScript> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableClass;

    @Override
    public Options getOptions() {
        if (options == null) {

            Options options = new Options();

            options.addOption(Option.builder("m").longOpt("metadata")
                .desc("only check the given metadata field, i.e. dc.contributor.author. Can be repeated or given "
                    + "as a comma separated list. Defaults to every field carrying an authority")
                .hasArg().required(false).build());

            options.addOption(Option.builder("f").longOpt("fix")
                .desc("repair the dangling authorities found: clear drops the authority keeping the text value, "
                    + "relink looks the text value up again and re-points the authority to the matching item. "
                    + "Without this option nothing is modified")
                .hasArg().required(false).build());

            options.addOption(Option.builder("c").longOpt("csv")
                .desc("also produce dangling-authorities.csv with one row per broken reference")
                .hasArg(false).required(false).build());

            super.options = options;
        }
        return options;
    }

    @Override
    public Class<T> getDspaceRunnableClass() {
        return dspaceRunnableClass;
    }

    /**
     * Generic setter for the dspaceRunnableClass
     *
     * @param dspaceRunnableClass The dspaceRunnableClass to be set on this
     *                            DanglingAuthorityScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

}
