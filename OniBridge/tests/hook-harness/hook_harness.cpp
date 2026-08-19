#include <onibridge/authentication_adapter.hpp>
#include <onibridge/forwarding.hpp>
#include <onibridge/hooks.hpp>
#include <onibridge/native_patch.hpp>
#include <onibridge/service.hpp>

#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif

using namespace onistone::onibridge;

namespace {
int failures = 0;
void check(bool value, const char* message) {
    if (!value) {
        std::cerr << "FAILED: " << message << '\n';
        ++failures;
    }
}

class FixtureHook final : public NativeLoginHook {
  public:
    bool install(const HookProfile&, std::string&) override {
        active_ = true;
        return true;
    }
    bool uninstall(std::string&) override {
        active_ = false;
        return true;
    }
    [[nodiscard]] bool installed() const noexcept override {
        return active_;
    }

  private:
    bool active_{};
};

extern "C" int replacement_fixture() {
    return 9;
}

std::vector<std::byte> fixture_secret() {
    std::vector<std::byte> result(32);
    for (std::size_t index = 0; index < result.size(); ++index)
        result[index] = static_cast<std::byte>(index + 1);
    return result;
}

std::int64_t current_time_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}

void direct_call_patch_test() {
    constexpr std::size_t page_size = 4096;
#ifdef _WIN32
    auto* page = static_cast<std::byte*>(
        VirtualAlloc(nullptr, page_size, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE));
#else
    auto* page = static_cast<std::byte*>(mmap(nullptr,
                                              page_size,
                                              PROT_READ | PROT_WRITE | PROT_EXEC,
                                              MAP_PRIVATE | MAP_ANONYMOUS,
                                              -1,
                                              0));
    if (page == MAP_FAILED)
        page = nullptr;
#endif
    check(page != nullptr, "executable hook fixture allocates");
    if (page == nullptr)
        return;
    constexpr std::size_t helper_offset = 0x100;
    const std::array<std::byte, 6> helper{std::byte{0xb8},
                                          std::byte{0x07},
                                          std::byte{0},
                                          std::byte{0},
                                          std::byte{0},
                                          std::byte{0xc3}};
    std::copy(helper.begin(), helper.end(), page + helper_offset);
    std::array<std::byte, 14> caller{std::byte{0x48},
                                     std::byte{0x83},
                                     std::byte{0xec},
                                     std::byte{0x28}, // shadow space and alignment
                                     std::byte{0xe8},
                                     std::byte{0},
                                     std::byte{0},
                                     std::byte{0},
                                     std::byte{0},
                                     std::byte{0x48},
                                     std::byte{0x83},
                                     std::byte{0xc4},
                                     std::byte{0x28},
                                     std::byte{0xc3}};
    const auto displacement = static_cast<std::int32_t>(helper_offset - 9);
    std::memcpy(caller.data() + 5, &displacement, sizeof(displacement));
    std::copy(caller.begin(), caller.end(), page);
#ifdef _WIN32
    FlushInstructionCache(GetCurrentProcess(), page, page_size);
#else
    __builtin___clear_cache(reinterpret_cast<char*>(page),
                            reinterpret_cast<char*>(page + page_size));
#endif
    using Function = int (*)();
    auto function = reinterpret_cast<Function>(page);
    check(function() == 7, "fixture direct CALL reaches its original helper");

    DirectCallSiteHook hook;
    std::string error;
    check(hook.install(page,
                       4,
                       helper_offset,
                       reinterpret_cast<void*>(&replacement_fixture),
                       std::span<const std::byte>(caller.data() + 4, 5),
                       error),
          "reviewed direct CALL is replaced through a near relay");
    check(function() == 9, "near relay reaches the replacement callback");
    check(!hook.install(page,
                        4,
                        helper_offset,
                        reinterpret_cast<void*>(&replacement_fixture),
                        std::span<const std::byte>(caller.data() + 4, 5),
                        error),
          "double installation is rejected");
    check(hook.uninstall(error), "direct CALL hook rolls back only its own bytes");
    check(function() == 7, "rollback restores the original helper call");
#ifdef _WIN32
    VirtualFree(page, 0, MEM_RELEASE);
#else
    munmap(page, page_size);
#endif
}

