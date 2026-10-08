package com.urlshortener.user;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the rate-limit knobs (issue #7): {@link RateLimitProperties} —
 * {@code app.rate-limit.creations-per-hour}, overridable through the
 * {@code URLSHORTENER_RATE_LIMIT_CREATIONS_PER_HOUR} env knob that {@code application.yml}
 * exposes (default 60; the same default lives in the record for contexts without the
 * yml file).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {
}
