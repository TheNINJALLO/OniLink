#pragma once

#include <cstdint>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <unordered_map>

namespace onistone::onibridge {

struct VerifiedIdentity {
    std::string player_name;
    std::string xuid;
    std::string backend_uuid;
    std::string proxy_uuid;
    std::string real_ip;
    std::uint16_t real_port{};
    std::string proxy_id;
    std::string bridge_id;
    std::string backend_name;
    std::string session_id;
};

class VerifiedIdentityRegistry final {
  public:
    void store(VerifiedIdentity identity);
    [[nodiscard]] std::optional<VerifiedIdentity> by_player_name(std::string_view name) const;
    [[nodiscard]] std::optional<VerifiedIdentity> by_xuid(std::string_view xuid) const;
    [[nodiscard]] std::optional<VerifiedIdentity> by_backend_uuid(std::string_view uuid) const;
    [[nodiscard]] std::optional<VerifiedIdentity> by_session_id(std::string_view session_id) const;
    [[nodiscard]] std::optional<VerifiedIdentity> active_player(std::string_view name) const;
    void remove_session(std::string_view session_id);
    [[nodiscard]] std::size_t size() const;

  private:
    mutable std::shared_mutex mutex_;
    std::unordered_map<std::string, VerifiedIdentity> sessions_;
};

class PostLoginIdentityVerifier final {
  public:
    explicit PostLoginIdentityVerifier(VerifiedIdentityRegistry& identities)
        : identities_(identities) {}
    [[nodiscard]] bool verify(std::string_view session_id, std::string_view actual_xuid);

  private:
    VerifiedIdentityRegistry& identities_;
};

} // namespace onistone::onibridge
