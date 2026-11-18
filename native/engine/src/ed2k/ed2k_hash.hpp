#pragma once

#include "ed2k_link.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace td::ed2k {

class Md4 {
public:
    Md4() = default;

    void update(std::span<const uint8_t> data);
    Ed2kHash finish();

private:
    void block(std::span<const uint8_t, 64> data);

    std::array<uint32_t, 4> state_{
        0x67452301u,
        0xefcdab89u,
        0x98badcfeu,
        0x10325476u,
    };
    std::array<uint8_t, 64> buffer_{};
    size_t buffered_ = 0;
    uint64_t bytes_ = 0;
    bool finished_ = false;
};

class Sha1 {
public:
    Sha1() = default;

    void update(std::span<const uint8_t> data);
    AichHash finish();

private:
    void block(std::span<const uint8_t, 64> data);

    std::array<uint32_t, 5> state_{
        0x67452301u,
        0xefcdab89u,
        0x98badcfeu,
        0x10325476u,
        0xc3d2e1f0u,
    };
    std::array<uint8_t, 64> buffer_{};
    size_t buffered_ = 0;
    uint64_t bytes_ = 0;
    bool finished_ = false;
};

Ed2kHash md4_digest(std::span<const uint8_t> data);
AichHash sha1_digest(std::span<const uint8_t> data);

size_t part_count(uint64_t file_size);
size_t hash_count(uint64_t file_size);
uint64_t part_offset(uint64_t file_size, size_t part);
uint64_t part_length(uint64_t file_size, size_t part);
size_t block_count(uint64_t file_size, size_t part);

// The file hash of a multi-part ED2K file is MD4 of the concatenated part
// hashes. Exact part multiples retain an empty trailing part hash.
Ed2kHash build_ed2k_file_hash(std::span<const Ed2kHash> part_hashes);

struct Ed2kHashSet {
    Ed2kHash file_hash{};
    std::vector<Ed2kHash> part_hashes;
};

class Ed2kFileHasher {
public:
    void update(std::span<const uint8_t> data);

    Ed2kHash file_hash() const;
    std::vector<Ed2kHash> part_hashes() const;
    uint64_t size() const noexcept { return size_; }

private:
    Md4 part_;
    uint64_t size_ = 0;
    uint64_t part_bytes_ = 0;
    std::vector<Ed2kHash> full_parts_;
};

Ed2kHashSet compute_ed2k_hash_set(std::span<const uint8_t> data);

AichHash aich_pair_hash(const AichHash& left, const AichHash& right);

// AICH hashes SHA-1 leaves in ED2K block units. Leaves never cross part
// boundaries; the tree switches to part-sized units above one part and uses
// eMule's odd-unit-on-a-left-branch split rule.
std::vector<AichHash> compute_aich_leaves(std::span<const uint8_t> data);
AichHash aich_root_from_leaves(uint64_t file_size, std::span<const AichHash> leaves);
AichHash compute_aich_root(std::span<const uint8_t> data);

} // namespace td::ed2k
