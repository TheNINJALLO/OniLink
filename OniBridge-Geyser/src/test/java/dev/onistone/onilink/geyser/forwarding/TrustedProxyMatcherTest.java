package dev.onistone.onilink.geyser.forwarding;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProxyMatcherTest {
    @Test
    void matchesIpv4AndIpv6Boundaries() throws Exception {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of("10.20.0.0/16", "2001:db8::/48"));
        assertTrue(matcher.matches(InetAddress.getByName("10.20.255.255")));
        assertFalse(matcher.matches(InetAddress.getByName("10.21.0.1")));
        assertTrue(matcher.matches(InetAddress.getByName("2001:db8:0:ffff::1")));
        assertFalse(matcher.matches(InetAddress.getByName("2001:db9::1")));
    }

    @Test
    void literalParserNeverAcceptsHostnamesOrAmbiguousIpv4() {
        assertNotNull(TrustedProxyMatcher.parseLiteral("127.0.0.1"));
        assertNotNull(TrustedProxyMatcher.parseLiteral("::1"));
        assertNull(TrustedProxyMatcher.parseLiteral("localhost"));
        assertNull(TrustedProxyMatcher.parseLiteral("127.000.0.1"));
        assertNull(TrustedProxyMatcher.parseLiteral("fe80::1%3"));
    }

    @Test
    void cidrIsMandatoryAndValidated() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyMatcher(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyMatcher(List.of("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyMatcher(List.of("127.0.0.1/33")));
    }
}
