package com.potner.security;

import com.potner.auth.jwt.JwtPayload;
import com.potner.auth.jwt.JwtTokenProvider;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final AppUserRepository appUserRepository;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, AppUserRepository appUserRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.appUserRepository = appUserRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String accessToken = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            JwtPayload payload = jwtTokenProvider.parseAccessToken(accessToken);
            AppUser user = appUserRepository.findById(payload.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN));
            if (!user.isActive()) {
                throw new BusinessException(ErrorCode.ACCOUNT_INACTIVE);
            }

            AuthenticatedUser principal = new AuthenticatedUser(user.getId());
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal,
                    accessToken,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (BusinessException exception) {
            SecurityContextHolder.clearContext();
            request.setAttribute(RestAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE, exception.errorCode());
        }

        filterChain.doFilter(request, response);
    }
}
