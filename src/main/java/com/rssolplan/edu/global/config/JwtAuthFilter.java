package com.rssolplan.edu.global.config;

import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String REDIS_ROLE_KEY_PREFIX = "auth:role:";
    private static final Duration ROLE_CACHE_TTL = Duration.ofMinutes(30);

    private final JwtTokenProvider jwt;
    private final SchoolUserRepository schoolUserRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    public JwtAuthFilter(JwtTokenProvider jwt,
                         SchoolUserRepository schoolUserRepository,
                         UserRepository userRepository,
                         StringRedisTemplate redisTemplate) {
        this.jwt = jwt;
        this.schoolUserRepository = schoolUserRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        log.debug("Incoming request: {}", uri);

        String header = request.getHeader("Authorization");

        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            if (jwt.validate(token)) {
                Long userId = jwt.getUserId(token);
                String role = resolveRole(userId);

                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + role));

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("JWT valid, userId={}, role={}", userId, role);

            } else {
                log.warn("Invalid JWT token for uri={}", uri);
            }
        } else {
            log.trace("No Authorization header for {}", uri);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveRole(Long userId) {
        String cacheKey = REDIS_ROLE_KEY_PREFIX + userId;

        // 1. Redis cache hit
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. Cache miss: resolve via user's activeSchoolId
        String role = "GUEST";
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.getActiveSchoolId() != null) {
            role = schoolUserRepository
                    .findByUser_IdAndSchool_Id(userId, user.getActiveSchoolId())
                    .map(su -> su.getPosition().name())  // ADMIN / TEACHER
                    .orElse("GUEST");
        }

        // 3. Populate cache
        redisTemplate.opsForValue().set(cacheKey, role, ROLE_CACHE_TTL);
        return role;
    }
}
