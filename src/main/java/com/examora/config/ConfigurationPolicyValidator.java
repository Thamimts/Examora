package com.examora.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup fail-fast validator for Examora's {@link ConfigurationPolicy}.
 *
 * <p>Runs as a {@link SmartInitializingSingleton}, so it executes after every
 * singleton bean (including {@code OAuthConfig} and the datasource) has been
 * created but while the container is still refreshing. Any {@code ERROR} issue
 * returned by {@link ConfigurationPolicy#evaluate} aborts startup with one
 * aggregated, secret-safe message before the web server begins serving.</p>
 *
 * <p>This bean never reads or logs secret values:</p>
 * <ul>
 *   <li>Provider credentials ({@code client-id}, {@code client-secret},
 *       {@code redirect-uri}) are captured only as <em>presence flags</em>
 *       (non-blank? true) via the same property keys {@code OAuthConfig}
 *       binds, then handed to the policy as {@link ConfigurationPolicy.ProviderState}.</li>
 *   <li>{@code OAUTH_ENABLED} and the active profiles are presence-only too.</li>
 *   <li>Issue messages contain only property/environment names — never client
 *       ids, secrets, redirect URIs, or any other configured value.</li>
 * </ul>
 */
@Component
public class ConfigurationPolicyValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationPolicyValidator.class);

    private final Environment environment;

    public ConfigurationPolicyValidator(Environment environment) {
	this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
	List<ConfigurationPolicy.Issue> issues = evaluate(environment);
	if (issues.isEmpty()) {
	    log.info("Examora configuration policy: valid. OAuth={} demo-data-isolation={}",
		    Boolean.valueOf(resolveBool("examora.oauth.enabled", false)));
	    return;
	}

	long errors = issues.stream().filter(i -> i.severity() == ConfigurationPolicy.Severity.ERROR).count();
	for (ConfigurationPolicy.Issue issue : issues) {
	    if (issue.severity() == ConfigurationPolicy.Severity.ERROR) {
		log.error("Configuration policy [{}]: {}", issue.property(), issue.message());
	    } else {
		log.warn("Configuration policy [{}]: {}", issue.property(), issue.message());
	    }
	}
	if (errors > 0) {
	    StringBuilder summary = new StringBuilder(
		    "Examora startup aborted by the production configuration policy ("
			    + errors + " error(s), " + (issues.size() - errors) + " warning(s)).\n");
	    for (ConfigurationPolicy.Issue issue : issues) {
		if (issue.severity() == ConfigurationPolicy.Severity.ERROR) {
		    summary.append(" - ").append(issue.property()).append(": ")
			    .append(issue.message()).append('\n');
		}
	    }
	    summary.append("Fix the reported properties and restart. Secret values are intentionally "
		    + "never included in policy messages.");
	    throw new IllegalStateException(summary.toString());
	}
    }

    /**
     * Build the policy inputs from the live {@link Environment} and evaluate.
     * Structured as a static method so the integration path and any test can
     * share exactly the same wiring.
     */
    public static List<ConfigurationPolicy.Issue> evaluate(Environment environment) {
	List<ConfigurationPolicy.ProviderState> providers = new ArrayList<>();
	providers.add(providerState(environment, "google"));
	providers.add(providerState(environment, "github"));

	return ConfigurationPolicy.evaluate(
		resolveBool(environment.getProperty("examora.oauth.enabled", "false"), false),
		providers,
		List.of(environment.getActiveProfiles()),
		sqlDataLocations(environment));
    }

    /** Presence-only snapshot: values are never read, only whether they are present. */
    private static ConfigurationPolicy.ProviderState providerState(Environment environment, String name) {
	String prefix = "examora.oauth." + name;
	return new ConfigurationPolicy.ProviderState(
		name,
		hasValue(environment, prefix + ".client-id"),
		hasValue(environment, prefix + ".client-secret"),
		hasValue(environment, prefix + ".redirect-uri"));
    }

    private static boolean hasValue(Environment environment, String property) {
	String value = environment.getProperty(property, (String) null);
	return value != null && !value.isBlank();
    }

    /** Normalise {@code spring.sql.init.data-locations} into a resolvable list. */
    private static List<String> sqlDataLocations(Environment environment) {
	List<String> locations = new ArrayList<>();
	String raw = environment.getProperty("spring.sql.init.data-locations", "");
	if (raw != null && !raw.isBlank()) {
	    for (String part : raw.split(",")) {
		String trimmed = part.trim();
		if (!trimmed.isEmpty()) {
		    locations.add(trimmed);
		}
	    }
	}
	return locations;
    }

    private static boolean resolveBool(String raw, boolean fallback) {
	if (raw == null || raw.isBlank()) {
	    return fallback;
	}
	return "true".equalsIgnoreCase(raw.trim())
		|| "1".equals(raw.trim());
    }

    static String oauthEnabledLabel(Environment environment) {
	return Boolean.valueOf(resolveBool(environment.getProperty("examora.oauth.enabled", "false"), false))
		.toString().toLowerCase(Locale.ROOT);
    }
}
