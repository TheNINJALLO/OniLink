#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <string_view>

namespace onistone::onibridge {

struct LoginEnvelope {
    std::string player_name;
    std::string forwarding_token;
};

struct LoginEnvelopeResult {
    std::optional<LoginEnvelope> envelope;
    std::string error;

    [[nodiscard]] explicit operator bool() const noexcept { return envelope.has_value(); }
};

class LoginEnvelopeParser final {
public:
    [[nodiscard]] static LoginEnvelopeResult parse(
        std::string_view packet_payload,
        std::size_t maximum_packet_size = 1'048'576,
        std::size_t maximum_token_size = 4'096);
};

} // namespace onistone::onibridge
