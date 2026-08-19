#include <onibridge/config.hpp>
#include <onibridge/generated_adapter.hpp>
#include <onibridge/login_envelope.hpp>
#include <onibridge/operations.hpp>
#include <onibridge/service.hpp>

#include <endstone/endstone.hpp>
#include <endstone/event/player/player_login_event.h>
#include <endstone/event/player/player_quit_event.h>
#include <endstone/event/server/packet_receive_event.h>
#include <endstone/event/server/packet_send_event.h>

#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef ONIBRIDGE_VERSION
#define ONIBRIDGE_VERSION "0.1.3"
#endif

namespace onistone::onibridge {

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
                          "[legacy_verification]\nenabled = false\n";
                throw std::runtime_error("created onibridge.toml; configure it and restart");
            }
            config_ = load_config(path);
            auto active_secret = load_secret(config_.active_secret);
            ForwardingKeyRing keys{{config_.active_key_id, std::move(active_secret)}, std::nullopt};
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
            }
        } catch (const std::exception& exception) {
            hook_error_ = exception.what();
            getLogger().critical("OniBridge startup failed closed: {}", hook_error_);
            getServer().shutdown();
        }
    }

    void onDisable() override {
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
    OniBridgeConfig config_;
    std::unique_ptr<OniBridgeService> service_;
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
