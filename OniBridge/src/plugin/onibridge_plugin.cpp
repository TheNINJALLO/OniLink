#include <onibridge/config.hpp>
#include <onibridge/control.hpp>
#include <onibridge/generated_adapter.hpp>
#include <onibridge/login_envelope.hpp>
#include <onibridge/operations.hpp>
#include <onibridge/service.hpp>

#include <endstone/endstone.hpp>
#include <endstone/event/player/player_login_event.h>
#include <endstone/event/player/player_quit_event.h>
#include <endstone/event/server/packet_receive_event.h>
#include <endstone/event/server/packet_send_event.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <charconv>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef ONIBRIDGE_VERSION
#define ONIBRIDGE_VERSION "0.3.0-beta.1"
#endif
#ifndef ONIBRIDGE_BDS_VERSION
#define ONIBRIDGE_BDS_VERSION "profile-bound"
#endif
#ifndef ONIBRIDGE_BDS_SHA256
#define ONIBRIDGE_BDS_SHA256 "profile-bound"
#endif
#ifndef ONIBRIDGE_OPERATING_SYSTEM
#define ONIBRIDGE_OPERATING_SYSTEM "unknown"
#endif

namespace onistone::onibridge {
namespace {

std::string json_escape(std::string_view value) {
    std::string result;
    result.reserve(value.size() + 8);
    for (const unsigned char ch : value) {
        switch (ch) {
        case '"':
            result += "\\\"";
            break;
        case '\\':
            result += "\\\\";
            break;
        case '\n':
            result += "\\n";
            break;
        case '\r':
            result += "\\r";
            break;
        case '\t':
            result += "\\t";
            break;
        default:
            if (ch < 0x20)
                throw std::invalid_argument("control value contains a forbidden character");
            result.push_back(static_cast<char>(ch));
        }
    }
    return result;
}

void json_space(std::string_view input, std::size_t& position) {
    while (position < input.size() && (input[position] == ' ' || input[position] == '\t' ||
                                       input[position] == '\r' || input[position] == '\n'))
        ++position;
}

std::string parse_json_string(std::string_view input, std::size_t& position) {
    if (position >= input.size() || input[position++] != '"')
        throw std::invalid_argument("expected JSON string");
    std::string result;
    while (position < input.size()) {
        const char ch = input[position++];
        if (ch == '"')
            return result;
        if (static_cast<unsigned char>(ch) < 0x20)
            throw std::invalid_argument("invalid JSON string");
        if (ch != '\\') {
            result.push_back(ch);
            continue;
        }
        if (position >= input.size())
            throw std::invalid_argument("unterminated JSON escape");
        switch (input[position++]) {
        case '"':
            result.push_back('"');
            break;
        case '\\':
            result.push_back('\\');
            break;
        case '/':
            result.push_back('/');
            break;
        case 'b':
            result.push_back('\b');
            break;
        case 'f':
            result.push_back('\f');
            break;
        case 'n':
            result.push_back('\n');
            break;
        case 'r':
            result.push_back('\r');
            break;
        case 't':
            result.push_back('\t');
            break;
        default:
            throw std::invalid_argument("unsupported JSON escape");
        }
    }
    throw std::invalid_argument("unterminated JSON string");
}

std::size_t json_value_end(std::string_view input, std::size_t start) {
    if (start >= input.size())
        throw std::invalid_argument("missing JSON value");
    if (input[start] == '"') {
        auto cursor = start;
        static_cast<void>(parse_json_string(input, cursor));
        return cursor;
    }
    if (input[start] == '{' || input[start] == '[') {
        const char open = input[start];
        const char close = open == '{' ? '}' : ']';
        int depth = 0;
        bool quoted = false;
        bool escaped = false;
        for (std::size_t cursor = start; cursor < input.size(); ++cursor) {
            const char ch = input[cursor];
            if (quoted) {
                if (escaped)
                    escaped = false;
                else if (ch == '\\')
                    escaped = true;
                else if (ch == '"')
                    quoted = false;
                continue;
            }
            if (ch == '"')
                quoted = true;
            else if (ch == open)
                ++depth;
            else if (ch == close && --depth == 0)
                return cursor + 1;
        }
        throw std::invalid_argument("unterminated JSON container");
    }
    auto cursor = start;
    while (cursor < input.size() && input[cursor] != ',' && input[cursor] != '}' &&
           input[cursor] != ']')
        ++cursor;
    return cursor;
}

std::optional<std::string_view> json_field(std::string_view object, std::string_view wanted) {
    std::size_t position = 0;
    json_space(object, position);
    if (position >= object.size() || object[position++] != '{')
        throw std::invalid_argument("control payload must be a JSON object");
    while (true) {
        json_space(object, position);
        if (position < object.size() && object[position] == '}')
            return std::nullopt;
        const auto key = parse_json_string(object, position);
        json_space(object, position);
        if (position >= object.size() || object[position++] != ':')
            throw std::invalid_argument("malformed control payload");
        json_space(object, position);
        const auto start = position;
        const auto end = json_value_end(object, start);
        if (key == wanted)
            return object.substr(start, end - start);
        position = end;
        json_space(object, position);
        if (position >= object.size())
            throw std::invalid_argument("unterminated control payload");
        if (object[position++] == '}')
            return std::nullopt;
        if (object[position - 1] != ',')
            throw std::invalid_argument("malformed control payload separator");
    }
}

std::string json_text(std::string_view object,
                      std::string_view key,
                      std::size_t maximum,
                      std::string fallback = {}) {
    const auto field = json_field(object, key);
    if (!field)
        return fallback;
    std::size_t position = 0;
    auto value = parse_json_string(*field, position);
    if (position != field->size() || value.empty() || value.size() > maximum ||
        value.find_first_of("\r\n\0") != std::string::npos)
        throw std::invalid_argument(std::string(key) + " is invalid");
    return value;
}

double json_number(std::string_view object,
                   std::string_view key,
                   double minimum,
                   double maximum,
                   std::optional<double> fallback = std::nullopt) {
    const auto field = json_field(object, key);
    if (!field) {
        if (fallback)
            return *fallback;
        throw std::invalid_argument(std::string(key) + " is required");
    }
    std::string text(*field);
    char* end{};
    const auto value = std::strtod(text.c_str(), &end);
    if (end != text.c_str() + text.size() || !std::isfinite(value) || value < minimum ||
        value > maximum)
        throw std::invalid_argument(std::string(key) + " is outside safe bounds");
    return value;
}

int json_integer(std::string_view object,
                 std::string_view key,
                 int minimum,
                 int maximum,
                 std::optional<int> fallback = std::nullopt) {
    const auto value = json_number(
        object, key, minimum, maximum, fallback ? std::optional<double>(*fallback) : std::nullopt);
    if (value != static_cast<int>(value))
        throw std::invalid_argument(std::string(key) + " must be an integer");
    return static_cast<int>(value);
}

bool json_boolean(std::string_view object, std::string_view key, bool fallback) {
    const auto field = json_field(object, key);
    if (!field)
        return fallback;
    if (*field == "true")
        return true;
    if (*field == "false")
        return false;
    throw std::invalid_argument(std::string(key) + " must be boolean");
}

std::string_view action_values(std::string_view payload) {
    const auto version = json_integer(payload, "payloadVersion", 1, 1);
    static_cast<void>(version);
    const auto values = json_field(payload, "values");
    if (!values || values->empty() || values->front() != '{')
        throw std::invalid_argument("control action values object is required");
    static constexpr std::array<std::string_view, 9> forbidden{"packetId",
                                                               "rawBytes",
                                                               "wireBytes",
                                                               "memoryAddress",
                                                               "jwt",
                                                               "token",
                                                               "signature",
                                                               "privateKey",
                                                               "shellCommand"};
    for (const auto key : forbidden)
        if (json_field(*values, key))
            throw std::invalid_argument("control action contains a forbidden field");
    return *values;
}

int control_role_rank(std::string_view payload) {
    const auto role = json_text(payload, "actorRole", 16);
    if (role == "VIEWER")
        return 0;
    if (role == "OPERATOR")
        return 1;
    if (role == "ADMIN")
        return 2;
    if (role == "OWNER")
        return 3;
    throw std::invalid_argument("control actor role is not recognized");
}

int required_control_role(std::string_view action) {
    if (action == "GET_PLAYER_STATE" || action == "GET_PLAYER_POSITION")
        return 0;
    // Every other action advertised by the native bridge reads or mutates authoritative
    // backend state and therefore requires an administrator.
    return 2;
}

bool safe_identifier(std::string_view value, std::size_t maximum = 128) {
    if (value.empty() || value.size() > maximum)
        return false;
    return std::all_of(value.begin(), value.end(), [](unsigned char ch) {
        return std::isalnum(ch) || ch == '_' || ch == '-' || ch == '.' || ch == ':';
    });
}

ControlResult rejected(std::string_view reason) {
    return {"REJECTED", "{\"reason\":\"" + json_escape(reason) + "\"}"};
}

ControlResult unsupported(std::string_view reason) {
    return {"UNSUPPORTED", "{\"reason\":\"" + json_escape(reason) + "\"}"};
}

} // namespace

class OniBridgePlugin : public endstone::Plugin {
  public:
    void onEnable() override {
        try {
            const auto path = getDataFolder() / "onibridge.toml";
            if (!std::filesystem::exists(path)) {
                std::filesystem::create_directories(getDataFolder());
                std::ofstream output(path);
                output << "bridge_id = \"change-me\"\nbackend_name = \"change-me\"\n"
                          "trusted_proxy_cidrs = [\"127.0.0.1/32\", \"::1/128\"]\n"
                          "shutdown_on_hook_failure = true\nreject_direct_joins = true\n\n"
                          "[forwarding]\nprotocol = 2\nactive_key_id = \"key-1\"\n"
                          "active_secret_env = \"ONIBRIDGE_FORWARDING_SECRET\"\n"
                          "maximum_token_size = 4096\nmaximum_lifetime_ms = 10000\n"
                          "allowed_clock_skew_ms = 2000\nreplay_cache_max_entries = 10000\n\n"
                          "[identity]\nuuid_mode = \"preserve_backend\"\nverify_post_login_xuid = "
                          "true\n"
                          "store_verified_identities = "
                          "true\n\n[commands]\nregister_native_commands = true\n"
                          "command_namespace = \"onibridge\"\ninterfere_with_backend_commands = "
                          "false\n\n"
                          "[compatibility]\nrequired_profile = \"\"\nallow_unreviewed_profile = "
                          "false\n"
                          "allow_unknown_bds = false\nallow_unknown_endstone = false\n\n"
                          "[legacy_verification]\nenabled = false\n\n"
                          "# Separate authenticated control channel; never reuse the forwarding "
                          "secret.\n"
                          "[control]\nenabled = false\nlisten_host = \"127.0.0.1\"\n"
                          "listen_port = 19132\nbridge_id = \"change-me-control\"\n"
                          "backend_name = \"change-me\"\nkey_id = \"control-key-1\"\n"
                          "secret_environment = \"ONIBRIDGE_CONTROL_SECRET\"\nsecret_file = \"\"\n"
                          "trusted_proxy_cidrs = [\"127.0.0.1/32\", \"::1/128\"]\n"
                          "max_frame_bytes = 262144\nmax_connections = 4\nmax_in_flight = 32\n"
                          "clock_skew_seconds = 30\nreplay_retention_seconds = 120\n"
                          "allow_insecure_private_network = false\nallow_public_address = false\n\n"
                          "[control.tls]\nenabled = false\ncertificate_file = \"\"\n"
                          "private_key_file = \"\"\nclient_ca_file = \"\"\n"
                          "require_client_certificate = true\n";
                throw std::runtime_error("created onibridge.toml; configure it and restart");
            }
            config_ = load_config(path);
            auto active_secret = load_secret(config_.active_secret);
            ForwardingKeyRing keys{{config_.active_key_id, active_secret}, std::nullopt};
            if (!config_.previous_key_id.empty()) {
                keys.previous =
                    ForwardingKey{config_.previous_key_id, load_secret(config_.previous_secret)};
            }
            service_ =
                std::make_unique<OniBridgeService>(config_.bridge_id,
                                                   config_.backend_name,
                                                   std::move(keys),
                                                   TrustedProxyMatcher(config_.trusted_proxy_cidrs),
                                                   config_.replay_cache_max_entries,
                                                   config_.maximum_token_size,
                                                   config_.maximum_lifetime_ms,
                                                   config_.allowed_clock_skew_ms);

            registerEvent(
                &OniBridgePlugin::onPacketReceive, *this, endstone::EventPriority::Monitor);
            registerEvent(&OniBridgePlugin::onPacketSend, *this, endstone::EventPriority::Monitor);
            registerEvent(&OniBridgePlugin::onPlayerLogin, *this, endstone::EventPriority::Highest);
            registerEvent(&OniBridgePlugin::onPlayerQuit, *this, endstone::EventPriority::Monitor);

            if (getServer().getName() != "Endstone" || getServer().getVersion() != "0.11.9") {
                hook_error_ = "this adapter requires exact Endstone 0.11.9; unknown native "
                              "runtimes fail closed";
            } else if (config_.required_profile.empty()) {
                hook_error_ = "no required production hook profile is configured";
            } else {
                hook_active_ = install_generated_native_login_hook(
                    *service_, config_, command_monitor_, hook_error_);
            }
            if (!hook_active_) {
                getLogger().critical("OniBridge authentication is inactive: {}", hook_error_);
                if (config_.shutdown_on_hook_failure)
                    getServer().shutdown();
            } else {
                getLogger().info(
                    "OniBridge native identity hook is active for the exact reviewed profile.");
                if (config_.control.enabled) {
                    auto control_secret = load_secret(config_.control.secret);
                    if (control_secret == active_secret)
                        throw std::runtime_error(
                            "OniControl secret bytes must differ from the OniForward secret");
                    ControlCapabilities capabilities{
                        .onibridge_version = ONIBRIDGE_VERSION,
                        .bds_version = ONIBRIDGE_BDS_VERSION,
                        .endstone_version = getServer().getVersion(),
                        .operating_system = ONIBRIDGE_OPERATING_SYSTEM,
                        .executable_hash = ONIBRIDGE_BDS_SHA256,
                        .active_profile = config_.required_profile,
                        .profile_review_status = "approved",
                        .supported_actions = supportedControlActions(),
                    };
                    control_server_ = std::make_unique<ControlServer>(
                        config_.control,
                        std::move(control_secret),
                        std::move(capabilities),
                        [this](ControlRequest request, ControlCompletion completion) {
                            dispatchControl(std::move(request), std::move(completion));
                        });
                    control_server_->start();
                    getLogger().info("OniControl is listening on {}:{} for backend {}.",
                                     config_.control.listen_host,
                                     config_.control.listen_port,
                                     config_.control.backend_name);
                }
            }
        } catch (const std::exception& exception) {
            hook_error_ = exception.what();
            getLogger().critical("OniBridge startup failed closed: {}", hook_error_);
            getServer().shutdown();
        }
    }

