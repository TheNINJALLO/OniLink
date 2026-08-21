#include <onibridge/config.hpp>

#include "../forwarding/crypto.hpp"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <set>
#include <sstream>
#include <stdexcept>
#include <unordered_map>

namespace onistone::onibridge {
namespace {

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) {
        return {};
    }
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

std::string without_comment(std::string_view line) {
    bool quoted = false;
    bool escaped = false;
    for (std::size_t i = 0; i < line.size(); ++i) {
        if (escaped) {
            escaped = false;
            continue;
        }
        if (line[i] == '\\' && quoted) {
            escaped = true;
            continue;
        }
        if (line[i] == '"') {
            quoted = !quoted;
        }
        if (line[i] == '#' && !quoted) {
            return std::string(line.substr(0, i));
        }
    }
    return std::string(line);
}

std::string string_value(const std::unordered_map<std::string, std::string>& values,
                         std::string_view key,
                         std::string fallback = {}) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) {
        return fallback;
    }
    const auto& raw = found->second;
    if (raw.size() < 2 || raw.front() != '"' || raw.back() != '"') {
        throw std::runtime_error(std::string(key) + " must be a quoted TOML string");
    }
    std::string result;
    for (std::size_t i = 1; i + 1 < raw.size(); ++i) {
        if (raw[i] == '\\') {
            if (++i + 1 > raw.size()) {
                throw std::runtime_error("invalid TOML string escape");
            }
            if (raw[i] == 'n') {
                result.push_back('\n');
            } else if (raw[i] == 'r') {
                result.push_back('\r');
            } else if (raw[i] == 't') {
                result.push_back('\t');
            } else if (raw[i] == '\\' || raw[i] == '"') {
                result.push_back(raw[i]);
            } else {
                throw std::runtime_error("unsupported TOML string escape");
            }
        } else {
            result.push_back(raw[i]);
        }
    }
    return result;
}

bool bool_value(const std::unordered_map<std::string, std::string>& values,
                std::string_view key,
                bool fallback) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) {
        return fallback;
    }
    if (found->second == "true") {
        return true;
    }
    if (found->second == "false") {
        return false;
    }
    throw std::runtime_error(std::string(key) + " must be true or false");
}

std::int64_t int_value(const std::unordered_map<std::string, std::string>& values,
                       std::string_view key,
                       std::int64_t fallback) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) {
        return fallback;
    }
    std::int64_t result = 0;
    const auto [end, error] =
        std::from_chars(found->second.data(), found->second.data() + found->second.size(), result);
    if (error != std::errc{} || end != found->second.data() + found->second.size()) {
        throw std::runtime_error(std::string(key) + " must be an integer");
    }
    return result;
}

std::vector<std::string> string_array(const std::unordered_map<std::string, std::string>& values,
                                      std::string_view key) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) {
        return {};
    }
    const auto& raw = found->second;
    if (raw.size() < 2 || raw.front() != '[' || raw.back() != ']') {
        throw std::runtime_error(std::string(key) + " must be an array");
    }
    std::vector<std::string> result;
    std::size_t offset = 1;
    while (offset + 1 < raw.size()) {
        while (offset < raw.size() &&
               (std::isspace(static_cast<unsigned char>(raw[offset])) || raw[offset] == ',')) {
            ++offset;
        }
        if (offset >= raw.size() - 1) {
            break;
        }
        if (raw[offset] != '"') {
            throw std::runtime_error(std::string(key) + " must contain only strings");
        }
        const auto end = raw.find('"', offset + 1);
        if (end == std::string::npos) {
            throw std::runtime_error("unterminated TOML array string");
        }
        result.push_back(raw.substr(offset + 1, end - offset - 1));
        offset = end + 1;
    }
    return result;
}

bool is_loopback(std::string_view host) {
    return host == "127.0.0.1" || host == "::1" || host == "localhost";
}

bool is_private_literal(std::string_view host) {
    if (is_loopback(host) || host.starts_with("10.") || host.starts_with("192.168.") ||
        host.starts_with("fc") || host.starts_with("fd"))
        return true;
    if (!host.starts_with("172."))
        return false;
    const auto second_dot = host.find('.', 4);
    if (second_dot == std::string_view::npos)
        return false;
    int octet = 0;
    const auto [end, error] = std::from_chars(host.data() + 4, host.data() + second_dot, octet);
    return error == std::errc{} && end == host.data() + second_dot && octet >= 16 && octet <= 31;
}

} // namespace

