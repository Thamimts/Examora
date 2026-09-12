package com.examora.security;

import com.examora.config.OAuthProviderConfig;
import com.examora.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Shared token-exchange and provider HTTP handling. Secrets are never logged;
 * only the HTTP status of a failed provider request is recorded.
 */
public abstract class AbstractOAuthClient implements OAuthProviderClient {
    private static final Logger log = LoggerFactory.getLogger(AbstractOAuthClient.class);

    protected final OAuthProviderConfig config;
    protected final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    protected AbstractOAuthClient(OAuthProviderConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    protected String acquireAccessToken(String code) {
        String body = "client_id=" + urlEncode(config.clientId())
                + "&client_secret=" + urlEncode(config.clientSecret())
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(config.redirectUri())
                + "&grant_type=authorization_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode response = sendJson(request);
        JsonNode token = response.get("access_token");
        if (token == null || token.isNull() || !token.isTextual()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The sign-in service did not return an access token.");
        }
        return token.asText();
    }

    protected JsonNode getJson(String url, String accessToken, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", accept)
                .GET();
        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return sendJson(builder.build());
    }

    private JsonNode sendJson(HttpRequest request) {
        byte[] payload;
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            payload = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OAuth provider request failed with HTTP {}", response.statusCode());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The sign-in service is temporarily unavailable.");
            }
            JsonNode json = objectMapper.readTree(payload);
            if (json == null || json.isMissingNode() || (!json.isObject() && !json.isArray())) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The sign-in service returned an invalid response.");
            }
            return json;
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The sign-in request was interrupted.");
        } catch (Exception exception) {
            log.warn("OAuth provider request could not be completed: {}", exception.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The sign-in service could not be reached.");
        }
    }

    protected static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}