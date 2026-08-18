#pragma once

#include <onibridge/forwarding.hpp>
#include <onibridge/identity.hpp>

#include <optional>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>

namespace onistone::onibridge {

struct IdentityDecision {
    std::optional<VerifiedIdentity> identity;
    std::string error;
    [[nodiscard]] explicit operator bool() const noexcept { return identity.has_value(); }
};

class OniBridgeService final {
public:
    OniBridgeService(
        std::string bridge_id,
        std::string backend_name,
        ForwardingKeyRing keys,
        TrustedProxyMatcher trusted_proxies,
        std::size_t replay_maximum = 10'000,
        std::size_t maximum_token_size = 4'096,
        std::int64_t maximum_lifetime_ms = 10'000,
        std::int64_t allowed_clock_skew_ms = 2'000);

    [[nodiscard]] IdentityDecision verify_forwarded_login(
        std::string_view token,
        std::string_view actual_socket_source,
        std::string_view login_player_name,
        std::string backend_uuid,
        std::int64_t now_ms);

    [[nodiscard]] IdentityDecision stage_forwarded_login(
        std::string_view token,
        std::string_view actual_socket_source,
        std::string_view login_player_name,
        std::int64_t now_ms);

    [[nodiscard]] IdentityDecision consume_staged_login(
        std::string_view login_player_name,
        std::string backend_uuid,
        std::int64_t now_ms);

    void discard_staged_login(std::string_view login_player_name);
    [[nodiscard]] std::size_t pending_logins() const;

    [[nodiscard]] VerifiedIdentityRegistry& identities() noexcept { return identities_; }

private:
    std::string bridge_id_;
    std::string backend_name_;
    ForwardingKeyRing keys_;
    TrustedProxyMatcher trusted_proxies_;
    ReplayCache replay_;
    VerifiedIdentityRegistry identities_;
    struct PendingLogin {
        VerifiedIdentity identity;
        std::int64_t expires_at_ms{};
    };
    mutable std::mutex pending_mutex_;
    std::unordered_map<std::string, PendingLogin> pending_;
    std::size_t maximum_token_size_;
    std::int64_t maximum_lifetime_ms_;
    std::int64_t allowed_clock_skew_ms_;
};

} // namespace onistone::onibridge
