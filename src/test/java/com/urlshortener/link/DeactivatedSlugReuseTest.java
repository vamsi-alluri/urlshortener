package com.urlshortener.link;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The never-reissued guarantee drawn through the generator/collision check
 * (issue #6, plan item 5 — the one permitted non-HTTP assertion): after a
 * Short Link is Deactivated, a large batch drawn through the creation loop
 * never yields the Deactivated Slug. The row stays with {@code deactivated_at}
 * set (issue #6's soft state), so the PRIMARY KEY and the insert-reject retry
 * keep the Slug out of circulation forever (ADR-0004) — the collision retry
 * rejects any existing row, Deactivated ones included, pinned since #1.
 */
class DeactivatedSlugReuseTest extends LinkApiTestBase {

    /** Large enough to put a reissued Slug in the batch's way if one could exist. */
    private static final int BATCH = 500;

    /** The User every Short Link here is created and deactivated as (#4's attribution). */
    private static final long OWNER = 990_001L;

    @Autowired
    private ShortLinkService service;

    @Test
    void aDeactivatedSlugIsNeverReissued() {
        String deactivatedSlug = service.create("https://example.com/retired", OWNER).slug();
        service.deactivate(deactivatedSlug, OWNER);

        Set<String> slugs = new HashSet<>();
        for (int creation = 0; creation < BATCH; creation++) {
            slugs.add(service.create("https://example.com/batch/" + creation, OWNER).slug());
        }

        assertThat(slugs).as("every batch creation draws a fresh Slug").hasSize(BATCH);
        assertThat(slugs)
                .as("the Deactivated Slug is never reissued (ADR-0004)")
                .doesNotContain(deactivatedSlug);
    }
}
