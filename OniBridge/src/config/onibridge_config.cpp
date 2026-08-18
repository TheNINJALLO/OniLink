#include <onibridge/config.hpp>

#include "../forwarding/crypto.hpp"

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <charconv>
#include <cctype>
#include <set>
#include <sstream>
#include <stdexcept>
#include <unordered_map>

namespace onistone::onibridge {
namespace {

std::string trim(std::string value) {
    const auto first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

std::string without_comment(std::string_view line) {
    bool quoted = false;
    bool escaped = false;
    for (std::size_t i = 0; i < line.size(); ++i) {
        if (escaped) { escaped = false; continue; }
        if (line[i] == '\\' && quoted) { escaped = true; continue; }
        if (line[i] == '"') quoted = !quoted;
        if (line[i] == '#' && !quoted) return std::string(line.substr(0, i));
    }
    return std::string(line);
}

std::string string_value(const std::unordered_map<std::string, std::string>& values, std::string_view key, std::string fallback = {}) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) return fallback;
    const auto& raw = found->second;
    if (raw.size() < 2 || raw.front() != '"' || raw.back() != '"') throw std::runtime_error(std::string(key) + " must be a quoted TOML string");
    std::string result;
    for (std::size_t i = 1; i + 1 < raw.size(); ++i) {
        if (raw[i] == '\\') {
            if (++i + 1 > raw.size()) throw std::runtime_error("invalid TOML string escape");
            if (raw[i] == 'n') result.push_back('\n');
            else if (raw[i] == 'r') result.push_back('\r');
            else if (raw[i] == 't') result.push_back('\t');
            else if (raw[i] == '\\' || raw[i] == '"') result.push_back(raw[i]);
            else throw std::runtime_error("unsupported TOML string escape");
        } else result.push_back(raw[i]);
    }
    return result;
}

bool bool_value(const std::unordered_map<std::string, std::string>& values, std::string_view key, bool fallback) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) return fallback;
    if (found->second == "true") return true;
    if (found->second == "false") return false;
    throw std::runtime_error(std::string(key) + " must be true or false");
}

std::int64_t int_value(const std::unordered_map<std::string, std::string>& values, std::string_view key, std::int64_t fallback) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) return fallback;
    std::int64_t result = 0;
    const auto [end, error] = std::from_chars(found->second.data(), found->second.data() + found->second.size(), result);
    if (error != std::errc{} || end != found->second.data() + found->second.size()) throw std::runtime_error(std::string(key) + " must be an integer");
    return result;
}

std::vector<std::string> string_array(const std::unordered_map<std::string, std::string>& values, std::string_view key) {
    const auto found = values.find(std::string(key));
    if (found == values.end()) return {};
    const auto& raw = found->second;
    if (raw.size() < 2 || raw.front() != '[' || raw.back() != ']') throw std::runtime_error(std::string(key) + " must be an array");
    std::vector<std::string> result;
    std::size_t offset = 1;
    while (offset + 1 < raw.size()) {
        while (offset < raw.size() && (std::isspace(static_cast<unsigned char>(raw[offset])) || raw[offset] == ',')) ++offset;
        if (offset >= raw.size() - 1) break;
        if (raw[offset] != '"') throw std::runtime_error(std::string(key) + " must contain only strings");
        const auto end = raw.find('"', offset + 1);
        if (end == std::string::npos) throw std::runtime_error("unterminated TOML array string");
        result.push_back(raw.substr(offset + 1, end - offset - 1));
        offset = end + 1;
    }
    return result;
}

} // namespace