void authentication_adapter_test() {
    constexpr std::size_t page_size = 4096;
#ifdef _WIN32
    auto* page = static_cast<std::byte*>(
        VirtualAlloc(nullptr, page_size, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE));
#else
    auto* page = static_cast<std::byte*>(mmap(nullptr,
                                              page_size,
                                              PROT_READ | PROT_WRITE | PROT_EXEC,
                                              MAP_PRIVATE | MAP_ANONYMOUS,
                                              -1,
                                              0));
    if (page == MAP_FAILED)
        page = nullptr;
#endif
    check(page != nullptr, "authentication adapter fixture allocates");
    if (page == nullptr)
        return;

    const auto string_size = sizeof(std::string);
    const auto auth_size_unaligned = 10 * string_size + 8 + string_size + 16 + 2;
    const auto auth_size = (auth_size_unaligned + 7) & ~std::size_t{7};
    const auto xbox_offset = 6 * string_size;
    const auto best_offset = 9 * string_size;
    const auto uuid_offset = 10 * string_size + 8 + string_size;
    constexpr std::size_t helper_offset = 0x100;

    std::array<std::byte, 8> helper{};
    helper[0] = std::byte{0xc6};
#ifdef _WIN32
    helper[1] = std::byte{0x81}; // mov byte ptr [rcx+disp32], 1
#else
    helper[1] = std::byte{0x87}; // mov byte ptr [rdi+disp32], 1
#endif
    const auto engaged_offset = static_cast<std::uint32_t>(auth_size);
    std::memcpy(helper.data() + 2, &engaged_offset, sizeof(engaged_offset));
    helper[6] = std::byte{1};
    helper[7] = std::byte{0xc3};
    std::copy(helper.begin(), helper.end(), page + helper_offset);

    std::array<std::byte, 14> caller{std::byte{0x48},
                                     std::byte{0x83},
                                     std::byte{0xec},
                                     std::byte{0x28},
                                     std::byte{0xe8},
                                     std::byte{0},
                                     std::byte{0},
                                     std::byte{0},
                                     std::byte{0},
                                     std::byte{0x48},
                                     std::byte{0x83},
                                     std::byte{0xc4},
                                     std::byte{0x28},
                                     std::byte{0xc3}};
    const auto displacement = static_cast<std::int32_t>(helper_offset - 9);
    std::memcpy(caller.data() + 5, &displacement, sizeof(displacement));
    std::copy(caller.begin(), caller.end(), page);
#ifdef _WIN32
    FlushInstructionCache(GetCurrentProcess(), page, page_size);
#else
    __builtin___clear_cache(reinterpret_cast<char*>(page),
                            reinterpret_cast<char*>(page + page_size));
#endif

    const auto now = current_time_ms();
    ForwardingClaims claims{
        .protocol_version = 2,
        .key_id = "key-1",
        .proxy_id = "proxy-1",
        .bridge_id = "bridge-1",
        .backend_name = "backend-1",
        .session_id = "018f47f2-c001-7000-8000-000000000002",
        .nonce = "ffeeddccbbaa99887766554433221100",
        .player_name = "Alex",
        .xuid = "2533274790395904",
        .proxy_uuid = "123e4567-e89b-12d3-a456-426614174000",
        .real_ip = "203.0.113.42",
        .real_port = 54321,
        .issued_at_ms = now,
        .expires_at_ms = now + 5'000,
    };
    ForwardingKey signing_key{"key-1", fixture_secret()};
    OniBridgeService service("bridge-1",
                             "backend-1",
                             {signing_key, std::nullopt},
                             TrustedProxyMatcher({"127.0.0.1/32"}));
    const auto token = sign_forwarding_token(claims, signing_key);
    check(static_cast<bool>(service.stage_forwarded_login(token, "127.0.0.1", "Alex", now)),
          "valid packet-stage identity is ready for the native hook fixture");

    struct alignas(16) NativeBuffer {
        std::array<std::byte, 512> bytes{};
    } source, destination;
    auto* xuid = new (source.bytes.data()) std::string();
    auto* xbox = new (source.bytes.data() + xbox_offset) std::string("Alex");
    auto* best = new (source.bytes.data() + best_offset) std::string("Alex");
    std::array<std::uint64_t, 2> uuid{0x123e4567e89b12d3ULL, 0xa456426614174000ULL};
    std::memcpy(source.bytes.data() + uuid_offset, uuid.data(), sizeof(uuid));

    const AuthenticationCallSiteSpec spec{
        .call_rva = 4,
        .move_helper_rva = helper_offset,
        .native_string_size = string_size,
        .authentication_info_size = auth_size,
        .xuid_offset = 0,
        .xbox_live_name_offset = xbox_offset,
        .best_display_name_offset = best_offset,
        .authenticated_uuid_offset = uuid_offset,
        .optional_engaged_offset = auth_size,
        .expected_call_bytes = caller.data() + 4,
        .expected_call_bytes_size = 5,
    };
    AuthenticationCallSiteAdapter adapter;
    std::string error;
    check(adapter.install(page, spec, service, error),
          "authentication adapter installs on its exact call profile");
    using AuthenticationCall = void (*)(void*, void*);
    auto invoke = reinterpret_cast<AuthenticationCall>(page);
    invoke(destination.bytes.data(), source.bytes.data());
    check(*xuid == claims.xuid,
          "native adapter substitutes the verified XUID before the helper returns");
    check(destination.bytes[auth_size] == std::byte{1},
          "verified authentication result remains engaged");
    check(service.identities().by_xuid(claims.xuid).has_value(),
          "verified native identity is committed once");

    NativeBuffer direct_source, direct_destination;
    auto* direct_xuid = new (direct_source.bytes.data()) std::string();
    auto* direct_xbox = new (direct_source.bytes.data() + xbox_offset) std::string("DirectJoin");
    auto* direct_best = new (direct_source.bytes.data() + best_offset) std::string("DirectJoin");
    invoke(direct_destination.bytes.data(), direct_source.bytes.data());
    check(direct_destination.bytes[auth_size] == std::byte{0},
          "unverified direct join returns an empty auth result");
    check(direct_xuid->empty(), "unverified direct join never receives a substituted XUID");
    check(adapter.accepted() == 1 && adapter.rejected() == 1,
          "adapter diagnostics count exact accept/reject outcomes");
    check(adapter.uninstall(error), "authentication adapter restores the original helper call");

    direct_best->~basic_string();
    direct_xbox->~basic_string();
    direct_xuid->~basic_string();
    best->~basic_string();
    xbox->~basic_string();
    xuid->~basic_string();
#ifdef _WIN32
    VirtualFree(page, 0, MEM_RELEASE);
#else
    munmap(page, page_size);
#endif
}
} // namespace

