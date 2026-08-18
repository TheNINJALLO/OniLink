#pragma once

#include <onibridge/native_patch.hpp>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>

namespace onistone::onibridge {

class OniBridgeService;

struct AuthenticationCallSiteSpec {
    std::uint64_t call_rva{};
    std::uint64_t move_helper_rva{};
    std::size_t native_string_size{};
    std::size_t authentication_info_size{};
    std::size_t xuid_offset{};
    std::size_t xbox_live_name_offset{};
    std::size_t best_display_name_offset{};
    std::size_t authenticated_uuid_offset{};
    std::size_t optional_engaged_offset{};
    const std::byte* expected_call_bytes{};
    std::size_t expected_call_bytes_size{};
};

class AuthenticationCallSiteAdapter final {
public:
    AuthenticationCallSiteAdapter() = default;
    AuthenticationCallSiteAdapter(const AuthenticationCallSiteAdapter&) = delete;
    AuthenticationCallSiteAdapter& operator=(const AuthenticationCallSiteAdapter&) = delete;
    ~AuthenticationCallSiteAdapter();

    bool install(void* executable_base, AuthenticationCallSiteSpec spec, OniBridgeService& service, std::string& error);
    bool uninstall(std::string& error);
    [[nodiscard]] bool installed() const noexcept { return patch_.installed(); }
    [[nodiscard]] std::uint64_t accepted() const noexcept { return accepted_.load(); }
    [[nodiscard]] std::uint64_t rejected() const noexcept { return rejected_.load(); }

private:
    static void replacement(void* destination, void* source) noexcept;
    void handle(void* destination, void* source) noexcept;
    void reject(void* destination) noexcept;

    static std::atomic<AuthenticationCallSiteAdapter*> active_;
    DirectCallSiteHook patch_;
    AuthenticationCallSiteSpec spec_{};
    OniBridgeService* service_{};
    std::atomic_uint64_t accepted_{0};
    std::atomic_uint64_t rejected_{0};
};

} // namespace onistone::onibridge
