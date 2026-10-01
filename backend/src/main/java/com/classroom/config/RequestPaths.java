package com.classroom.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * R20-13: the one place that turns a request into "the path the application routes on".
 *
 * <p>Servlet filters must NOT match on {@link HttpServletRequest#getServletPath()}. That value is only the full
 * application path when the DispatcherServlet is mapped at {@code "/"}; under another servlet mapping (for example a
 * {@code /api/*} prefix mapping) it is the mapping prefix only, and under MockMvc it is empty. A filter that decides
 * "is this one of my endpoints?" from it therefore silently stops matching - a throttle written that way fails OPEN
 * with no error anywhere.
 *
 * <p>The request URI minus the context path is what the client actually asked for and is independent of how the
 * DispatcherServlet is mapped. Spring Security's firewall has already rejected encoded / ambiguous paths (";", "//",
 * "%2e", "%2f") before any of our filters run, so no extra normalisation is needed here.
 */
final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * The request path below the context path, taken from the request URI (no query string). With no context path this
     * is the URI itself; a request URI that does not start with the context path (only possible in synthetic requests)
     * is returned unchanged rather than mangled. Never {@code null}.
     */
    static String withinContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String context = request.getContextPath();
        if (context == null || context.isEmpty() || !uri.startsWith(context)) {
            return uri;
        }
        // "/app" must not be stripped from "/application/x": the context path has to end at a path-segment boundary.
        if (uri.length() > context.length() && uri.charAt(context.length()) != '/') {
            return uri;
        }
        return uri.substring(context.length());
    }
}
