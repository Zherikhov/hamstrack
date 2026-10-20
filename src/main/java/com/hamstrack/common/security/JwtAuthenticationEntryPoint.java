package com.hamstrack.common.security;

import com.hamstrack.common.exception.ProblemJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The 401 every unauthenticated call to {@code /api/**} receives — written here because the security
 * chain refuses before any handler exists, so no {@code @ExceptionHandler} can see it.
 *
 * <p><strong>It is a problem document like every other refusal in this product, and it was not</strong>
 * (HD-305). It answered {@code application/json} and serialised through a bare Jackson 2
 * {@code ObjectMapper} this class constructed itself, which is not the mapper MVC renders with — so
 * the most frequently parsed refusal in the product differed from its siblings in both its label and
 * its shape. Both now come from {@link ProblemJson}; the detail stays deliberately contentless,
 * because what is wrong with the credential is not a thing an unauthenticated caller is told.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemJson problemJson;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        problemJson.write(response,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }
}
