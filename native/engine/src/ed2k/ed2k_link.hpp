#pragma once

#include <array>
#include <compare>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace td::ed2k {

inline constexpr uint64_t kPartSize = 9'728'000;
inline constexpr uint64_t kBlockSize = 184'320;
inline constexpr uint64_t kMaximumFileSize = 256ULL << 30;

using Ed2kHash = std::array<uint8_t, 16>;
using AichHash = std::array<uint8_t, 20>;

struct Ed2kSource {
    std::array<uint8_t, 16> address{};
    uint16_t port = 0;
    bool ipv6 = false;

    bool operator==(const Ed2kSource&) const = default;
};

struct Ed2kFileLink {
    std::string name;
    uint64_t size = 0;
    Ed2kHash file_hash{};
    std::vector<Ed2kHash> part_hashes;
    std::optional<AichHash> aich_hash;
    std::vector<Ed2kSource> sources;
};

class Ed2kLinkError final : public std::invalid_argument {
public:
    explicit Ed2kLinkError(const std::string& message) : std::invalid_argument(message) {}
};

// Returns true only for a single safe file name. Separators, drive/stream
// prefixes, control characters, dot components, invalid UTF-8 and names that
// Windows would normalize or reserve are rejected.
bool safe_ed2k_file_name(std::string_view name) noexcept;

// Parses ed2k://|file|<name>|<size>|<md4>|[h=AICH|][p=hash[:hash...]|]
// [sources,address:port[,address:port...]|]/ and throws Ed2kLinkError on any
// malformed or duplicate field. It performs no network I/O.
Ed2kFileLink parse_ed2k_file_link(std::string_view link);

} // namespace td::ed2k
