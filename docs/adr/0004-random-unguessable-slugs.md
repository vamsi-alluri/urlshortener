# Random unguessable Slugs

Slugs are random base62 characters at a fixed length, not a sequential counter or a destination hash. On a public service, a guessable scheme lets anyone enumerate every Short Link and Destination that strangers have created — random Slugs make enumeration impossible and need no coordination between instances. The cost is slightly longer Slugs; the length can grow later but never shrink, so the initial length is the real commitment.
