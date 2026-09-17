package ch.it4user.fintube.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;

/** Owns the HTTP-only session cookie policy used by authentication controllers. */
@Service
public class SessionCookieService {
    private final boolean secure;

    public SessionCookieService(@Value("${fintube.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    public void set(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, Duration.ofDays(14)).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String token, Duration maxAge) {
        return ResponseCookie.from("FT_SESSION", token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
