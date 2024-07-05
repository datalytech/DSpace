package org.dspace.app.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dspace.app.rest.RestResourceController;

public class Bibliometris extends DSpaceObjectRest {
    public static final String NAME = "bibliometris";
    public static final String CATEGORY = RestAddressableModel.CORE;
    public static final String PLURAL_NAME = "bibliometris";

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getType() {
        return NAME;
    }

    public Class getController() {
        return RestResourceController.class;
    }
}