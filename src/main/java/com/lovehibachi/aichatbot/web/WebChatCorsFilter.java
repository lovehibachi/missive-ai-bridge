package com.lovehibachi.aichatbot.web;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies strict, configurable CORS to browser chat endpoints only. */
@Component
public class WebChatCorsFilter extends OncePerRequestFilter {
    private final BridgeProperties properties;
    public WebChatCorsFilter(BridgeProperties properties) { this.properties = properties; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        String allowed = properties.getWebChat().getAllowedOrigin();
        if (origin != null && origin.equals(allowed) && request.getRequestURI().startsWith("/api/chat")) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Chat-Session");
            response.setHeader("Access-Control-Max-Age", "600");
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
