#include <onibridge/config.hpp>
#include <onibridge/forwarding.hpp>
#include <onibridge/login_envelope.hpp>
#include <onibridge/service.hpp>

#include <atomic>
#include <array>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

using namespace onistone::onibridge;

namespace {

int failures = 0;

void check(bool condition, const char* description) {
    if (!condition) {
        std::cerr << "FAILED: " << description << '\n';
        ++failures;
    }
}

std::string base64url(std::string_view input) {
    static constexpr char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string result;
    unsigned accumulator = 0;
    int bits = 0;
    for (const auto ch : input) {
        accumulator = (accumulator << 8) | static_cast<unsigned char>(ch);
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            result.push_back(alphabet[(accumulator >> bits) & 0x3f]);
        }
    }
    if (bits) result.push_back(alphabet[(accumulator << (6 - bits)) & 0x3f]);
    return result;
}

void append_u32_le(std::string& output, std::size_t value) {
    for (unsigned shift = 0; shift < 32; shift += 8) output.push_back(static_cast<char>((value >> shift) & 0xff));
}

void append_varuint(std::string& output, std::size_t value) {
    do {
        auto next = static_cast<unsigned char>(value & 0x7f);
        value >>= 7;
        if (value) next |= 0x80;
        output.push_back(static_cast<char>(next));
    } while (value);
}

std::string login_payload(std::string_view name, std::string_view token) {
    const auto json = std::string("{\"DeviceOS\":7,\"ThirdPartyName\":\"") + std::string(name)
        + "\",\"Nested\":{\"ignored\":[true,false,null,3.5]},\"OniForward\":\"" + std::string(token) + "\"}";
    const auto jwt = std::string("e30.") + base64url(json) + ".AA";
    const std::string auth = "{\"AuthenticationType\":2,\"Certificate\":\"\",\"Token\":\"placeholder\"}";
    std::string envelope;
    append_u32_le(envelope, auth.size());
    envelope += auth;
    append_u32_le(envelope, jwt.size());
    envelope += jwt;
    std::string result{"\0\0\x08\x78", 4};
    append_varuint(result, envelope.size());
    result += envelope;
    return result;
}

ForwardingClaims fixture() {
    return {
        .protocol_version = 2,
        .key_id = "key-2026-01",
        .proxy_id = "edge-1",
        .bridge_id = "kingdom-main",
        .backend_name = "kingdom",
        .session_id = "018f47f2-c001-7000-8000-000000000001",
        .nonce = "00112233445566778899aabbccddeeff",
        .player_name = "Alex",
        .xuid = "2533274790395904",
        .proxy_uuid = "123e4567-e89b-12d3-a456-426614174000",
        .real_ip = "2001:db8::42",
        .real_port = 54321,
        .issued_at_ms = 1'800'000'000'000,
        .expires_at_ms = 1'800'000'005'000,
    };
}

ForwardingKey key() {
    const std::string secret = "correct horse battery staple";
    std::vector<std::byte> bytes(secret.size());
    for (std::size_t i = 0; i < secret.size(); ++i) bytes[i] = static_cast<std::byte>(static_cast<unsigned char>(secret[i]));
    return {"key-2026-01", std::move(bytes)};
}

ForwardingValidation validation() {
    return {
        .expected_player_name = "aLeX",
        .expected_bridge_id = "kingdom-main",
        .expected_backend_name = "kingdom",
        .now_ms = 1'800'000'001'000,
    };
}

void token_tests() {
    const auto token = sign_forwarding_token(fixture(), key());
    check(token == "T05JRgEOAQABMgIAC2tleS0yMDI2LTAxAwAGZWRnZS0xBAAMa2luZ2RvbS1tYWluBQAHa2luZ2RvbQYAJDAxOGY0N2YyLWMwMDEtNzAwMC04MDAwLTAwMDAwMDAwMDAwMQcAIDAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmCAAEQWxleAkAEDI1MzMyNzQ3OTAzOTU5MDQKACQxMjNlNDU2Ny1lODliLTEyZDMtYTQ1Ni00MjY2MTQxNzQwMDALAAwyMDAxOmRiODo6NDIMAAU1NDMyMQ0ADTE4MDAwMDAwMDAwMDAOAA0xODAwMDAwMDA1MDAw.922WXG-qG04OJiAFAzPSlrNh4mi7LObu0V2oDdc9KX0", "C++ output matches the shared vector");
    const auto verified = verify_forwarding_token(token, {key(), std::nullopt}, validation());
    check(static_cast<bool>(verified), "valid token verifies");
    check(verified && verified.claims->xuid == "2533274790395904", "XUID survives canonical encoding");

    auto tampered = token;
    tampered[tampered.size() / 3] = tampered[tampered.size() / 3] == 'A' ? 'B' : 'A';
    check(!verify_forwarding_token(tampered, {key(), std::nullopt}, validation()), "tampered token is rejected");

    auto wrong_context = validation();
    wrong_context.expected_backend_name = "lobby";
    check(!verify_forwarding_token(token, {key(), std::nullopt}, wrong_context), "backend mismatch is rejected");

    auto expired = validation();
    expired.now_ms = fixture().expires_at_ms + expired.allowed_clock_skew_ms + 1;
    check(!verify_forwarding_token(token, {key(), std::nullopt}, expired), "expired token is rejected");

    auto future = validation();
    future.now_ms = fixture().issued_at_ms - future.allowed_clock_skew_ms - 1;
    check(!verify_forwarding_token(token, {key(), std::nullopt}, future), "future token is rejected");

    auto previous = key();
    auto active = key();
    active.id = "key-2026-02";
    active.secret[0] ^= std::byte{1};
    check(static_cast<bool>(verify_forwarding_token(token, {active, previous}, validation())), "previous rotation key verifies");
}

