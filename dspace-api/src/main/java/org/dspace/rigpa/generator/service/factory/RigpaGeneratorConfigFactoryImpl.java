/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.rigpa.generator.service.factory;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.dspace.rigpa.generator.GeneratorOfTitle;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * This is the standard implementation of the {@link RigpaGeneratorConfigFactory}
 * and contains the {@link RigpaGeneratorConfigFactoryImpl#generatorsMap} derived
 * from the configuration file {@linkplain rigpa-consumers.xml}.
 * 
 * @author Vincenzo Mecca (vins01-4science - vincenzo.mecca at 4science.com)
 *
 */
public class RigpaGeneratorConfigFactoryImpl extends RigpaGeneratorConfigFactory {

    private Map<String, GeneratorOfTitle> generatorsMap;

    @Autowired
    protected void setGenerators(Set<GeneratorOfTitle> generators) {
        setGeneratorsMap(
            generators.stream()
                .collect(
                    Collectors.toMap(
                        GeneratorOfTitle::getSupportedType,
                        Function.identity()
                    )
                )
        );
    }

    @Override
    public Map<String, GeneratorOfTitle> getGeneratorsMap() {
        return this.generatorsMap;
    }

    public void setGeneratorsMap(Map<String, GeneratorOfTitle> generatorsMap) {
        this.generatorsMap = generatorsMap;
    }

    @Override
    public GeneratorOfTitle getGeneratorFor(String entityType) {
        if (entityType == null) {
            return null;
        }
        return this.generatorsMap.get(entityType);
    }

}