    void onDisable() override {
        if (control_server_) {
            control_server_->stop();
            control_server_.reset();
        }
        if (hook_active_) {
            std::string error;
            if (!uninstall_generated_native_login_hook(error)) {
                getLogger().critical("OniBridge could not safely remove its native hook: {}",
                                     error);
            }
        }
        service_.reset();
        hook_active_ = false;
    }

    bool onCommand(endstone::CommandSender& sender,
                   const endstone::Command& command,
                   const std::vector<std::string>& args) override {
        if (command.getName() != "onibridge" || args.empty())
            return false;
        const auto& action = args[0];
        if (action == "status") {
            sender.sendMessage("OniBridge hook active: {}", hook_active_ ? "true" : "false");
            if (!hook_error_.empty())
                sender.sendErrorMessage("Compatibility: {}", hook_error_);
        } else if (action == "version") {
            sender.sendMessage("OniBridge {}", ONIBRIDGE_VERSION);
        } else if (action == "profile") {
            sender.sendMessage("Required profile: {}",
                               config_.required_profile.empty() ? "none"
                                                                : config_.required_profile);
            sender.sendMessage("Production validation: {}", hook_active_ ? "passed" : "failed");
        } else if (action == "sessions") {
            sender.sendMessage("Verified active sessions: {}",
                               service_ ? service_->identities().size() : 0);
        } else if (action == "identity") {
            if (args.size() != 2 || !service_)
                return false;
            const auto identity = service_->identities().by_player_name(args[1]);
            if (!identity)
                sender.sendErrorMessage("No verified identity for that player.");
            else
                sender.sendMessage("{}: XUID {}, proxy {}, backend {}",
                                   identity->player_name,
                                   identity->xuid,
                                   identity->proxy_id,
                                   identity->backend_name);
        } else if (action == "test-config") {
            const auto error = config_.validate();
            sender.sendMessage(error ? "Configuration invalid" : "Configuration valid");
            if (error)
                sender.sendErrorMessage("{}", *error);
        } else if (action == "command-status") {
            sender.sendMessage("Backend command registry observed: {}",
                               command_monitor_.registry_updates() ? "true" : "false");
            sender.sendMessage("Last command registry update: {}",
                               command_monitor_.last_registry_update_ms());
            sender.sendMessage("Soft enum updates observed: {}",
                               command_monitor_.soft_enum_updates());
            sender.sendMessage("Command packets altered by OniBridge: false");
            sender.sendMessage("Normal Endstone command chain active: true");
        } else
            return false;
        return true;
    }