std::optional<std::string> OniBridgeConfig::validate() const {
    if (bridge_id.empty() || backend_name.empty()) return "bridge_id and backend_name are required";
    if (trusted_proxy_cidrs.empty()) return "at least one trusted_proxy_cidr is required";
    if (!reject_direct_joins) return "reject_direct_joins must remain enabled";
    if (forwarding_protocol != 2) return "only OniForward protocol 2 is supported";
    if (active_key_id.empty()) return "active forwarding key ID is required";
    const auto active_sources = !active_secret.environment_variable.empty() + !active_secret.restricted_file.empty();
    if (active_sources != 1) return "configure exactly one active secret source";
    const auto previous_sources = !previous_secret.environment_variable.empty() + !previous_secret.restricted_file.empty();
    if ((previous_key_id.empty() && previous_sources != 0) || (!previous_key_id.empty() && previous_sources != 1)) {
        return "previous key ID and exactly one secret source must be configured together";
    }
    if (!previous_key_id.empty() && previous_key_id == active_key_id) return "active and previous key IDs must differ";
    if (maximum_token_size < 256 || maximum_token_size > 65'536) return "maximum token size is outside safe limits";
    if (maximum_lifetime_ms < 1 || maximum_lifetime_ms > 10'000) return "maximum lifetime must be 1..10000 ms";
    if (allowed_clock_skew_ms < 0 || allowed_clock_skew_ms > 10'000) return "clock skew is outside safe limits";
    if (replay_cache_max_entries == 0 || replay_cache_max_entries > 1'000'000) return "replay cache limit is outside safe limits";
    if (interfere_with_backend_commands) return "OniBridge may not alter backend command packets";
    if (legacy_verification_enabled) return "legacy verification cannot be enabled in the native authentication path";
    if (allow_unknown_bds || allow_unknown_endstone) return "unknown BDS or Endstone runtimes are forbidden";
    return std::nullopt;
}

std::vector<std::byte> load_secret(const SecretSource& source) {
    std::string value;
    if (!source.environment_variable.empty() && source.restricted_file.empty()) {
        const auto* raw = std::getenv(source.environment_variable.c_str());
        if (raw == nullptr) throw std::runtime_error("configured secret environment variable is not set");
        value = raw;
    } else if (source.environment_variable.empty() && !source.restricted_file.empty()) {
        const auto permissions = std::filesystem::status(source.restricted_file).permissions();
        constexpr auto forbidden = std::filesystem::perms::group_all | std::filesystem::perms::others_all;
        if (permissions != std::filesystem::perms::unknown
            && (permissions & forbidden) != std::filesystem::perms::none) {
            throw std::runtime_error("configured secret file is accessible by group or others");
        }
        std::ifstream input(source.restricted_file, std::ios::binary);
        if (!input) throw std::runtime_error("cannot read configured secret file");
        value.assign(std::istreambuf_iterator<char>(input), {});
        while (!value.empty() && (value.back() == '\r' || value.back() == '\n')) value.pop_back();
    } else {
        throw std::runtime_error("secret source is ambiguous or missing");
    }
    const auto padding = value.size() - value.find_last_not_of('=') - 1;
    if (padding > 2 || value.find('=') < value.size() - padding) {
        std::fill(value.begin(), value.end(), '\0');
        throw std::runtime_error("forwarding secret is not canonical Base64");
    }
    while (!value.empty() && value.back() == '=') value.pop_back();
    std::replace(value.begin(), value.end(), '+', '-');
    std::replace(value.begin(), value.end(), '/', '_');
    auto decoded = crypto::base64url_decode(value);
    std::fill(value.begin(), value.end(), '\0');
    if (!decoded || decoded->size() < 32) throw std::runtime_error("forwarding secret must be Base64 for at least 32 bytes");
    return std::move(*decoded);
}

OniBridgeConfig load_config(const std::filesystem::path& path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open OniBridge TOML configuration");
    std::unordered_map<std::string, std::string> values;
    std::string section;
    std::string pending;
    std::string line;
    while (std::getline(input, line)) {
        auto clean = trim(without_comment(line));
        if (clean.empty()) continue;
        if (!pending.empty()) { pending += " " + clean; clean = pending; }
        if (clean.front() == '[' && clean.back() == ']' && clean.find('=') == std::string::npos) {
            if (!pending.empty()) throw std::runtime_error("unterminated TOML array before section");
            section = trim(clean.substr(1, clean.size() - 2));
            continue;
        }
        const auto equals = clean.find('=');
        if (equals == std::string::npos) throw std::runtime_error("invalid TOML assignment");
        if (clean.substr(equals + 1).find('[') != std::string::npos && clean.find(']', equals + 1) == std::string::npos) {
            pending = clean;
            continue;
        }
        pending.clear();
        const auto key = trim(clean.substr(0, equals));
        const auto full_key = section.empty() ? key : section + "." + key;
        if (key.empty() || values.contains(full_key)) throw std::runtime_error("empty or duplicate TOML key: " + full_key);
        values.emplace(full_key, trim(clean.substr(equals + 1)));
    }
    if (!pending.empty()) throw std::runtime_error("unterminated TOML array");
    const std::set<std::string> allowed{
        "bridge_id", "backend_name", "trusted_proxy_cidrs", "shutdown_on_hook_failure", "reject_direct_joins",
        "forwarding.protocol", "forwarding.active_key_id", "forwarding.active_secret_env", "forwarding.active_secret_file",
        "forwarding.previous_key_id", "forwarding.previous_secret_env", "forwarding.previous_secret_file",
        "forwarding.maximum_token_size", "forwarding.maximum_lifetime_ms", "forwarding.allowed_clock_skew_ms",
        "forwarding.replay_cache_max_entries", "identity.uuid_mode", "identity.verify_post_login_xuid",
        "identity.store_verified_identities", "commands.register_native_commands", "commands.command_namespace",
        "commands.interfere_with_backend_commands", "compatibility.required_profile", "compatibility.allow_unreviewed_profile",
        "compatibility.allow_unknown_bds", "compatibility.allow_unknown_endstone", "legacy_verification.enabled"
    };
    for (const auto& [key, unused] : values) if (!allowed.contains(key)) throw std::runtime_error("unknown OniBridge TOML key: " + key);

    OniBridgeConfig config;
    config.bridge_id = string_value(values, "bridge_id");
    config.backend_name = string_value(values, "backend_name");
    config.trusted_proxy_cidrs = string_array(values, "trusted_proxy_cidrs");
    config.shutdown_on_hook_failure = bool_value(values, "shutdown_on_hook_failure", true);
    config.reject_direct_joins = bool_value(values, "reject_direct_joins", true);
    config.forwarding_protocol = static_cast<std::uint32_t>(int_value(values, "forwarding.protocol", 2));
    config.active_key_id = string_value(values, "forwarding.active_key_id");
    config.active_secret.environment_variable = string_value(values, "forwarding.active_secret_env");
    config.active_secret.restricted_file = string_value(values, "forwarding.active_secret_file");
    config.previous_key_id = string_value(values, "forwarding.previous_key_id");
    config.previous_secret.environment_variable = string_value(values, "forwarding.previous_secret_env");
    config.previous_secret.restricted_file = string_value(values, "forwarding.previous_secret_file");
    config.maximum_token_size = static_cast<std::size_t>(int_value(values, "forwarding.maximum_token_size", 4'096));
    config.maximum_lifetime_ms = int_value(values, "forwarding.maximum_lifetime_ms", 10'000);
    config.allowed_clock_skew_ms = int_value(values, "forwarding.allowed_clock_skew_ms", 2'000);
    config.replay_cache_max_entries = static_cast<std::size_t>(int_value(values, "forwarding.replay_cache_max_entries", 10'000));
    const auto uuid_mode = string_value(values, "identity.uuid_mode", "preserve_backend");
    if (uuid_mode == "preserve_backend") config.uuid_mode = UuidMode::preserve_backend;
    else if (uuid_mode == "proxy_experimental") config.uuid_mode = UuidMode::proxy_experimental;
    else throw std::runtime_error("unsupported identity.uuid_mode");
    config.verify_post_login_xuid = bool_value(values, "identity.verify_post_login_xuid", true);
    config.store_verified_identities = bool_value(values, "identity.store_verified_identities", true);
    config.register_native_commands = bool_value(values, "commands.register_native_commands", true);
    if (string_value(values, "commands.command_namespace", "onibridge") != "onibridge") throw std::runtime_error("command namespace must remain onibridge");
    config.interfere_with_backend_commands = bool_value(values, "commands.interfere_with_backend_commands", false);
    config.required_profile = string_value(values, "compatibility.required_profile");
    config.allow_unreviewed_profile = bool_value(values, "compatibility.allow_unreviewed_profile", false);
    config.allow_unknown_bds = bool_value(values, "compatibility.allow_unknown_bds", false);
    config.allow_unknown_endstone = bool_value(values, "compatibility.allow_unknown_endstone", false);
    config.legacy_verification_enabled = bool_value(values, "legacy_verification.enabled", false);
    for (auto* source : {&config.active_secret, &config.previous_secret}) {
        if (!source->restricted_file.empty() && source->restricted_file.is_relative()) source->restricted_file = path.parent_path() / source->restricted_file;
    }
    if (const auto error = config.validate()) throw std::runtime_error(*error);
    return config;
}

} // namespace onistone::onibridge
