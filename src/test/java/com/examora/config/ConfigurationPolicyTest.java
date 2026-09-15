package com.examora.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.examora.config.ConfigurationPolicy.Issue;
import com.examora.config.ConfigurationPolicy.ProviderState;
import com.examora.config.ConfigurationPolicy.Severity;

/**
 * Pure unit tests for {@link ConfigurationPolicy}, Examora's P6.1 production
 * configuration fail-fast policy. Follows the same convention as the other
 * pure policy tests in this repository: no Spring context, no I/O, no network.
 *
 * <p>Policy inputs are <em>presence booleans</em> only — client-ids, secrets
 * and redirect URIs are never passed into the policy, so a passing test also
 * proves the policy's messages cannot leak those values by construction.</p>
 *
 * <p>Cases covered (see ConfigurationPolicyValidator / config-docs):</p>
 * <ul>
 *   <li><b>A</b> OAuth disabled + nothing configured: valid (no issues).</li>
 *   <li><b>B</b> OAuth enabled + no provider fully configured: startup ERROR.</li>
 *   <li><b>C</b> OAuth enabled + partially configured provider: startup ERROR.</li>
 *   <li><b>D</b> OAuth enabled + one fully configured provider: valid.</li>
 *   <li><b>F</b> OAuth disabled + partial/asymmetric provider: WARNING only
 *       (never an ERROR, so dev/demo/tests stay green).</li>
 *   <li><b>Fp</b> demo profile + production profile together: startup ERROR;
 *       demo alone: valid.</li>
 *   <li><b>Sql</b> demo SQL data loaded outside the demo profile: startup
 *       ERROR; demo data with the demo profile: valid.</li>
 *   <li><b>G</b> Messages are secret-safe: they cite only property keys and
 *       never resemble client-ids, secrets, or JWT material.</li>
 * </ul>
 */
class ConfigurationPolicyTest {

    private static final ProviderState EMPTY_GOOGLE = new ProviderState("google", false, false, false);
    private static final ProviderState EMPTY_GITHUB = new ProviderState("github", false, false, false);

    /** Property keys are lower-case dotted identifiers, never values. */
    private static final Pattern KEY_SHAPED =
	    Pattern.compile("[a-z][a-zA-Z0-9.-]*");

    /** Roughly matches JWT/base64/opaque-token-shaped content we must never emit. */
    private static final Pattern SECRET_SHAPED =
	    Pattern.compile("(eyJ[a-zA-Z0-9_-]{6,}|[A-Za-z0-9+/]{40,}={0,2})");

    @Test
    void A_oauthDisabled_WithNothingConfigured_isValid() {
	List<Issue> issues =
		ConfigurationPolicy.evaluate(false, List.of(EMPTY_GOOGLE, EMPTY_GITHUB), List.of(), List.of());
	assertTrue(issues.isEmpty(), () -> "expected no issues but got " + issues);
    }

    @Test
    void B_oauthEnabled_NoProviderFullyConfigured_isStartupError() {
	List<Issue> issues = ConfigurationPolicy.evaluate(true,
		List.of(EMPTY_GOOGLE, EMPTY_GITHUB), List.of(), List.of());
	assertTrue(issues.stream().anyMatch(i -> i.severity() == Severity.ERROR),
		() -> "expected at least one ERROR but got " + issues);
    }

    @Test
    void C_oauthEnabled_PartiallyConfiguredProvider_isStartupError() {
	List<Issue> issues = ConfigurationPolicy.evaluate(true,
		List.of(new ProviderState("google", true, false, false), EMPTY_GITHUB),
		List.of(), List.of());
	assertTrue(issues.stream().anyMatch(i -> i.severity() == Severity.ERROR),
		() -> "partial provider with oauth on must fail fast: " + issues);
    }

    @Test
    void D_oauthEnabled_FullyConfiguredProvider_isValid() {
	List<Issue> issues = ConfigurationPolicy.evaluate(true,
		List.of(new ProviderState("google", true, true, true), EMPTY_GITHUB),
		List.of(), List.of());
	assertTrue(issues.isEmpty(), () -> "fully configured provider with oauth on: " + issues);
    }

    @Test
    void F_oauthDisabled_AsymmetricProvider_isWarningOnly() {
	List<Issue> issues = ConfigurationPolicy.evaluate(false,
		List.of(new ProviderState("google", true, false, true), EMPTY_GITHUB),
		List.of(), List.of());
	assertFalse(issues.isEmpty(), "asymmetric provider should be surfaced even with oauth off");
	assertTrue(issues.stream().noneMatch(i -> i.severity() == Severity.ERROR),
		() -> "with oauth disabled, partial providers must be warnings only: " + issues);
    }

    @Test
    void Fp_demoAndProductionProfilesTogether_isStartupError() {
	List<String> sql = List.of("classpath:schema.sql", "classpath:data-demo.sql");
	List<Issue> demoAndProd = ConfigurationPolicy.evaluate(false,
		List.of(), List.of("demo", "prod"), sql);
	assertTrue(demoAndProd.stream().anyMatch(i -> i.severity() == Severity.ERROR),
		() -> "demo + production together must abort startup: " + demoAndProd);

	List<Issue> demoAlone = ConfigurationPolicy.evaluate(false,
		List.of(), List.of("demo"), sql);
	assertTrue(demoAlone.stream().noneMatch(i -> i.severity() == Severity.ERROR),
		() -> "demo alone is a valid dev configuration: " + demoAlone);
    }

    @Test
    void Sql_demoDataWithoutDemoProfile_isStartupError() {
	List<String> sql = List.of("classpath:schema.sql", "classpath:data-demo.sql");
	List<Issue> noDemoProfile = ConfigurationPolicy.evaluate(false,
		List.of(), List.of("prod"), sql);
	assertTrue(noDemoProfile.stream().anyMatch(i -> i.severity() == Severity.ERROR),
		() -> "loading demo data outside the demo profile must abort: " + noDemoProfile);

	List<Issue> withDemoProfile = ConfigurationPolicy.evaluate(false,
		List.of(), List.of("demo"), sql);
	assertTrue(withDemoProfile.stream().noneMatch(i -> i.severity() == Severity.ERROR),
		() -> "demo data under the demo profile is valid: " + withDemoProfile);
    }

    @Test
    void G_issueMessages_NeverContainSecretShapedContent() {
	ProviderState asymmetric = new ProviderState("google", true, false, true);
	List<Issue> issues = ConfigurationPolicy.evaluate(false, List.of(asymmetric, EMPTY_GITHUB),
		List.of("demo", "prod"), List.of("classpath:schema.sql", "classpath:data-demo.sql"));
	assertFalse(issues.isEmpty(), "this scenario should produce at least the asymmetric warning");

	for (Issue issue : issues) {
	    assertFalse(SECRET_SHAPED.matcher(issue.message()).find(),
		    () -> "issue message must never leak secret-shaped content: " + issue.message());
	    assertTrue(KEY_SHAPED.matcher(issue.property()).matches(),
		    () -> "issue property must be a plain key, not a value: " + issue.property());
	}
    }
}