    void onPacketReceive(endstone::PacketReceiveEvent& event) {
        if (!service_ || event.isCancelled() || event.getPacketId() != 1 ||
            event.getSubClientId() != 0)
            return;
        const auto envelope =
            LoginEnvelopeParser::parse(event.getPayload(), 1'048'576, config_.maximum_token_size);
        if (!envelope) {
            rejected_envelopes_.fetch_add(1);
            last_envelope_error_ = envelope.error;
            return;
        }
        const auto address = event.getAddress();
        const auto decision =
            service_->stage_forwarded_login(envelope.envelope->forwarding_token,
                                            address.getHostname(),
                                            envelope.envelope->player_name,
                                            std::chrono::duration_cast<std::chrono::milliseconds>(
                                                std::chrono::system_clock::now().time_since_epoch())
                                                .count());
        if (!decision) {
            rejected_envelopes_.fetch_add(1);
            last_envelope_error_ = decision.error;
            return;
        }
        staged_envelopes_.fetch_add(1);
        last_envelope_error_.clear();
    }

    void onPlayerLogin(endstone::PlayerLoginEvent& event) {
        if (!service_ || !config_.verify_post_login_xuid)
            return;
        const auto identity = service_->identities().by_player_name(event.getPlayer().getName());
        if (!identity || identity->xuid != event.getPlayer().getXuid()) {
            if (identity)
                service_->identities().remove_session(identity->session_id);
            event.setKickMessage("OniBridge could not verify the native player identity.");
            event.setCancelled(true);
        }
    }

