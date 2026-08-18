#include <onibridge/operations.hpp>

#include <utility>

namespace onistone::onibridge {

void CommandCompatibilityMonitor::observed_registry_update(std::int64_t now_ms) noexcept {
    registry_updates_.fetch_add(1);
    last_registry_update_ms_.store(now_ms);
}

void CommandCompatibilityMonitor::observed_soft_enum_update() noexcept { soft_enum_updates_.fetch_add(1); }

RateLimitedSecurityLogger::RateLimitedSecurityLogger(Sink sink, std::chrono::milliseconds interval)
    : sink_(std::move(sink)), interval_ms_(interval.count()) {}

void RateLimitedSecurityLogger::log(
    std::string key,
    std::string_view severity,
    std::string_view message,
    std::int64_t now_ms) {
    std::scoped_lock lock(mutex_);
    const auto found = last_log_.find(key);
    if (found != last_log_.end() && now_ms - found->second < interval_ms_) return;
    last_log_.insert_or_assign(std::move(key), now_ms);
    sink_(severity, message);
}

} // namespace onistone::onibridge
