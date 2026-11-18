#include "ed2k_link.hpp"

#include "ed2k_hash.hpp"

#include <algorithm>
#include <array>
#include <charconv>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#endif

namespace td::ed2k {
namespace {

constexpr size_t kMaximumLinkLength = 64 * 1024;

bool ascii_space(char value) {
    return value == ' ' || value == '\t' || value == '\r' ||
           value == '\n' || value == '\f' || value == '\v';
}

std::string_view trim_ascii(std::string_view text) {
    while (!text.empty() && ascii_space(text.front())) {
        text.remove_prefix(1);
    }
    while (!text.empty() && ascii_space(text.back())) {
        text.remove_suffix(1);
    }
    return text;
}

char ascii_lower(char value) {
    return value >= 'A' && value <= 'Z'
               ? static_cast<char>(value - 'A' + 'a')
               : value;
}

bool starts_with_ascii_case_insensitive(
    std::string_view text, std::string_view prefix) {
    if (text.size() < prefix.size()) {
        return false;
    }
    for (size_t index = 0; index < prefix.size(); ++index) {
        if (ascii_lower(text[index]) != ascii_lower(prefix[index])) {
            return false;
        }
    }
    return true;
}

bool ascii_hex(char value) {
    return (value >= '0' && value <= '9') ||
           (value >= 'a' && value <= 'f') ||
           (value >= 'A' && value <= 'F');
}

uint8_t hex_value(char value) {
    if (value >= '0' && value <= '9') {
        return static_cast<uint8_t>(value - '0');
    }
    return static_cast<uint8_t>(ascii_lower(value) - 'a' + 10);
}

std::string percent_decode(std::string_view text) {
    std::string output;
    output.reserve(text.size());
    for (size_t index = 0; index < text.size(); ++index) {
        if (text[index] != '%') {
            output.push_back(text[index]);
            continue;
        }
        if (index + 2 >= text.size() || !ascii_hex(text[index + 1]) ||
            !ascii_hex(text[index + 2])) {
            throw Ed2kLinkError("invalid percent escape in file name");
        }
        output.push_back(static_cast<char>(
            (hex_value(text[index + 1]) << 4) | hex_value(text[index + 2])));
        index += 2;
    }
    return output;
}

bool reserved_windows_stem(std::string_view name) {
    const auto dot = name.find('.');
    const auto stem = name.substr(0, dot);
    if (stem == "." || stem == "..") {
        return true;
    }

    auto equals_ascii_case_insensitive = [&](std::string_view expected) {
        if (stem.size() != expected.size()) {
            return false;
        }
        for (size_t index = 0; index < stem.size(); ++index) {
            if (ascii_lower(stem[index]) != ascii_lower(expected[index])) {
                return false;
            }
        }
        return true;
    };

    if (equals_ascii_case_insensitive("con") ||
        equals_ascii_case_insensitive("prn") ||
        equals_ascii_case_insensitive("aux") ||
        equals_ascii_case_insensitive("nul") ||
        equals_ascii_case_insensitive("clock$")) {
        return true;
    }
    if (stem.size() == 4 &&
        ((ascii_lower(stem[0]) == 'c' &&
          ascii_lower(stem[1]) == 'o' &&
          ascii_lower(stem[2]) == 'm') ||
         (ascii_lower(stem[0]) == 'l' &&
          ascii_lower(stem[1]) == 'p' &&
          ascii_lower(stem[2]) == 't')) &&
        stem[3] >= '1' && stem[3] <= '9') {
        return true;
    }
    return false;
}

bool valid_utf8_file_name(std::string_view name) {
    for (size_t index = 0; index < name.size();) {
        const auto first = static_cast<uint8_t>(name[index]);
        if (first < 0x80) {
            if (first < 0x20 || first == 0x7f) {
                return false;
            }
            switch (first) {
            case '<':
            case '>':
            case ':':
            case '"':
            case '/':
            case '\\':
            case '|':
            case '?':
            case '*':
                return false;
            default:
                break;
            }
            ++index;
            continue;
        }

        size_t length = 0;
        uint32_t code_point = 0;
        uint32_t minimum = 0;
        if ((first & 0xe0) == 0xc0) {
            length = 2;
            code_point = first & 0x1f;
            minimum = 0x80;
        } else if ((first & 0xf0) == 0xe0) {
            length = 3;
            code_point = first & 0x0f;
            minimum = 0x800;
        } else if ((first & 0xf8) == 0xf0) {
            length = 4;
            code_point = first & 0x07;
            minimum = 0x10000;
        } else {
            return false;
        }
        if (index + length > name.size()) {
            return false;
        }
        for (size_t offset = 1; offset < length; ++offset) {
            const auto continuation =
                static_cast<uint8_t>(name[index + offset]);
            if ((continuation & 0xc0) != 0x80) {
                return false;
            }
            code_point = (code_point << 6) | (continuation & 0x3f);
        }
        if (code_point < minimum || code_point > 0x10ffff ||
            (code_point >= 0xd800 && code_point <= 0xdfff) ||
            (code_point >= 0x80 && code_point <= 0x9f)) {
            return false;
        }
        index += length;
    }
    return true;
}

uint64_t parse_uint64(std::string_view text, std::string_view field_name) {
    if (text.empty()) {
        throw Ed2kLinkError(std::string(field_name) + " is empty");
    }
    for (const auto value : text) {
        if (value < '0' || value > '9') {
            throw Ed2kLinkError(
                std::string(field_name) + " is not a decimal integer");
        }
    }
    uint64_t value = 0;
    const auto [end, error] =
        std::from_chars(text.data(), text.data() + text.size(), value);
    if (error != std::errc{} || end != text.data() + text.size()) {
        throw Ed2kLinkError(std::string(field_name) + " is out of range");
    }
    return value;
}

template <size_t Size>
std::array<uint8_t, Size> parse_hex(std::string_view text,
                                    std::string_view field_name) {
    if (text.size() != Size * 2) {
        throw Ed2kLinkError(
            std::string(field_name) + " must contain exactly " +
            std::to_string(Size * 2) + " hexadecimal digits");
    }
    std::array<uint8_t, Size> value{};
    for (size_t index = 0; index < Size; ++index) {
        const auto high = text[index * 2];
        const auto low = text[index * 2 + 1];
        if (!ascii_hex(high) || !ascii_hex(low)) {
            throw Ed2kLinkError(
                std::string(field_name) + " contains a non-hexadecimal digit");
        }
        value[index] =
            static_cast<uint8_t>((hex_value(high) << 4) | hex_value(low));
    }
    return value;
}

uint8_t base32_value(char value) {
    const auto lower = ascii_lower(value);
    if (lower >= 'a' && lower <= 'z') {
        return static_cast<uint8_t>(lower - 'a');
    }
    if (value >= '2' && value <= '7') {
        return static_cast<uint8_t>(value - '2' + 26);
    }
    throw Ed2kLinkError("AICH hash contains a non-base32 digit");
}

AichHash parse_aich_base32(std::string_view text) {
    if (text.size() != 32) {
        throw Ed2kLinkError("AICH hash must contain exactly 32 base32 digits");
    }
    AichHash output{};
    uint32_t accumulator = 0;
    unsigned bits = 0;
    size_t written = 0;
    for (const auto value : text) {
        accumulator = (accumulator << 5) | base32_value(value);
        bits += 5;
        if (bits >= 8) {
            bits -= 8;
            output[written++] =
                static_cast<uint8_t>(accumulator >> bits);
            accumulator &= (1u << bits) - 1u;
        }
    }
    if (written != output.size() || bits != 0 || accumulator != 0) {
        throw Ed2kLinkError("AICH hash is not canonical base32");
    }
    return output;
}

bool parse_ipv4(std::string_view text, std::array<uint8_t, 16>& address) {
    size_t begin = 0;
    for (size_t component = 0; component < 4; ++component) {
        const auto end = text.find('.', begin);
        const auto last = component == 3;
        if ((last && end != std::string_view::npos) ||
            (!last && end == std::string_view::npos)) {
            return false;
        }
        const auto part = text.substr(
            begin, last ? text.size() - begin : end - begin);
        if (part.empty() || part.size() > 3) {
            return false;
        }
        unsigned value = 0;
        for (const auto digit : part) {
            if (digit < '0' || digit > '9') {
                return false;
            }
            value = value * 10 + static_cast<unsigned>(digit - '0');
        }
        if (value > 255) {
            return false;
        }
        address[component] = static_cast<uint8_t>(value);
        if (!last) {
            begin = end + 1;
        }
    }
    return true;
}

bool parse_ipv6(std::string_view text, std::array<uint8_t, 16>& address) {
    const std::string copy(text);
#ifdef _WIN32
    WSADATA data{};
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
        return false;
    }
    const auto result = InetPtonA(AF_INET6, copy.c_str(), address.data());
    WSACleanup();
    return result == 1;
#else
    return inet_pton(AF_INET6, copy.c_str(), address.data()) == 1;
#endif
}

uint16_t parse_port(std::string_view text) {
    const auto value = parse_uint64(text, "ED2K source port");
    if (value == 0 || value > 65535) {
        throw Ed2kLinkError("ED2K source port is outside 1..65535");
    }
    return static_cast<uint16_t>(value);
}

Ed2kSource parse_source(std::string_view text) {
    if (text.empty()) {
        throw Ed2kLinkError("ED2K source list contains an empty item");
    }

    Ed2kSource source;
    std::string_view host;
    std::string_view port;
    if (text.front() == '[') {
        const auto close = text.find("]:");
        if (close == std::string_view::npos) {
            throw Ed2kLinkError("invalid bracketed IPv6 ED2K source");
        }
        host = text.substr(1, close - 1);
        port = text.substr(close + 2);
        source.ipv6 = true;
        if (host.empty() || !parse_ipv6(host, source.address)) {
            throw Ed2kLinkError("invalid IPv6 ED2K source address");
        }
    } else {
        const auto colon = text.rfind(':');
        if (colon == std::string_view::npos ||
            text.find(':') != colon) {
            throw Ed2kLinkError("ED2K source must be address:port");
        }
        host = text.substr(0, colon);
        port = text.substr(colon + 1);
        if (!parse_ipv4(host, source.address)) {
            throw Ed2kLinkError("invalid IPv4 ED2K source address");
        }
    }
    source.port = parse_port(port);
    return source;
}

void parse_sources(std::string_view text, std::vector<Ed2kSource>& output) {
    if (text.empty()) {
        throw Ed2kLinkError("ED2K sources field is empty");
    }
    size_t begin = 0;
    while (begin <= text.size()) {
        const auto end = text.find(',', begin);
        const auto item = text.substr(
            begin, end == std::string_view::npos ? text.size() - begin
                                                 : end - begin);
        output.push_back(parse_source(item));
        if (end == std::string_view::npos) {
            break;
        }
        begin = end + 1;
        if (begin == text.size()) {
            throw Ed2kLinkError("ED2K sources field has a trailing comma");
        }
    }
}

std::vector<std::string_view> split_fields(std::string_view body) {
    std::vector<std::string_view> fields;
    size_t begin = 0;
    while (true) {
        const auto end = body.find('|', begin);
        fields.push_back(body.substr(
            begin, end == std::string_view::npos ? body.size() - begin
                                                 : end - begin));
        if (end == std::string_view::npos) {
            break;
        }
        begin = end + 1;
    }
    return fields;
}

} // namespace

