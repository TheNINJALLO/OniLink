#include <onibridge/control.hpp>

#include "../forwarding/crypto.hpp"

#include <onibridge/forwarding.hpp>

#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <thread>
#include <unordered_map>
#include <unordered_set>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
using socket_handle = SOCKET;
constexpr socket_handle invalid_socket = INVALID_SOCKET;
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
using socket_handle = int;
constexpr socket_handle invalid_socket = -1;
#endif

namespace onistone::onibridge {
namespace {

using Clock = std::chrono::system_clock;

void close_socket(socket_handle socket) noexcept {
    if (socket == invalid_socket)
        return;
#ifdef _WIN32
    closesocket(socket);
#else
    close(socket);
#endif
}

std::int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch())
        .count();
}

std::span<const std::byte> bytes(std::string_view value) {
    return {reinterpret_cast<const std::byte*>(value.data()), value.size()};
}

std::string escape_json(std::string_view value) {
    std::string output;
    output.reserve(value.size() + 8);
    for (const unsigned char ch : value) {
        switch (ch) {
        case '"':
            output += "\\\"";
            break;
        case '\\':
            output += "\\\\";
            break;
        case '\b':
            output += "\\b";
            break;
        case '\f':
            output += "\\f";
            break;
        case '\n':
            output += "\\n";
            break;
        case '\r':
            output += "\\r";
            break;
        case '\t':
            output += "\\t";
            break;
        default:
            if (ch < 0x20)
                throw std::runtime_error("control JSON contains a forbidden control character");
            output.push_back(static_cast<char>(ch));
        }
    }
    return output;
}

class FlatJsonParser {
  public:
    explicit FlatJsonParser(std::string_view input) : input_(input) {}

    std::unordered_map<std::string, std::string> parse() {
        space();
        take('{');
        space();
        std::unordered_map<std::string, std::string> result;
        if (peek('}')) {
            ++position_;
            return result;
        }
        while (true) {
            auto key = string();
            space();
            take(':');
            space();
            auto value =
                peek('"') ? std::string(1, '\1') + string() : std::string(1, '\2') + integer();
            if (!result.emplace(std::move(key), std::move(value)).second)
                throw std::runtime_error("duplicate control JSON field");
            space();
            if (peek('}')) {
                ++position_;
                break;
            }
            take(',');
            space();
        }
        space();
        if (position_ != input_.size())
            throw std::runtime_error("trailing control JSON data");
        return result;
    }

  private:
    std::string string() {
        take('"');
        std::string result;
        while (position_ < input_.size()) {
            const char ch = input_[position_++];
            if (ch == '"')
                return result;
            if (static_cast<unsigned char>(ch) < 0x20)
                throw std::runtime_error("invalid control JSON string");
            if (ch != '\\') {
                result.push_back(ch);
                continue;
            }
            if (position_ >= input_.size())
                throw std::runtime_error("unterminated control JSON escape");
            const char escaped = input_[position_++];
            switch (escaped) {
            case '"':
                result.push_back('"');
                break;
            case '\\':
                result.push_back('\\');
                break;
            case '/':
                result.push_back('/');
                break;
            case 'b':
                result.push_back('\b');
                break;
            case 'f':
                result.push_back('\f');
                break;
            case 'n':
                result.push_back('\n');
                break;
            case 'r':
                result.push_back('\r');
                break;
            case 't':
                result.push_back('\t');
                break;
            default:
                throw std::runtime_error("unsupported control JSON escape");
            }
        }
        throw std::runtime_error("unterminated control JSON string");
    }

    std::string integer() {
        const auto start = position_;
        if (peek('-'))
            ++position_;
        while (position_ < input_.size() && input_[position_] >= '0' && input_[position_] <= '9')
            ++position_;
        if (position_ == start || (position_ == start + 1 && input_[start] == '-'))
            throw std::runtime_error("control JSON value must be string or integer");
        return std::string(input_.substr(start, position_ - start));
    }

    void space() {
        while (position_ < input_.size() &&
               (input_[position_] == ' ' || input_[position_] == '\t' ||
                input_[position_] == '\r' || input_[position_] == '\n'))
            ++position_;
    }

    void take(char expected) {
        if (position_ >= input_.size() || input_[position_++] != expected)
            throw std::runtime_error("malformed control JSON");
    }
    bool peek(char expected) const {
        return position_ < input_.size() && input_[position_] == expected;
    }

