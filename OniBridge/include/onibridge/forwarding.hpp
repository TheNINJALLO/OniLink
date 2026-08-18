#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace onistone::onibridge {

inline constexpr std::uint8_t kOniForwardEncodingVersion = 1;
inline constexpr std::uint32_t kOniForwardProtocolVersion = 2;

struct ForwardingClaims {
    std::uint32_t protocol_version{kOniForwardProtocolVersion};
    std::string key_id;
    std::string proxy_id;
    std::string bridge_id;
    std::string backend_name;
    std::string session_id;
    std::string nonce;
    std::string player_name;
    std::string xuid;
    std::string proxy_uuid;
    std::string real_ip;
    std::uint16_t real_port{};
    std::int64_t issued_at_ms{};
    std::int64_t expires_at_ms{};
};

struct ForwardingKey {
    std::string id;
    std::vector<std::byte> secret;
};

struct ForwardingKeyRing {
    ForwardingKey active;
    std::optional<ForwardingKey> previous;
};

struct ForwardingValidation {
    std::string expected_player_name;
    std::string expected_bridge_id;
    std::string expected_backend_name;
    std::int64_t now_ms{};
    std::int64_t maximum_lifetime_ms{10'000};
    std::int64_t allowed_clock_skew_ms{2'000};
    std::size_t maximum_token_size{4'096};
};

struct ForwardingResult {
    std::optional<ForwardingClaims> claims;
    std::string error;

    [[nodiscard]] explicit operator bool() const noexcept { return claims.has_value(); }
};

[[nodiscard]] std::string sign_forwarding_token(const ForwardingClaims& claims, const ForwardingKey& key);
[[nodiscard]] ForwardingResult verify_forwarding_token(
    std::string_view token,
    const ForwardingKeyRing& keys,
    const ForwardingValidation& validation);

class ForwardingTokenParser final {
public:
    [[nodiscard]] static bool is_well_formed(
        std::string_view token,
        std::size_t maximum_token_size,
        std::string& error);
};

class ForwardingTokenVerifier final {
public:
    explicit ForwardingTokenVerifier(ForwardingKeyRing keys) : keys_(std::move(keys)) {}
    [[nodiscard]] ForwardingResult verify(std::string_view token, const ForwardingValidation& validation) const;

private:
    ForwardingKeyRing keys_;
};

class ReplayCache final {
public:
    explicit ReplayCache(std::size_t maximum_entries = 10'000);
    [[nodiscard]] bool consume(const ForwardingClaims& claims, std::int64_t now_ms);
    [[nodiscard]] std::size_t size() const noexcept;

private:
    static constexpr std::size_t kShardCount = 32;
    struct Shard {
        mutable std::mutex mutex;
        std::unordered_map<std::string, std::int64_t> entries;
    };
    std::array<Shard, kShardCount> shards_;
    std::size_t maximum_entries_;
    std::atomic_size_t size_{0};
};

class TrustedProxyMatcher final {
public:
    TrustedProxyMatcher() = default;
    explicit TrustedProxyMatcher(const std::vector<std::string>& cidrs);
    void add(std::string_view cidr);
    [[nodiscard]] bool matches(std::string_view address) const;

private:
    struct Network {
        std::array<std::uint8_t, 16> address{};
        std::uint8_t prefix{};
        bool ipv4{};
    };
    std::vector<Network> networks_;
};

} // namespace onistone::onibridge
