#include <onibridge/forwarding.hpp>

#include <algorithm>
#include <charconv>
#include <fstream>
#include <stdexcept>
#include <system_error>

namespace onistone::onibridge {
namespace {

bool safe_identifier(std::string_view value) {
    return !value.empty() && value.size() <= 64 &&
           std::all_of(value.begin(), value.end(), [](unsigned char ch) {
               return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                      (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
           });
}

bool canonical_uuid(std::string_view value) {
    if (value.size() != 36)
        return false;
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (value[index] != '-')
                return false;
        } else if (!((value[index] >= '0' && value[index] <= '9') ||
                     (value[index] >= 'a' && value[index] <= 'f'))) {
            return false;
        }
    }
    return true;
}

std::optional<std::uint64_t> boot_epoch(std::string_view value) {
    if (!canonical_uuid(value))
        return std::nullopt;
    std::array<char, 16> digits{};
    std::size_t output = 0;
    for (std::size_t index = 0; index <= 17; ++index) {
        if (value[index] != '-')
            digits[output++] = value[index];
    }
    std::uint64_t epoch = 0;
    const auto parsed = std::from_chars(digits.data(), digits.data() + digits.size(), epoch, 16);
    if (parsed.ec != std::errc{} || parsed.ptr != digits.data() + digits.size() || epoch == 0)
        return std::nullopt;
    return epoch;
}

std::vector<std::string> split_tabs(std::string_view line) {
    std::vector<std::string> fields;
    std::size_t start = 0;
    while (start <= line.size()) {
        const auto tab = line.find('\t', start);
        fields.emplace_back(
            line.substr(start, tab == std::string_view::npos ? line.size() - start : tab - start));
        if (tab == std::string_view::npos)
            break;
        start = tab + 1;
    }
    return fields;
}

} // namespace

ForwardingSequenceGuard::ForwardingSequenceGuard(std::filesystem::path state_file)
    : state_file_(std::move(state_file)) {
    load();
}

bool ForwardingSequenceGuard::consume(const ForwardingClaims& claims, std::string& error) {
    if (claims.protocol_version != kOniForwardProtocolVersion)
        return true;
    const auto incoming_epoch = boot_epoch(claims.proxy_boot_id);
    if (!incoming_epoch) {
        error = "OniForward proxy boot identity has no valid monotonic epoch";
        return false;
    }
    std::scoped_lock lock(mutex_);
    auto previous = states_;
    auto& state = states_[claims.proxy_id];
    bool persist = false;
    if (state.current_boot_id.empty()) {
        state.current_boot_id = claims.proxy_boot_id;
        state.highest_sequence = claims.sequence;
        state.recent_sequences.insert(claims.sequence);
        persist = true;
    } else if (state.current_boot_id != claims.proxy_boot_id) {
        if (state.retired_boot_ids.contains(claims.proxy_boot_id)) {
            error = "OniForward proxy boot identity was already retired";
            return false;
        }
        const auto current_epoch = boot_epoch(state.current_boot_id);
        if (!current_epoch || *incoming_epoch <= *current_epoch) {
            error = "OniForward proxy boot identity is not newer than persisted proxy state";
            return false;
        }
        state.retired_boot_ids.insert(state.current_boot_id);
        state.current_boot_id = claims.proxy_boot_id;
        state.highest_sequence = claims.sequence;
        state.restored_sequence_floor = 0;
        state.recent_sequences.clear();
        state.recent_sequences.insert(claims.sequence);
        persist = true;
    } else {
        if (claims.sequence <= state.restored_sequence_floor) {
            error = "OniForward v3 sequence predates persisted replay state";
            return false;
        }
        if (state.recent_sequences.contains(claims.sequence)) {
            error = "OniForward v3 sequence was replayed";
            return false;
        }
        if (state.highest_sequence > kReorderingWindow &&
            claims.sequence <= state.highest_sequence - kReorderingWindow) {
            error = "OniForward v3 sequence is outside the reordering window";
            return false;
        }
        state.recent_sequences.insert(claims.sequence);
        if (claims.sequence > state.highest_sequence) {
            state.highest_sequence = claims.sequence;
            persist = true;
        }
        if (state.highest_sequence > kReorderingWindow) {
            const auto floor = state.highest_sequence - kReorderingWindow;
            std::erase_if(state.recent_sequences,
                          [floor](std::uint64_t value) { return value <= floor; });
        }
    }
    if (persist) {
        try {
            persist_locked();
        } catch (const std::exception& exception) {
            states_ = std::move(previous);
            error = std::string("cannot persist OniForward v3 replay state: ") + exception.what();
            return false;
        }
    }
    return true;
}

