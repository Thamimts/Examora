package com.examora.controller;

import com.examora.config.OAuthConfig;
import com.examora.dto.ApiResponse;
import com.examora.exception.ApiException;
import com.examora.exception.TooManyRequestsException;
import com.examora.security.ClientIpResolver;
import com.examora.security.OAuthProvider;
import com.examora.service.OAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthController {
    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthService oauthService;
    private final OAuthConfig config;
    private final ClientIpResolver clientIpResolver;

    public OAuthController(OAuthService oauthService, OAuthConfig config, ClientIpResolver clientIpResolver) {
        this.oauthService = oauthService;
        this.config = config;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/providers")
    public ApiResponse<List<OAuthService.ProviderAvailability>> providers() {
        return ApiResponse.ok(oauthService.availableProviders());
    }

    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable String provider,
                                      @RequestParam(value = "returnTo", required = false) String returnTo,
                                      HttpServletRequest http) {
        OAuthProvider resolved = requireProvider(provider);
        String authorizeUrl = oauthService.startLoginUrl(resolved, returnTo, clientIpResolver.resolve(http));
        return redirect(authorizeUrl);
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         HttpServletRequest http) {
        OAuthProvider resolved = requireProvider(provider);
        try {
            OAuthService.OAuthCallbackResult result = oauthService.completeCallback(
                    resolved, code, state, clientIpResolver.resolve(http));
            String target = config.frontendBase() + result.returnTo() + "?token=" + urlEncode(result.auth().token());
            return redirect(target);
        } catch (ApiException exception) {
            log.info("OAuth callback for {} rejected: {}", provider, exception.getMessage());
            return redirect(config.frontendBase() + "/login?error=" + urlEncode(exception.getMessage()));
        } catch (TooManyRequestsException exception) {
            log.warn("OAuth callback for {} rate limited", provider);
            return redirect(config.frontendBase() + "/login?error=" + urlEncode("Too many sign-in attempts. Please try again later."));
        }
    }

    private OAuthProvider requireProvider(String provider) {
        try {
            return OAuthProvider.valueOf(provider.trim().toUpperCase());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Unknown sign-in provider.");
        }
    }

    private ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}