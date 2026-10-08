package com.urlshortener.link;

/**
 * The Destination rules of issue #4 (D10/D11), each carrying the type code that
 * the {@code 400} problem+json answer reports for it. One Destination can break
 * several rules at once — a {@code ftp://} URL on a private host, over the
 * length limit — and every broken rule is reported, together, in one response.
 * The browser form of #19 renders the same broken rules, with the same type
 * codes and details, as its form's error list.
 */
public enum DestinationRule {

    /**
     * Not a parsable, absolute http or https URL with a host: the value is not a
     * web URL at all — {@code javascript:}, {@code data:}, {@code ftp:},
     * {@code mailto:}, relative URLs, and hostless URLs.
     */
    INVALID_SCHEME("invalid_scheme",
            "The Destination must be an absolute http or https URL with a host."),

    /**
     * The host is {@code localhost} or a {@code *.localhost} subdomain, or a
     * private, loopback, or link-local IP literal (127/8, 10/8, 172.16/12,
     * 192.168/16, 169.254/16, {@code ::1}, {@code fc00::/7}, {@code fe80::/10}).
     */
    PRIVATE_DESTINATION("private_destination",
            "The Destination's host must be public: localhost, *.localhost, and private, "
                    + "loopback, and link-local IP addresses are not allowed."),

    /** More than {@value DestinationValidator#MAX_LENGTH} characters long. */
    DESTINATION_TOO_LONG("destination_too_long",
            "The Destination must be at most " + DestinationValidator.MAX_LENGTH + " characters.");

    private final String typeCode;
    private final String detail;

    DestinationRule(String typeCode, String detail) {
        this.typeCode = typeCode;
        this.detail = detail;
    }

    /** The machine-readable code, reported in the problem+json {@code violations} list (D10). */
    public String typeCode() {
        return typeCode;
    }

    /** The human-readable explanation of what to fix. */
    public String detail() {
        return detail;
    }

    /** The rule a constraint violation's message template names, or null when it names none. */
    static DestinationRule byTypeCode(String typeCode) {
        for (DestinationRule rule : values()) {
            if (rule.typeCode.equals(typeCode)) {
                return rule;
            }
        }
        return null;
    }
}
