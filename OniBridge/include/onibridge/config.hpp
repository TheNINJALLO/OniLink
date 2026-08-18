#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace onistone::onibridge {

enum class UuidMode { preserve_backend, proxy_experimental };

struct SecretSource {
    std::string environment_variable;
    std::filesystem::path restricted_file;
};

struct OniBridgeConfig {
    std::string bridge_id;
    std::string backend_name;
    std::vector<std::string> trusted_proxy_cidrs;
    bool shutdown_on_hook_failure{true};
    bool reject_direct_joins{true};

    std::uint32_t forwarding_protocol{2};
    std::string active_key_id;
    SecretSource active_secret;
    std::string previous_key_id;
    SecretSource previous_secret;
    std::size_t maximum_token_size{4'096};
    std::int64_t maximum_lifetime_ms{10'000};
    std::int64_t allowed_clock_skew_ms{2'000};
    std::size_t replay_cache_max_entries{10'000};

    UuidMode uuid_mode{UuidMode::preserve_backend};
    bool verify_post_login_xuid{true};
    bool store_verified_identities{true};
    bool register_native_commands{true};
    bool interfere_with_backend_commands{false};

    std::string required_profile;
    bool allow_unreviewed_profile{false};
    bool allow_unknown_bds{false};
    bool allow_unknown_endstone{false};
    bool legacy_verification_enabled{false};

    [[nodiscard]] std::optional<std::string> validate() const;
};

[[nodiscard]] std::vector<std::byte> load_secret(const SecretSource& source);
[[nodiscard]] OniBridgeConfig load_config(const std::filesystem::path& path);

} // namespace onistone::onibridge
