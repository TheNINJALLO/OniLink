#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace onistone::onibridge {

inline constexpr std::uint8_t kOniForwardEncodingVersion = 1;
inline constexpr std::uint32_t kOniForwardLegacyProtocolVersion = 2;
inline constexpr std::uint32_t kOniForwardProtocolVersion = 3;

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
    std::string proxy_boot_id;
    std::uint64_t sequence{};
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
    // Protocol 2 permits migration from the legacy clock-based format. Protocol 3 disables
    // downgrade once both OniLink and OniBridge have been updated.
    std::uint32_t minimum_protocol_version{kOniForwardLegacyProtocolVersion};
    // Signed proxy clock minus backend clock. This translates between the two wall-clock
    // domains without widening the token lifetime or replay window. OniForward v3 ignores it.
    std::int64_t proxy_clock_offset_ms{};
    std::size_t maximum_token_size{4'096};
};

struct ForwardingResult {
    std::optional<ForwardingClaims> claims;
    std::string error;

    [[nodiscard]] explicit operator bool() const noexcept {
        return claims.has_value();
    }
};

[[nodiscard]] std::string sign_forwarding_token(const ForwardingClaims& claims,
                                                const ForwardingKey& key);
[[nodiscard]] ForwardingResult verify_forwarding_token(std::string_view token,
                                                       const ForwardingKeyRing& keys,
                                                       const ForwardingValidation& validation);

class ForwardingTokenParser final {
  public:
    [[nodiscard]] static bool
    is_well_formed(std::string_view token, std::size_t maximum_token_size, std::string& error);
};

class ForwardingTokenVerifier final {
  public:
    explicit ForwardingTokenVerifier(ForwardingKeyRing keys) : keys_(std::move(keys)) {}
    [[nodiscard]] ForwardingResult verify(std::string_view token,
                                          const ForwardingValidation& validation) const;

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

/**
 * Clock-independent v3 freshness guard.
 *
 * A proxy process signs a persisted monotonic boot UUID and a strictly increasing sequence into
 * every token.
 * The bridge persists the current and retired boot UUIDs per proxy and accepts each sequence once,
 * including a small window for concurrent logins that arrive out of order. This keeps consumed
 * sequences and retired proxy processes unusable even when either provider's wall clock jumps.
 */
class ForwardingSequenceGuard final {
  public:
    explicit ForwardingSequenceGuard(std::filesystem::path state_file = {});
    [[nodiscard]] bool consume(const ForwardingClaims& claims, std::string& error);

  private:
    static constexpr std::uint64_t kReorderingWindow = 4'096;
    struct State {
        std::string current_boot_id;
        std::uint64_t highest_sequence{};
        std::uint64_t restored_sequence_floor{};
        std::unordered_set<std::uint64_t> recent_sequences;
        std::unordered_set<std::string> retired_boot_ids;
    };

    void load();
    void persist_locked() const;

    std::filesystem::path state_file_;
    std::mutex mutex_;
    std::unordered_map<std::string, State> states_;
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
