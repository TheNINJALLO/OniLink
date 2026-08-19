#include <onibridge/identity.hpp>

#include <algorithm>
#include <cctype>
#include <mutex>

namespace onistone::onibridge {
namespace {

std::string lower(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), [](char ch) {
        return static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    });
    return result;
}

template <typename Predicate>
std::optional<VerifiedIdentity>
find_identity(const std::unordered_map<std::string, VerifiedIdentity>& identities,
              Predicate predicate) {
    const auto found = std::find_if(identities.begin(), identities.end(), [&](const auto& item) {
        return predicate(item.second);
    });
    return found == identities.end() ? std::nullopt : std::optional(found->second);
}

} // namespace

void VerifiedIdentityRegistry::store(VerifiedIdentity identity) {
    std::unique_lock lock(mutex_);
    sessions_.insert_or_assign(identity.session_id, std::move(identity));
}

std::optional<VerifiedIdentity>
VerifiedIdentityRegistry::by_player_name(std::string_view name) const {
    const auto wanted = lower(name);
    std::shared_lock lock(mutex_);
    return find_identity(
        sessions_, [&](const auto& identity) { return lower(identity.player_name) == wanted; });
}

std::optional<VerifiedIdentity> VerifiedIdentityRegistry::by_xuid(std::string_view xuid) const {
    std::shared_lock lock(mutex_);
    return find_identity(sessions_, [&](const auto& identity) { return identity.xuid == xuid; });
}

std::optional<VerifiedIdentity>
VerifiedIdentityRegistry::by_backend_uuid(std::string_view uuid) const {
    std::shared_lock lock(mutex_);
    return find_identity(sessions_,
                         [&](const auto& identity) { return identity.backend_uuid == uuid; });
}

std::optional<VerifiedIdentity>
VerifiedIdentityRegistry::by_session_id(std::string_view session_id) const {
    std::shared_lock lock(mutex_);
    const auto found = sessions_.find(std::string(session_id));
    return found == sessions_.end() ? std::nullopt : std::optional(found->second);
}

std::optional<VerifiedIdentity>
VerifiedIdentityRegistry::active_player(std::string_view name) const {
    return by_player_name(name);
}

void VerifiedIdentityRegistry::remove_session(std::string_view session_id) {
    std::unique_lock lock(mutex_);
    sessions_.erase(std::string(session_id));
}

std::size_t VerifiedIdentityRegistry::size() const {
    std::shared_lock lock(mutex_);
    return sessions_.size();
}

bool PostLoginIdentityVerifier::verify(std::string_view session_id, std::string_view actual_xuid) {
    const auto identity = identities_.by_session_id(session_id);
    if (!identity || identity->xuid != actual_xuid) {
        identities_.remove_session(session_id);
        return false;
    }
    return true;
}

} // namespace onistone::onibridge
