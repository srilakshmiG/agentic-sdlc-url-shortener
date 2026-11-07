package com.agentic.shortener;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Guards against open-redirect abuse and unsafe targets. Pure and unit tested. */
@Component
public class UrlValidator {

    static final int MAX_LENGTH = 2048;
    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** Returns the normalized URL or throws {@link InvalidUrlException}. */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidUrlException("URL is required");
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) throw new InvalidUrlException("URL is longer than " + MAX_LENGTH + " characters");
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is not well formed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) throw new InvalidUrlException("Only http and https URLs are allowed");
        if (uri.getUserInfo() != null) throw new InvalidUrlException("URLs with embedded credentials are not allowed");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new InvalidUrlException("URL must include a host");
        host = host.toLowerCase(Locale.ROOT);
        if (isPrivateHost(host)) throw new InvalidUrlException("Private, loopback and internal hosts are not allowed");
        return trimmed;
    }

    static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || (!host.contains(".") && !host.contains(":"))) return true;
        if (host.contains(":")) {
            String h = host.replace("[", "").replace("]", "");
            return h.equals("::1") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80") || h.equals("::");
        }
        var m = IPV4.matcher(host);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            return a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || a >= 224;
        }
        return false;
    }

    public static class InvalidUrlException extends RuntimeException {
        public InvalidUrlException(String message) { super(message); }
    }
}
