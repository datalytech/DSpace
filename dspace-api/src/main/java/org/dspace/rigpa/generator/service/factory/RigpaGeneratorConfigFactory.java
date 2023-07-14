/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.generator.service.factory;

import java.util.Map;

import org.dspace.rigpa.generator.GeneratorOfTitle;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * This abstract class contains the static reference to the {@link RigpaGeneratorConfigFactory}
 * implementation configured inside the {@linkplain rigpa-consumers.xml} file.
 * 
 * @author Vincenzo Mecca (vins01-4science - vincenzo.mecca at 4science.com)
 *
 */
public abstract class RigpaGeneratorConfigFactory {

    public static final String RIGPA_CONFIG_FACTORY = "rigpaGeneratorConfigFactory";

    public static RigpaGeneratorConfigFactory getInstance() {
        return DSpaceServicesFactory.getInstance().getServiceManager()
                .getServiceByName(RIGPA_CONFIG_FACTORY, RigpaGeneratorConfigFactory.class);
    }

    protected abstract Map<String, GeneratorOfTitle> getGeneratorsMap();

    public abstract GeneratorOfTitle getGeneratorFor(String entityType);

}
