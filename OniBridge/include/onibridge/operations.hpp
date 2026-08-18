#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>

namespace onistone::onibridge {

class CommandCompatibilityMonitor final {
public:
    void observed_registry_update(std::int64_t now_ms) noexcept;
    void observed_soft_enum_update() noexcept;
    [[nodiscard]] std::uint64_t registry_updates() const noexcept { return registry_updates_.load(); }
    [[nodiscard]] std::uint64_t soft_enum_updates() const noexcept { return soft_enum_updates_.load(); }
    [[nodiscard]] std::int64_t last_registry_update_ms() const noexcept { return last_registry_update_ms_.load(); }
    [[nodiscard]] static constexpr bool alters_command_packets() noexcept { return false; }

private:
    std::atomic_uint64_t registry_updates_{0};
    std::atomic_uint64_t soft_enum_updates_{0};
    std::atomic_int64_t last_registry_update_ms_{0};
};

class RateLimitedSecurityLogger final {
public:
    using Sink = std::function<void(std::string_view severity, std::string_view message)>;
    RateLimitedSecurityLogger(Sink sink, std::chrono::milliseconds interval);
    void log(std::string key, std::string_view severity, std::string_view message, std::int64_t now_ms);

private:
    Sink sink_;
    std::int64_t interval_ms_;
    std::mutex mutex_;
    std::unordered_map<std::string, std::int64_t> last_log_;
};

struct MigrationPlan {
    std::filesystem::path source;
    std::filesystem::path destination;
    bool source_exists{};
    bool destination_exists{};
    bool safe_to_apply{};
};

class LegacyDataMigrator final {
public:
    [[nodiscard]] static MigrationPlan plan(
        const std::filesystem::path& source,
        const std::filesystem::path& destination);
    static void apply(const MigrationPlan& plan, bool explicit_confirmation);
};

} // namespace onistone::onibridge

