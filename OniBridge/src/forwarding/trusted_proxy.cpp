#include <onibridge/forwarding.hpp>

#include <algorithm>
#include <charconv>
#include <stdexcept>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#endif

namespace onistone::onibridge {
namespace {

bool parse_ip(std::string_view text, std::array<std::uint8_t, 16>& output, bool& ipv4) {
#ifdef _WIN32
    static const bool winsock_ready = [] {
        WSADATA data{};
        return WSAStartup(MAKEWORD(2, 2), &data) == 0;
    }();
    if (!winsock_ready) return false;
#endif
    const std::string value(text);
    std::array<std::uint8_t, 16> v6{};
    if (inet_pton(AF_INET6, value.c_str(), v6.data()) == 1) {
        const std::array<std::uint8_t, 12> mapped{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff};
        if (std::equal(mapped.begin(), mapped.end(), v6.begin())) {
            output.fill(0); std::copy(v6.begin() + 12, v6.end(), output.begin()); ipv4 = true;
        } else { output = v6; ipv4 = false; }
        return true;
    }
    std::array<std::uint8_t, 4> v4{};
    if (inet_pton(AF_INET, value.c_str(), v4.data()) == 1) {
        output.fill(0); std::copy(v4.begin(), v4.end(), output.begin()); ipv4 = true; return true;
    }
    return false;
}

} // namespace

TrustedProxyMatcher::TrustedProxyMatcher(const std::vector<std::string>& cidrs) { for (const auto& cidr : cidrs) add(cidr); }

void TrustedProxyMatcher::add(std::string_view cidr) {
    const auto slash = cidr.rfind('/');
    if (slash == std::string_view::npos) throw std::invalid_argument("trusted proxy entry must be CIDR");
    Network network;
    if (!parse_ip(cidr.substr(0, slash), network.address, network.ipv4)) throw std::invalid_argument("invalid trusted proxy address");
    unsigned prefix = 0;
    const auto prefix_text = cidr.substr(slash + 1);
    const auto [end, error] = std::from_chars(prefix_text.data(), prefix_text.data() + prefix_text.size(), prefix);
    const auto maximum = network.ipv4 ? 32u : 128u;
    if (error != std::errc{} || end != prefix_text.data() + prefix_text.size() || prefix > maximum) throw std::invalid_argument("invalid trusted proxy prefix");
    network.prefix = static_cast<std::uint8_t>(prefix);
    networks_.push_back(network);
}

bool TrustedProxyMatcher::matches(std::string_view address) const {
    std::array<std::uint8_t, 16> candidate{};
    bool ipv4 = false;
    if (!parse_ip(address, candidate, ipv4)) return false;
    for (const auto& network : networks_) {
        if (network.ipv4 != ipv4) continue;
        const auto bytes = network.prefix / 8;
        const auto bits = network.prefix % 8;
        if (!std::equal(candidate.begin(), candidate.begin() + bytes, network.address.begin())) continue;
        if (bits == 0 || (candidate[bytes] & (0xff << (8 - bits))) == (network.address[bytes] & (0xff << (8 - bits)))) return true;
    }
    return false;
}

} // namespace onistone::onibridge
