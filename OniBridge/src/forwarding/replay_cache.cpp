#include <onibridge/forwarding.hpp>

#include <functional>
#include <stdexcept>

namespace onistone::onibridge {

ReplayCache::ReplayCache(std::size_t maximum_entries) : maximum_entries_(maximum_entries) {
    if (maximum_entries == 0)
        throw std::invalid_argument("replay cache maximum must be positive");
}

bool ReplayCache::consume(const ForwardingClaims& claims, std::int64_t now_ms) {
    const auto identity = claims.bridge_id + "\x1f" + claims.session_id + "\x1f" + claims.nonce;
    auto& shard = shards_[std::hash<std::string>{}(identity) % kShardCount];
    std::scoped_lock lock(shard.mutex);
    for (auto it = shard.entries.begin(); it != shard.entries.end();) {
        if (it->second < now_ms) {
            it = shard.entries.erase(it);
            size_.fetch_sub(1);
        } else
            ++it;
    }
    if (shard.entries.contains(identity))
        return false;
    auto observed = size_.load();
    do {
        if (observed >= maximum_entries_)
            return false;
    } while (!size_.compare_exchange_weak(observed, observed + 1));
    try {
        shard.entries.emplace(identity, claims.expires_at_ms);
    } catch (...) {
        size_.fetch_sub(1);
        throw;
    }
    return true;
}

std::size_t ReplayCache::size() const noexcept {
    return size_.load();
}

} // namespace onistone::onibridge