    std::string_view input_;
    std::size_t position_{};
};

std::string required(const std::unordered_map<std::string, std::string>& values,
                     std::string_view key,
                     std::size_t maximum) {
    const auto found = values.find(std::string(key));
    if (found == values.end() || found->second.size() < 2 || found->second.front() != '\1' ||
        found->second.size() - 1 > maximum ||
        found->second.find_first_of("\r\n") != std::string::npos)
        throw std::runtime_error("missing or invalid control envelope field");
    return found->second.substr(1);
}

std::int64_t integer(const std::unordered_map<std::string, std::string>& values,
                     std::string_view key) {
    const auto found = values.find(std::string(key));
    if (found == values.end() || found->second.size() < 2 || found->second.front() != '\2')
        throw std::runtime_error("control envelope integer type is invalid");
    const auto text = found->second.substr(1);
    std::int64_t result{};
    const auto [end, error] = std::from_chars(text.data(), text.data() + text.size(), result);
    if (error != std::errc{} || end != text.data() + text.size())
        throw std::runtime_error("control envelope integer is invalid");
    return result;
}

bool read_exact(socket_handle socket, std::byte* output, std::size_t size) {
    std::size_t offset = 0;
    while (offset < size) {
#ifdef _WIN32
        const auto read = recv(socket,
                               reinterpret_cast<char*>(output + offset),
                               static_cast<int>(std::min<std::size_t>(size - offset, INT_MAX)),
                               0);
#else
        const auto read = recv(socket, output + offset, size - offset, 0);
#endif
        if (read <= 0)
            return false;
        offset += static_cast<std::size_t>(read);
    }
    return true;
}

bool write_exact(socket_handle socket, const std::byte* input, std::size_t size) {
    std::size_t offset = 0;
    while (offset < size) {
#ifdef _WIN32
        const auto written = send(socket,
                                  reinterpret_cast<const char*>(input + offset),
                                  static_cast<int>(std::min<std::size_t>(size - offset, INT_MAX)),
                                  0);
#else
        const auto written = send(socket, input + offset, size - offset, 0);
#endif
        if (written <= 0)
            return false;
        offset += static_cast<std::size_t>(written);
    }
    return true;
}

std::string read_frame(socket_handle socket, std::size_t maximum) {
    std::array<std::byte, 4> header{};
    if (!read_exact(socket, header.data(), header.size()))
        return {};
    const auto length = (std::to_integer<std::uint32_t>(header[0]) << 24) |
                        (std::to_integer<std::uint32_t>(header[1]) << 16) |
                        (std::to_integer<std::uint32_t>(header[2]) << 8) |
                        std::to_integer<std::uint32_t>(header[3]);
    if (length == 0 || length > maximum)
        throw std::runtime_error("control frame length is outside configured bounds");
    std::string frame(length, '\0');
    if (!read_exact(socket, reinterpret_cast<std::byte*>(frame.data()), frame.size()))
        throw std::runtime_error("truncated control frame");
    return frame;
}

bool write_frame(socket_handle socket, std::string_view frame, std::size_t maximum) {
    if (frame.empty() || frame.size() > maximum || frame.size() > UINT32_MAX)
        return false;
    const auto length = static_cast<std::uint32_t>(frame.size());
    const std::array<std::byte, 4> header{static_cast<std::byte>(length >> 24),
                                          static_cast<std::byte>(length >> 16),
                                          static_cast<std::byte>(length >> 8),
                                          static_cast<std::byte>(length)};
    return write_exact(socket, header.data(), header.size()) &&
           write_exact(socket, reinterpret_cast<const std::byte*>(frame.data()), frame.size());
}

struct Envelope {
    std::string key_id, request_id, idempotency_key, nonce, bridge_id, backend, target_xuid, action,
        encoded_payload, signature;
    std::int64_t timestamp{};
};

