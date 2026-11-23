package com.wms.users;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

/** Tells requests made on this computer from those made elsewhere. */
final class LocalRequests {

    private static final Set<String> LOCAL_NAMES = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private LocalRequests() {
    }

    /** The caller, everyone it passed the request on for, and the name it was sent to. */
    static boolean isLocal(HttpServletRequest request) {
        return LOCAL_NAMES.contains(request.getServerName())
                && isLoopback(request.getRemoteAddr())
                && request.getHeader("Forwarded") == null
                && Collections.list(request.getHeaders("X-Forwarded-For")).stream()
                        .flatMap(header -> Arrays.stream(header.split(","))).allMatch(a -> isLoopback(a.trim()));
    }

    private static boolean isLoopback(String address) {
        if (!address.matches("[0-9a-fA-F.:]+")) {
            return false; // not an address; don't look it up
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
