#include <onibridge/login_envelope.hpp>

#include "crypto.hpp"

#include <array>
#include <cctype>
#include <cstdint>
#include <limits>
#include <string>
#include <utility>

namespace onistone::onibridge {
namespace {

LoginEnvelopeResult fail(std::string error) { return {std::nullopt, std::move(error)}; }

class Cursor final {
public:
    explicit Cursor(std::string_view input) : input_(input) {}

    [[nodiscard]] bool empty() const noexcept { return offset_ == input_.size(); }
    [[nodiscard]] std::size_t remaining() const noexcept { return input_.size() - offset_; }

    bool read_u32_le(std::uint32_t& result) {
        if (remaining() < 4) return false;
        result = static_cast<std::uint8_t>(input_[offset_])
            | (static_cast<std::uint32_t>(static_cast<std::uint8_t>(input_[offset_ + 1])) << 8)
            | (static_cast<std::uint32_t>(static_cast<std::uint8_t>(input_[offset_ + 2])) << 16)
            | (static_cast<std::uint32_t>(static_cast<std::uint8_t>(input_[offset_ + 3])) << 24);
        offset_ += 4;
        return true;
    }

    bool read_varuint(std::uint32_t& result) {
        result = 0;
        for (unsigned index = 0; index < 5; ++index) {
            if (empty()) return false;
            const auto value = static_cast<std::uint8_t>(input_[offset_++]);
            if (index == 4 && (value & 0xf0U) != 0) return false;
            result |= static_cast<std::uint32_t>(value & 0x7fU) << (index * 7U);
            if ((value & 0x80U) == 0) {
                if (index > 0 && value == 0) return false;
                return true;
            }
        }
        return false;
    }

    bool skip(std::size_t count) {
        if (count > remaining()) return false;
        offset_ += count;
        return true;
    }

    bool read(std::size_t count, std::string_view& result) {
        if (count > remaining()) return false;
        result = input_.substr(offset_, count);
        offset_ += count;
        return true;
    }

private:
    std::string_view input_;
    std::size_t offset_{};
};

class JsonCursor final {
public:
    explicit JsonCursor(std::string_view input) : input_(input) {}

    bool top_level_strings(std::string& name, std::string& token, std::string& error) {
        spaces();
        if (!take('{')) return set(error, "JWT payload is not a JSON object");
        spaces();
        if (take('}')) return set(error, "JWT payload has no identity fields");
        bool saw_name = false;
        bool saw_token = false;
        while (true) {
            std::string key;
            if (!string(key, error)) return false;
            spaces();
            if (!take(':')) return set(error, "JSON member is missing a colon");
            spaces();
            if (key == "ThirdPartyName" || key == "OniForward") {
                std::string value;
                if (!string(value, error)) return set(error, "required login field is not a JSON string");
                auto& seen = key == "ThirdPartyName" ? saw_name : saw_token;
                if (seen) return set(error, "duplicate required login field");
                seen = true;
                (key == "ThirdPartyName" ? name : token) = std::move(value);
            } else if (!value(0, error)) {
                return false;
            }
            spaces();
            if (take('}')) break;
            if (!take(',')) return set(error, "JSON object separator is invalid");
            spaces();
        }
        spaces();
        if (offset_ != input_.size()) return set(error, "JWT payload has trailing JSON data");
        if (!saw_name || !saw_token || name.empty() || token.empty()) {
            return set(error, "JWT payload is missing ThirdPartyName or OniForward");
        }
        return true;
    }

private:
    static bool set(std::string& error, std::string value) {
        error = std::move(value);
        return false;
    }

    void spaces() {
        while (offset_ < input_.size() && std::isspace(static_cast<unsigned char>(input_[offset_]))) ++offset_;
    }

    bool take(char wanted) {
        if (offset_ >= input_.size() || input_[offset_] != wanted) return false;
        ++offset_;
        return true;
    }

    bool string(std::string& output, std::string& error) {
        if (!take('"')) return set(error, "expected a JSON string");
        output.clear();
        while (offset_ < input_.size()) {
            const auto ch = static_cast<unsigned char>(input_[offset_++]);
            if (ch == '"') return true;
            if (ch < 0x20) return set(error, "JSON string contains a control byte");
            if (ch != '\\') {
                output.push_back(static_cast<char>(ch));
                continue;
            }
            if (offset_ >= input_.size()) return set(error, "truncated JSON escape");
            const auto escaped = input_[offset_++];
            switch (escaped) {
            case '"': case '\\': case '/': output.push_back(escaped); break;
            case 'b': output.push_back('\b'); break;
            case 'f': output.push_back('\f'); break;
            case 'n': output.push_back('\n'); break;
            case 'r': output.push_back('\r'); break;
            case 't': output.push_back('\t'); break;
            case 'u': {
                if (input_.size() - offset_ < 4) return set(error, "truncated JSON unicode escape");
                unsigned codepoint = 0;
                for (int index = 0; index < 4; ++index) {
                    const auto digit = input_[offset_++];
                    codepoint <<= 4;
                    if (digit >= '0' && digit <= '9') codepoint |= static_cast<unsigned>(digit - '0');
                    else if (digit >= 'a' && digit <= 'f') codepoint |= static_cast<unsigned>(digit - 'a' + 10);
                    else if (digit >= 'A' && digit <= 'F') codepoint |= static_cast<unsigned>(digit - 'A' + 10);
                    else return set(error, "invalid JSON unicode escape");
                }
                if (codepoint >= 0xd800 && codepoint <= 0xdfff) return set(error, "JSON surrogate escapes are not accepted");
                if (codepoint <= 0x7f) output.push_back(static_cast<char>(codepoint));
                else if (codepoint <= 0x7ff) {
                    output.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
                    output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
                } else {
                    output.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
                    output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
                    output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
                }
                break;
            }
            default: return set(error, "invalid JSON escape");
            }
        }
        return set(error, "unterminated JSON string");
    }

