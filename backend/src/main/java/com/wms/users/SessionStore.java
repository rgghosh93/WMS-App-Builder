package com.wms.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;

import jakarta.servlet.http.HttpServletRequest;

/** Checks who someone is and keeps their sessions. Only hashes of session tokens are stored. */
@Component
class SessionStore {

    static final Duration LENGTH = Duration.ofHours(12);
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    private final AppUserRepository users;
    private final DeviceSessionRepository sessions;
    private final PinHasher hasher;
    private final SecureRandom random = new SecureRandom();
    /** Checked when there is nothing to check against, so that takes as long as a wrong secret. */
    private final String decoyPin;
    private final String decoyPassword;

    SessionStore(AppUserRepository users, DeviceSessionRepository sessions, PinHasher hasher) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.decoyPin = hasher.hash("decoy");
        this.decoyPassword = hasher.hashPassword("decoy");
    }

    /**
     * The user, if the secret is right. Wrong ones are counted, and too many lock the user out for a
     * while, so a caller's transaction must not roll back on ApiException.
     */
    AppUser check(String userCode, String secret, DeviceSession.Kind kind) {
        boolean desktop = kind == DeviceSession.Kind.DESKTOP;
        Instant now = Instant.now();
        AppUser user = users.findLockedByUserCode(Codes.normalize(userCode)).orElse(null);
        if (user != null && user.isLockedAt(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong " + (desktop ? "passwords" : "PINs") + ". Try again in a few minutes.");
        }
        String stored = user == null ? null : desktop ? user.getPasswordHash() : user.getPinHash();
        boolean matches = hasher.matches(secret, stored != null ? stored : desktop ? decoyPassword : decoyPin) && stored != null;
        if (!matches || user.getStatus() != ActiveStatus.ACTIVE) {
            if (user != null && !matches) {
                user.recordFailure(MAX_FAILURES, now.plus(LOCKOUT));
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "User ID or " + (desktop ? "password" : "PIN") + " is not right");
        }
        return user;
    }

    /** Opens a session; the token goes to the browser and is not stored. */
    String open(AppUser user, DeviceSession.Kind kind) {
        Instant now = Instant.now();
        user.recordSuccess(now);
        sessions.deleteExpired(now);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.save(new DeviceSession(user, kind, sha256(token), now, now.plus(LENGTH)));
        return token;
    }

    /** Whose session it is, if it is still good. */
    Optional<AppUser> find(String token, DeviceSession.Kind kind) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findByTokenHashAndKind(sha256(token), kind)
                .filter(s -> Instant.now().isBefore(s.getExpiresAt()))
                .map(DeviceSession::getUser)
                .filter(u -> u.getStatus() == ActiveStatus.ACTIVE);
    }

    void close(String token, DeviceSession.Kind kind) {
        if (token != null && !token.isBlank()) {
            sessions.findByTokenHashAndKind(sha256(token), kind).ifPresent(sessions::delete);
        }
    }

    void closeOthers(UUID userId, String token) {
        sessions.deleteOthers(userId, sha256(token));
    }

    /** Scripts can't read it, and other sites can't send it. */
    static String cookie(String name, String path, String value, Duration maxAge, HttpServletRequest request) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(request.isSecure()).sameSite("Strict").path(path)
                .maxAge(maxAge).build().toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
