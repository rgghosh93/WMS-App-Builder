package com.wms.users;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wms.common.Codes;
import com.wms.common.api.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Desktop sign-in. The session token lives in an HTTP-only cookie that page scripts can't read. */
@RestController
@RequestMapping("/api/session")
public class DesktopSessionController {

    static final String COOKIE = "wms_desktop";

    private final DesktopSessionService service;

    public DesktopSessionController(DesktopSessionService service) {
        this.service = service;
    }

    public enum State { SIGNED_IN, SIGNED_OUT, SETUP }

    /**
     * @param user      who is signed in, or null
     * @param setupHere whether the first administrator may be made from where this was asked
     */
    public record SessionView(State state, DesktopUser user, boolean setupHere) {
    }

    public record SignInRequest(
            @NotBlank(message = "enter your user ID") @Size(max = 40) String userCode,
            @NotBlank(message = "enter your password") @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "SignInRequest[userCode=" + userCode + "]"; // keeps the password out of logs
        }
    }

    public record SetupRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String userCode,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull String password) {
        @Override
        public String toString() {
            return "SetupRequest[userCode=" + userCode + "]";
        }
    }

    public record PasswordRequest(@NotNull @Size(max = 200) String currentPassword, @NotNull String newPassword) {
        @Override
        public String toString() {
            return "PasswordRequest[]";
        }
    }

    @GetMapping
    public SessionView current(@CookieValue(name = COOKIE, required = false) String token, HttpServletRequest request) {
        return service.find(token).map(u -> new SessionView(State.SIGNED_IN, u, false)).orElseGet(() ->
                service.needsSetup() ? new SessionView(State.SETUP, null, LocalRequests.isLocal(request))
                        : new SessionView(State.SIGNED_OUT, null, false));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DesktopUser> signIn(@Valid @RequestBody SignInRequest body, HttpServletRequest request) {
        return signedIn(service.signIn(body.userCode(), body.password()), request);
    }

    @PostMapping(value = "/setup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DesktopUser> setUp(@Valid @RequestBody SetupRequest body, HttpServletRequest request) {
        if (!LocalRequests.isLocal(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The first administrator can only be made on the computer the WMS runs on");
        }
        return signedIn(service.setUp(body.userCode(), body.displayName(), body.password()), request);
    }

    @PostMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DesktopUser changePassword(@CookieValue(name = COOKIE, required = false) String token,
                                      @Valid @RequestBody PasswordRequest body) {
        return service.changePassword(token, body.currentPassword(), body.newPassword());
    }

    @DeleteMapping
    public ResponseEntity<Void> signOut(@CookieValue(name = COOKIE, required = false) String token, HttpServletRequest request) {
        service.signOut(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request)).build();
    }

    private static ResponseEntity<DesktopUser> signedIn(DesktopSessionService.SignIn signIn, HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(signIn.token(), SessionStore.LENGTH, request))
                .body(signIn.user());
    }

    private static String cookie(String value, Duration maxAge, HttpServletRequest request) {
        return SessionStore.cookie(COOKIE, "/api", value, maxAge, request);
    }
}