    void onPacketSend(endstone::PacketSendEvent& event) {
        if (event.isCancelled())
            return;
        constexpr int available_commands_packet = 76;
        constexpr int update_soft_enum_packet = 114;
        if (event.getPacketId() == available_commands_packet) {
            command_monitor_.observed_registry_update(
                std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch())
                    .count());
        } else if (event.getPacketId() == update_soft_enum_packet) {
            command_monitor_.observed_soft_enum_update();
        }
    }

    void onPlayerQuit(endstone::PlayerQuitEvent& event) {
        if (!service_)
            return;
        if (const auto identity =
                service_->identities().by_player_name(event.getPlayer().getName())) {
            service_->identities().remove_session(identity->session_id);
        }
    }

  private:
    std::vector<std::string> supportedControlActions() const {
        return {
            "PING",
            "GET_CAPABILITIES",
            "GET_BACKEND_HEALTH",
            "GET_ONLINE_PLAYERS",
            "GET_PLAYER_STATE",
            "PREPARE_DRAIN",
            "CLOSE_PLAYER_CONTAINERS",
            "SAVE_WORLD",
        };
    }

    void dispatchControl(ControlRequest request, ControlCompletion completion) {
        auto task = getServer().getScheduler().runTask(
            *this,
            [this, request = std::move(request), completion = std::move(completion)]() mutable {
                try {
                    completion(executeControl(request));
                } catch (const std::invalid_argument& exception) {
                    completion(rejected(exception.what()));
                } catch (const std::exception&) {
                    completion({"FAILED", "{\"reason\":\"authoritative action failed\"}"});
                }
            });
        if (!task)
            completion({"FAILED", "{\"reason\":\"server-thread scheduler rejected action\"}"});
    }

    endstone::Player* verifiedPlayer(const ControlRequest& request) {
        if (!service_)
            return nullptr;
        const auto identity = service_->identities().by_xuid(request.target_xuid);
        if (!identity)
            return nullptr;
        auto* player = getServer().getPlayer(identity->player_name);
        if (!player || player->getXuid() != request.target_xuid)
            return nullptr;
        return player;
    }

