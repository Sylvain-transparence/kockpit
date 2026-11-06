package org.kockpit.features.manifest.services.backend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Rejects (403) calls to a domain/env feature endpoint the user has no access to - filtering
 * GET /manifests only hides menus, it doesn't stop a direct call to /{domain}/{env}/audits.
 *
 * <p>Endpoints map to the manifest service types used in policy resources ("audit:*", ...).
 * Endpoints that name a service ({id}) are checked against that service; the others need access
 * to at least one service of the type. Heartbeat isn't a grantable service type: seeing the
 * manifest is enough.
 */
@Slf4j
@RequiredArgsConstructor
class ManifestAccessInterceptor implements HandlerInterceptor {

    static final Map<String, String> SERVICE_TYPE_BY_FEATURE = Map.of(
            "audits", "audit",
            "dashboard", "audit",
            "cache", "cache",
            "dyna-config", "dynaconfig",
            "feature-flipping", "featureflipping",
            "heartbeat", ""
    );

    private final ManifestAccessService manifestAccessService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String feature = featureSegment(request);
        if (vars == null || feature == null || !SERVICE_TYPE_BY_FEATURE.containsKey(feature)) {
            return true;
        }
        String domain = vars.get("domain");
        String env = vars.get("env");
        // Checked whether access control is on or not: domain/env build index names and storage
        // paths downstream ("*" would search every domain's alias, ".." leave the storage dir).
        if (!ManifestNames.isValidSegment(domain) || !ManifestNames.isValidSegment(env)) {
            log.info("Invalid domain/env rejected: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"bad_request\",\"message\":\"Invalid domain or env\"}");
            return false;
        }
        if (!manifestAccessService.isEnabled()) {
            return true;
        }
        String type = SERVICE_TYPE_BY_FEATURE.get(feature);
        String id = vars.get("id");

        if (manifestAccessService.canAccess(SecurityContextHolder.getContext().getAuthentication(),
                domain, env, type.isEmpty() ? null : type, id)) {
            return true;
        }
        log.info("Access denied to {} {} ({}/{} {} {})", request.getMethod(), request.getRequestURI(), domain, env, feature, id);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"No access to %s for %s/%s\"}"
                .formatted(feature, domain, env));
        return false;
    }

    // "/{domain}/{env}/{feature}/..." -> feature (third path segment after the context path).
    private static String featureSegment(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] segments = path.startsWith("/") ? path.substring(1).split("/") : path.split("/");
        return segments.length >= 3 ? segments[2] : null;
    }
}
