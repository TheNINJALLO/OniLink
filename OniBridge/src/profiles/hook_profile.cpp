#include <onibridge/hooks.hpp>

#include "../forwarding/crypto.hpp"

#include <algorithm>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <sstream>
#include <stdexcept>

namespace onistone::onibridge {
namespace {

std::string hex(std::span<const std::byte> value) {
    std::ostringstream output;
    output << std::hex << std::setfill('0');
    for (const auto byte : value) {
        output << std::setw(2) << std::to_integer<unsigned>(byte);
    }
    return output.str();
}

} // namespace

ModuleFingerprint BdsRuntimeDetector::inspect(const std::filesystem::path& module) {
    std::ifstream input(module, std::ios::binary);
    if (!input) {
        throw std::runtime_error("cannot open BDS runtime module");
    }
    std::vector<char> chars((std::istreambuf_iterator<char>(input)), {});
    std::vector<std::byte> bytes(chars.size());
    for (std::size_t i = 0; i < chars.size(); ++i) {
        bytes[i] = static_cast<std::byte>(static_cast<unsigned char>(chars[i]));
    }
    std::string format;
    if (bytes.size() >= 20 && bytes[0] == std::byte{0x7f} && bytes[1] == std::byte{'E'} &&
        bytes[2] == std::byte{'L'} && bytes[3] == std::byte{'F'} && bytes[4] == std::byte{2} &&
        bytes[5] == std::byte{1} && bytes[18] == std::byte{0x3e} && bytes[19] == std::byte{0}) {
        format = "ELF64";
    } else if (bytes.size() >= 64 && bytes[0] == std::byte{'M'} && bytes[1] == std::byte{'Z'}) {
        const auto pe = std::to_integer<std::uint32_t>(bytes[0x3c]) |
                        (std::to_integer<std::uint32_t>(bytes[0x3d]) << 8) |
                        (std::to_integer<std::uint32_t>(bytes[0x3e]) << 16) |
                        (std::to_integer<std::uint32_t>(bytes[0x3f]) << 24);
        if (pe + 26 > bytes.size() || bytes[pe] != std::byte{'P'} ||
            bytes[pe + 1] != std::byte{'E'} || bytes[pe + 2] != std::byte{0} ||
            bytes[pe + 3] != std::byte{0} || bytes[pe + 4] != std::byte{0x64} ||
            bytes[pe + 5] != std::byte{0x86} || bytes[pe + 24] != std::byte{0x0b} ||
            bytes[pe + 25] != std::byte{0x02}) {
            throw std::runtime_error("BDS PE runtime is not PE32+ x86-64");
        }
        format = "PE32+";
    } else {
        throw std::runtime_error("BDS runtime module is not supported x86-64 ELF/PE");
    }
    return {module, hex(crypto::sha256(bytes)), bytes.size(), format, "x86_64"};
}

void HookProfileRegistry::add(HookProfile profile) {
    if (profiles_.contains(profile.executable_sha256)) {
        throw std::invalid_argument("duplicate hook profile for executable hash");
    }
    profiles_.emplace(profile.executable_sha256, std::move(profile));
}

std::optional<HookProfile> HookProfileRegistry::select(const ModuleFingerprint& module) const {
    const auto found = profiles_.find(module.sha256);
    if (found == profiles_.end() || found->second.executable_size != module.size) {
        return std::nullopt;
    }
    return found->second;
}

std::optional<std::string> HookProfileValidator::validate(const HookProfile& profile,
                                                          const ModuleFingerprint& module,
                                                          std::span<const std::byte> loaded_module,
                                                          bool allow_unreviewed) {
    if (profile.schema != 1) {
        return "unsupported hook profile schema";
    }
    if (profile.executable_sha256 != module.sha256 || profile.executable_size != module.size) {
        return "BDS hash or size mismatch";
    }
    if (profile.architecture != "x86_64" || module.architecture != "x86_64") {
        return "unsupported runtime architecture";
    }
    if (!profile.production && !allow_unreviewed) {
        return "candidate hook profile is not permitted";
    }
    if ((!profile.human_reviewed || !profile.hook_harness_passed || !profile.live_tested ||
         !profile.endstone_chain_compatible) &&
        !allow_unreviewed) {
        return "hook profile evidence gates are incomplete";
    }
    if (profile.minimum_patch_length < 5 ||
        profile.expected_bytes.size() < profile.minimum_patch_length) {
        return "unsafe hook patch length";
    }
    if (profile.target_rva > loaded_module.size() ||
        profile.expected_bytes.size() > loaded_module.size() - profile.target_rva) {
        return "hook target is outside the runtime module";
    }
    const auto target = loaded_module.subspan(profile.target_rva, profile.expected_bytes.size());
    if (!std::equal(target.begin(), target.end(), profile.expected_bytes.begin())) {
        return "hook target bytes do not match the profile";
    }
    return std::nullopt;
}

bool HookManager::start(const HookProfile& profile, std::string& error) {
    if (hook_.installed()) {
        error = "native login hook is already installed";
        return false;
    }
    return hook_.install(profile, error);
}

bool HookManager::stop(std::string& error) {
    if (!hook_.installed()) {
        return true;
    }
    return hook_.uninstall(error);
}

} // namespace onistone::onibridge
