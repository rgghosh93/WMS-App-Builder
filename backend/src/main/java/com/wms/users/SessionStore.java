package com.wms.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.wms.common.ActiveStatus;
import com.wms.common.Codes;
import com.wms.common.api.ApiException;
import com.wms.facility.FacilityRef;
import com.wms.facility.FacilityRepository;
import com.wms.facility.Site;

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
    private final FacilityRepository facilities;
    private final SecureRandom random = new SecureRandom();
    /** Checked when there is nothing to check against, so that takes as long as a wrong secret. */
    private final String decoyPin;
    private final String decoyPassword;

    SessionStore(AppUserRepository users, DeviceSessionRepository sessions, PinHasher hasher, FacilityRepository facilities) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.facilities = facilities;
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

    /**
     * Someone signed in, and where.
     *
     * @param facility  where they are working
     * @param facilities where else they may
     */
    record Found(AppUser user, FacilityRef facility, List<FacilityRef> facilities) {
    }

    /** Opens a session; the token goes to the browser and is not stored. */
    String open(AppUser user, DeviceSession.Kind kind) {
        Instant now = Instant.now();
        user.recordSuccess(now);
        sessions.deleteExpired(now);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.save(new DeviceSession(user, kind, sha256(token), place(user, null).facility().id(), now, now.plus(LENGTH)));
        return token;
    }

    /** Whose session it is, if it is still good. */
    Optional<Found> find(String token, DeviceSession.Kind kind) {
        return session(token, kind).map(s -> place(s.getUser(), s.getFacilityId()));
    }

    private Optional<DeviceSession> session(String token, DeviceSession.Kind kind) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findByTokenHashAndKind(sha256(token), kind)
                .filter(s -> Instant.now().isBefore(s.getExpiresAt()))
                .filter(s -> s.getUser().getStatus() == ActiveStatus.ACTIVE);
    }

    /** Goes on in another facility. */
    Found go(String token, DeviceSession.Kind kind, UUID facilityId) {
        DeviceSession session = session(token, kind).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sign in to continue"));
        Found found = place(session.getUser(), facilityId);
        if (!found.facility().id().equals(facilityId)) {
            throw ApiException.invalid("You can't work there", Map.of("facilityId", "choose one of your facilities"));
        }
        session.setFacilityId(facilityId);
        session.getUser().setLastFacilityId(facilityId);
        return found;
    }

    /** Where someone works: where they say, or worked last, if they still may, or else the first place they may. */
    Found place(AppUser user, UUID wanted) {
        List<FacilityRef> allowed = facilities.findByStatusOrderByCodeAsc(ActiveStatus.ACTIVE).stream()
                .filter(f -> user.worksIn(f.getId())).map(FacilityRef::of).toList();
        if (allowed.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't work in any active facility. Ask an administrator.");
        }
        FacilityRef here = Stream.of(wanted, user.getLastFacilityId()).filter(Objects::nonNull)
                .flatMap(id -> allowed.stream().filter(f -> f.id().equals(id))).findFirst()
                .orElseGet(() -> allowed.stream().filter(f -> f.id().equals(Site.FIRST)).findFirst().orElse(allowed.getFirst()));
        return new Found(user, here, allowed);
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