bool safe_ed2k_file_name(std::string_view name) noexcept {
    if (name.empty() || name == "." || name == ".." ||
        name.back() == ' ' || name.back() == '.' ||
        !valid_utf8_file_name(name)) {
        return false;
    }
    return !reserved_windows_stem(name);
}

Ed2kFileLink parse_ed2k_file_link(std::string_view link) {
    link = trim_ascii(link);
    if (link.empty() || link.size() > kMaximumLinkLength) {
        throw Ed2kLinkError("invalid ed2k file link: empty or too long");
    }
    constexpr std::string_view scheme = "ed2k://";
    if (link.size() < scheme.size() ||
        !starts_with_ascii_case_insensitive(link.substr(0, scheme.size()),
                                            scheme)) {
        throw Ed2kLinkError("invalid ed2k file link: missing ed2k:// scheme");
    }

    const auto body = link.substr(scheme.size());
    if (body.empty() || body.front() != '|') {
        throw Ed2kLinkError("invalid ed2k file link: malformed fields");
    }
    const auto fields = split_fields(body);
    if (fields.size() < 6 || !fields.front().empty() ||
        !starts_with_ascii_case_insensitive(fields[1], "file") ||
        fields[1].size() != 4 || fields.back() != "/") {
        throw Ed2kLinkError("invalid ed2k file link: not a file link");
    }

    Ed2kFileLink result;
    result.name = percent_decode(fields[2]);
    if (!safe_ed2k_file_name(result.name)) {
        throw Ed2kLinkError("invalid ed2k file link: unsafe file name");
    }

    result.size = parse_uint64(fields[3], "ED2K file size");
    if (result.size == 0 || result.size > kMaximumFileSize) {
        throw Ed2kLinkError("ED2K file size must be between 1 and 256 GiB");
    }
    result.file_hash = parse_hex<16>(fields[4], "ED2K file hash");

    bool part_hashes_seen = false;
    bool aich_seen = false;
    bool sources_seen = false;
    for (size_t index = 5; index + 1 < fields.size(); ++index) {
        const auto field = fields[index];
        if (field == "/") {
            // Standard source links append a source list after the main
            // link terminator: ...|<hash>|/|sources,1.2.3.4:4662|/
            continue;
        }
        if (field.empty()) {
            throw Ed2kLinkError("ED2K link contains an empty extension field");
        }

        if (starts_with_ascii_case_insensitive(field, "p=")) {
            if (part_hashes_seen) {
                throw Ed2kLinkError("ED2K link contains duplicate part hashes");
            }
            part_hashes_seen = true;
            const auto hashes = field.substr(2);
            if (hashes.empty()) {
                throw Ed2kLinkError("ED2K part hash list is empty");
            }
            size_t begin = 0;
            while (begin <= hashes.size()) {
                const auto end = hashes.find(':', begin);
                const auto item = hashes.substr(
                    begin, end == std::string_view::npos
                               ? hashes.size() - begin
                               : end - begin);
                result.part_hashes.push_back(
                    parse_hex<16>(item, "ED2K part hash"));
                if (end == std::string_view::npos) {
                    break;
                }
                begin = end + 1;
                if (begin == hashes.size()) {
                    throw Ed2kLinkError(
                        "ED2K part hash list has a trailing colon");
                }
            }
            const auto expected = hash_count(result.size);
            if (expected == 0 || result.part_hashes.size() != expected) {
                throw Ed2kLinkError(
                    "ED2K part hash count does not match file size");
            }
            if (build_ed2k_file_hash(result.part_hashes) != result.file_hash) {
                throw Ed2kLinkError(
                    "ED2K part hashes do not match the file hash");
            }
        } else if (starts_with_ascii_case_insensitive(field, "h=")) {
            if (aich_seen) {
                throw Ed2kLinkError("ED2K link contains duplicate AICH hash");
            }
            aich_seen = true;
            result.aich_hash = parse_aich_base32(field.substr(2));
            if (std::all_of(result.aich_hash->begin(), result.aich_hash->end(),
                            [](uint8_t value) { return value == 0; })) {
                throw Ed2kLinkError("ED2K AICH hash cannot be all zero");
            }
        } else if (starts_with_ascii_case_insensitive(field, "sources,")) {
            if (sources_seen) {
                throw Ed2kLinkError("ED2K link contains duplicate sources");
            }
            sources_seen = true;
            parse_sources(field.substr(8), result.sources);
        } else {
            throw Ed2kLinkError(
                "ED2K link contains an unsupported extension field");
        }
    }
    return result;
}

} // namespace td::ed2k