Envelope parse_envelope(std::string_view frame) {
    auto values = FlatJsonParser(frame).parse();
    static const std::unordered_set<std::string> fields{"version",
                                                        "keyId",
                                                        "requestId",
                                                        "idempotencyKey",
                                                        "timestamp",
                                                        "nonce",
                                                        "bridgeId",
                                                        "backend",
                                                        "targetXuid",
                                                        "action",
                                                        "payload",
                                                        "signature"};
    if (values.size() != fields.size())
        throw std::runtime_error("control envelope field set is invalid");
    for (const auto& [key, unused] : values)
        if (!fields.contains(key))
            throw std::runtime_error("control envelope contains an unknown field");
    if (integer(values, "version") != 1)
        throw std::runtime_error("unsupported control protocol version");
    return {required(values, "keyId", 64),
            required(values, "requestId", 36),
            required(values, "idempotencyKey", 128),
            required(values, "nonce", 128),
            required(values, "bridgeId", 64),
            required(values, "backend", 64),
            required(values, "targetXuid", 32),
            required(values, "action", 64),
            required(values, "payload", 1'048'576),
            required(values, "signature", 128),
            integer(values, "timestamp")};
}

std::string signature_input(const Envelope& envelope) {
    return "ONICTL/1\n" + envelope.key_id + "\n" + envelope.request_id + "\n" +
           envelope.idempotency_key + "\n" + std::to_string(envelope.timestamp) + "\n" +
           envelope.nonce + "\n" + envelope.bridge_id + "\n" + envelope.backend + "\n" +
           envelope.target_xuid + "\n" + envelope.action + "\n" + envelope.encoded_payload;
}

std::string idempotency_fingerprint(const Envelope& envelope) {
    return envelope.target_xuid + "\n" + envelope.action + "\n" + envelope.encoded_payload;
}

std::string response_frame(const ControlConfig& config,
                           std::span<const std::byte> secret,
                           const Envelope& request,
                           const ControlResult& result,
                           std::uint64_t revision) {
    static const std::unordered_set<std::string> allowed{"QUEUED",
                                                         "VALIDATING",
                                                         "SENT",
                                                         "ACCEPTED",
                                                         "EXECUTING",
                                                         "CONFIRMED",
                                                         "PARTIAL",
                                                         "REJECTED",
                                                         "UNSUPPORTED",
                                                         "FAILED",
                                                         "TIMED_OUT",
                                                         "CANCELLED"};
    const auto status = allowed.contains(result.status) ? result.status : "FAILED";
    const auto encoded_payload = crypto::base64url_encode(bytes(result.payload_json));
    const auto timestamp = now_ms();
    const auto input = "ONICTL/1-RESPONSE\n" + config.key_id + "\n" + request.request_id + "\n" +
                       status + "\n" + std::to_string(timestamp) + "\n" + config.bridge_id + "\n" +
                       config.backend_name + "\n" + std::to_string(revision) + "\n" +
                       encoded_payload;
    const auto signature = crypto::base64url_encode(crypto::hmac_sha256(secret, bytes(input)));
    return "{\"version\":1,\"keyId\":\"" + escape_json(config.key_id) + "\",\"requestId\":\"" +
           escape_json(request.request_id) + "\",\"status\":\"" + status +
           "\",\"timestamp\":" + std::to_string(timestamp) + ",\"bridgeId\":\"" +
           escape_json(config.bridge_id) + "\",\"backend\":\"" + escape_json(config.backend_name) +
           "\",\"capabilityRevision\":" + std::to_string(revision) + ",\"payload\":\"" +
           encoded_payload + "\",\"signature\":\"" + signature + "\"}";
}

std::string capabilities_json(const ControlConfig& config, const ControlCapabilities& capability) {
    std::string actions = "[";
    for (std::size_t i = 0; i < capability.supported_actions.size(); ++i) {
        if (i)
            actions += ',';
        actions += "{\"action\":\"" + escape_json(capability.supported_actions[i]) +
                   "\",\"payloadVersion\":1}";
    }
    actions += ']';
    return "{\"controlProtocolVersion\":1,\"oniBridgeVersion\":\"" +
           escape_json(capability.onibridge_version) + "\",\"backend\":\"" +
           escape_json(config.backend_name) + "\",\"bridgeId\":\"" + escape_json(config.bridge_id) +
           "\",\"bdsVersion\":\"" + escape_json(capability.bds_version) +
           "\",\"endstoneVersion\":\"" + escape_json(capability.endstone_version) +
           "\",\"operatingSystem\":\"" + escape_json(capability.operating_system) +
           "\",\"executableHash\":\"" + escape_json(capability.executable_hash) +
           "\",\"activeProfile\":\"" + escape_json(capability.active_profile) +
           "\",\"profileReviewStatus\":\"" + escape_json(capability.profile_review_status) +
           "\",\"supportedActions\":" + actions +
           ",\"maximumRequestSize\":" + std::to_string(config.max_frame_bytes) +
           ",\"maximumBlockRegionSize\":" + std::to_string(capability.maximum_block_region_size) +
           ",\"maximumInventoryStackCount\":" +
           std::to_string(capability.maximum_inventory_stack_count) +
           ",\"tlsActive\":false,\"revision\":" + std::to_string(capability.revision) + '}';
}

