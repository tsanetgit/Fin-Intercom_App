package org.tsanet.connector.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The replay guard's three properties: once, bounded-fail-closed, and window-scoped. */
class NonceRegistryTest {

    @Test
    void aNonceIsAcceptedExactlyOnce() {
        NonceRegistry registry = new NonceRegistry();
        assertNull(registry.accept("n1", 1000));
        assertEquals("replayed_nonce", registry.accept("n1", 1010));
        assertNull(registry.accept("n2", 1010), "a different nonce is unaffected");
    }

    @Test
    void blankNoncesAreInvalidNotReplayed() {
        NonceRegistry registry = new NonceRegistry();
        assertEquals("invalid_nonce", registry.accept(null, 1000));
        assertEquals("invalid_nonce", registry.accept("  ", 1000));
    }

    @Test
    void theWindowForgetsButOnlyAfterTheFreshnessTtl() {
        NonceRegistry registry = new NonceRegistry();
        assertNull(registry.accept("n1", 1000));
        assertEquals("replayed_nonce", registry.accept("n1", 1000 + AdminSignatures.FRESHNESS_SECONDS),
                "inside the window it is still a replay");
        assertNull(registry.accept("n1", 1000 + AdminSignatures.FRESHNESS_SECONDS + 1),
                "past the window the nonce is forgotten - freshness has expired the signature anyway");
    }

    @Test
    void aFullRegistryRejectsRatherThanEvicting() {
        NonceRegistry registry = new NonceRegistry();
        registry.fillForTest(NonceRegistry.CAPACITY, 2000);
        assertEquals("invalid_nonce", registry.accept("fresh", 2000),
                "reject-on-full: evicting oldest would let a flood age out the nonce an attacker replays");
        assertNull(registry.accept("fresh", 2000 + AdminSignatures.FRESHNESS_SECONDS + 1),
                "the window draining restores service");
    }
}
