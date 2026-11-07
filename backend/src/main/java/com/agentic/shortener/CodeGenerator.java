package com.agentic.shortener;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    static final int LENGTH = 7;
    static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    static final Set<String> RESERVED = Set.of("api", "actuator", "admin", "health", "static", "assets", "login", "favicon");

    private final SecureRandom random = new SecureRandom();

    public String next() {
        var sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public boolean isValidAlias(String alias) {
        return alias != null && ALIAS.matcher(alias).matches() && !RESERVED.contains(alias.toLowerCase(Locale.ROOT));
    }
}
