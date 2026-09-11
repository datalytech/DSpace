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
 * The {@link ScriptConfiguration} for the {@link MatchAuthorityScript}.
 *
 * @author Claude Code
 */
public class MatchAuthorityScriptConfiguration<T extends MatchAuthorityScript> extends ScriptConfiguration<T> {

    private Class<T> dspaceRunnableClass;

    @Override
    public Options getOptions() {
        if (options == null) {

            Options options = new Options();

            options.addOption(Option.builder("m").longOpt("metadata")
                .desc("the metadata to match, i.e. crispj.investigator. Can be repeated or given as a comma "
                    + "separated list. Defaults to " + String.join(", ", MatchAuthorityScript.DEFAULT_FIELDS))
                .hasArg().required(false).build());

            options.addOption(Option.builder("f").longOpt("fix")
                .desc("write the authorities found. Without this option nothing is modified and the script only "
                    + "reports what it would do")
                .hasArg(false).required(false).build());

            options.addOption(Option.builder("r").longOpt("recheck")
                .desc("also check the values that already carry an authority, and re-point the ones whose "
                    + "authority is missing, of the wrong entity type or linked to another name")
                .hasArg(false).required(false).build());

            options.addOption(Option.builder("u").longOpt("update-value")
                .desc("replace the text value with the name of the matched entity, for the given fields only. "
                    + "Can be repeated or given as a comma separated list. The value of crispj.investigator is "
                    + "never replaced, as it may credit the person through one of the alternative names")
                .hasArg().required(false).build());

            options.addOption(Option.builder("t").longOpt("entity-type")
                .desc("the entity type expected for a field, as field=EntityType, i.e. "
                    + "crispj.investigator=Person. Only needed when the authority does not declare one through "
                    + "cris.ItemAuthority.<name>.entityType. Can be repeated")
                .hasArg().required(false).build());

            options.addOption(Option.builder("l").longOpt("loose")
                .desc("accept the single candidate returned by the authority even when none of its names "
                    + "matches the value. Off by default, as it links values the authority only guessed")
                .hasArg(false).required(false).build());

            options.addOption(Option.builder("i").longOpt("item")
                .desc("only check the item with the given uuid, useful to try the script out")
                .hasArg().required(false).build());

            options.addOption(Option.builder("c").longOpt("collection")
                .desc("only check the items owned by the collection with the given uuid")
                .hasArg().required(false).build());

            options.addOption(Option.builder("o").longOpt("csv")
                .desc("also produce authority-match.csv with one row per checked value")
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
     *                            MatchAuthorityScriptConfiguration
     */
    @Override
    public void setDspaceRunnableClass(Class<T> dspaceRunnableClass) {
        this.dspaceRunnableClass = dspaceRunnableClass;
    }

}