void ForwardingSequenceGuard::load() {
    if (state_file_.empty() || !std::filesystem::exists(state_file_))
        return;
    std::ifstream input(state_file_, std::ios::binary);
    if (!input)
        throw std::runtime_error("cannot open OniForward sequence state");
    std::string line;
    while (std::getline(input, line)) {
        if (!line.empty() && line.back() == '\r')
            line.pop_back();
        if (line.empty())
            continue;
        const auto fields = split_tabs(line);
        if (fields.size() != 4 || (fields[0] != "C" && fields[0] != "R") ||
            !safe_identifier(fields[1]) || !canonical_uuid(fields[2])) {
            throw std::runtime_error("OniForward sequence state is malformed");
        }
        auto& state = states_[fields[1]];
        if (fields[0] == "R") {
            if (fields[3] != "-")
                throw std::runtime_error("OniForward retired boot state is malformed");
            state.retired_boot_ids.insert(fields[2]);
            continue;
        }
        std::uint64_t highest = 0;
        const auto parsed =
            std::from_chars(fields[3].data(), fields[3].data() + fields[3].size(), highest);
        if (parsed.ec != std::errc{} || parsed.ptr != fields[3].data() + fields[3].size() ||
            highest == 0 || !state.current_boot_id.empty()) {
            throw std::runtime_error("OniForward current boot state is malformed");
        }
        state.current_boot_id = fields[2];
        state.highest_sequence = highest;
        state.restored_sequence_floor = highest;
    }
    if (!input.eof())
        throw std::runtime_error("cannot read OniForward sequence state");
    for (const auto& [proxy_id, state] : states_) {
        static_cast<void>(proxy_id);
        if (state.current_boot_id.empty() || !boot_epoch(state.current_boot_id) ||
            state.retired_boot_ids.contains(state.current_boot_id)) {
            throw std::runtime_error("OniForward sequence state has no valid current boot");
        }
    }
}

void ForwardingSequenceGuard::persist_locked() const {
    if (state_file_.empty())
        return;
    if (!state_file_.parent_path().empty())
        std::filesystem::create_directories(state_file_.parent_path());
    auto temporary = state_file_;
    temporary += ".tmp";
    {
        std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
        if (!output)
            throw std::runtime_error("cannot create sequence-state temporary file");
        for (const auto& [proxy_id, state] : states_) {
            if (!state.current_boot_id.empty())
                output << "C\t" << proxy_id << '\t' << state.current_boot_id << '\t'
                       << state.highest_sequence << '\n';
            for (const auto& retired : state.retired_boot_ids)
                output << "R\t" << proxy_id << '\t' << retired << "\t-\n";
        }
        output.flush();
        if (!output)
            throw std::runtime_error("cannot write sequence-state temporary file");
    }
#ifndef _WIN32
    std::filesystem::permissions(temporary,
                                 std::filesystem::perms::owner_read |
                                     std::filesystem::perms::owner_write,
                                 std::filesystem::perm_options::replace);
#endif
    std::error_code error;
    std::filesystem::rename(temporary, state_file_, error);
    if (error) {
        std::filesystem::remove(state_file_, error);
        error.clear();
        std::filesystem::rename(temporary, state_file_, error);
    }
    if (error)
        throw std::runtime_error("cannot install sequence-state file: " + error.message());
}

} // namespace onistone::onibridge
