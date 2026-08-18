#include <onibridge/service.hpp>

#include <algorithm>
#include <cctype>
#include <limits>
#include <mutex>
#include <utility>

namespace onistone::onibridge {
namespace {

std::string identity_key(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), [](char ch) {
        return static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    });
    return result;
}

} // namespace

OniBridgeService::OniBridgeService(
    std::string bridge_id,
    std::string backend_name,
    ForwardingKeyRing keys,
    TrustedProxyMatcher trusted_proxies,
    std::size_t replay_maximum,
    std::size_t maximum_token_size,
    std::int64_t maximum_lifetime_ms,
    std::int64_t allowed_clock_skew_ms)
    : bridge_id_(std::move(bridge_id)), backend_name_(std::move(backend_name)), keys_(std::move(keys)),
      trusted_proxies_(std::move(trusted_proxies)), replay_(replay_maximum),
      maximum_token_size_(maximum_token_size), maximum_lifetime_ms_(maximum_lifetime_ms),
      allowed_clock_skew_ms_(allowed_clock_skew_ms) {}

IdentityDecision OniBridgeService::verify_forwarded_login(
    std::string_view token,
    std::string_view actual_socket_source,
    std::string_view login_player_name,
    std::string backend_uuid,
    std::int64_t now_ms) {
    auto staged = stage_forwarded_login(token, actual_socket_source, login_player_name, now_ms);
    if (!staged) return staged;
    return consume_staged_login(login_player_name, std::move(backend_uuid), now_ms);
}

IdentityDecision OniBridgeService::stage_forwarded_login(
    std::string_view token,
    std::string_view actual_socket_source,
    std::string_view login_player_name,
    std::int64_t now_ms) {
    ForwardingValidation context{
        .expected_player_name = std::string(login_player_name),
        .expected_bridge_id = bridge_id_,
        .expected_backend_name = backend_name_,
        .now_ms = now_ms,
        .maximum_lifetime_ms = maximum_lifetime_ms_,
        .allowed_clock_skew_ms = allowed_clock_skew_ms_,
        .maximum_token_size = maximum_token_size_,
    };
    auto result = verify_forwarding_token(token, keys_, context);
    if (!result) return {std::nullopt, result.error};
    if (!trusted_proxies_.matches(actual_socket_source)) return {std::nullopt, "socket source is not a trusted proxy"};
    auto replay_claims = *result.claims;
    if (replay_claims.expires_at_ms <= std::numeric_limits<std::int64_t>::max() - context.allowed_clock_skew_ms) {
        replay_claims.expires_at_ms += context.allowed_clock_skew_ms;
    }
    if (!replay_.consume(replay_claims, now_ms)) return {std::nullopt, "forwarding token replay or capacity limit"};
    const auto& claims = *result.claims;
    VerifiedIdentity identity{
        .player_name = claims.player_name,
        .xuid = claims.xuid,
        .backend_uuid = {},
        .proxy_uuid = claims.proxy_uuid,
        .real_ip = claims.real_ip,
        .real_port = claims.real_port,
        .proxy_id = claims.proxy_id,
        .bridge_id = claims.bridge_id,
        .backend_name = claims.backend_name,
        .session_id = claims.session_id,
    };
    const auto key = identity_key(identity.player_name);
    std::lock_guard lock(pending_mutex_);
    for (auto it = pending_.begin(); it != pending_.end();) {
        if (it->second.expires_at_ms < now_ms) it = pending_.erase(it);
        else ++it;
    }
    if (pending_.contains(key)) return {std::nullopt, "a verified login for this player is already pending"};
    const auto pending_expiry = claims.expires_at_ms > std::numeric_limits<std::int64_t>::max() - allowed_clock_skew_ms_
        ? std::numeric_limits<std::int64_t>::max()
        : claims.expires_at_ms + allowed_clock_skew_ms_;
    pending_.emplace(key, PendingLogin{identity, pending_expiry});
    return {std::move(identity), {}};
}

IdentityDecision OniBridgeService::consume_staged_login(
    std::string_view login_player_name,
    std::string backend_uuid,
    std::int64_t now_ms) {
    const auto key = identity_key(login_player_name);
    VerifiedIdentity identity;
    {
        std::lock_guard lock(pending_mutex_);
        const auto found = pending_.find(key);
        if (found == pending_.end()) return {std::nullopt, "no verified OniForward login is pending"};
        if (found->second.expires_at_ms < now_ms) {
            pending_.erase(found);
            return {std::nullopt, "pending OniForward login expired"};
        }
        identity = std::move(found->second.identity);
        pending_.erase(found);
    }
    identity.backend_uuid = std::move(backend_uuid);
    identities_.store(identity);
    return {std::move(identity), {}};
}

void OniBridgeService::discard_staged_login(std::string_view login_player_name) {
    std::lock_guard lock(pending_mutex_);
    pending_.erase(identity_key(login_player_name));
}

std::size_t OniBridgeService::pending_logins() const {
    std::lock_guard lock(pending_mutex_);
    return pending_.size();
}

} // namespace onistone::onibridge