int main() {
    direct_call_patch_test();
    authentication_adapter_test();
    std::vector<std::byte> image(64, std::byte{0x90});
    image[16] = std::byte{0x55};
    image[17] = std::byte{0x48};
    image[18] = std::byte{0x89};
    image[19] = std::byte{0xe5};
    image[20] = std::byte{0x90};
    ModuleFingerprint module{"fixture", "fixture-hash", image.size(), "ELF64", "x86_64"};
    HookProfile profile{
        .schema = 1,
        .bds_version = "fixture",
        .executable_sha256 = "fixture-hash",
        .executable_size = image.size(),
        .operating_system = "linux",
        .architecture = "x86_64",
        .abi = "sysv-amd64",
        .target_function_role = "pre-storage identity substitution",
        .target_section = ".text",
        .target_rva = 16,
        .expected_bytes =
            {std::byte{0x55}, std::byte{0x48}, std::byte{0x89}, std::byte{0xe5}, std::byte{0x90}},
        .minimum_patch_length = 5,
        .production = false,
        .human_reviewed = false,
        .hook_harness_passed = false,
        .live_tested = false,
        .endstone_chain_compatible = false,
    };
    check(!HookProfileValidator::validate(profile, module, image, true),
          "candidate fixture validates only in harness mode");
    check(HookProfileValidator::validate(profile, module, image).has_value(),
          "candidate is rejected for production");
    auto changed = image;
    changed[16] = std::byte{0xcc};
    check(HookProfileValidator::validate(profile, module, changed, true).has_value(),
          "expected-byte mismatch fails");
    auto wrong_hash = module;
    wrong_hash.sha256 = "different";
    check(HookProfileValidator::validate(profile, wrong_hash, image, true).has_value(),
          "runtime hash mismatch fails");

    HookProfileRegistry registry;
    registry.add(profile);
    check(registry.select(module).has_value(), "profile registry selects by exact hash and size");
    check(!registry.select(wrong_hash).has_value(),
          "profile registry rejects a different runtime hash");
    bool duplicate_rejected = false;
    try {
        registry.add(profile);
    } catch (const std::invalid_argument&) {
        duplicate_rejected = true;
    }
    check(duplicate_rejected, "duplicate profile hash is rejected");

    FixtureHook hook;
    HookManager manager(hook);
    std::string error;
    check(manager.start(profile, error) && manager.active(), "hook installs once");
    check(!manager.start(profile, error), "double install is rejected");
    check(manager.stop(error) && !manager.active(), "hook uninstalls cleanly");

    // This is the required logical chain. A generated adapter must replace this fixture with
    // instruction/trampoline evidence for an exact BDS/Endstone pair before promotion.
    const std::vector<std::string> chain{"endstone-precheck",
                                         "previous-bds-target",
                                         "endstone-postcheck",
                                         "onibridge-verify-and-substitute",
                                         "player-storage-selection"};
    check(chain[2] == "endstone-postcheck" && chain[3] == "onibridge-verify-and-substitute",
          "fixture preserves the previous Endstone chain before substitution");
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