std::optional<std::string> ControlConfig::validate() const {
    if (!enabled)
        return std::nullopt;
    if (listen_port == 0 || bridge_id.empty() || backend_name.empty() || key_id.empty())
        return "enabled control requires a listen port, bridge ID, backend name, and key ID";
    const auto sources = !secret.environment_variable.empty() + !secret.restricted_file.empty();
    if (sources != 1)
        return "enabled control requires exactly one separate secret source";
    if (trusted_proxy_cidrs.empty())
        return "enabled control requires at least one trusted proxy CIDR";
    if (max_frame_bytes < 1'024 || max_frame_bytes > 1'048'576)
        return "control max_frame_bytes must be 1024..1048576";
    if (max_connections == 0 || max_connections > 128 || max_in_flight == 0 ||
        max_in_flight > 1'024)
        return "control connection and in-flight limits are outside safe bounds";
    if (clock_skew_seconds < 1 || clock_skew_seconds > 300 || replay_retention_seconds < 30 ||
        replay_retention_seconds > 3'600)
        return "control time and replay limits are outside safe bounds";
    if (tls.enabled) {
        if (tls.certificate_file.empty() || tls.private_key_file.empty() ||
            tls.client_ca_file.empty() || !tls.require_client_certificate)
            return "control TLS requires a certificate, private key, client CA, and client "
                   "certificates";
        return "control TLS is not available in this release artifact; use loopback or a private "
               "network tunnel";
    }
    if (!is_private_literal(listen_host) && !allow_public_address)
        return "cleartext control may bind only a private literal unless allow_public_address=true";
    if (!is_loopback(listen_host) && !allow_insecure_private_network)
        return "cleartext control off loopback requires allow_insecure_private_network=true";
    return std::nullopt;
}

std::optional<std::string> OniBridgeConfig::validate() const {
    if (bridge_id.empty() || backend_name.empty()) {
        return "bridge_id and backend_name are required";
    }
    if (trusted_proxy_cidrs.empty()) {
        return "at least one trusted_proxy_cidr is required";
    }
    if (!reject_direct_joins) {
        return "reject_direct_joins must remain enabled";
    }
    if (forwarding_protocol != 2) {
        return "only OniForward protocol 2 is supported";
    }
    if (active_key_id.empty()) {
        return "active forwarding key ID is required";
    }
    const auto active_sources =
        !active_secret.environment_variable.empty() + !active_secret.restricted_file.empty();
    if (active_sources != 1) {
        return "configure exactly one active secret source";
    }
    const auto previous_sources =
        !previous_secret.environment_variable.empty() + !previous_secret.restricted_file.empty();
    if ((previous_key_id.empty() && previous_sources != 0) ||
        (!previous_key_id.empty() && previous_sources != 1)) {
        return "previous key ID and exactly one secret source must be configured together";
    }
    if (!previous_key_id.empty() && previous_key_id == active_key_id) {
        return "active and previous key IDs must differ";
    }
    if (maximum_token_size < 256 || maximum_token_size > 65'536) {
        return "maximum token size is outside safe limits";
    }
    if (maximum_lifetime_ms < 1 || maximum_lifetime_ms > 10'000) {
        return "maximum lifetime must be 1..10000 ms";
    }
    if (allowed_clock_skew_ms < 0 || allowed_clock_skew_ms > 10'000) {
        return "clock skew is outside safe limits";
    }
    if (replay_cache_max_entries == 0 || replay_cache_max_entries > 1'000'000) {
        return "replay cache limit is outside safe limits";
    }
    if (interfere_with_backend_commands) {
        return "OniBridge may not alter backend command packets";
    }
    if (legacy_verification_enabled) {
        return "legacy verification cannot be enabled in the native authentication path";
    }
    if (allow_unknown_bds || allow_unknown_endstone) {
        return "unknown BDS or Endstone runtimes are forbidden";
    }
    if (const auto error = control.validate())
        return error;
    const auto forwarding_source =
        !active_secret.environment_variable.empty()
            ? "env:" + active_secret.environment_variable
            : "file:" + active_secret.restricted_file.lexically_normal().string();
    const auto control_source =
        !control.secret.environment_variable.empty()
            ? "env:" + control.secret.environment_variable
            : "file:" + control.secret.restricted_file.lexically_normal().string();
    if (control.enabled && forwarding_source == control_source)
        return "OniControl and OniForward must use different secret sources";
    return std::nullopt;
}

std::vector<std::byte> load_secret(const SecretSource& source) {
    std::string value;
    if (!source.environment_variable.empty() && source.restricted_file.empty()) {
        const auto* raw = std::getenv(source.environment_variable.c_str());
        if (raw == nullptr) {
            throw std::runtime_error("configured secret environment variable is not set");
        }
        value = raw;
    } else if (source.environment_variable.empty() && !source.restricted_file.empty()) {
        if (!std::filesystem::is_regular_file(source.restricted_file) ||
            std::filesystem::is_symlink(std::filesystem::symlink_status(source.restricted_file))) {
            throw std::runtime_error(
                "configured secret file must be a regular file, not a symlink");
        }
#ifndef _WIN32
        // Pterodactyl's browser editor and SFTP uploads commonly create files as 0644. The operator
        // explicitly selected this path as a secret source, so tighten it to owner-only before
        // reading it. A filesystem that refuses the change still fails closed below.
        std::error_code permission_error;
        std::filesystem::permissions(source.restricted_file,
                                     std::filesystem::perms::owner_read |
                                         std::filesystem::perms::owner_write,
                                     std::filesystem::perm_options::replace,
                                     permission_error);
        if (permission_error) {
            throw std::runtime_error("cannot restrict configured secret file to owner-only access");
        }
#endif
        const auto permissions = std::filesystem::status(source.restricted_file).permissions();
        constexpr auto forbidden =
            std::filesystem::perms::group_all | std::filesystem::perms::others_all;
        if (permissions != std::filesystem::perms::unknown &&
            (permissions & forbidden) != std::filesystem::perms::none) {
            throw std::runtime_error("configured secret file is accessible by group or others");
        }
        std::ifstream input(source.restricted_file, std::ios::binary);
        if (!input) {
            throw std::runtime_error("cannot read configured secret file");
        }
        value.assign(std::istreambuf_iterator<char>(input), {});
        while (!value.empty() && (value.back() == '\r' || value.back() == '\n')) {
            value.pop_back();
        }
    } else {
        throw std::runtime_error("secret source is ambiguous or missing");
    }
    const auto padding = value.size() - value.find_last_not_of('=') - 1;
    if (padding > 2 || value.find('=') < value.size() - padding) {
        std::fill(value.begin(), value.end(), '\0');
        throw std::runtime_error("forwarding secret is not canonical Base64");
    }
    while (!value.empty() && value.back() == '=') {
        value.pop_back();
    }
    std::replace(value.begin(), value.end(), '+', '-');
    std::replace(value.begin(), value.end(), '/', '_');
    auto decoded = crypto::base64url_decode(value);
    std::fill(value.begin(), value.end(), '\0');
    if (!decoded || decoded->size() < 32) {
        throw std::runtime_error("forwarding secret must be Base64 for at least 32 bytes");
    }
    return std::move(*decoded);
}

OniBridgeConfig load_config(const std::filesystem::path& path) {
    std::ifstream input(path);
    if (!input) {
        throw std::runtime_error("cannot open OniBridge TOML configuration");
    }
    std::unordered_map<std::string, std::string> values;
    std::string section;
    std::string pending;
    std::string line;
    while (std::getline(input, line)) {
        auto clean = trim(without_comment(line));
        if (clean.empty()) {
            continue;
        }
        if (!pending.empty()) {
            pending += " " + clean;
            clean = pending;
        }
        if (clean.front() == '[' && clean.back() == ']' && clean.find('=') == std::string::npos) {
            if (!pending.empty()) {
                throw std::runtime_error("unterminated TOML array before section");
            }
            section = trim(clean.substr(1, clean.size() - 2));
            continue;
        }
        const auto equals = clean.find('=');
        if (equals == std::string::npos) {
            throw std::runtime_error("invalid TOML assignment");
        }
        if (clean.substr(equals + 1).find('[') != std::string::npos &&
            clean.find(']', equals + 1) == std::string::npos) {
            pending = clean;
            continue;
        }
        pending.clear();
        const auto key = trim(clean.substr(0, equals));
        const auto full_key = section.empty() ? key : section + "." + key;
        if (key.empty() || values.contains(full_key)) {
            throw std::runtime_error("empty or duplicate TOML key: " + full_key);
        }
        values.emplace(full_key, trim(clean.substr(equals + 1)));
    }
    if (!pending.empty()) {
        throw std::runtime_error("unterminated TOML array");
    }
    const std::set<std::string> allowed{
        "backend_name",
        "bridge_id",
        "commands.command_namespace",
        "commands.interfere_with_backend_commands",
        "commands.register_native_commands",
        "control.allow_insecure_private_network",
        "control.allow_public_address",
        "control.backend_name",
        "control.bridge_id",
        "control.clock_skew_seconds",
        "control.enabled",
        "control.key_id",
        "control.listen_host",
        "control.listen_port",
        "control.max_connections",
        "control.max_frame_bytes",
        "control.max_in_flight",
        "control.replay_retention_seconds",
        "control.secret_environment",
        "control.secret_file",
        "control.trusted_proxy_cidrs",
        "control.tls.certificate_file",
        "control.tls.client_ca_file",
        "control.tls.enabled",
        "control.tls.private_key_file",
        "control.tls.require_client_certificate",
        "compatibility.allow_unknown_bds",
        "compatibility.allow_unknown_endstone",
        "compatibility.allow_unreviewed_profile",
        "compatibility.required_profile",
        "forwarding.active_key_id",
        "forwarding.active_secret_env",
        "forwarding.active_secret_file",
        "forwarding.allowed_clock_skew_ms",
        "forwarding.maximum_lifetime_ms",
        "forwarding.maximum_token_size",
        "forwarding.previous_key_id",
        "forwarding.previous_secret_env",
        "forwarding.previous_secret_file",
        "forwarding.protocol",
        "forwarding.replay_cache_max_entries",
        "identity.store_verified_identities",
        "identity.uuid_mode",
        "identity.verify_post_login_xuid",
        "legacy_verification.enabled",
        "reject_direct_joins",
        "shutdown_on_hook_failure",
        "trusted_proxy_cidrs",
    };
    for (const auto& [key, unused] : values) {
        if (!allowed.contains(key)) {
            throw std::runtime_error("unknown OniBridge TOML key: " + key);
        }
    }

    OniBridgeConfig config;
    config.bridge_id = string_value(values, "bridge_id");
    config.backend_name = string_value(values, "backend_name");
    config.trusted_proxy_cidrs = string_array(values, "trusted_proxy_cidrs");
    config.shutdown_on_hook_failure = bool_value(values, "shutdown_on_hook_failure", true);
    config.reject_direct_joins = bool_value(values, "reject_direct_joins", true);
    config.forwarding_protocol =
        static_cast<std::uint32_t>(int_value(values, "forwarding.protocol", 2));
    config.active_key_id = string_value(values, "forwarding.active_key_id");
    config.active_secret.environment_variable =
        string_value(values, "forwarding.active_secret_env");
    config.active_secret.restricted_file = string_value(values, "forwarding.active_secret_file");
    config.previous_key_id = string_value(values, "forwarding.previous_key_id");
    config.previous_secret.environment_variable =
        string_value(values, "forwarding.previous_secret_env");
    config.previous_secret.restricted_file =
        string_value(values, "forwarding.previous_secret_file");
    config.maximum_token_size =
        static_cast<std::size_t>(int_value(values, "forwarding.maximum_token_size", 4'096));
    config.maximum_lifetime_ms = int_value(values, "forwarding.maximum_lifetime_ms", 10'000);
    config.allowed_clock_skew_ms = int_value(values, "forwarding.allowed_clock_skew_ms", 2'000);
    config.replay_cache_max_entries =
        static_cast<std::size_t>(int_value(values, "forwarding.replay_cache_max_entries", 10'000));
    const auto uuid_mode = string_value(values, "identity.uuid_mode", "preserve_backend");
    if (uuid_mode == "preserve_backend") {
        config.uuid_mode = UuidMode::preserve_backend;
    } else if (uuid_mode == "proxy_experimental") {
        config.uuid_mode = UuidMode::proxy_experimental;
    } else {
        throw std::runtime_error("unsupported identity.uuid_mode");
    }
    config.verify_post_login_xuid = bool_value(values, "identity.verify_post_login_xuid", true);
    config.store_verified_identities =
        bool_value(values, "identity.store_verified_identities", true);
    config.register_native_commands = bool_value(values, "commands.register_native_commands", true);
    if (string_value(values, "commands.command_namespace", "onibridge") != "onibridge") {
        throw std::runtime_error("command namespace must remain onibridge");
    }
    config.interfere_with_backend_commands =
        bool_value(values, "commands.interfere_with_backend_commands", false);
    config.required_profile = string_value(values, "compatibility.required_profile");
    config.allow_unreviewed_profile =
        bool_value(values, "compatibility.allow_unreviewed_profile", false);
    config.allow_unknown_bds = bool_value(values, "compatibility.allow_unknown_bds", false);
    config.allow_unknown_endstone =
        bool_value(values, "compatibility.allow_unknown_endstone", false);
    config.legacy_verification_enabled = bool_value(values, "legacy_verification.enabled", false);
    config.control.enabled = bool_value(values, "control.enabled", false);
    config.control.listen_host = string_value(values, "control.listen_host", "127.0.0.1");
    const auto control_port = int_value(values, "control.listen_port", 19'132);
    if (control_port < 1 || control_port > 65'535)
        throw std::runtime_error("control.listen_port must be 1..65535");
    config.control.listen_port = static_cast<std::uint16_t>(control_port);
    config.control.bridge_id = string_value(values, "control.bridge_id", config.bridge_id);
    config.control.backend_name = string_value(values, "control.backend_name", config.backend_name);
    config.control.key_id = string_value(values, "control.key_id", "control-key-1");
    config.control.secret.environment_variable = string_value(values, "control.secret_environment");
    config.control.secret.restricted_file = string_value(values, "control.secret_file");
    config.control.trusted_proxy_cidrs = string_array(values, "control.trusted_proxy_cidrs");
    if (config.control.trusted_proxy_cidrs.empty())
        config.control.trusted_proxy_cidrs = {"127.0.0.1/32", "::1/128"};
    config.control.max_frame_bytes =
        static_cast<std::size_t>(int_value(values, "control.max_frame_bytes", 262'144));
    config.control.max_connections =
        static_cast<std::size_t>(int_value(values, "control.max_connections", 4));
    config.control.max_in_flight =
        static_cast<std::size_t>(int_value(values, "control.max_in_flight", 32));
    config.control.clock_skew_seconds = int_value(values, "control.clock_skew_seconds", 30);
    config.control.replay_retention_seconds =
        int_value(values, "control.replay_retention_seconds", 120);
    config.control.allow_insecure_private_network =
        bool_value(values, "control.allow_insecure_private_network", false);
    config.control.allow_public_address = bool_value(values, "control.allow_public_address", false);
    config.control.tls.enabled = bool_value(values, "control.tls.enabled", false);
    config.control.tls.certificate_file = string_value(values, "control.tls.certificate_file");
    config.control.tls.private_key_file = string_value(values, "control.tls.private_key_file");
    config.control.tls.client_ca_file = string_value(values, "control.tls.client_ca_file");
    config.control.tls.require_client_certificate =
        bool_value(values, "control.tls.require_client_certificate", true);
    for (auto* source : {&config.active_secret, &config.previous_secret}) {
        if (!source->restricted_file.empty() && source->restricted_file.is_relative()) {
            source->restricted_file = path.parent_path() / source->restricted_file;
        }
    }
    if (!config.control.secret.restricted_file.empty() &&
        config.control.secret.restricted_file.is_relative())
        config.control.secret.restricted_file =
            path.parent_path() / config.control.secret.restricted_file;
    for (auto* tls_path : {&config.control.tls.certificate_file,
                           &config.control.tls.private_key_file,
                           &config.control.tls.client_ca_file}) {
        if (!tls_path->empty() && tls_path->is_relative())
            *tls_path = path.parent_path() / *tls_path;
    }
    if (const auto error = config.validate()) {
        throw std::runtime_error(*error);
    }
    return config;
}

} // namespace onistone::onibridge
