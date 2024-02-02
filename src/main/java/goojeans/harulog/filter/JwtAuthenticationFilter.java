package goojeans.harulog.filter;

import goojeans.harulog.domain.BusinessException;
import goojeans.harulog.domain.ResponseCode;
import goojeans.harulog.user.domain.entity.Users;
import goojeans.harulog.user.repository.UserRepository;
import goojeans.harulog.user.service.LoginService;
import goojeans.harulog.user.util.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// TODO: 리팩터링 필요 (로직 점검 및 중복 코드 제거)
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final LoginService loginService;

    private final String LOGIN_URI = "/api/login";
    private final String HOME_URI = "/api";

    @Value("${jwt.cookie.expiration}")
    private Integer COOKIE_EXPIRATION;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().equals(LOGIN_URI) || request.getRequestURI().equals(HOME_URI) || request.getRequestURI().equals("/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String accessToken = jwtTokenProvider.extractAccessToken(request).stream()
                .findAny()
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_LOGIN_REQUIRED));

        Claims claims = jwtTokenProvider.getClaim(accessToken).stream()
                .findAny()
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_UNAUTHORIZED));

        Authentication authentication = jwtTokenProvider.getAuthentication(claims);

        String refreshToken = null;

        // Access Token 기간 만료 로직
        // Refresh Token 재발급
        if (jwtTokenProvider.isExpired(accessToken)) {

            // 쿠키에서 Refresh Token 추출
            refreshToken = jwtTokenProvider.extractRefreshToken(request).stream()
                .findAny()
                .filter(jwtTokenProvider::validate)
                .filter(token ->
                        !jwtTokenProvider.isExpired(token))
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_LOGIN_REQUIRED));

            //TODO: refresh token 만료 시 유효성 검사를 위한 DB 접근 2 select
            Users sessionUser = userRepository.findUsersByNickname(claims.get("nickname").toString()).stream()
                    .findAny()
                    .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
            log.info("session user = {}", sessionUser.getUserName());

            // DB 저장된 Refresh Token 과 불일치 시
            String storedRefreshToken = sessionUser.getRefreshToken();
            if (storedRefreshToken == null){
                throw new BusinessException(ResponseCode.USER_LOGIN_REQUIRED);
            }
            if (!storedRefreshToken.equals(refreshToken)){
                throw new BusinessException(ResponseCode.USER_UNAUTHORIZED);
            }

            refreshToken = jwtTokenProvider.generateRefreshToken();
            sessionUser.updateRefreshToken(refreshToken);

            loginService.updateRefreshToken(sessionUser);

            accessToken = jwtTokenProvider.generateAccessToken(authentication);

        }

        response.addHeader("Authorization", accessToken);

        Cookie cookie = new Cookie("refreshToken", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_EXPIRATION);

        response.addCookie(cookie);

        // Access Token 기간
        SecurityContextHolder.getContext().setAuthentication(authentication);


        filterChain.doFilter(request, response);
    }
}