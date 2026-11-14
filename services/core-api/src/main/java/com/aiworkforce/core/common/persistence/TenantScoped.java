package com.aiworkforce.core.common.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository of organization-owned data. Enforced by {@code ArchitectureTest}:
 * <ul>
 *   <li>every declared method name contains {@code Organization} (it takes the organization id);</li>
 *   <li>no code calls the unscoped inherited methods ({@code findById}, {@code findAll}, ...).</li>
 * </ul>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {
}
