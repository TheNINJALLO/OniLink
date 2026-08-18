#include "crypto.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>

namespace onistone::onibridge::crypto {
namespace {

constexpr std::array<std::uint32_t, 64> k{
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

void transform(std::array<std::uint32_t, 8>& state, const std::byte* block) {
    std::array<std::uint32_t, 64> words{};
    for (std::size_t i = 0; i < 16; ++i) {
        words[i] = (std::to_integer<std::uint32_t>(block[i * 4]) << 24) |
                   (std::to_integer<std::uint32_t>(block[i * 4 + 1]) << 16) |
                   (std::to_integer<std::uint32_t>(block[i * 4 + 2]) << 8) |
                   std::to_integer<std::uint32_t>(block[i * 4 + 3]);
    }
    for (std::size_t i = 16; i < 64; ++i) {
        const auto s0 = std::rotr(words[i - 15], 7) ^ std::rotr(words[i - 15], 18) ^ (words[i - 15] >> 3);
        const auto s1 = std::rotr(words[i - 2], 17) ^ std::rotr(words[i - 2], 19) ^ (words[i - 2] >> 10);
        words[i] = words[i - 16] + s0 + words[i - 7] + s1;
    }
    auto [a, b, c, d, e, f, g, h] = state;
    for (std::size_t i = 0; i < 64; ++i) {
        const auto s1 = std::rotr(e, 6) ^ std::rotr(e, 11) ^ std::rotr(e, 25);
        const auto choice = (e & f) ^ (~e & g);
        const auto t1 = h + s1 + choice + k[i] + words[i];
        const auto s0 = std::rotr(a, 2) ^ std::rotr(a, 13) ^ std::rotr(a, 22);
        const auto majority = (a & b) ^ (a & c) ^ (b & c);
        const auto t2 = s0 + majority;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

} // namespace

Digest sha256(std::span<const std::byte> input) {
    std::array<std::uint32_t, 8> state{
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    std::vector<std::byte> padded(input.begin(), input.end());
    const auto bit_length = static_cast<std::uint64_t>(padded.size()) * 8;
    padded.push_back(std::byte{0x80});
    while (padded.size() % 64 != 56) padded.push_back(std::byte{0});
    for (int shift = 56; shift >= 0; shift -= 8) padded.push_back(static_cast<std::byte>((bit_length >> shift) & 0xff));
    for (std::size_t offset = 0; offset < padded.size(); offset += 64) transform(state, padded.data() + offset);
    Digest result{};
    for (std::size_t i = 0; i < state.size(); ++i) {
        result[i * 4] = static_cast<std::byte>(state[i] >> 24);
        result[i * 4 + 1] = static_cast<std::byte>(state[i] >> 16);
        result[i * 4 + 2] = static_cast<std::byte>(state[i] >> 8);
        result[i * 4 + 3] = static_cast<std::byte>(state[i]);
    }
    return result;
}

Digest hmac_sha256(std::span<const std::byte> key, std::span<const std::byte> message) {
    std::array<std::byte, 64> normalized{};
    if (key.size() > normalized.size()) {
        const auto digest = sha256(key);
        std::copy(digest.begin(), digest.end(), normalized.begin());
    } else {
        std::copy(key.begin(), key.end(), normalized.begin());
    }
    std::array<std::byte, 64> inner_key{}, outer_key{};
    for (std::size_t i = 0; i < normalized.size(); ++i) {
        inner_key[i] = normalized[i] ^ std::byte{0x36};
        outer_key[i] = normalized[i] ^ std::byte{0x5c};
    }
    std::vector<std::byte> inner(inner_key.begin(), inner_key.end());
    inner.insert(inner.end(), message.begin(), message.end());
    const auto inner_hash = sha256(inner);
    std::vector<std::byte> outer(outer_key.begin(), outer_key.end());
    outer.insert(outer.end(), inner_hash.begin(), inner_hash.end());
    return sha256(outer);
}

bool constant_time_equal(std::span<const std::byte> left, std::span<const std::byte> right) noexcept {
    std::size_t different = left.size() ^ right.size();
    const auto count = std::min(left.size(), right.size());
    for (std::size_t i = 0; i < count; ++i) different |= std::to_integer<unsigned>(left[i] ^ right[i]);
    return different == 0;
}

std::string base64url_encode(std::span<const std::byte> input) {
    static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string result;
    result.reserve((input.size() * 4 + 2) / 3);
    std::uint32_t accumulator = 0;
    int bits = 0;
    for (const auto value : input) {
        accumulator = (accumulator << 8) | std::to_integer<std::uint8_t>(value);
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            result.push_back(alphabet[(accumulator >> bits) & 0x3f]);
        }
    }
    if (bits) result.push_back(alphabet[(accumulator << (6 - bits)) & 0x3f]);
    return result;
}

std::optional<std::vector<std::byte>> base64url_decode(std::string_view input) {
    if (input.empty() || input.find('=') != std::string_view::npos || input.size() % 4 == 1) return std::nullopt;
    std::vector<std::byte> result;
    result.reserve(input.size() * 3 / 4);
    std::uint32_t accumulator = 0;
    int bits = 0;
    for (char ch : input) {
        std::uint8_t value;
        if (ch >= 'A' && ch <= 'Z') value = ch - 'A';
        else if (ch >= 'a' && ch <= 'z') value = ch - 'a' + 26;
        else if (ch >= '0' && ch <= '9') value = ch - '0' + 52;
        else if (ch == '-') value = 62;
        else if (ch == '_') value = 63;
        else return std::nullopt;
        accumulator = (accumulator << 6) | value;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            result.push_back(static_cast<std::byte>((accumulator >> bits) & 0xff));
        }
    }
    if (bits && (accumulator & ((1u << bits) - 1u)) != 0) return std::nullopt;
    return result;
}

} // namespace onistone::onibridge::crypto

