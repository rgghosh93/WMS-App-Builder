package com.wms.users;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wms.common.api.ApiException;

/** Which passwords may be used. Long matters more than complicated. */
final class Passwords {

    static final int MIN = 10;
    static final int MAX = 128;
    private static final int MIN_DIFFERENT = 5;
    private static final Set<String> COMMON = Set.of("password", "passw0rd", "administrator", "warehouse", "welcome", "letmein",
            "qwerty", "qwertyuiop", "1234567890", "0123456789", "changeme", "iloveyou");

    private Passwords() {
    }

    static void check(String password, String userCode, String field) {
        String problem = problemWith(password, userCode);
        if (problem != null) {
            throw ApiException.invalid("That password can't be used", Map.of(field, problem));
        }
    }

    private static String problemWith(String password, String userCode) {
        if (password == null || password.length() < MIN) {
            return "use at least " + MIN + " characters";
        }
        if (password.length() > MAX) {
            return "use at most " + MAX + " characters";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (lower.contains(userCode.toLowerCase(Locale.ROOT))) {
            return "don't use the user ID in it";
        }
        // A common word with only digits or punctuation around it is still easy to guess.
        String bare = lower.replaceAll("^[^a-z]+|[^a-z]+$", "");
        if (password.chars().distinct().count() < MIN_DIFFERENT || COMMON.contains(bare) || COMMON.contains(lower)) {
            return "too easy to guess";
        }
        return null;
    }
}
