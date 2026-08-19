#pragma once

#include <array>
#include <cstddef>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace onistone::onibridge::crypto {

using Digest = std::array<std::byte, 32>;

[[nodiscard]] Digest sha256(std::span<const std::byte> input);
[[nodiscard]] Digest hmac_sha256(std::span<const std::byte> key,
                                 std::span<const std::byte> message);
[[nodiscard]] bool constant_time_equal(std::span<const std::byte> left,
                                       std::span<const std::byte> right) noexcept;
[[nodiscard]] std::string base64url_encode(std::span<const std::byte> input);
[[nodiscard]] std::optional<std::vector<std::byte>> base64url_decode(std::string_view input);

} // namespace onistone::onibridge::crypto
