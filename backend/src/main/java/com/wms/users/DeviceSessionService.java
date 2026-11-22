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

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;

/** Signs people in and out of mobile devices. */
@Service
@Transactional
public class DeviceSessionService {

    static final Duration SESSION_LENGTH = Duration.ofHours(12);
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(5);

    private final AppUserRepository users;
    private final DeviceSessionRepository sessions;
    private final PinHasher hasher;
    private final SecureRandom random = new SecureRandom();
    /** Checked when the user ID is unknown, so a wrong ID takes as long as a wrong PIN. */
    private final String decoyHash;

    public DeviceSessionService(AppUserRepository users, DeviceSessionRepository sessions, PinHasher hasher) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.decoyHash = hasher.hash("decoy");
    }

    /** A new session: the token goes to the device and is not stored here. */
    public record SignIn(String token, DeviceUser user) {
    }

    // A wrong PIN must still be counted, so the failure doesn't roll the transaction back.
    @Transactional(noRollbackFor = ApiException.class)
    public SignIn signIn(String userCode, String pin) {
        Instant now = Instant.now();
        AppUser user = users.findByUserCode(Codes.normalize(userCode)).orElse(null);
        if (user != null && user.isLockedAt(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong PINs. Try again in a few minutes.");
        }
        boolean matches = hasher.matches(pin, user == null ? decoyHash : user.getPinHash());
        if (user == null || !matches || user.getStatus() != ActiveStatus.ACTIVE) {
            if (user != null && !matches) {
                user.recordFailure(MAX_FAILURES, now.plus(LOCKOUT));
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "User ID or PIN is not right");
        }
        user.recordSuccess();
        sessions.deleteExpired(now);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.save(new DeviceSession(user, sha256(token), now, now.plus(SESSION_LENGTH)));
        return new SignIn(token, DeviceUser.of(user));
    }

    /** The user a token belongs to, if the session is still good. */
    @Transactional(readOnly = true)
    public Optional<DeviceUser> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findByTokenHash(sha256(token))
                .filter(s -> Instant.now().isBefore(s.getExpiresAt()))
                .map(DeviceSession::getUser)
                .filter(u -> u.getStatus() == ActiveStatus.ACTIVE)
                .map(DeviceUser::of);
    }

    public void signOut(String token) {
        if (token != null && !token.isBlank()) {
            sessions.findByTokenHash(sha256(token)).ifPresent(sessions::delete);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
