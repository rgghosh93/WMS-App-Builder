package com.wms.users;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.springframework.stereotype.Component;

/** Hashes PINs and passwords with salted PBKDF2. Stored as {@code pbkdf2$iterations$salt$hash}. */
@Component
public class PinHasher {

    private static final int ITERATIONS = 210_000;
    /** Passwords protect more and are entered less often, so they take more work. */
    private static final int PASSWORD_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public String hash(String pin) {
        return hash(pin, ITERATIONS);
    }

    public String hashPassword(String password) {
        return hash(password, PASSWORD_ITERATIONS);
    }

    private String hash(String pin, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        Base64.Encoder base64 = Base64.getEncoder();
        return "pbkdf2$" + iterations + "$" + base64.encodeToString(salt) + "$"
                + base64.encodeToString(derive(pin, salt, iterations));
    }

    public boolean matches(String pin, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2")) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        return MessageDigest.isEqual(expected, derive(pin, salt, Integer.parseInt(parts[1])));
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        }
    }
}
