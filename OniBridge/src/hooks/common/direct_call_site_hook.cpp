#include <onibridge/native_patch.hpp>

#include <algorithm>
#include <array>
#include <bit>
#include <cerrno>
#include <climits>
#include <cstring>
#include <limits>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif

namespace onistone::onibridge {
namespace {

constexpr std::size_t kRelayCodeSize = 14;

bool relative_displacement(const void* instruction_after, const void* destination, std::int32_t& result) {
    const auto difference = reinterpret_cast<std::intptr_t>(destination)
        - reinterpret_cast<std::intptr_t>(instruction_after);
    if (difference < std::numeric_limits<std::int32_t>::min()
        || difference > std::numeric_limits<std::int32_t>::max()) return false;
    result = static_cast<std::int32_t>(difference);
    return true;
}

#ifdef _WIN32
void* allocate_near(const void* target, std::size_t& allocation_size, std::string& error) {
    SYSTEM_INFO info{};
    GetSystemInfo(&info);
    allocation_size = info.dwPageSize;
    const auto granularity = static_cast<std::uintptr_t>(info.dwAllocationGranularity);
    const auto center = reinterpret_cast<std::uintptr_t>(target) & ~(granularity - 1U);
    constexpr std::uintptr_t reach = 0x7fff0000ULL;
    for (std::uintptr_t delta = granularity; delta <= reach; delta += granularity) {
        const std::array<std::uintptr_t, 2> candidates{
            center >= delta ? center - delta : 0,
            center <= std::numeric_limits<std::uintptr_t>::max() - delta ? center + delta : 0,
        };
        for (const auto address : candidates) {
            if (address == 0) continue;
            auto* memory = VirtualAlloc(reinterpret_cast<void*>(address), allocation_size,
                                        MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
            if (memory == nullptr) continue;
            std::int32_t unused = 0;
            if (relative_displacement(static_cast<const std::byte*>(target) + 5, memory, unused)) return memory;
            VirtualFree(memory, 0, MEM_RELEASE);
        }
    }
    error = "unable to allocate a relay within the x64 direct-call range";
    return nullptr;
}

bool protect_relay(void* memory, std::size_t size, std::string& error) {
    DWORD previous = 0;
    if (!VirtualProtect(memory, size, PAGE_EXECUTE_READ, &previous)) {
        error = "VirtualProtect could not make the relay executable";
        return false;
    }
    FlushInstructionCache(GetCurrentProcess(), memory, size);
    return true;
}

bool write_code(void* destination, std::span<const std::byte> bytes, std::string& error) {
    DWORD previous = 0;
    if (!VirtualProtect(destination, bytes.size(), PAGE_EXECUTE_READWRITE, &previous)) {
        error = "VirtualProtect could not unlock the reviewed call site";
        return false;
    }
    std::memcpy(destination, bytes.data(), bytes.size());
    FlushInstructionCache(GetCurrentProcess(), destination, bytes.size());
    DWORD ignored = 0;
    if (!VirtualProtect(destination, bytes.size(), previous, &ignored)) {
        error = "VirtualProtect could not restore call-site protection";
        return false;
    }
    return true;
}

void release_relay(void* memory, std::size_t) {
    if (memory != nullptr) VirtualFree(memory, 0, MEM_RELEASE);
}
#else
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

void* allocate_near(const void* target, std::size_t& allocation_size, std::string& error) {
    const auto page = static_cast<std::uintptr_t>(sysconf(_SC_PAGESIZE));
    allocation_size = page;
    const auto center = reinterpret_cast<std::uintptr_t>(target) & ~(page - 1U);
    constexpr std::uintptr_t reach = 0x7fff0000ULL;
    constexpr std::uintptr_t step = 0x10000ULL;
    for (std::uintptr_t delta = step; delta <= reach; delta += step) {
        const std::array<std::uintptr_t, 2> candidates{
            center >= delta ? center - delta : 0,
            center <= std::numeric_limits<std::uintptr_t>::max() - delta ? center + delta : 0,
        };
        for (const auto address : candidates) {
            if (address == 0) continue;
            void* memory = mmap(reinterpret_cast<void*>(address), allocation_size,
                                PROT_READ | PROT_WRITE,
                                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
            if (memory == MAP_FAILED) continue;
            std::int32_t unused = 0;
            if (relative_displacement(static_cast<const std::byte*>(target) + 5, memory, unused)) return memory;
            munmap(memory, allocation_size);
        }
    }
    error = "unable to mmap a relay within the x64 direct-call range";
    return nullptr;
}

bool protect_relay(void* memory, std::size_t size, std::string& error) {
    if (mprotect(memory, size, PROT_READ | PROT_EXEC) != 0) {
        error = "mprotect could not make the relay executable";
        return false;
    }
    __builtin___clear_cache(static_cast<char*>(memory), static_cast<char*>(memory) + size);
    return true;
}

bool write_code(void* destination, std::span<const std::byte> bytes, std::string& error) {
    const auto page_size = static_cast<std::uintptr_t>(sysconf(_SC_PAGESIZE));
    const auto begin = reinterpret_cast<std::uintptr_t>(destination) & ~(page_size - 1U);
    const auto end = (reinterpret_cast<std::uintptr_t>(destination) + bytes.size() + page_size - 1U)
        & ~(page_size - 1U);
    if (mprotect(reinterpret_cast<void*>(begin), end - begin, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        error = "mprotect could not unlock the reviewed call site";
        return false;
    }
    std::memcpy(destination, bytes.data(), bytes.size());
    __builtin___clear_cache(static_cast<char*>(destination), static_cast<char*>(destination) + bytes.size());
    if (mprotect(reinterpret_cast<void*>(begin), end - begin, PROT_READ | PROT_EXEC) != 0) {
        error = "mprotect could not restore call-site protection";
        return false;
    }
    return true;
}

void release_relay(void* memory, std::size_t size) {
    if (memory != nullptr) munmap(memory, size);
}
#endif

} // namespace

DirectCallSiteHook::~DirectCallSiteHook() {
    std::string ignored;
    uninstall(ignored);
}

bool DirectCallSiteHook::install(
    void* module_base,
    std::uint64_t call_rva,
    std::uint64_t expected_destination_rva,
    void* replacement,
    std::span<const std::byte> expected_bytes,
    std::string& error) {
    if (installed()) {
        error = "direct call-site hook is already installed";
        return false;
    }
    if (module_base == nullptr || replacement == nullptr || expected_bytes.size() < original_bytes_.size()) {
        error = "direct call-site hook arguments are invalid";
        return false;
    }
    auto* call_site = static_cast<std::byte*>(module_base) + call_rva;
    std::copy_n(expected_bytes.begin(), original_bytes_.size(), original_bytes_.begin());
    if (!std::equal(original_bytes_.begin(), original_bytes_.end(), call_site)) {
        error = "loaded call-site bytes differ from the exact reviewed profile";
        return false;
    }
    if (original_bytes_[0] != std::byte{0xe8}) {
        error = "reviewed target is no longer a direct x64 CALL";
        return false;
    }
    std::int32_t old_displacement = 0;
    std::memcpy(&old_displacement, original_bytes_.data() + 1, sizeof(old_displacement));
    original_destination_ = call_site + 5 + old_displacement;
    if (original_destination_ != static_cast<std::byte*>(module_base) + expected_destination_rva) {
        error = "loaded CALL destination differs from the reviewed authentication helper";
        original_destination_ = nullptr;
        return false;
    }
    relay_ = allocate_near(call_site, relay_size_, error);
    if (relay_ == nullptr) {
        original_destination_ = nullptr;
        return false;
    }
    std::array<std::byte, kRelayCodeSize> relay_code{
        std::byte{0xff}, std::byte{0x25}, std::byte{0}, std::byte{0}, std::byte{0}, std::byte{0},
    };
    const auto replacement_value = reinterpret_cast<std::uintptr_t>(replacement);
    std::memcpy(relay_code.data() + 6, &replacement_value, sizeof(replacement_value));
    std::memcpy(relay_, relay_code.data(), relay_code.size());
    if (!protect_relay(relay_, relay_size_, error)) {
        release_relay(relay_, relay_size_);
        relay_ = nullptr;
        original_destination_ = nullptr;
        return false;
    }
    std::int32_t new_displacement = 0;
    if (!relative_displacement(call_site + 5, relay_, new_displacement)) {
        error = "allocated relay is outside the direct-call range";
        release_relay(relay_, relay_size_);
        relay_ = nullptr;
        original_destination_ = nullptr;
        return false;
    }
    patched_bytes_[0] = std::byte{0xe8};
    std::memcpy(patched_bytes_.data() + 1, &new_displacement, sizeof(new_displacement));
    if (!write_code(call_site, patched_bytes_, error)) {
        release_relay(relay_, relay_size_);
        relay_ = nullptr;
        original_destination_ = nullptr;
        return false;
    }
    call_site_ = call_site;
    error.clear();
    return true;
}

bool DirectCallSiteHook::uninstall(std::string& error) {
    if (!installed()) return true;
    if (!std::equal(patched_bytes_.begin(), patched_bytes_.end(), call_site_)) {
        error = "call site changed after OniBridge installation; refusing destructive rollback";
        return false;
    }
    if (!write_code(call_site_, original_bytes_, error)) return false;
    release_relay(relay_, relay_size_);
    call_site_ = nullptr;
    relay_ = nullptr;
    relay_size_ = 0;
    original_destination_ = nullptr;
    error.clear();
    return true;
}

} // namespace onistone::onibridge