std::string peer_address(const sockaddr_storage& address) {
    char output[INET6_ADDRSTRLEN]{};
    if (address.ss_family == AF_INET) {
        const auto* ipv4 = reinterpret_cast<const sockaddr_in*>(&address);
        if (inet_ntop(AF_INET, &ipv4->sin_addr, output, sizeof(output)))
            return output;
    } else if (address.ss_family == AF_INET6) {
        const auto* ipv6 = reinterpret_cast<const sockaddr_in6*>(&address);
        if (inet_ntop(AF_INET6, &ipv6->sin6_addr, output, sizeof(output)))
            return output;
    }
    return {};
}

} // namespace

class ControlServer::Impl final {
  public:
    Impl(ControlConfig config,
         std::vector<std::byte> secret,
         ControlCapabilities capability,
         ControlDispatcher dispatcher)
        : config_(std::move(config)), secret_(std::move(secret)),
          capability_(std::move(capability)), dispatcher_(std::move(dispatcher)),
          trusted_(config_.trusted_proxy_cidrs) {
        if (const auto error = config_.validate())
            throw std::invalid_argument(*error);
        if (secret_.size() < 32 || !dispatcher_)
            throw std::invalid_argument("control secret and dispatcher are required");
    }

    ~Impl() {
        stop();
    }

