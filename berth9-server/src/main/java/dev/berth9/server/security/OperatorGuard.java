package dev.berth9.server.security;

import dev.berth9.server.config.Berth9Properties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Console write operations (fixing records, chaos, saving mappings) need the operator token when one is
 * configured. Reads stay open so the dashboard can be shown without a login; partner pushes use HMAC instead.
 */
@Configuration
public class OperatorGuard implements WebMvcConfigurer, HandlerInterceptor {

    private final String token;

    public OperatorGuard(Berth9Properties props) {
        this.token = props.security().operatorToken();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/inbound/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if (token == null || token.isBlank() || "GET".equals(method) || "OPTIONS".equals(method) || "HEAD".equals(method)) {
            return true;
        }
        String given = request.getHeader("X-Operator-Token");
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"operator token required (X-Operator-Token)\"}");
        return false;
    }
}