    ControlResult executeControl(const ControlRequest& request) {
        if (!getServer().isPrimaryThread())
            return {"FAILED", "{\"reason\":\"action was not on the primary server thread\"}"};
        if (control_role_rank(request.payload_json) < required_control_role(request.action))
            return rejected("authenticated actor role is not permitted to execute this action");
        const auto& action = request.action;
        if (action == "GET_BACKEND_HEALTH")
            return {"CONFIRMED", "{\"healthy\":true,\"primaryThread\":true}"};
        if (action == "PREPARE_DRAIN")
            return {"CONFIRMED", "{\"prepared\":true,\"routingControlledByOniLink\":true}"};
        if (action == "GET_ONLINE_PLAYERS")
            return unsupported("portable online-player enumeration is unavailable in the pinned Endstone API");
        if (action == "CLOSE_PLAYER_CONTAINERS")
            return unsupported("the pinned Endstone API has no safe close-container operation");
        if (action == "SAVE_WORLD")
            return unsupported("the pinned Endstone API has no public synchronous world-save operation");
        auto* player = verifiedPlayer(request);
        if (!player)
            return rejected("target XUID is not an active verified OniBridge player");
        const auto values = action_values(request.payload_json);
        if (action == "GET_PLAYER_STATE")
            return playerState(*player);
        return unsupported("action is not advertised by this OniBridge capability profile");
    }

    static ControlResult playerState(endstone::Player& player) {
        const auto location = player.getLocation();
        return {"CONFIRMED",
                "{\"name\":\"" + json_escape(player.getName()) + "\",\"xuid\":\"" +
                    json_escape(player.getXuid()) +
                    "\",\"health\":" + std::to_string(player.getHealth()) +
                    ",\"maxHealth\":" + std::to_string(player.getMaxHealth()) +
                    ",\"gameMode\":" + std::to_string(static_cast<int>(player.getGameMode())) +
                    ",\"experience\":" + std::to_string(player.getTotalExp()) +
                    ",\"level\":" + std::to_string(player.getExpLevel()) + ",\"dimension\":\"" +
                    json_escape(location.getDimension().getName()) + "\",\"x\":" +
                    std::to_string(location.getX()) + ",\"y\":" + std::to_string(location.getY()) +
                    ",\"z\":" + std::to_string(location.getZ()) + "}"};
    }

    static ControlResult playerPosition(endstone::Player& player) {
        const auto location = player.getLocation();
        return {"CONFIRMED",
                "{\"dimension\":\"" + json_escape(location.getDimension().getName()) + "\",\"x\":" +
                    std::to_string(location.getX()) + ",\"y\":" + std::to_string(location.getY()) +
                    ",\"z\":" + std::to_string(location.getZ()) +
                    ",\"yaw\":" + std::to_string(location.getYaw()) +
                    ",\"pitch\":" + std::to_string(location.getPitch()) + "}"};
    }

    static ControlResult playerInventory(endstone::Player& player) {
        const auto contents = player.getInventory().getContents();
        std::string items = "[";
        bool first = true;
        for (std::size_t slot = 0; slot < contents.size(); ++slot) {
            if (!contents[slot])
                continue;
            if (!first)
                items += ',';
            first = false;
            items += "{\"slot\":" + std::to_string(slot) + ",\"item\":\"" +
                     json_escape(std::format("{}", contents[slot]->getType().getId())) +
                     "\",\"amount\":" + std::to_string(contents[slot]->getAmount()) +
                     ",\"data\":" + std::to_string(contents[slot]->getData()) + "}";
        }
        return {"CONFIRMED",
                "{\"size\":" + std::to_string(contents.size()) + ",\"items\":" + items + "]}"};
    }

    static ControlResult playerPermissions(endstone::Player& player) {
        std::vector<std::pair<std::string, bool>> permissions;
        for (const auto* permission : player.getEffectivePermissions())
            if (permission)
                permissions.emplace_back(permission->getPermission(), permission->getValue());
        std::sort(permissions.begin(), permissions.end());
        std::string result = "[";
        for (std::size_t index = 0; index < permissions.size(); ++index) {
            if (index)
                result += ',';
            result += "{\"permission\":\"" + json_escape(permissions[index].first) +
                      "\",\"value\":" + (permissions[index].second ? "true}" : "false}");
        }
        return {"CONFIRMED",
                "{\"operator\":" + std::string(player.isOp() ? "true" : "false") +
                    ",\"permissions\":" + result + "]}"};
    }

    ControlResult
    teleportPlayer(endstone::Player& player, std::string_view values, bool dimension_required) {
        const auto current = player.getLocation();
        auto dimension_name =
            json_text(values,
                      "dimension",
                      64,
                      dimension_required ? std::string{} : current.getDimension().getName());
        if (dimension_name.empty() || !safe_identifier(dimension_name, 64))
            throw std::invalid_argument("dimension is invalid");
        auto* level = getServer().getLevel();
        auto* dimension = level ? level->getDimension(dimension_name) : nullptr;
        if (!dimension)
            return rejected("requested dimension does not exist");
        const auto x = json_number(values, "x", -30'000'000, 30'000'000);
        const auto y = json_number(values, "y", -2'048, 4'096);
        const auto z = json_number(values, "z", -30'000'000, 30'000'000);
        const auto yaw = json_number(values, "yaw", -360, 360, current.getYaw());
        const auto pitch = json_number(values, "pitch", -90, 90, current.getPitch());
        const auto moved = player.teleport(endstone::Location(
            *dimension, x, y, z, static_cast<float>(pitch), static_cast<float>(yaw)));
        if (!moved)
            return {"FAILED", "{\"reason\":\"BDS rejected teleport\"}"};
        const auto confirmed = player.getLocation();
        return {"CONFIRMED",
                "{\"dimension\":\"" + json_escape(confirmed.getDimension().getName()) +
                    "\",\"x\":" + std::to_string(confirmed.getX()) +
                    ",\"y\":" + std::to_string(confirmed.getY()) +
                    ",\"z\":" + std::to_string(confirmed.getZ()) + "}"};
    }