    void start() {
        if (!config_.enabled || running_.exchange(true))
            return;
#ifdef _WIN32
        WSADATA data{};
        if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
            running_ = false;
            throw std::runtime_error("cannot initialize Windows sockets for OniControl");
        }
#endif
        try {
            listener_ = bind_listener();
            accept_thread_ = std::jthread([this](std::stop_token token) { accept_loop(token); });
        } catch (...) {
            running_ = false;
#ifdef _WIN32
            WSACleanup();
#endif
            throw;
        }
    }

    void stop() noexcept {
        if (!running_.exchange(false))
            return;
        if (accept_thread_.joinable())
            accept_thread_.request_stop();
#ifdef _WIN32
        shutdown(listener_, SD_BOTH);
#else
        shutdown(listener_, SHUT_RDWR);
#endif
        close_socket(listener_);
        listener_ = invalid_socket;
        if (accept_thread_.joinable())
            accept_thread_.join();
        {
            std::lock_guard lock(clients_mutex_);
            for (auto socket : client_sockets_) {
#ifdef _WIN32
                shutdown(socket, SD_BOTH);
#else
                shutdown(socket, SHUT_RDWR);
#endif
            }
        }
        workers_.clear();
        {
            std::lock_guard lock(clients_mutex_);
            client_sockets_.clear();
        }
        std::fill(secret_.begin(), secret_.end(), std::byte{});
#ifdef _WIN32
        WSACleanup();
#endif
    }

    bool running() const noexcept {
        return running_;
    }

  private:
    socket_handle bind_listener() {
        sockaddr_storage address{};
        socklen_t length{};
        int family{};
        if (config_.listen_host.find(':') != std::string::npos) {
            family = AF_INET6;
            auto* ipv6 = reinterpret_cast<sockaddr_in6*>(&address);
            ipv6->sin6_family = AF_INET6;
            ipv6->sin6_port = htons(config_.listen_port);
            if (inet_pton(AF_INET6, config_.listen_host.c_str(), &ipv6->sin6_addr) != 1)
                throw std::runtime_error("control listen_host must be a literal address");
            length = sizeof(*ipv6);
        } else {
            family = AF_INET;
            auto* ipv4 = reinterpret_cast<sockaddr_in*>(&address);
            ipv4->sin_family = AF_INET;
            ipv4->sin_port = htons(config_.listen_port);
            if (inet_pton(AF_INET, config_.listen_host.c_str(), &ipv4->sin_addr) != 1)
                throw std::runtime_error("control listen_host must be a literal address");
            length = sizeof(*ipv4);
        }
        socket_handle listener = socket(family, SOCK_STREAM, IPPROTO_TCP);
        if (listener == invalid_socket)
            throw std::runtime_error("cannot create OniControl listener socket");
        int reuse = 1;
        setsockopt(listener,
                   SOL_SOCKET,
                   SO_REUSEADDR,
                   reinterpret_cast<const char*>(&reuse),
                   sizeof(reuse));
        if (bind(listener, reinterpret_cast<sockaddr*>(&address), length) != 0 ||
            listen(listener, static_cast<int>(config_.max_connections)) != 0) {
            close_socket(listener);
            throw std::runtime_error("cannot bind OniControl listener");
        }
        return listener;
    }

    void accept_loop(std::stop_token token) {
        while (!token.stop_requested() && running_) {
            sockaddr_storage peer{};
            socklen_t peer_length = sizeof(peer);
            const auto client = accept(listener_, reinterpret_cast<sockaddr*>(&peer), &peer_length);
            if (client == invalid_socket) {
                if (running_)
                    continue;
                break;
            }
            const auto address = peer_address(peer);
            if (!trusted_.matches(address) ||
                active_connections_.load() >= config_.max_connections) {
                close_socket(client);
                continue;
            }
            active_connections_.fetch_add(1);
            {
                std::lock_guard lock(clients_mutex_);
                client_sockets_.insert(client);
            }
            prune_workers();
            auto done = std::make_shared<std::atomic_bool>(false);
            workers_.push_back(Worker{done, std::jthread([this, client, done](std::stop_token) {
                                          serve(client);
                                          {
                                              std::lock_guard lock(clients_mutex_);
                                              client_sockets_.erase(client);
                                          }
                                          close_socket(client);
                                          active_connections_.fetch_sub(1);
                                          done->store(true);
                                      })});
        }
    }

    void prune_workers() {
        workers_.erase(std::remove_if(workers_.begin(),
                                      workers_.end(),
                                      [](Worker& worker) { return worker.done->load(); }),
                       workers_.end());
    }

    void serve(socket_handle client) noexcept {
        try {
            while (running_) {
                auto frame = read_frame(client, config_.max_frame_bytes);
                if (frame.empty())
                    return;
                auto request = parse_envelope(frame);
                auto result = authenticate_and_dispatch(request);
                auto response =
                    response_frame(config_, secret_, request, result, capability_.revision);
                if (!write_frame(client, response, config_.max_frame_bytes))
                    return;
            }
        } catch (...) {
            // Authentication failures deliberately close the connection without an oracle response.
        }
    }

    ControlResult authenticate_and_dispatch(const Envelope& envelope) {
        if (envelope.key_id != config_.key_id || envelope.bridge_id != config_.bridge_id ||
            envelope.backend != config_.backend_name)
            throw std::runtime_error("control envelope scope mismatch");
        const auto current = now_ms();
        if (std::llabs(current - envelope.timestamp) > config_.clock_skew_seconds * 1'000)
            throw std::runtime_error("stale control envelope");
        const auto supplied = crypto::base64url_decode(envelope.signature);
        const auto expected = crypto::hmac_sha256(secret_, bytes(signature_input(envelope)));
        if (!supplied || !crypto::constant_time_equal(*supplied, expected))
            throw std::runtime_error("invalid control signature");
        const auto nonce = crypto::base64url_decode(envelope.nonce);
        if (!nonce || nonce->size() < 16 || nonce->size() > 64)
            throw std::runtime_error("invalid control nonce");
        auto payload = crypto::base64url_decode(envelope.encoded_payload);
        if (!payload || payload->size() > config_.max_frame_bytes)
            throw std::runtime_error("invalid control payload");
        std::string payload_json(reinterpret_cast<const char*>(payload->data()), payload->size());
        if (payload_json.empty() || payload_json.front() != '{' || payload_json.back() != '}')
            throw std::runtime_error("control payload must be a JSON object");
        {
            std::lock_guard lock(state_mutex_);
            prune(current);
            if (!nonces_.emplace(envelope.nonce, current + config_.replay_retention_seconds * 1'000)
                     .second)
                throw std::runtime_error("replayed control nonce");
            if (const auto found = idempotency_.find(envelope.idempotency_key);
                found != idempotency_.end()) {
                if (found->second.fingerprint != idempotency_fingerprint(envelope))
                    throw std::runtime_error(
                        "control idempotency key was reused for a different request");
                return found->second.result;
            }
        }
        if (envelope.action == "GET_CAPABILITIES" || envelope.action == "CAPABILITIES")
            return cache(envelope.idempotency_key,
                         {"CONFIRMED", capabilities_json(config_, capability_)},
                         current,
                         idempotency_fingerprint(envelope));
        if (envelope.action == "PING" || envelope.action == "HEARTBEAT")
            return cache(envelope.idempotency_key,
                         {"CONFIRMED", "{\"healthy\":true}"},
                         current,
                         idempotency_fingerprint(envelope));
        if (in_flight_.fetch_add(1) >= config_.max_in_flight) {
            in_flight_.fetch_sub(1);
            return {"REJECTED", "{\"reason\":\"control in-flight limit reached\"}"};
        }
        struct CompletionState {
            std::mutex mutex;
            std::condition_variable changed;
            std::optional<ControlResult> result;
        };
        auto state = std::make_shared<CompletionState>();
        try {
            dispatcher_(ControlRequest{envelope.request_id,
                                       envelope.idempotency_key,
                                       envelope.target_xuid,
                                       envelope.action,
                                       std::move(payload_json)},
                        [state](ControlResult result) {
                            std::lock_guard lock(state->mutex);
                            if (!state->result) {
                                state->result = std::move(result);
                                state->changed.notify_one();
                            }
                        });
        } catch (...) {
            in_flight_.fetch_sub(1);
            return {"FAILED", "{\"reason\":\"dispatcher rejected the request\"}"};
        }
        std::unique_lock lock(state->mutex);
        const auto ready = state->changed.wait_for(
            lock, std::chrono::seconds(30), [&] { return state->result.has_value(); });
        in_flight_.fetch_sub(1);
        if (!ready)
            return {"TIMED_OUT", "{\"reason\":\"server-thread action timed out\"}"};
        return cache(envelope.idempotency_key,
                     std::move(*state->result),
                     current,
                     idempotency_fingerprint(envelope));
    }

    ControlResult
    cache(std::string key, ControlResult result, std::int64_t current, std::string fingerprint) {
        std::lock_guard lock(state_mutex_);
        idempotency_[std::move(key)] = CachedResult{
            result, current + config_.replay_retention_seconds * 1'000, std::move(fingerprint)};
        return result;
    }

    void prune(std::int64_t current) {
        for (auto it = nonces_.begin(); it != nonces_.end();)
            it = it->second < current ? nonces_.erase(it) : std::next(it);
        for (auto it = idempotency_.begin(); it != idempotency_.end();)
            it = it->second.expires_at < current ? idempotency_.erase(it) : std::next(it);
        const auto maximum = config_.max_in_flight * 128;
        if (nonces_.size() > maximum || idempotency_.size() > maximum)
            throw std::runtime_error("control replay cache capacity reached");
    }

    struct CachedResult {
        ControlResult result;
        std::int64_t expires_at{};
        std::string fingerprint;
    };

    struct Worker {
        std::shared_ptr<std::atomic_bool> done;
        std::jthread thread;
    };

    ControlConfig config_;
    std::vector<std::byte> secret_;
    ControlCapabilities capability_;
    ControlDispatcher dispatcher_;
    TrustedProxyMatcher trusted_;
    std::atomic_bool running_{false};
    std::atomic_size_t active_connections_{0};
    std::atomic_size_t in_flight_{0};
    socket_handle listener_{invalid_socket};
    std::jthread accept_thread_;
    std::vector<Worker> workers_;
    std::mutex clients_mutex_;
    std::unordered_set<socket_handle> client_sockets_;
    std::mutex state_mutex_;
    std::unordered_map<std::string, std::int64_t> nonces_;
    std::unordered_map<std::string, CachedResult> idempotency_;
};

ControlServer::ControlServer(ControlConfig config,
                             std::vector<std::byte> secret,
                             ControlCapabilities capabilities,
                             ControlDispatcher dispatcher)
    : impl_(std::make_unique<Impl>(
          std::move(config), std::move(secret), std::move(capabilities), std::move(dispatcher))) {}

ControlServer::~ControlServer() = default;
void ControlServer::start() {
    impl_->start();
}
void ControlServer::stop() noexcept {
    impl_->stop();
}
bool ControlServer::running() const noexcept {
    return impl_->running();
}

} // namespace onistone::onibridge
