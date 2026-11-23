package com.wms.users;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.api.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Device sign-in. The session token lives in an HTTP-only cookie that page scripts can't read. */
@RestController
@RequestMapping("/api/rf/session")
public class DeviceSessionController {

    static final String COOKIE = "wms_device";

    private final DeviceSessionService service;

    public DeviceSessionController(DeviceSessionService service) {
        this.service = service;
    }

    public record SignInRequest(
            @NotBlank(message = "enter your user ID") @Size(max = 40) String userCode,
            @NotBlank(message = "enter your PIN") @Size(max = 20) String pin) {
    }

    @PostMapping
    public ResponseEntity<DeviceUser> signIn(@Valid @RequestBody SignInRequest body, HttpServletRequest request) {
        DeviceSessionService.SignIn signIn = service.signIn(body.userCode(), body.pin());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(signIn.token(), SessionStore.LENGTH, request))
                .body(signIn.user());
    }

    @GetMapping
    public DeviceUser current(@CookieValue(name = COOKIE, required = false) String token) {
        return service.find(token).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue"));
    }

    @DeleteMapping
    public ResponseEntity<Void> signOut(@CookieValue(name = COOKIE, required = false) String token,
                                        HttpServletRequest request) {
        service.signOut(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request))
                .build();
    }

    private static String cookie(String value, Duration maxAge, HttpServletRequest request) {
        return SessionStore.cookie(COOKIE, "/api/rf", value, maxAge, request);
    }
}
