package org.tsanet.connector.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.tsanet.connector.config.ConnectorProperties;

/**
 * One parameterized verifier for all three inbound signature schemes
 * (docs/decisions.md D1). Default-deny: /healthz is the only open route, requests
 * matching a scheme are verified, and anything else is rejected 401, so an encoded or
 * parameterized path that dodges the prefix match gets rejected rather than passed
 * through. Matching uses a decoded, semicolon-stripped path so the 401-vs-verify split
 * mirrors what the container routes. Missing or unconfigured secrets fail closed.
 */
@Component
public class SignatureVerificationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerificationFilter.class);

    /** header, algorithm, required prefix ("" = bare hex), optional prefix tolerated. */
    record Scheme(String pathPrefix, String header, String algorithm, String prefix, boolean prefixOptional,
                  Function<ConnectorProperties, String> secret) {
    }

    private static final List<Scheme> SCHEMES = List.of(
            new Scheme("/canvas/", "X-Body-Signature", "HmacSHA256", "", false,
                    ConnectorProperties::intercomClientSecret),
            new Scheme("/webhooks/intercom", "X-Hub-Signature", "HmacSHA1", "sha1=", false,
                    ConnectorProperties::intercomClientSecret),
            // Prefix tolerated pending byte-exact confirmation from the first live
            // TSANet delivery (docs/decisions.md D1).
            new Scheme("/webhooks/tsanet", "x-hub-signature-256", "HmacSHA256", "sha256=", true,
                    ConnectorProperties::tsanetWebhookSecret));

    private final ConnectorProperties properties;

    public SignatureVerificationFilter(ConnectorProperties properties) {
        this.properties = properties;
    }

    /** The only unauthenticated paths (exact match on the normalized path). */
    private static final List<String> OPEN = List.of("/healthz");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        String path = normalizedPath(request);
        if (path == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unroutable_path");
            return;
        }
        if (OPEN.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        Scheme scheme = match(path);
        if (scheme == null) {
            log.warn("rejecting {}: no signature scheme for path (default-deny)", path);
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated_path");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(properties.maxBodyBytes() + 1);
        if (body.length > properties.maxBodyBytes()) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "body_too_large");
            return;
        }
        String secret = scheme.secret().apply(properties);
        if (secret == null || secret.isBlank()) {
            log.warn("rejecting {}: secret for {} not configured", request.getRequestURI(), scheme.header());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "secret_not_configured");
            return;
        }
        String provided = request.getHeader(scheme.header());
        if (provided == null || !verify(scheme, secret, body, provided.trim())) {
            log.warn("rejecting {}: invalid or missing {}", request.getRequestURI(), scheme.header());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid_signature");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static Scheme match(String uri) {
        for (Scheme s : SCHEMES) {
            if (uri.startsWith(s.pathPrefix())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Context-path-stripped, semicolon-param-stripped, single-decoded, slash-collapsed
     * path; null when undecodable. Single decode matches container behavior, and
     * default-deny covers anything that still looks unusual afterwards.
     */
    private static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        uri = uri.replaceAll(";[^/]*", "");
        try {
            uri = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException undecodable) {
            return null;
        }
        return uri.replaceAll("/{2,}", "/");
    }

    private static boolean verify(Scheme scheme, String secret, byte[] body, String provided) {
        String hex = provided;
        if (!scheme.prefix().isEmpty()) {
            if (provided.regionMatches(true, 0, scheme.prefix(), 0, scheme.prefix().length())) {
                hex = provided.substring(scheme.prefix().length());
            } else if (!scheme.prefixOptional()) {
                return false;
            }
        }
        return Hmacs.constantTimeHexEquals(Hmacs.hmacHex(scheme.algorithm(), secret, body), hex);
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
