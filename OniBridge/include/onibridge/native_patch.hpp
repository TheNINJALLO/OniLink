#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

namespace onistone::onibridge {

class DirectCallSiteHook final {
public:
    DirectCallSiteHook() = default;
    DirectCallSiteHook(const DirectCallSiteHook&) = delete;
    DirectCallSiteHook& operator=(const DirectCallSiteHook&) = delete;
    ~DirectCallSiteHook();

    bool install(
        void* module_base,
        std::uint64_t call_rva,
        std::uint64_t expected_destination_rva,
        void* replacement,
        std::span<const std::byte> expected_bytes,
        std::string& error);
    bool uninstall(std::string& error);

    [[nodiscard]] bool installed() const noexcept { return call_site_ != nullptr; }
    [[nodiscard]] void* original_destination() const noexcept { return original_destination_; }

private:
    std::byte* call_site_{};
    void* relay_{};
    std::size_t relay_size_{};
    void* original_destination_{};
    std::array<std::byte, 5> original_bytes_{};
    std::array<std::byte, 5> patched_bytes_{};
};

} // namespace onistone::onibridge