    bool literal(std::string_view wanted) {
        if (input_.substr(offset_, wanted.size()) != wanted) return false;
        offset_ += wanted.size();
        return true;
    }

    bool number() {
        const auto begin = offset_;
        if (take('-') && offset_ == input_.size()) return false;
        if (take('0')) {
            if (offset_ < input_.size() && std::isdigit(static_cast<unsigned char>(input_[offset_]))) return false;
        } else {
            const auto digits = offset_;
            while (offset_ < input_.size() && std::isdigit(static_cast<unsigned char>(input_[offset_]))) ++offset_;
            if (digits == offset_) return false;
        }
        if (take('.')) {
            const auto digits = offset_;
            while (offset_ < input_.size() && std::isdigit(static_cast<unsigned char>(input_[offset_]))) ++offset_;
            if (digits == offset_) return false;
        }
        if (offset_ < input_.size() && (input_[offset_] == 'e' || input_[offset_] == 'E')) {
            ++offset_;
            if (offset_ < input_.size() && (input_[offset_] == '+' || input_[offset_] == '-')) ++offset_;
            const auto digits = offset_;
            while (offset_ < input_.size() && std::isdigit(static_cast<unsigned char>(input_[offset_]))) ++offset_;
            if (digits == offset_) return false;
        }
        return offset_ > begin;
    }

    bool value(unsigned depth, std::string& error) {
        if (depth > 32 || offset_ >= input_.size()) return set(error, "JSON nesting or value is invalid");
        if (input_[offset_] == '"') {
            std::string ignored;
            return string(ignored, error);
        }
        if (input_[offset_] == '{') {
            ++offset_;
            spaces();
            if (take('}')) return true;
            while (true) {
                std::string key;
                if (!string(key, error)) return false;
                spaces();
                if (!take(':')) return set(error, "nested JSON object is invalid");
                spaces();
                if (!value(depth + 1, error)) return false;
                spaces();
                if (take('}')) return true;
                if (!take(',')) return set(error, "nested JSON object separator is invalid");
                spaces();
            }
        }
        if (input_[offset_] == '[') {
            ++offset_;
            spaces();
            if (take(']')) return true;
            while (true) {
                if (!value(depth + 1, error)) return false;
                spaces();
                if (take(']')) return true;
                if (!take(',')) return set(error, "JSON array separator is invalid");
                spaces();
            }
        }
        if (literal("true") || literal("false") || literal("null") || number()) return true;
        return set(error, "unsupported JSON value");
    }

    std::string_view input_;
    std::size_t offset_{};
};

} // namespace

LoginEnvelopeResult LoginEnvelopeParser::parse(
    std::string_view packet_payload,
    std::size_t maximum_packet_size,
    std::size_t maximum_token_size) {
    if (packet_payload.size() < 13 || packet_payload.size() > maximum_packet_size) {
        return fail("Login packet payload size is invalid");
    }
    Cursor packet(packet_payload);
    if (!packet.skip(4)) return fail("Login packet is missing the protocol version");
    std::uint32_t jwt_size = 0;
    if (!packet.read_varuint(jwt_size) || jwt_size != packet.remaining()) {
        return fail("Login JWT envelope length is invalid");
    }
    std::uint32_t auth_size = 0;
    if (!packet.read_u32_le(auth_size) || auth_size > packet.remaining() || !packet.skip(auth_size)) {
        return fail("Login authentication payload length is invalid");
    }
    std::uint32_t client_size = 0;
    std::string_view client_jwt;
    if (!packet.read_u32_le(client_size) || client_size != packet.remaining()
        || !packet.read(client_size, client_jwt) || !packet.empty()) {
        return fail("Login client JWT length is invalid");
    }
    const auto first = client_jwt.find('.');
    const auto second = first == std::string_view::npos ? first : client_jwt.find('.', first + 1);
    if (first == std::string_view::npos || second == std::string_view::npos
        || client_jwt.find('.', second + 1) != std::string_view::npos || second == first + 1) {
        return fail("Login client JWT framing is invalid");
    }
    const auto decoded = crypto::base64url_decode(client_jwt.substr(first + 1, second - first - 1));
    if (!decoded || decoded->empty() || decoded->size() > maximum_packet_size) {
        return fail("Login client JWT payload is not canonical Base64URL");
    }
    const std::string_view json(reinterpret_cast<const char*>(decoded->data()), decoded->size());
    LoginEnvelope envelope;
    std::string error;
    if (!JsonCursor(json).top_level_strings(envelope.player_name, envelope.forwarding_token, error)) {
        return fail(std::move(error));
    }
    if (envelope.player_name.size() > 64 || envelope.forwarding_token.size() > maximum_token_size) {
        return fail("Login identity or OniForward token exceeds policy");
    }
    return {std::move(envelope), {}};
}

} // namespace onistone::onibridge
