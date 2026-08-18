#include <onibridge/authentication_adapter.hpp>

#include <onibridge/service.hpp>

#include <array>
#include <chrono>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>

namespace onistone::onibridge {
namespace {

std::int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string& native_string(void* object, std::size_t offset) {
    return *reinterpret_cast<std::string*>(static_cast<std::byte*>(object) + offset);
}

std::string uuid_string(const void* object, std::size_t offset) {
    std::array<std::uint64_t, 2> words{};
    std::memcpy(words.data(), static_cast<const std::byte*>(object) + offset, sizeof(words));
    std::ostringstream output;
    output << std::hex << std::setfill('0')
           << std::setw(8) << ((words[0] >> 32) & 0xffffffffULL) << '-'
           << std::setw(4) << ((words[0] >> 16) & 0xffffULL) << '-'
           << std::setw(4) << (words[0] & 0xffffULL) << '-'
           << std::setw(4) << ((words[1] >> 48) & 0xffffULL) << '-'
           << std::setw(12) << (words[1] & 0xffffffffffffULL);
    return output.str();
}

} // namespace

std::atomic<AuthenticationCallSiteAdapter*> AuthenticationCallSiteAdapter::active_{nullptr};

AuthenticationCallSiteAdapter::~AuthenticationCallSiteAdapter() {
    std::string ignored;
    uninstall(ignored);
}

bool AuthenticationCallSiteAdapter::install(
    void* executable_base,
    AuthenticationCallSiteSpec spec,
    OniBridgeService& service,
    std::string& error) {
    if (spec.native_string_size != sizeof(std::string)) {
        error = "loaded C++ standard-library string ABI differs from the reviewed BDS profile";
        return false;
    }
    if (spec.authentication_info_size == 0
        || spec.optional_engaged_offset != spec.authentication_info_size
        || spec.xuid_offset >= spec.authentication_info_size
        || spec.xbox_live_name_offset >= spec.authentication_info_size
        || spec.best_display_name_offset >= spec.authentication_info_size
        || spec.authenticated_uuid_offset + 16 > spec.authentication_info_size
        || spec.expected_call_bytes == nullptr || spec.expected_call_bytes_size < 5) {
        error = "generated authentication ABI specification is internally inconsistent";
        return false;
    }
    AuthenticationCallSiteAdapter* expected = nullptr;
    if (!active_.compare_exchange_strong(expected, this)) {
        error = "another authentication call-site adapter is already active";
        return false;
    }
    spec_ = spec;
    service_ = &service;
    if (!patch_.install(
            executable_base, spec.call_rva, spec.move_helper_rva,
            reinterpret_cast<void*>(&AuthenticationCallSiteAdapter::replacement),
            {spec.expected_call_bytes, spec.expected_call_bytes_size}, error)) {
        service_ = nullptr;
        active_.store(nullptr);
        return false;
    }
    error.clear();
    return true;
}

bool AuthenticationCallSiteAdapter::uninstall(std::string& error) {
    if (!patch_.installed()) return true;
    if (!patch_.uninstall(error)) return false;
    service_ = nullptr;
    AuthenticationCallSiteAdapter* expected = this;
    active_.compare_exchange_strong(expected, nullptr);
    return true;
}

void AuthenticationCallSiteAdapter::replacement(void* destination, void* source) noexcept {
    if (auto* adapter = active_.load()) adapter->handle(destination, source);
    else if (destination != nullptr) *static_cast<std::byte*>(destination) = std::byte{0};
}

void AuthenticationCallSiteAdapter::reject(void* destination) noexcept {
    if (destination != nullptr) {
        auto* bytes = static_cast<std::byte*>(destination);
        bytes[0] = std::byte{0};
        bytes[spec_.optional_engaged_offset] = std::byte{0};
    }
    rejected_.fetch_add(1);
}

void AuthenticationCallSiteAdapter::handle(void* destination, void* source) noexcept {
    std::string consumed_session;
    if (destination == nullptr || source == nullptr || service_ == nullptr) {
        reject(destination);
        return;
    }
    try {
        const auto best_name = native_string(source, spec_.best_display_name_offset);
        const auto xbox_name = native_string(source, spec_.xbox_live_name_offset);
        auto decision = service_->consume_staged_login(
            best_name, uuid_string(source, spec_.authenticated_uuid_offset), now_ms());
        if (!decision && !xbox_name.empty() && xbox_name != best_name) {
            decision = service_->consume_staged_login(
                xbox_name, uuid_string(source, spec_.authenticated_uuid_offset), now_ms());
        }
        if (!decision) {
            reject(destination);
            return;
        }
        consumed_session = decision.identity->session_id;
        try {
            native_string(source, spec_.xuid_offset) = decision.identity->xuid;
        } catch (...) {
            service_->identities().remove_session(decision.identity->session_id);
            throw;
        }
        using MoveHelper = void (*)(void*, void*);
        reinterpret_cast<MoveHelper>(patch_.original_destination())(destination, source);
        accepted_.fetch_add(1);
    } catch (...) {
        if (!consumed_session.empty() && service_ != nullptr) {
            service_->identities().remove_session(consumed_session);
        }
        reject(destination);
    }
}

} // namespace onistone::onibridge
