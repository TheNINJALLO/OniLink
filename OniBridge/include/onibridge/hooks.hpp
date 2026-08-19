#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace onistone::onibridge {

struct ModuleFingerprint {
    std::filesystem::path path;
    std::string sha256;
    std::uint64_t size{};
    std::string file_format;
    std::string architecture;
};

struct HookProfile {
    std::uint32_t schema{};
    std::string bds_version;
    std::string executable_sha256;
    std::uint64_t executable_size{};
    std::string operating_system;
    std::string architecture;
    std::string abi;
    std::string target_function_role;
    std::string target_section;
    std::uint64_t target_rva{};
    std::vector<std::byte> expected_bytes;
    std::size_t minimum_patch_length{};
    bool production{};
    bool human_reviewed{};
    bool hook_harness_passed{};
    bool live_tested{};
    bool endstone_chain_compatible{};
};

class BdsRuntimeDetector final {
  public:
    [[nodiscard]] static ModuleFingerprint inspect(const std::filesystem::path& module);
};

class HookProfileRegistry final {
  public:
    void add(HookProfile profile);
    [[nodiscard]] std::optional<HookProfile> select(const ModuleFingerprint& module) const;

  private:
    std::unordered_map<std::string, HookProfile> profiles_;
};

class HookProfileValidator final {
  public:
    [[nodiscard]] static std::optional<std::string>
    validate(const HookProfile& profile,
             const ModuleFingerprint& module,
             std::span<const std::byte> loaded_module,
             bool allow_unreviewed = false);
};

class NativeLoginHook {
  public:
    virtual ~NativeLoginHook() = default;
    virtual bool install(const HookProfile& profile, std::string& error) = 0;
    virtual bool uninstall(std::string& error) = 0;
    [[nodiscard]] virtual bool installed() const noexcept = 0;
};

class HookManager final {
  public:
    explicit HookManager(NativeLoginHook& hook) : hook_(hook) {}
    bool start(const HookProfile& profile, std::string& error);
    bool stop(std::string& error);
    [[nodiscard]] bool active() const noexcept {
        return hook_.installed();
    }

  private:
    NativeLoginHook& hook_;
};

} // namespace onistone::onibridge
