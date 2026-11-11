package com.aiworkforce.core.common.exception;

/**
 * Thrown by application services when a resource does not exist <em>or is not visible to the
 * caller</em>. Using 404 for resources of another organization avoids revealing their existence.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;

    public ResourceNotFoundException(String resourceType) {
        super(resourceType + " not found");
        this.resourceType = resourceType;
    }

    public String resourceType() {
        return resourceType;
    }
}