void replay_tests() {
    ReplayCache cache(10'000);
    auto claims = fixture();
    std::atomic_int accepted{0};
    std::vector<std::thread> threads;
    for (int i = 0; i < 32; ++i) threads.emplace_back([&] { if (cache.consume(claims, claims.issued_at_ms)) ++accepted; });
    for (auto& thread : threads) thread.join();
    check(accepted == 1, "replay identity is consumed atomically");
    check(cache.size() == 1, "replay cache stays bounded for duplicates");
}

void cidr_tests() {
    TrustedProxyMatcher matcher({"127.0.0.1/32", "10.20.0.0/16", "2001:db8::/32"});
    check(matcher.matches("127.0.0.1"), "IPv4 host CIDR matches");
    check(matcher.matches("::ffff:10.20.4.5"), "IPv4-mapped IPv6 matches IPv4 network");
    check(matcher.matches("2001:db8::feed"), "IPv6 network matches");
    check(!matcher.matches("10.21.0.1"), "outside IPv4 CIDR is rejected");
    check(!matcher.matches("not-an-ip"), "invalid socket source is rejected");
}

void secret_file_tests() {
#ifdef _WIN32
    // Windows filesystems do not expose POSIX mode bits; native Windows deployments use the
    // environment-variable secret source instead.
    return;
#else
    const auto path = std::filesystem::temp_directory_path() / "onibridge-secret-file-test.key";
    std::filesystem::remove(path);
    {
        std::ofstream output(path, std::ios::binary);
        output << "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n";
    }
    std::filesystem::permissions(
        path,
        std::filesystem::perms::owner_read | std::filesystem::perms::owner_write
            | std::filesystem::perms::group_read | std::filesystem::perms::others_read,
        std::filesystem::perm_options::replace);
    SecretSource source;
    source.restricted_file = path;
    const auto secret = load_secret(source);
    check(secret.size() == 32, "secret file loads 32 decoded bytes");
    const auto permissions = std::filesystem::status(path).permissions();
    constexpr auto exposed = std::filesystem::perms::group_all | std::filesystem::perms::others_all;
    check((permissions & exposed) == std::filesystem::perms::none,
          "secret file permissions are automatically restricted to the owner");
    std::filesystem::remove(path);
#endif
}

void service_tests() {
    OniBridgeService service("kingdom-main", "kingdom", {key(), std::nullopt}, TrustedProxyMatcher({"10.0.0.0/8"}));
    const auto token = sign_forwarding_token(fixture(), key());
    const auto accepted = service.verify_forwarded_login(
        token, "10.5.4.3", "alex", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 1'800'000'001'000);
    check(static_cast<bool>(accepted), "trusted signed forwarding identity is accepted");
    check(service.identities().by_xuid("2533274790395904").has_value(), "verified identity lookup by XUID works");
    check(!service.verify_forwarded_login(
        token, "10.5.4.3", "alex", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 1'800'000'001'001),
        "replayed service token is rejected");

    OniBridgeService untrusted("kingdom-main", "kingdom", {key(), std::nullopt}, TrustedProxyMatcher({"127.0.0.1/32"}));
    check(!untrusted.verify_forwarded_login(
        token, "10.5.4.3", "Alex", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 1'800'000'001'000),
        "valid token from an untrusted socket source is rejected");
}

void login_envelope_tests() {
    const auto token = sign_forwarding_token(fixture(), key());
    const auto packet = login_payload("Alex", token);
    const auto parsed = LoginEnvelopeParser::parse(packet);
    check(static_cast<bool>(parsed), "raw Bedrock Login packet exposes a bounded OniForward envelope");
    check(parsed && parsed.envelope->player_name == "Alex", "Login envelope preserves ThirdPartyName");
    check(parsed && parsed.envelope->forwarding_token == token, "Login envelope preserves the exact signed token");

    auto bad_length = packet;
    bad_length[4] = 1;
    check(!LoginEnvelopeParser::parse(bad_length), "Login envelope rejects a mismatched JWT length");
    check(!LoginEnvelopeParser::parse(login_payload("Alex", std::string(4'097, 'A'))),
          "Login envelope enforces the token size before verification");

    OniBridgeService service("kingdom-main", "kingdom", {key(), std::nullopt}, TrustedProxyMatcher({"10.0.0.0/8"}));
    const auto staged = service.stage_forwarded_login(token, "10.5.4.3", "Alex", 1'800'000'001'000);
    check(static_cast<bool>(staged) && service.pending_logins() == 1,
          "packet-stage verification consumes and records one transport-bound identity");
    check(!service.identities().by_xuid(fixture().xuid).has_value(),
          "staged identity is not exposed before the native authentication hook");
    const auto committed = service.consume_staged_login(
        "aLeX", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", 1'800'000'001'001);
    check(static_cast<bool>(committed) && service.pending_logins() == 0,
          "native-stage consumption binds the verified login case-insensitively");
    check(service.identities().by_xuid(fixture().xuid).has_value(),
          "identity becomes active only after native pre-storage consumption");
    check(!service.consume_staged_login("Alex", "uuid", 1'800'000'001'002),
          "a staged login is single-use independently of token replay protection");
}

} // namespace

int main() {
    token_tests();
    replay_tests();
    cidr_tests();
    secret_file_tests();
    service_tests();
    login_envelope_tests();
    if (failures) std::cerr << failures << " test(s) failed\n";
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
