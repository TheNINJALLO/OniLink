#include <onibridge/forwarding.hpp>

#include "crypto.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <charconv>
#include <limits>
#include <span>
#include <stdexcept>
#include <unordered_map>

namespace onistone::onibridge {
namespace {

constexpr std::array<std::byte, 4> magic{
    std::byte{'O'}, std::byte{'N'}, std::byte{'I'}, std::byte{'F'}};
constexpr std::size_t v2_field_count = 14;
constexpr std::size_t v3_field_count = 16;

std::string decimal(auto value) {
    return std::to_string(value);
}

std::vector<std::string> fields(const ForwardingClaims& c) {
    std::vector<std::string> result{
        decimal(c.protocol_version),
        c.key_id,
        c.proxy_id,
        c.bridge_id,
        c.backend_name,
        c.session_id,
        c.nonce,
        c.player_name,
        c.xuid,
        c.proxy_uuid,
        c.real_ip,
        decimal(c.real_port),
        decimal(c.issued_at_ms),
        decimal(c.expires_at_ms),
    };
    if (c.protocol_version == kOniForwardProtocolVersion) {
        result.push_back(c.proxy_boot_id);
        result.push_back(decimal(c.sequence));
    } else if (c.protocol_version != kOniForwardLegacyProtocolVersion) {
        throw std::invalid_argument("unsupported OniForward protocol version");
    }
    return result;
}

std::vector<std::byte> encode(const ForwardingClaims& claims) {
    std::vector<std::byte> result(magic.begin(), magic.end());
    result.push_back(static_cast<std::byte>(kOniForwardEncodingVersion));
    const auto values = fields(claims);
    result.push_back(static_cast<std::byte>(values.size()));
    for (std::size_t index = 0; index < values.size(); ++index) {
        const auto& value = values[index];
        if (value.empty() || value.size() > std::numeric_limits<std::uint16_t>::max()) {
            throw std::invalid_argument("invalid OniForward field length");
        }
        result.push_back(static_cast<std::byte>(index + 1));
        result.push_back(static_cast<std::byte>((value.size() >> 8) & 0xff));
        result.push_back(static_cast<std::byte>(value.size() & 0xff));
        for (char ch : value) {
            result.push_back(static_cast<std::byte>(static_cast<unsigned char>(ch)));
        }
    }
    return result;
}

template <typename T> bool parse_integer(std::string_view value, T& output) {
    const auto [end, error] = std::from_chars(value.data(), value.data() + value.size(), output);
    return error == std::errc{} && end == value.data() + value.size();
}

bool ascii_case_equal(std::string_view left, std::string_view right) {
    return left.size() == right.size() &&
           std::equal(left.begin(), left.end(), right.begin(), [](char a, char b) {
               return std::tolower(static_cast<unsigned char>(a)) ==
                      std::tolower(static_cast<unsigned char>(b));
           });
}

bool valid_uuid(std::string_view value) {
    if (value.size() != 36) {
        return false;
    }
    for (std::size_t i = 0; i < value.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (value[i] != '-') {
                return false;
            }
        } else if (!((value[i] >= '0' && value[i] <= '9') ||
                     (value[i] >= 'a' && value[i] <= 'f'))) {
            return false;
        }
    }
    return true;
}

bool valid_identifier(std::string_view value) {
    return !value.empty() && value.size() <= 64 &&
           std::all_of(value.begin(), value.end(), [](unsigned char ch) {
               return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                      (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
           });
}

bool valid_utf8(std::string_view value) {
    std::size_t i = 0;
    while (i < value.size()) {
        const auto first = static_cast<unsigned char>(value[i++]);
        if (first <= 0x7f) {
            continue;
        }
        std::size_t continuation = 0;
        std::uint32_t codepoint = 0;
        if ((first & 0xe0) == 0xc0) {
            continuation = 1;
            codepoint = first & 0x1f;
            if (codepoint < 2) {
                return false;
            }
        } else if ((first & 0xf0) == 0xe0) {
            continuation = 2;
            codepoint = first & 0x0f;
        } else if ((first & 0xf8) == 0xf0) {
            continuation = 3;
            codepoint = first & 0x07;
        } else {
            return false;
        }
        if (i + continuation > value.size()) {
            return false;
        }
        for (std::size_t j = 0; j < continuation; ++j) {
            const auto next = static_cast<unsigned char>(value[i++]);
            if ((next & 0xc0) != 0x80) {
                return false;
            }
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if ((continuation == 2 && codepoint < 0x800) ||
            (continuation == 3 && codepoint < 0x10000) || codepoint > 0x10ffff ||
            (codepoint >= 0xd800 && codepoint <= 0xdfff)) {
            return false;
        }
    }
    return true;
}

ForwardingResult fail(std::string value) {
    return {std::nullopt, std::move(value)};
}

std::int64_t saturating_add(std::int64_t value, std::int64_t adjustment) {
    if (adjustment > 0 && value > std::numeric_limits<std::int64_t>::max() - adjustment)
        return std::numeric_limits<std::int64_t>::max();
    if (adjustment < 0 && value < std::numeric_limits<std::int64_t>::min() - adjustment)
        return std::numeric_limits<std::int64_t>::min();
    return value + adjustment;
}

std::string signed_difference(std::int64_t left, std::int64_t right) {
    if (left >= right) {
        const auto distance = static_cast<std::uint64_t>(left) - static_cast<std::uint64_t>(right);
        return std::to_string(distance);
    }
    const auto distance = static_cast<std::uint64_t>(right) - static_cast<std::uint64_t>(left);
    return "-" + std::to_string(distance);
}

std::string clock_error(std::string_view reason,
                        const ForwardingClaims& claims,
                        const ForwardingValidation& validation) {
    return std::string(reason) + " (observed proxy-minus-backend clock offset " +
           signed_difference(claims.issued_at_ms, validation.now_ms) +
           " ms; configured proxy_clock_offset_ms=" +
           std::to_string(validation.proxy_clock_offset_ms) +
           ", allowed_clock_skew_ms=" + std::to_string(validation.allowed_clock_skew_ms) + ")";
}

ForwardingResult decode_payload(std::span<const std::byte> payload) {
    if (payload.size() < 6 || !std::equal(magic.begin(), magic.end(), payload.begin())) {
        return fail("invalid payload magic");
    }
    if (std::to_integer<unsigned>(payload[4]) != kOniForwardEncodingVersion) {
        return fail("unsupported encoding version");
    }
    const auto field_count = std::to_integer<unsigned>(payload[5]);
    if (field_count != v2_field_count && field_count != v3_field_count) {
        return fail("missing or extra fields");
    }
    std::vector<std::string> values(field_count);
    std::size_t offset = 6;
    for (std::size_t expected = 1; expected <= field_count; ++expected) {
        if (offset + 3 > payload.size()) {
            return fail("truncated field header");
        }
        const auto id = std::to_integer<unsigned>(payload[offset++]);
        if (id != expected) {
            return fail(id < expected ? "duplicate or unordered field"
                                      : "missing or unordered field");
        }
        const auto length = (std::to_integer<unsigned>(payload[offset]) << 8) |
                            std::to_integer<unsigned>(payload[offset + 1]);
        offset += 2;
        if (length == 0 || offset + length > payload.size()) {
            return fail("empty or truncated field");
        }
        values[expected - 1].assign(reinterpret_cast<const char*>(payload.data() + offset), length);
        if (!valid_utf8(values[expected - 1])) {
            return fail("field is not canonical UTF-8");
        }
        offset += length;
    }
    if (offset != payload.size()) {
        return fail("trailing payload bytes");
    }
    ForwardingClaims claims;
    unsigned protocol = 0;
    unsigned port = 0;
    if (!parse_integer(values[0], protocol) ||
        (protocol != kOniForwardLegacyProtocolVersion && protocol != kOniForwardProtocolVersion)) {
        return fail("unsupported protocol version");
    }
    if ((protocol == kOniForwardLegacyProtocolVersion) != (field_count == v2_field_count))
        return fail("protocol field set is invalid");
    if (!parse_integer(values[11], port) || port > 65535) {
        return fail("invalid real port");
    }
    if (!parse_integer(values[12], claims.issued_at_ms) ||
        !parse_integer(values[13], claims.expires_at_ms)) {
        return fail("invalid timestamp");
    }
    claims.protocol_version = protocol;
    claims.key_id = values[1];
    claims.proxy_id = values[2];
    claims.bridge_id = values[3];
    claims.backend_name = values[4];
    claims.session_id = values[5];
    claims.nonce = values[6];
    claims.player_name = values[7];
    claims.xuid = values[8];
    claims.proxy_uuid = values[9];
    claims.real_ip = values[10];
    claims.real_port = static_cast<std::uint16_t>(port);
    if (protocol == kOniForwardProtocolVersion) {
        if (!valid_uuid(values[14]) || !parse_integer(values[15], claims.sequence) ||
            claims.sequence == 0) {
            return fail("invalid OniForward v3 freshness claims");
        }
        claims.proxy_boot_id = values[14];
    }
    return {std::move(claims), {}};
}

const ForwardingKey* find_key(const ForwardingKeyRing& keys, std::string_view id) {
    if (keys.active.id == id) {
        return &keys.active;
    }
    if (keys.previous && keys.previous->id == id) {
        return &*keys.previous;
    }
    return nullptr;
}

} // namespace

std::string sign_forwarding_token(const ForwardingClaims& claims, const ForwardingKey& key) {
    if (claims.key_id != key.id || key.secret.empty()) {
        throw std::invalid_argument("claims key ID or signing key is invalid");
    }
    const auto payload = encode(claims);
    const auto signature = crypto::hmac_sha256(key.secret, payload);
    return crypto::base64url_encode(payload) + "." + crypto::base64url_encode(signature);
}

ForwardingResult verify_forwarding_token(std::string_view token,
                                         const ForwardingKeyRing& keys,
                                         const ForwardingValidation& validation) {
    if (token.empty() || token.size() > validation.maximum_token_size) {
        return fail("token size is invalid");
    }
    const auto separator = token.find('.');
    if (separator == std::string_view::npos ||
        token.find('.', separator + 1) != std::string_view::npos) {
        return fail("token framing is invalid");
    }
    const auto payload = crypto::base64url_decode(token.substr(0, separator));
    const auto supplied_signature = crypto::base64url_decode(token.substr(separator + 1));
    if (!payload || !supplied_signature || supplied_signature->size() != 32) {
        return fail("token base64 or signature is invalid");
    }
    auto decoded = decode_payload(*payload);
    if (!decoded) {
        return decoded;
    }
    const auto* key = find_key(keys, decoded.claims->key_id);
    if (!key || key->secret.empty()) {
        return fail("unknown signing key");
    }
    const auto expected = crypto::hmac_sha256(key->secret, *payload);
    if (!crypto::constant_time_equal(expected, *supplied_signature)) {
        return fail("signature mismatch");
    }
    auto& claims = *decoded.claims;
    if (claims.protocol_version < validation.minimum_protocol_version) {
        return fail("OniForward protocol downgrade is disabled by backend policy");
    }
    if (!valid_identifier(claims.proxy_id)) {
        return fail("proxy ID is invalid");
    }
    if (claims.xuid.empty() || !std::all_of(claims.xuid.begin(), claims.xuid.end(), [](char ch) {
            return ch >= '0' && ch <= '9';
        })) {
        return fail("XUID is not ASCII digits");
    }
    if (!valid_uuid(claims.proxy_uuid)) {
        return fail("proxy UUID is invalid");
    }
    if (!ascii_case_equal(claims.player_name, validation.expected_player_name)) {
        return fail("player name mismatch");
    }
    if (claims.bridge_id != validation.expected_bridge_id ||
        claims.backend_name != validation.expected_backend_name) {
        return fail("bridge or backend mismatch");
    }
    if (claims.expires_at_ms < claims.issued_at_ms ||
        claims.expires_at_ms - claims.issued_at_ms > validation.maximum_lifetime_ms) {
        return fail("token lifetime exceeds policy");
    }
    if (claims.protocol_version == kOniForwardLegacyProtocolVersion) {
        const auto expected_proxy_now =
            saturating_add(validation.now_ms, validation.proxy_clock_offset_ms);
        if (claims.issued_at_ms >
            saturating_add(expected_proxy_now, validation.allowed_clock_skew_ms)) {
            return fail(clock_error("token was issued in the future", claims, validation));
        }
        if (claims.expires_at_ms <
            saturating_add(expected_proxy_now, -validation.allowed_clock_skew_ms)) {
            return fail(clock_error("token is expired", claims, validation));
        }
    }
    TrustedProxyMatcher address_validator;
    try {
        address_validator.add(claims.real_ip +
                              (claims.real_ip.find(':') == std::string::npos ? "/32" : "/128"));
    } catch (const std::invalid_argument&) {
        return fail("real IP is invalid");
    }
    return decoded;
}

bool ForwardingTokenParser::is_well_formed(std::string_view token,
                                           std::size_t maximum_token_size,
                                           std::string& error) {
    if (token.empty() || token.size() > maximum_token_size) {
        error = "token size is invalid";
        return false;
    }
    const auto separator = token.find('.');
    if (separator == std::string_view::npos ||
        token.find('.', separator + 1) != std::string_view::npos) {
        error = "token framing is invalid";
        return false;
    }
    const auto payload = crypto::base64url_decode(token.substr(0, separator));
    const auto signature = crypto::base64url_decode(token.substr(separator + 1));
    if (!payload || !signature || signature->size() != 32) {
        error = "token base64 or signature is invalid";
        return false;
    }
    const auto decoded = decode_payload(*payload);
    if (!decoded) {
        error = decoded.error;
        return false;
    }
    error.clear();
    return true;
}

ForwardingResult ForwardingTokenVerifier::verify(std::string_view token,
                                                 const ForwardingValidation& validation) const {
    return verify_forwarding_token(token, keys_, validation);
}

} // namespace onistone::onibridge
