package com.ironside.challengesphere.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final AuthTokenStore authTokenStore;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   CustomUserDetailsService userDetailsService,
                                   AuthTokenStore authTokenStore) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.authTokenStore = authTokenStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);

        try {
            if (StringUtils.hasText(token) && jwtService.isValidAccessToken(token)) {
                Long userId = jwtService.extractUserId(token);
                if (userId != null
                        && authTokenStore.exists(token, "access", userId)
                        && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = userDetailsService.loadUserById(userId);
                    if (!userDetails.isEnabled()
                            || !userDetails.isAccountNonLocked()
                            || !userDetails.isAccountNonExpired()
                            || !userDetails.isCredentialsNonExpired()) {
                        authTokenStore.revokeAllForUser(userId);
                    } else {
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            }
        } catch (UsernameNotFoundException ignored) {
            Long userId = safeExtractUserId(token);
            authTokenStore.revokeAllForUser(userId);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    private Long safeExtractUserId(String token) {
        try {
            return jwtService.extractUserId(token);
        } catch (Exception ignored) {
            return null;
        }
    }
}
