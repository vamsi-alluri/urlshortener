package com.urlshortener.user;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The rate-limit knobs (issue #7, D15): {@code app.rate-limit.creations-per-hour} — the
 * per-key allowance of Short Link creations per hour. A config value, not a policy (the
 * default is human-generous; normal use never touches it), and no lifetime quota exists
 * anywhere (Q18): the allowance refills forever.
 *
 * <p>A value below 1 fails startup with this message rather than silently blocking
 * every creation — "block all creation" is not a setting this knob offers.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
record RateLimitProperties(@DefaultValue("60") int creationsPerHour) {

    RateLimitProperties {
        if (creationsPerHour < 1) {
            throw new IllegalArgumentException(
                    "app.rate-limit.creations-per-hour must be at least 1, was " + creationsPerHour);
        }
    }
}
