#pragma once

#include <onibridge/config.hpp>

#include <cstddef>
#include <functional>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace onistone::onibridge {

struct ControlRequest {
    std::string request_id;
    std::string idempotency_key;
    std::string target_xuid;
    std::string action;
    std::string payload_json;
};

struct ControlResult {
    std::string status;
    std::string payload_json{"{}"};
};

struct ControlCapabilities {
    std::string onibridge_version;
    std::string bds_version;
    std::string endstone_version;
    std::string operating_system;
    std::string executable_hash;
    std::string active_profile;
    std::string profile_review_status;
    std::vector<std::string> supported_actions;
    std::size_t maximum_block_region_size{32'768};
    std::size_t maximum_inventory_stack_count{2'304};
    std::uint64_t revision{1};
};

using ControlCompletion = std::function<void(ControlResult)>;
using ControlDispatcher = std::function<void(ControlRequest, ControlCompletion)>;

/** Bounded authenticated ONICTL/1 server. No Endstone API is touched on its worker threads. */
class ControlServer final {
  public:
    ControlServer(ControlConfig config,
                  std::vector<std::byte> secret,
                  ControlCapabilities capabilities,
                  ControlDispatcher dispatcher);
    ~ControlServer();
    ControlServer(const ControlServer&) = delete;
    ControlServer& operator=(const ControlServer&) = delete;

    void start();
    void stop() noexcept;
    [[nodiscard]] bool running() const noexcept;

  private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace onistone::onibridge
