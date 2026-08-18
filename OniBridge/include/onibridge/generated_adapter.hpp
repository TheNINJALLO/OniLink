#pragma once

#include <string>

namespace onistone::onibridge {

class CommandCompatibilityMonitor;
struct OniBridgeConfig;
class OniBridgeService;

// Implemented only by sdkgen output tied to one reviewed platform profile. The release build
// refuses to configure without that generated source file.
bool install_generated_native_login_hook(
    OniBridgeService& service,
    const OniBridgeConfig& config,
    CommandCompatibilityMonitor& command_monitor,
    std::string& error);
bool uninstall_generated_native_login_hook(std::string& error);

} // namespace onistone::onibridge

