package com.alfagift.finledger.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mengembalikan error keamanan dalam format RFC 7807 (problem+json).
 */
@Component
@RequiredArgsConstructor
public class SecurityProblemResponseWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "UNAUTHORIZED", "Autentikasi diperlukan: sertakan header Authorization: Bearer <token>");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                "FORBIDDEN", "Role Anda tidak memiliki izin untuk operasi ini");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String title, String errorCode, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://api.finledger.com/errors/" + errorCode.toLowerCase());
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("errorCode", errorCode);
        body.put("timestamp", LocalDateTime.now());
        body.put("errors", null);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
