package io.github.alef2509.ledger.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The stream version as a strong entity tag. */
final class ETags {

    private ETags() {
    }

    static String of(int version) {
        return "\"" + version + "\"";
    }

    static Integer parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String value = ifMatch.strip();
        if (value.length() >= 3 && value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return Integer.valueOf(value.substring(1, value.length() - 1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must be a strong entity tag such as \"3\"");
    }
}
