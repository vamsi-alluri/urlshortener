package com.urlshortener.link;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Validates that a value is a Destination (GLOSSARY): a public web URL — a
 * parsable, absolute http or https URL with a host, whose host is neither
 * {@code localhost} nor a private, loopback, or link-local IP literal, at most
 * {@value #MAX_LENGTH} characters in all (issue #4, D11).
 *
 * <p>Every broken rule is reported as its own constraint violation whose message
 * template is the rule's type code ({@link DestinationRule#typeCode()}), so
 * {@code ShortLinkController} can answer one {@code 400} problem+json carrying
 * all violations together (D10).
 *
 * <p>Checks are syntactic (ADR-0006): hosts are inspected as written, never
 * DNS-resolved at creation. IPv4 and IPv6 literals are parsed here rather than
 * through {@code InetAddress}, which would either resolve names (a network call
 * with latency and caching problems of its own) or reinterpret historical
 * notations (octal IPv4) that these rules do not mean.
 */
public final class DestinationValidator implements ConstraintValidator<Destination, String> {

    /** D11: a Destination is at most 2048 characters, boundary included. */
    static final int MAX_LENGTH = 2048;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        List<DestinationRule> violations = violationsOf(value);
        if (violations.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        for (DestinationRule violation : violations) {
            context.buildConstraintViolationWithTemplate(violation.typeCode())
                    .addConstraintViolation();
        }
        return false;
    }

    /**
     * Every rule the candidate Destination breaks (D10: all of them, together) — the one
     * analysis both surfaces share: Jakarta validation renders it as the {@code 400}
     * problem+json violations list, and the browser form (#19) as its error list.
     */
    public static List<DestinationRule> violationsOf(String destination) {
        List<DestinationRule> violations = new ArrayList<>(3);
        if (destination == null || destination.isBlank()) {
            // no scheme at all: not a web URL — the only rule a blank value can break
            violations.add(DestinationRule.INVALID_SCHEME);
            return violations;
        }
        if (destination.length() > MAX_LENGTH) {
            violations.add(DestinationRule.DESTINATION_TOO_LONG);
        }
        URI uri = parseOrNull(destination);
        String scheme = uri == null || uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri == null ? null : uri.getHost();
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || host == null) {
            violations.add(DestinationRule.INVALID_SCHEME);
        }
        if (host != null && isPrivateHost(host)) {
            violations.add(DestinationRule.PRIVATE_DESTINATION);
        }
        return violations;
    }

    private static URI parseOrNull(String destination) {
        try {
            return URI.create(destination);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * D11: {@code localhost} and its subdomains, and private, loopback, or
     * link-local IP literals. Any other host is taken as written — a syntactic
     * check never resolves it (ADR-0006).
     */
    private static boolean isPrivateHost(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        // DNS names may carry trailing dots ("localhost."): browsers resolve them to
        // the same hosts, so the dots are folded away before the name is inspected.
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.equals("localhost") || name.endsWith(".localhost")) {
            return true;
        }
        byte[] ipv4 = parseIpv4Literal(name);
        if (ipv4 != null) {
            return isPrivateIpv4(ipv4[0] & 0xFF, ipv4[1] & 0xFF);
        }
        byte[] ipv6 = parseIpv6Literal(name);
        if (ipv6 != null) {
            return isPrivateIpv6(ipv6);
        }
        return false;
    }

    /**
     * A dotted-decimal IPv4 literal — four octets, 0–255 — or null when the name
     * is not one. Leading zeros read as decimal ("010.0.0.1" is 10.0.0.1): the
     * value is treated as the IPv4 address it resembles, never as a DNS name to
     * resolve.
     */
    private static byte[] parseIpv4Literal(String name) {
        String[] octets = name.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3
                    || !octet.chars().allMatch(DestinationValidator::isDigit)) {
                return null;
            }
            int value = Integer.parseInt(octet);
            if (value > 255) {
                return null;
            }
            address[i] = (byte) value;
        }
        return address;
    }

    /** D11's IPv4 ranges, plus 0/8: "this network" resolves to the local host in practice. */
    private static boolean isPrivateIpv4(int first, int second) {
        return first == 127                                    // loopback 127/8
                || first == 10                                 // private 10/8
                || (first == 172 && (second & 0xF0) == 0x10)   // private 172.16/12
                || (first == 192 && second == 168)             // private 192.168/16
                || (first == 169 && second == 254)             // link-local 169.254/16
                || first == 0;                                 // "this network" 0/8
    }

    /**
     * An IPv6 literal as {@code URI.getHost()} yields it — brackets included,
     * at most one {@code ::}, an optional dotted-quad tail, an optional
     * {@code %25zone} — or null when the text is not an IPv6 literal.
     */
    private static byte[] parseIpv6Literal(String name) {
        if (name.length() < 2 || name.charAt(0) != '[' || name.charAt(name.length() - 1) != ']') {
            return null; // a hostname: never an IPv6 literal
        }
        String text = name.substring(1, name.length() - 1);
        if (text.indexOf(':') < 0) {
            return null; // brackets around something that is not an IPv6 literal
        }
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (!text.chars().allMatch(c -> c == ':' || c == '.' || isHex(c))) {
            return null;
        }
        int compression = text.indexOf("::");
        if (compression >= 0 && compression != text.lastIndexOf("::")) {
            return null; // at most one "::"
        }
        String head = compression >= 0 ? text.substring(0, compression) : text;
        String tail = compression >= 0 ? text.substring(compression + 2) : null;
        int[] headWords = parseIpv6Words(head, compression >= 0);
        int[] tailWords = tail == null ? new int[0] : parseIpv6Words(tail, compression >= 0);
        if (headWords == null || tailWords == null) {
            return null;
        }
        int wordCount = headWords.length + tailWords.length;
        if (compression >= 0 ? wordCount > 7 : wordCount != 8) {
            // uncompressed needs all eight words; "::" stands in for at least one
            return null;
        }
        byte[] address = new byte[16];
        fillWords(address, 0, headWords);
        fillWords(address, 16 - 2 * tailWords.length, tailWords);
        return address;
    }

    /** The 16-bit words of one half of a (possibly "::"-compressed) IPv6 literal. */
    private static int[] parseIpv6Words(String part, boolean mayBeEmpty) {
        if (part.isEmpty()) {
            return mayBeEmpty ? new int[0] : null;
        }
        String[] segments = part.split(":", -1);
        // a lone leading or trailing ':' (":1", "1:") is malformed outside "::"
        if (segments[0].isEmpty() || segments[segments.length - 1].isEmpty()) {
            return null;
        }
        int[] words = new int[2 * segments.length]; // the dotted-quad tail expands to two words
        int count = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.contains(".")) {
                // a dotted quad only ever rides at the end ("::ffff:10.0.0.1")
                if (i != segments.length - 1) {
                    return null;
                }
                byte[] ipv4 = parseIpv4Literal(segment);
                if (ipv4 == null) {
                    return null;
                }
                words[count++] = ((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF);
                words[count++] = ((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF);
            } else {
                if (segment.length() > 4) {
                    return null;
                }
                words[count++] = Integer.parseInt(segment, 16); // hex-checked above
            }
        }
        return Arrays.copyOf(words, count);
    }

    private static void fillWords(byte[] address, int offset, int[] words) {
        for (int i = 0; i < words.length; i++) {
            address[offset + 2 * i] = (byte) (words[i] >>> 8);
            address[offset + 2 * i + 1] = (byte) words[i];
        }
    }

    /** D11's IPv6 ranges; an embedded IPv4 address (mapped or compatible) follows the IPv4 rules. */
    private static boolean isPrivateIpv6(byte[] address) {
        if (isZeroRange(address, 0, 15) && (address[15] & 0xFF) == 1) {
            return true; // loopback ::1
        }
        if (((address[0] & 0xFF) & 0xFE) == 0xFC) {
            return true; // unique local fc00::/7
        }
        if ((address[0] & 0xFF) == 0xFE && (address[1] & 0xC0) == 0x80) {
            return true; // link-local fe80::/10
        }
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) forms carry an
        // IPv4 address in their last four bytes
        if (isZeroRange(address, 0, 12)
                || (isZeroRange(address, 0, 10) && (address[10] & 0xFF) == 0xFF && (address[11] & 0xFF) == 0xFF)) {
            return isPrivateIpv4(address[12] & 0xFF, address[13] & 0xFF);
        }
        return false;
    }

    private static boolean isZeroRange(byte[] address, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(int c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
