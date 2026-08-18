package dev.onistone.onilink.geyser.forwarding;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Matches only numeric IPv4/IPv6 literals against an explicit CIDR allowlist. */
public final class TrustedProxyMatcher {
    private record Network(byte[] bytes, int prefix) {
    }

    private final List<Network> networks;

    public TrustedProxyMatcher(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            throw new IllegalArgumentException("at least one trusted proxy CIDR is required");
        }
        List<Network> parsed = new ArrayList<>();
        for (String cidr : cidrs) {
            parsed.add(parseCidr(cidr));
        }
        networks = List.copyOf(parsed);
    }

    public boolean matches(InetAddress address) {
        byte[] candidate = address.getAddress();
        for (Network network : networks) {
            if (candidate.length != network.bytes().length) {
                continue;
            }
            int wholeBytes = network.prefix() / 8;
            int remainingBits = network.prefix() % 8;
            boolean equal = true;
            for (int index = 0; index < wholeBytes; index++) {
                equal &= candidate[index] == network.bytes()[index];
            }
            if (remainingBits != 0) {
                int mask = 0xff << (8 - remainingBits);
                equal &= (candidate[wholeBytes] & mask) == (network.bytes()[wholeBytes] & mask);
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }

    public static InetAddress parseLiteral(String value) {
        if (value == null || value.isBlank() || value.indexOf('%') >= 0) {
            return null;
        }
        if (value.indexOf(':') < 0) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] address = new byte[4];
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)
                        || part.length() > 1 && part.charAt(0) == '0') {
                    return null;
                }
                int number = Integer.parseInt(part);
                if (number > 255) {
                    return null;
                }
                address[index] = (byte) number;
            }
            try {
                return InetAddress.getByAddress(address);
            } catch (UnknownHostException impossible) {
                return null;
            }
        }
        if (!value.chars().allMatch(character -> character == ':' || character == '.' || Character.digit(character, 16) >= 0)) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet6Address || address instanceof Inet4Address ? address : null;
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static Network parseCidr(String value) {
        String[] parts = value.trim().split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("trusted proxy entry must be CIDR: " + value);
        }
        InetAddress address = parseLiteral(parts[0]);
        if (address == null) {
            throw new IllegalArgumentException("trusted proxy address is not a literal: " + value);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("trusted proxy prefix is invalid: " + value, exception);
        }
        int maximum = address.getAddress().length * 8;
        if (prefix < 0 || prefix > maximum) {
            throw new IllegalArgumentException("trusted proxy prefix is out of range: " + value);
        }
        byte[] bytes = address.getAddress().clone();
        int wholeBytes = prefix / 8;
        int remainingBits = prefix % 8;
        if (remainingBits != 0) {
            bytes[wholeBytes] &= (byte) (0xff << (8 - remainingBits));
            wholeBytes++;
        }
        java.util.Arrays.fill(bytes, wholeBytes, bytes.length, (byte) 0);
        return new Network(bytes, prefix);
    }
}
