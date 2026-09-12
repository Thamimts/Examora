package com.examora.security;

/**
 * Fine-grained permissions used by {@link RolePermissions} and
 * {@link com.examora.service.AuthorizationService}. Roles are mapped to a set of
 * permissions; the existing path-matcher rules and inline role checks are preserved.
 */
public enum Permission {
    /** View and update your own profile, 2FA setup, and password. */
    ACCOUNT_SELF,
    /** Start and submit exam attempts. */
    ATTEMPT_START,
    ATTEMPT_SUBMIT,
    /** Review your own results and history. */
    RESULT_VIEW_OWN,
    /** Review all results (staff). */
    RESULT_VIEW_ALL,
    /** Request an extra attempt. */
    RETEST_REQUEST,
    /** Approve or reject retest requests. */
    RETEST_APPROVE,
    /** Create, edit, publish and delete exams (staff). */
    EXAM_MANAGE,
    /** View the full exam catalog (staff). */
    EXAM_VIEW_ALL,
    /** Manage questions and options (staff). */
    QUESTION_MANAGE,
    /** Monitor proctoring events (teachers: own exams only). */
    PROCTOR_MONITOR,
    /** Adaptive practice. */
    PRACTICE,
    /** AI practice and AI tutor sessions. */
    AI_PRACTICE,
    AI_TUTOR,
    /** Personalized AI guidance. */
    AI_COACH,
    /** View your own analytics. */
    ANALYTICS_VIEW,
    /** View system-wide analytics. */
    ADMIN_ANALYTICS_VIEW,
    /** Manage user accounts. */
    USER_MANAGE,
    /** View the audit log. */
    AUDIT_LOG_VIEW
}