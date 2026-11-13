package com.aiworkforce.core.access;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Permission catalog. Codes match the {@code permissions} table (seeded by Flyway V2;
 * a test asserts both stay identical). Permissions are always evaluated server-side from
 * the database, never from token claims or client state.
 */
public enum Permission {
    ORGANIZATION_READ("organization.read"),
    ORGANIZATION_UPDATE("organization.update"),
    MEMBER_READ("member.read"),
    MEMBER_MANAGE("member.manage"),
    MEMBER_ROLES_ASSIGN("member.roles.assign"),
    ROLE_READ("role.read"),
    ROLE_MANAGE("role.manage"),
    INVITATION_READ("invitation.read"),
    INVITATION_CREATE("invitation.create"),
    INVITATION_REVOKE("invitation.revoke"),
    AUDIT_READ("audit.read"),
    EMPLOYEE_READ("employee.read"),
    EMPLOYEE_CREATE("employee.create"),
    EMPLOYEE_MANAGE("employee.manage"),
    EMPLOYEE_DEACTIVATE("employee.deactivate"),
    DEPARTMENT_READ("department.read"),
    DEPARTMENT_MANAGE("department.manage"),
    POSITION_READ("position.read"),
    POSITION_MANAGE("position.manage"),
    TEAM_READ("team.read"),
    TEAM_MANAGE("team.manage"),
    /** Reserved to the built-in OWNER role: never grantable to custom roles. */
    OWNERSHIP_MANAGE("ownership.manage");

    private static final Map<String, Permission> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(Permission::code, Function.identity()));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Permission fromCode(String code) {
        Permission permission = BY_CODE.get(code);
        if (permission == null) {
            throw new IllegalArgumentException("Unknown permission: " + code);
        }
        return permission;
    }

    public static boolean isKnown(String code) {
        return BY_CODE.containsKey(code);
    }
}
