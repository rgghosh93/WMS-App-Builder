package com.wms.common;

import java.util.Locale;

/** Helpers for business codes (IDs, identifiers) entered by users. */
public final class Codes {

    /** Letters, digits, dash, underscore and dot; blank is allowed. */
    public static final String PATTERN = "^\\s*$|^\\s*[A-Za-z0-9][A-Za-z0-9._-]*\\s*$";
    public static final String MESSAGE = "use letters, numbers, '-', '_' or '.'";

    private Codes() {
    }

    /** Trims and upper-cases a code; blank becomes null. */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