    static endstone::ItemStack itemStack(std::string_view values) {
        auto item = json_text(values, "item", 128);
        if (!safe_identifier(item))
            throw std::invalid_argument("item must be a namespaced runtime identifier");
        const auto amount = json_integer(values, "amount", 1, 255, 1);
        const auto data = json_integer(values, "data", 0, 65'535, 0);
        return endstone::ItemStack(endstone::ItemTypeId(item), amount, data);
    }

    static ControlResult
    mutateInventory(endstone::Player& player, std::string_view values, std::string_view action) {
        auto& inventory = player.getInventory();
        if (action == "CLEAR_INVENTORY") {
            inventory.clear();
            if (!inventory.isEmpty())
                return {"FAILED", "{\"reason\":\"inventory did not confirm empty\"}"};
            return {"CONFIRMED", "{\"empty\":true}"};
        }
        if (action == "DAMAGE_ITEM" || action == "REPAIR_ITEM") {
            const auto slot = json_integer(values, "slot", 0, inventory.getSize() - 1);
            auto item = inventory.getItem(slot);
            if (!item)
                return rejected("inventory slot is empty");
            const auto amount = json_integer(values, "amount", 1, 65'535, 1);
            const auto maximum = std::max(0, item->getType().getMaxDurability());
            const auto changed =
                action == "DAMAGE_ITEM" ? item->getData() + amount : item->getData() - amount;
            item->setData(std::clamp(changed, 0, maximum));
            inventory.setItem(slot, *item);
            const auto confirmed = inventory.getItem(slot);
            if (!confirmed || confirmed->getData() != item->getData())
                return {"FAILED", "{\"reason\":\"item durability did not confirm\"}"};
            return {"CONFIRMED",
                    "{\"slot\":" + std::to_string(slot) +
                        ",\"data\":" + std::to_string(confirmed->getData()) + "}"};
        }
        auto item = itemStack(values);
        if (action == "GIVE_ITEM") {
            auto leftover = inventory.addItem(item);
            return {leftover.empty() ? "CONFIRMED" : "PARTIAL",
                    "{\"unplacedStacks\":" + std::to_string(leftover.size()) + "}"};
        }
        if (action == "REMOVE_ITEM") {
            auto leftover = inventory.removeItem(item);
            return {leftover.empty() ? "CONFIRMED" : "PARTIAL",
                    "{\"unremovedStacks\":" + std::to_string(leftover.size()) + "}"};
        }
        if (action == "SET_INVENTORY_SLOT") {
            const auto slot = json_integer(values, "slot", 0, inventory.getSize() - 1);
            inventory.setItem(slot, item);
            const auto confirmed = inventory.getItem(slot);
            if (!confirmed || confirmed->getAmount() != item.getAmount() ||
                confirmed->getType().getId() != item.getType().getId())
                return {"FAILED", "{\"reason\":\"inventory slot did not confirm\"}"};
            return {"CONFIRMED", "{\"slot\":" + std::to_string(slot) + "}"};
        }
        if (action == "SET_OFFHAND_SLOT") {
            inventory.setItemInOffHand(item);
            const auto confirmed = inventory.getItemInOffHand();
            if (!confirmed || confirmed->getType().getId() != item.getType().getId())
                return {"FAILED", "{\"reason\":\"offhand slot did not confirm\"}"};
            return {"CONFIRMED", "{\"slot\":\"offhand\"}"};
        }
        const auto armor = json_text(values, "armorSlot", 16);
        std::optional<endstone::ItemStack> confirmed;
        if (armor == "helmet") {
            inventory.setHelmet(item);
            confirmed = inventory.getHelmet();
        } else if (armor == "chestplate") {
            inventory.setChestplate(item);
            confirmed = inventory.getChestplate();
        } else if (armor == "leggings") {
            inventory.setLeggings(item);
            confirmed = inventory.getLeggings();
        } else if (armor == "boots") {
            inventory.setBoots(item);
            confirmed = inventory.getBoots();
        } else {
            throw std::invalid_argument("armorSlot must be helmet, chestplate, leggings, or boots");
        }
        if (!confirmed || confirmed->getType().getId() != item.getType().getId() ||
            confirmed->getAmount() != item.getAmount())
            return {"FAILED", "{\"reason\":\"armor slot did not confirm\"}"};
        return {"CONFIRMED", "{\"slot\":\"" + armor + "\"}"};
    }

    static ControlResult
    mutateHealth(endstone::Player& player, std::string_view values, std::string_view action) {
        const auto amount = json_integer(values, "amount", 0, 1'000);
        int target = amount;
        if (action == "HEAL")
            target = player.getHealth() + amount;
        else if (action == "DAMAGE")
            target = player.getHealth() - amount;
        target = std::clamp(target, 0, player.getMaxHealth());
        player.setHealth(target);
        if (player.getHealth() != target)
            return {"FAILED", "{\"reason\":\"health did not confirm\"}"};
        return {"CONFIRMED", "{\"health\":" + std::to_string(target) + "}"};
    }

    static ControlResult
    mutateExperience(endstone::Player& player, std::string_view values, std::string_view action) {
        const auto amount = json_integer(values, "amount", -10'000'000, 10'000'000);
        if (action == "SET_LEVEL") {
            if (amount < 0)
                throw std::invalid_argument("level cannot be negative");
            player.setExpLevel(amount);
            if (player.getExpLevel() != amount)
                return {"FAILED", "{\"reason\":\"experience level did not confirm\"}"};
            return {"CONFIRMED", "{\"level\":" + std::to_string(amount) + "}"};
        }
        const auto before = player.getTotalExp();
        const auto requested = action == "SET_EXPERIENCE"
                                   ? static_cast<std::int64_t>(amount)
                                   : static_cast<std::int64_t>(before) + amount;
        if (requested < 0 || requested > std::numeric_limits<int>::max())
            throw std::invalid_argument("resulting total experience is outside safe bounds");
        const auto delta = requested - before;
        if (delta < std::numeric_limits<int>::min() || delta > std::numeric_limits<int>::max())
            throw std::invalid_argument("experience delta is outside safe bounds");
        player.giveExp(static_cast<int>(delta));
        const auto confirmed = player.getTotalExp();
        if (confirmed != requested)
            return {"FAILED", "{\"reason\":\"total experience did not confirm\"}"};
        return {"CONFIRMED", "{\"totalExperience\":" + std::to_string(confirmed) + "}"};
    }

    static ControlResult setGamemode(endstone::Player& player, std::string_view values) {
        auto mode = json_text(values, "gameMode", 16);
        std::transform(mode.begin(), mode.end(), mode.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        endstone::GameMode target;
        if (mode == "survival")
            target = endstone::GameMode::Survival;
        else if (mode == "creative")
            target = endstone::GameMode::Creative;
        else if (mode == "adventure")
            target = endstone::GameMode::Adventure;
        else if (mode == "spectator")
            target = endstone::GameMode::Spectator;
        else
            throw std::invalid_argument("gameMode is invalid");
        player.setGameMode(target);
        if (player.getGameMode() != target)
            return {"FAILED", "{\"reason\":\"gamemode did not confirm\"}"};
        return {"CONFIRMED", "{\"gameMode\":\"" + mode + "\"}"};
    }

    static ControlResult setAbility(endstone::Player& player, std::string_view values) {
        const auto ability = json_text(values, "ability", 32);
        if (ability == "allow_flight") {
            const auto enabled = json_boolean(values, "enabled", false);
            player.setAllowFlight(enabled);
            return {"CONFIRMED",
                    "{\"allowFlight\":" +
                        std::string(player.getAllowFlight() ? "true}" : "false}")};
        }
        if (ability == "flying") {
            const auto enabled = json_boolean(values, "enabled", false);
            player.setFlying(enabled);
            return {"CONFIRMED",
                    "{\"flying\":" + std::string(player.isFlying() ? "true}" : "false}")};
        }
        if (ability == "fly_speed") {
            const auto speed = json_number(values, "value", 0, 1);
            player.setFlySpeed(static_cast<float>(speed));
            return {"CONFIRMED", "{\"flySpeed\":" + std::to_string(player.getFlySpeed()) + "}"};
        }
        if (ability == "walk_speed") {
            const auto speed = json_number(values, "value", 0, 1);
            player.setWalkSpeed(static_cast<float>(speed));
            return {"CONFIRMED", "{\"walkSpeed\":" + std::to_string(player.getWalkSpeed()) + "}"};
        }
        return unsupported("ability is not exposed by the reviewed Endstone API");
    }

    static ControlResult mutateTag(endstone::Player& player, std::string_view values, bool add) {
        const auto tag = json_text(values, "tag", 64);
        if (!safe_identifier(tag, 64))
            throw std::invalid_argument("tag contains unsupported characters");
        const auto changed = add ? player.addScoreboardTag(tag) : player.removeScoreboardTag(tag);
        const auto tags = player.getScoreboardTags();
        const auto present = std::find(tags.begin(), tags.end(), tag) != tags.end();
        if (present != add)
            return {"FAILED", "{\"reason\":\"scoreboard tag did not confirm\"}"};
        return {"CONFIRMED", "{\"changed\":" + std::string(changed ? "true" : "false") + "}"};
    }

    static ControlResult setPermission(endstone::Player& player, std::string_view values) {
        const auto permission = json_text(values, "permission", 32);
        if (permission != "operator")
            return unsupported("only the public Endstone operator permission is mutable");
        const auto enabled = json_boolean(values, "enabled", false);
        player.setOp(enabled);
        if (player.isOp() != enabled)
            return {"FAILED", "{\"reason\":\"operator state did not confirm\"}"};
        return {"CONFIRMED", "{\"operator\":" + std::string(enabled ? "true}" : "false}")};
    }

    ControlResult mutateBlocks(endstone::Player& player, std::string_view values, bool region) {
        auto block_type = json_text(values, "block", 128);
        if (!safe_identifier(block_type))
            throw std::invalid_argument("block must be a namespaced runtime identifier");
        auto* dimension = &player.getDimension();
        const auto dimension_name = json_text(values, "dimension", 64, dimension->getName());
        if (dimension_name != dimension->getName()) {
            auto* level = getServer().getLevel();
            dimension = level ? level->getDimension(dimension_name) : nullptr;
        }
        if (!dimension)
            return rejected("requested dimension does not exist");
        int min_x, min_y, min_z, max_x, max_y, max_z;
        if (region) {
            const auto from = json_field(values, "from");
            const auto to = json_field(values, "to");
            if (!from || !to)
                throw std::invalid_argument("block region requires from and to objects");
            min_x = json_integer(*from, "x", -30'000'000, 30'000'000);
            min_y = json_integer(*from, "y", -2'048, 4'096);
            min_z = json_integer(*from, "z", -30'000'000, 30'000'000);
            max_x = json_integer(*to, "x", -30'000'000, 30'000'000);
            max_y = json_integer(*to, "y", -2'048, 4'096);
            max_z = json_integer(*to, "z", -30'000'000, 30'000'000);
            if (min_x > max_x)
                std::swap(min_x, max_x);
            if (min_y > max_y)
                std::swap(min_y, max_y);
            if (min_z > max_z)
                std::swap(min_z, max_z);
        } else {
            min_x = max_x = json_integer(values, "x", -30'000'000, 30'000'000);
            min_y = max_y = json_integer(values, "y", -2'048, 4'096);
            min_z = max_z = json_integer(values, "z", -30'000'000, 30'000'000);
        }
        const auto volume = static_cast<std::uint64_t>(max_x - min_x + 1) *
                            static_cast<std::uint64_t>(max_y - min_y + 1) *
                            static_cast<std::uint64_t>(max_z - min_z + 1);
        if (volume > 32'768)
            return rejected("block region exceeds the advertised 32768-block limit");
        const auto physics = json_boolean(values, "applyPhysics", false);
        std::size_t changed{};
        for (int x = min_x; x <= max_x; ++x)
            for (int y = min_y; y <= max_y; ++y)
                for (int z = min_z; z <= max_z; ++z) {
                    auto block = dimension->getBlockAt(x, y, z);
                    if (!block)
                        continue;
                    block->setType(block_type, physics);
                    if (block->getType() == block_type)
                        ++changed;
                }
        if (changed != volume)
            return {changed ? "PARTIAL" : "FAILED",
                    "{\"changed\":" + std::to_string(changed) +
                        ",\"requested\":" + std::to_string(volume) + "}"};
        return {"CONFIRMED", "{\"changed\":" + std::to_string(changed) + "}"};
    }

    ControlResult mutateEntity(endstone::Player& player, std::string_view values, bool spawn) {
        if (spawn) {
            auto type = json_text(values, "entity", 128);
            if (!safe_identifier(type))
                throw std::invalid_argument("entity must be a namespaced runtime identifier");
            const auto x = json_number(values, "x", -30'000'000, 30'000'000);
            const auto y = json_number(values, "y", -2'048, 4'096);
            const auto z = json_number(values, "z", -30'000'000, 30'000'000);
            auto* entity = player.getDimension().spawnActor(
                endstone::Location(player.getDimension(), x, y, z), type);
            if (!entity || !entity->isValid())
                return {"FAILED", "{\"reason\":\"BDS rejected entity spawn\"}"};
            return {"CONFIRMED",
                    "{\"entityId\":" + std::to_string(entity->getId()) +
                        ",\"runtimeId\":" + std::to_string(entity->getRuntimeId()) + "}"};
        }
        const auto entity_id =
            static_cast<std::int64_t>(json_number(values, "entityId", 1, 9'007'199'254'740'991.0));
        auto* level = getServer().getLevel();
        if (!level)
            return {"FAILED", "{\"reason\":\"level is unavailable\"}"};
        for (auto* entity : level->getActors()) {
            if (!entity || entity->getId() != entity_id)
                continue;
            if (dynamic_cast<endstone::Player*>(entity))
                return rejected("REMOVE_ENTITY cannot remove a player");
            entity->remove();
            return {entity->isValid() ? "PARTIAL" : "CONFIRMED",
                    std::string("{\"removed\":") + (entity->isValid() ? "false}" : "true}")};
        }
        return rejected("entity ID is not active");
    }

    OniBridgeConfig config_;
    std::unique_ptr<OniBridgeService> service_;
    std::unique_ptr<ControlServer> control_server_;
    CommandCompatibilityMonitor command_monitor_;
    bool hook_active_{false};
    std::string hook_error_;
    std::atomic_uint64_t staged_envelopes_{0};
    std::atomic_uint64_t rejected_envelopes_{0};
    std::string last_envelope_error_;
};

} // namespace onistone::onibridge

ENDSTONE_PLUGIN("onibridge", ONIBRIDGE_VERSION, onistone::onibridge::OniBridgePlugin) {
    prefix = "OniBridge";
    description = "Native, fail-closed OniForward identity bridge for Endstone BDS";
    authors = {"Onistone contributors"};
    command("onibridge")
        .description("Inspect OniBridge identity and compatibility state")
        .usages("/onibridge status",
                "/onibridge version",
                "/onibridge profile",
                "/onibridge identity <player: string>",
                "/onibridge sessions",
                "/onibridge test-config",
                "/onibridge command-status")
        .permissions("onibridge.command.admin");
    permission("onibridge.command.admin")
        .description("Use OniBridge administrative diagnostics")
        .default_(endstone::PermissionDefault::Operator);
}
