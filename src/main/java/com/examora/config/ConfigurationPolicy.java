package com.examora.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Declarative production configuration policy for Examora.
 *
 * <p>All validation is pure and testable: a static {@code evaluate} returns a
 * list of {@code Issue}s and never touches the network or the container. The
 * startup validator ({@code ConfigurationPolicyValidator}) feeds real
 * Environment/OAuth values into {@link #evaluate} and fails the application
 * fast whenever an issue is returned.</p>
 *
 * <p>Policy (applies at startup only):</p>
 * <ul>
 *   <li><b>OAuth fail-fast</b> - when OAuth is enabled, at least one provider
 *       must be fully configured (client-id, client-secret and redirect-uri).
 *       Partial or asymmetric configuration is a startup error, so a
 *       misconfigured provider can never be silently accepted.</li>
 *   <li><b>Suspicious partial config</b> - even when OAuth is disabled, a
 *       client-id without a secret (or a secret without an id) is reported as
 *       a warning so it cannot silently leak into a later accidental enable.</li>
 *   <li><b>Demo isolation</b> - the demo profile/data must never be active in a
 *       production profile; combining them is a startup error.</li>
 * </ul>
 *
 * <p>No secret values are ever embedded in issue messages; messages reference
 * only property/environment names. This keeps errors readable and safe to log.</p>
 */
public final class ConfigurationPolicy {

    /** Profiles that are reserved for non-production environments. */
    private static final Set<String> DEMO_NAMES = Set.of("demo", "dev");

    /** Profiles / markers that indicate an explicitly production intent. */
    private static final Set<String> PRODUCTION_MARKERS = Set.of("prod", "production", "staging");

    /** Message text shared by every secret-safe citation. */
    private static final String NEVER_LOG_VALUES = " Values are intentionally omitted from this message.";

    private ConfigurationPolicy() {
    }

    public enum Severity {
	ERROR, WARNING
    }

    /**
     * A single policy finding. {@code property} is the environment/property key
     * that failed; {@code message} is secret-safe (never contains secret values).
     */
    public record Issue(Severity severity, String property, String message) {
    }

    /**
     * Snapshot of one OAuth provider's configuration. Client-id and secret are
     * captured only as presence flags ({@code configured}) so that the policy
     * can reason about completeness without ever exposing the values in
     * messages or logs.
     */
    public record ProviderState(
	    String name,
	    boolean clientIdSet,
	    boolean clientSecretSet,
	    boolean redirectUriSet) {

	public boolean partiallyConfigured() {
	    int set = (clientIdSet ? 1 : 0) + (clientSecretSet ? 1 : 0) + (redirectUriSet ? 1 : 0);
	    return set > 0 && set < 3;
	}

	public boolean fullyConfigured() {
	    return clientIdSet && clientSecretSet && redirectUriSet;
	}

	/** Initiates clientId+secret but has no supported redirect URI configured. */
	public boolean asymmetric() {
	    return clientIdSet != clientSecretSet;
	}
    }

    /**
     * Evaluate the whole startup policy.
     *
     * @param oauthEnabled  the {@code examora.oauth.enabled} value
     * @param providers     per-provider presence snapshots (in registration order)
     * @param activeProfiles currently active Spring profiles
     * @param sqlDataLocations the SQL data-locations the app is configured to load
     * @return sorted issue list (errors first, then warnings); empty when valid
     */
    public static List<Issue> evaluate(boolean oauthEnabled,
	    List<ProviderState> providers,
	    List<String> activeProfiles,
	    List<String> sqlDataLocations) {
	List<Issue> issues = new ArrayList<>();
	issues.addAll(oauth(oauthEnabled, providers));
	issues.addAll(profiles(activeProfiles, sqlDataLocations));
	issues.sort(Comparator
		.comparing((Issue i) -> i.severity().ordinal())
		.thenComparing(Issue::property));
	return issues;
    }

    /**
     * OAuth configuration policy.
     *
     * <ul>
     *   <li>OAuth disabled: no error. A provider carrying a client-id without a
     *       matching secret (or the reverse) is reported as a warning.</li>
     *   <li>OAuth enabled: at least one provider must be fully configured.
     *       Any partially-configured provider fails startup with a clear error.
     *       (A provider with no values at all is simply "not configured" and is
     *       permitted, matching the existing dev/demo behaviour.)</li>
     * </ul>
     */
    public static List<Issue> oauth(boolean enabled, List<ProviderState> providers) {
	List<Issue> issues = new ArrayList<>();
	if (enabled) {
	    boolean noneFullyConfigured = true;
	    for (ProviderState provider : providers) {
		if (provider.fullyConfigured()) {
		    noneFullyConfigured = false;
		} else if (provider.partiallyConfigured()) {
		    issues.add(new Issue(Severity.ERROR, "examora.oauth." + provider.name(),
			    "OAuth is enabled, but provider \"" + provider.name()
				    + "\" is only partially configured. Every enabled provider must define "
				    + "client-id, client-secret and redirect-uri together ("
				    + "OAUTH_ENABLED=true is set, so partial or asymmetric provider "
				    + "configuration is a startup error)." + NEVER_LOG_VALUES));
		}
	    }
	    if (noneFullyConfigured) {
		issues.add(new Issue(Severity.ERROR, "examora.oauth.enabled",
			"OAuth is enabled (OAUTH_ENABLED=true) but no provider is fully configured. "
				+ "At least one of examora.oauth.google or examora.oauth.github must provide "
				+ "client-id, client-secret and redirect-uri before OAuth can start."
				+ NEVER_LOG_VALUES));
	    }
	} else {
	    for (ProviderState provider : providers) {
		if (provider.asymmetric()) {
		    issues.add(new Issue(Severity.WARNING, "examora.oauth." + provider.name(),
			    "OAuth is disabled, but provider \"" + provider.name() + "\" has a client-id "
				    + "without a matching client-secret (or the reverse). This looks like an "
				    + "unfinished or accidentally-leaked configuration and will fail startup "
				    + "once OAUTH_ENABLED=true." + NEVER_LOG_VALUES));
		} else if (provider.partiallyConfigured() && provider.redirectUriSet) {
		    issues.add(new Issue(Severity.WARNING, "examora.oauth." + provider.name(),
			    "OAuth is disabled, but provider \"" + provider.name()
				    + "\" has a redirect-uri set without matching client credentials. "
				    + "Redirect URIs are not secrets, but an unfinished provider configuration "
				    + "should be completed or removed." + NEVER_LOG_VALUES));
		}
	    }
	}
	return issues;
    }

    /**
     * Profile / demo-data isolation policy.
     *
     * <ul>
     *   <li><b>Production</b>= any active profile matching {@code PRODUCTION_MARKERS};
     *       <b>demo</b>= any matching {@code DEMO_NAMES}.</li>
     *   <li>Demo + production together is a startup error.</li>
     *   <li>Demo data (data-demo.sql) must only ever be loaded when a demo marker
     *       profile is active; loading demo data in any other profile is an error.</li>
     * </ul>
     */
    public static List<Issue> profiles(List<String> activeProfiles, List<String> sqlDataLocations) {
	List<Issue> issues = new ArrayList<>();
	List<String> profiles = activeProfiles == null ? List.of() : activeProfiles;
	boolean demo = profiles.stream().anyMatch(p -> DEMO_NAMES.contains(p.toLowerCase(Locale.ROOT)));
	boolean production = profiles.stream().anyMatch(p -> PRODUCTION_MARKERS.contains(p.toLowerCase(Locale.ROOT)));

	if (demo && production) {
	    issues.add(new Issue(Severity.ERROR, "spring.profiles.active",
		    "The demo profile is active alongside a production profile. Demo accounts and demo data "
			    + "must never load in production. Remove the demo profile (SPRING_PROFILES_ACTIVE=demo) "
			    + "and use only production profiles."));
	}

	List<String> dataLocations = sqlDataLocations == null ? List.of() : sqlDataLocations;
	boolean loadsDemoData = dataLocations.stream().anyMatch(loc -> loc.toLowerCase(Locale.ROOT).contains("data-demo"));
	if (loadsDemoData && !demo) {
	    issues.add(new Issue(Severity.ERROR, "spring.sql.init.data-locations",
		    "Demo data (data-demo.sql) is configured to load, but no demo profile is active. "
			    + "Demo data must only be initialized with the demo profile. Set SQL_INIT_MODE=never "
			    + "or remove the demo data-location from this profile."));
	}
	return issues;
    }
}
