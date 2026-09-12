package com.examora.security;

import com.examora.model.Role;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps each {@link Role} to the {@link Permission}s it is granted.
 */
public final class RolePermissions {
    private static final Set<Permission> STUDENT = EnumSet.of(
            Permission.ACCOUNT_SELF,
            Permission.ATTEMPT_START,
            Permission.ATTEMPT_SUBMIT,
            Permission.RESULT_VIEW_OWN,
            Permission.RETEST_REQUEST,
            Permission.PRACTICE,
            Permission.AI_PRACTICE,
            Permission.AI_TUTOR,
            Permission.AI_COACH,
            Permission.ANALYTICS_VIEW);

    private static final Set<Permission> TEACHER = EnumSet.of(
            Permission.ACCOUNT_SELF,
            Permission.EXAM_MANAGE,
            Permission.EXAM_VIEW_ALL,
            Permission.QUESTION_MANAGE,
            Permission.RESULT_VIEW_ALL,
            Permission.PROCTOR_MONITOR);

    private static final Set<Permission> ADMIN = EnumSet.of(
            Permission.ACCOUNT_SELF,
            Permission.ATTEMPT_START,
            Permission.ATTEMPT_SUBMIT,
            Permission.RESULT_VIEW_OWN,
            Permission.RESULT_VIEW_ALL,
            Permission.RETEST_REQUEST,
            Permission.RETEST_APPROVE,
            Permission.EXAM_MANAGE,
            Permission.EXAM_VIEW_ALL,
            Permission.QUESTION_MANAGE,
            Permission.PROCTOR_MONITOR,
            Permission.PRACTICE,
            Permission.AI_PRACTICE,
            Permission.AI_TUTOR,
            Permission.AI_COACH,
            Permission.ANALYTICS_VIEW,
            Permission.ADMIN_ANALYTICS_VIEW,
            Permission.USER_MANAGE,
            Permission.AUDIT_LOG_VIEW);

    private static final Map<Role, Set<Permission>> PERMISSIONS = Map.of(
            Role.STUDENT, STUDENT,
            Role.TEACHER, TEACHER,
            Role.ADMIN, ADMIN);

    private RolePermissions() {
    }

    public static Set<Permission> forRole(Role role) {
        return PERMISSIONS.getOrDefault(role, Set.of());
    }

    public static boolean has(Role role, Permission permission) {
        return forRole(role).contains(permission);
    }
}