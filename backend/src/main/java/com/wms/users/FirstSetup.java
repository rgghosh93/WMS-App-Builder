package com.wms.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who may make the first administrator, while there is none: someone on the computer the WMS runs on, or, where it runs
 * in the cloud and nobody is, anyone with the setup key whoever runs it was given ({@code wms.setup.key}).
 */
@Component
public class FirstSetup {

    private static final Logger log = LoggerFactory.getLogger(FirstSetup.class);
    /** A key short enough to guess opens nothing. */
    static final int MIN_KEY = 20;

    private final byte[] key;
    private final boolean onThisComputer;

    FirstSetup(@Value("${wms.setup.key:}") String key, @Value("${wms.setup.on-this-computer:true}") boolean onThisComputer) {
        String trimmed = key == null ? "" : key.trim();
        if (!trimmed.isEmpty() && trimmed.length() < MIN_KEY) {
            log.warn("wms.setup.key is shorter than {} characters, so it is not used", MIN_KEY);
            trimmed = "";
        }
        this.key = trimmed.getBytes(StandardCharsets.UTF_8);
        this.onThisComputer = onThisComputer;
    }

    /** Whether it may be made from where this request came, without a key. */
    boolean here(HttpServletRequest request) {
        return onThisComputer && LocalRequests.isLocal(request);
    }

    boolean withKey() {
        return key.length > 0;
    }

    /** Whether this is the key; compared in the same time whatever it is, so it can't be found a character at a time. */
    boolean opens(String given) {
        return withKey() && given != null && MessageDigest.isEqual(key, given.trim().getBytes(StandardCharsets.UTF_8));
    }
}
