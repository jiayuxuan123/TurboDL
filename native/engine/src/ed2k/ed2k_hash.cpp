#include "ed2k_hash.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>
#include <stdexcept>

namespace td::ed2k {
namespace {

uint32_t rotate_left(uint32_t value, unsigned count) {
    return std::rotl(value, static_cast<int>(count));
}

uint32_t load_le32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
           (static_cast<uint32_t>(data[1]) << 8) |
           (static_cast<uint32_t>(data[2]) << 16) |
           (static_cast<uint32_t>(data[3]) << 24);
}

uint32_t load_be32(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) |
           (static_cast<uint32_t>(data[1]) << 16) |
           (static_cast<uint32_t>(data[2]) << 8) |
           static_cast<uint32_t>(data[3]);
}

void store_le32(uint8_t* data, uint32_t value) {
    data[0] = static_cast<uint8_t>(value);
    data[1] = static_cast<uint8_t>(value >> 8);
    data[2] = static_cast<uint8_t>(value >> 16);
    data[3] = static_cast<uint8_t>(value >> 24);
}

void store_be32(uint8_t* data, uint32_t value) {
    data[0] = static_cast<uint8_t>(value >> 24);
    data[1] = static_cast<uint8_t>(value >> 16);
    data[2] = static_cast<uint8_t>(value >> 8);
    data[3] = static_cast<uint8_t>(value);
}

void store_le64(uint8_t* data, uint64_t value) {
    for (size_t index = 0; index < 8; ++index) {
        data[index] = static_cast<uint8_t>(value >> (index * 8));
    }
}

void store_be64(uint8_t* data, uint64_t value) {
    for (size_t index = 0; index < 8; ++index) {
        data[index] = static_cast<uint8_t>(value >> ((7 - index) * 8));
    }
}

void validate_hash_size(uint64_t file_size) {
    if (file_size > kMaximumFileSize) {
        throw std::length_error("ED2K file size exceeds 256 GiB");
    }
}

size_t aich_leaf_count(uint64_t file_size) {
    validate_hash_size(file_size);
    size_t count = 0;
    for (size_t part = 0; part < part_count(file_size); ++part) {
        const auto blocks = block_count(file_size, part);
        if (blocks > std::numeric_limits<size_t>::max() - count) {
            throw std::length_error("AICH leaf count overflow");
        }
        count += blocks;
    }
    return count;
}

class AichTreeBuilder {
public:
    AichTreeBuilder(uint64_t file_size, std::span<const AichHash> leaves)
        : file_size_(file_size), leaves_(leaves) {}

    AichHash build() const {
        return node(0, file_size_, true);
    }

private:
    AichHash node(uint64_t begin, uint64_t size, bool left_branch) const {
        if (size == 0) {
            throw std::invalid_argument("AICH node cannot be empty");
        }
        if (size <= kBlockSize) {
            const auto part = begin / kPartSize;
            const auto within_part = begin - part * kPartSize;
            if (within_part % kBlockSize != 0) {
                throw std::invalid_argument("AICH leaf is not block aligned");
            }
            const auto blocks_per_part = block_count(kPartSize, 0);
            const auto index = part * blocks_per_part + within_part / kBlockSize;
            if (index >= leaves_.size()) {
                throw std::invalid_argument("AICH leaf table is incomplete");
            }
            return leaves_[static_cast<size_t>(index)];
        }

        const auto unit = size > kPartSize ? kPartSize : kBlockSize;
        auto units = (size + unit - 1) / unit;
        if (left_branch) {
            ++units;
        }
        const auto left_units = units / 2;
        const auto left_size = left_units * unit;
        if (left_units == 0 || left_size >= size) {
            throw std::invalid_argument("AICH node cannot be split");
        }
        return aich_pair_hash(
            node(begin, left_size, true),
            node(begin + left_size, size - left_size, false));
    }

    uint64_t file_size_;
    std::span<const AichHash> leaves_;
};

} // namespace

void Md4::block(std::span<const uint8_t, 64> data) {
    std::array<uint32_t, 16> words{};
    for (size_t index = 0; index < words.size(); ++index) {
        words[index] = load_le32(data.data() + index * 4);
    }

    auto a = state_[0];
    auto b = state_[1];
    auto c = state_[2];
    auto d = state_[3];

    const auto f = [](uint32_t x, uint32_t y, uint32_t z) {
        return (x & y) | (~x & z);
    };
    const auto g = [](uint32_t x, uint32_t y, uint32_t z) {
        return (x & y) | (x & z) | (y & z);
    };
    const auto h = [](uint32_t x, uint32_t y, uint32_t z) {
        return x ^ y ^ z;
    };

    constexpr unsigned first_shift[4] = {3, 7, 11, 19};
    for (size_t index = 0; index < 16; ++index) {
        const auto shift = first_shift[index & 3];
        const auto next = rotate_left(a + f(b, c, d) + words[index], shift);
        a = d;
        d = c;
        c = b;
        b = next;
    }

    constexpr unsigned second_shift[4] = {3, 5, 9, 13};
    constexpr size_t second_order[16] = {
        0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15,
    };
    for (size_t index = 0; index < 16; ++index) {
        const auto shift = second_shift[index & 3];
        const auto next = rotate_left(
            a + g(b, c, d) + words[second_order[index]] + 0x5a827999u,
            shift);
        a = d;
        d = c;
        c = b;
        b = next;
    }

    constexpr unsigned third_shift[4] = {3, 9, 11, 15};
    constexpr size_t third_order[16] = {
        0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15,
    };
    for (size_t index = 0; index < 16; ++index) {
        const auto shift = third_shift[index & 3];
        const auto next = rotate_left(
            a + h(b, c, d) + words[third_order[index]] + 0x6ed9eba1u,
            shift);
        a = d;
        d = c;
        c = b;
        b = next;
    }

    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
}

void Md4::update(std::span<const uint8_t> data) {
    if (finished_) {
        throw std::logic_error("MD4 is already finalized");
    }
    if (data.size() > std::numeric_limits<uint64_t>::max() - bytes_) {
        throw std::length_error("MD4 input is too large");
    }
    bytes_ += data.size();

    size_t offset = 0;
    if (buffered_ != 0) {
        const auto take = std::min(data.size(), buffer_.size() - buffered_);
        std::memcpy(buffer_.data() + buffered_, data.data(), take);
        buffered_ += take;
        offset += take;
        if (buffered_ == buffer_.size()) {
            block(std::span<const uint8_t, 64>(buffer_));
            buffered_ = 0;
        }
    }
    while (data.size() - offset >= 64) {
        block(std::span<const uint8_t, 64>(data.data() + offset, 64));
        offset += 64;
    }
    if (offset < data.size()) {
        buffered_ = data.size() - offset;
        std::memcpy(buffer_.data(), data.data() + offset, buffered_);
    }
}

Ed2kHash Md4::finish() {
    if (finished_) {
        throw std::logic_error("MD4 is already finalized");
    }
    finished_ = true;

    const auto bit_length = bytes_ * 8;
    buffer_[buffered_++] = 0x80;
    if (buffered_ > 56) {
        std::fill(buffer_.begin() + static_cast<std::ptrdiff_t>(buffered_),
                  buffer_.end(), 0);
        block(std::span<const uint8_t, 64>(buffer_));
        buffered_ = 0;
    }
    std::fill(buffer_.begin() + static_cast<std::ptrdiff_t>(buffered_),
              buffer_.begin() + 56, 0);
    store_le64(buffer_.data() + 56, bit_length);
    block(std::span<const uint8_t, 64>(buffer_));

    Ed2kHash digest{};
    for (size_t index = 0; index < state_.size(); ++index) {
        store_le32(digest.data() + index * 4, state_[index]);
    }
    return digest;
}

void Sha1::block(std::span<const uint8_t, 64> data) {
    std::array<uint32_t, 80> words{};
    for (size_t index = 0; index < 16; ++index) {
        words[index] = load_be32(data.data() + index * 4);
    }
    for (size_t index = 16; index < words.size(); ++index) {
        words[index] = rotate_left(
            words[index - 3] ^ words[index - 8] ^ words[index - 14] ^
                words[index - 16],
            1);
    }

    auto a = state_[0];
    auto b = state_[1];
    auto c = state_[2];
    auto d = state_[3];
    auto e = state_[4];

    for (size_t index = 0; index < words.size(); ++index) {
        uint32_t function = 0;
        uint32_t constant = 0;
        if (index < 20) {
            function = (b & c) | (~b & d);
            constant = 0x5a827999u;
        } else if (index < 40) {
            function = b ^ c ^ d;
            constant = 0x6ed9eba1u;
        } else if (index < 60) {
            function = (b & c) | (b & d) | (c & d);
            constant = 0x8f1bbcdcu;
        } else {
            function = b ^ c ^ d;
            constant = 0xca62c1d6u;
        }
        const auto next = rotate_left(a, 5) + function + e + constant +
                          words[index];
        e = d;
        d = c;
        c = rotate_left(b, 30);
        b = a;
        a = next;
    }

    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
    state_[4] += e;
}

void Sha1::update(std::span<const uint8_t> data) {
    if (finished_) {
        throw std::logic_error("SHA-1 is already finalized");
    }
    if (data.size() > std::numeric_limits<uint64_t>::max() - bytes_) {
        throw std::length_error("SHA-1 input is too large");
    }
    bytes_ += data.size();

    size_t offset = 0;
    if (buffered_ != 0) {
        const auto take = std::min(data.size(), buffer_.size() - buffered_);
        std::memcpy(buffer_.data() + buffered_, data.data(), take);
        buffered_ += take;
        offset += take;
        if (buffered_ == buffer_.size()) {
            block(std::span<const uint8_t, 64>(buffer_));
            buffered_ = 0;
        }
    }
    while (data.size() - offset >= 64) {
        block(std::span<const uint8_t, 64>(data.data() + offset, 64));
        offset += 64;
    }
    if (offset < data.size()) {
        buffered_ = data.size() - offset;
        std::memcpy(buffer_.data(), data.data() + offset, buffered_);
    }
}

AichHash Sha1::finish() {
    if (finished_) {
        throw std::logic_error("SHA-1 is already finalized");
    }
    finished_ = true;

    const auto bit_length = bytes_ * 8;
    buffer_[buffered_++] = 0x80;
    if (buffered_ > 56) {
        std::fill(buffer_.begin() + static_cast<std::ptrdiff_t>(buffered_),
                  buffer_.end(), 0);
        block(std::span<const uint8_t, 64>(buffer_));
        buffered_ = 0;
    }
    std::fill(buffer_.begin() + static_cast<std::ptrdiff_t>(buffered_),
              buffer_.begin() + 56, 0);
    store_be64(buffer_.data() + 56, bit_length);
    block(std::span<const uint8_t, 64>(buffer_));

    AichHash digest{};
    for (size_t index = 0; index < state_.size(); ++index) {
        store_be32(digest.data() + index * 4, state_[index]);
    }
    return digest;
}

Ed2kHash md4_digest(std::span<const uint8_t> data) {
    Md4 hash;
    hash.update(data);
    return hash.finish();
}

AichHash sha1_digest(std::span<const uint8_t> data) {
    Sha1 hash;
    hash.update(data);
    return hash.finish();
}

size_t part_count(uint64_t file_size) {
    validate_hash_size(file_size);
    if (file_size == 0) {
        return 0;
    }
    return static_cast<size_t>((file_size + kPartSize - 1) / kPartSize);
}

size_t hash_count(uint64_t file_size) {
    validate_hash_size(file_size);
    if (file_size < kPartSize) {
        return 0;
    }
    return static_cast<size_t>(file_size / kPartSize) + 1;
}

uint64_t part_offset(uint64_t file_size, size_t part) {
    if (part >= part_count(file_size)) {
        throw std::out_of_range("ED2K part index is out of range");
    }
    return static_cast<uint64_t>(part) * kPartSize;
}

uint64_t part_length(uint64_t file_size, size_t part) {
    const auto begin = part_offset(file_size, part);
    return std::min(kPartSize, file_size - begin);
}

size_t block_count(uint64_t file_size, size_t part) {
    const auto length = part_length(file_size, part);
    return static_cast<size_t>((length + kBlockSize - 1) / kBlockSize);
}

Ed2kHash build_ed2k_file_hash(std::span<const Ed2kHash> part_hashes) {
    if (part_hashes.empty()) {
        throw std::invalid_argument("ED2K file hash set cannot be empty");
    }
    Md4 hash;
    for (const auto& part_hash : part_hashes) {
        hash.update(part_hash);
    }
    return hash.finish();
}

void Ed2kFileHasher::update(std::span<const uint8_t> data) {
    if (data.size() > std::numeric_limits<uint64_t>::max() - size_) {
        throw std::length_error("ED2K input is too large");
    }
    if (size_ + data.size() > kMaximumFileSize) {
        throw std::length_error("ED2K file size exceeds 256 GiB");
    }

    size_t offset = 0;
    while (offset < data.size()) {
        const auto take = std::min<uint64_t>(
            data.size() - offset, kPartSize - part_bytes_);
        part_.update(data.subspan(offset, static_cast<size_t>(take)));
        offset += static_cast<size_t>(take);
        part_bytes_ += take;
        size_ += take;
        if (part_bytes_ == kPartSize) {
            full_parts_.push_back(part_.finish());
            part_ = Md4{};
            part_bytes_ = 0;
        }
    }
}

Ed2kHash Ed2kFileHasher::file_hash() const {
    if (full_parts_.empty()) {
        auto copy = part_;
        return copy.finish();
    }
    const auto parts = part_hashes();
    return build_ed2k_file_hash(parts);
}

std::vector<Ed2kHash> Ed2kFileHasher::part_hashes() const {
    if (full_parts_.empty()) {
        return {};
    }
    auto result = full_parts_;
    auto tail = part_;
    result.push_back(tail.finish());
    return result;
}

Ed2kHashSet compute_ed2k_hash_set(std::span<const uint8_t> data) {
    Ed2kFileHasher hasher;
    hasher.update(data);
    return {hasher.file_hash(), hasher.part_hashes()};
}

AichHash aich_pair_hash(const AichHash& left, const AichHash& right) {
    Sha1 hash;
    hash.update(left);
    hash.update(right);
    return hash.finish();
}

std::vector<AichHash> compute_aich_leaves(std::span<const uint8_t> data) {
    if (data.empty()) {
        throw std::invalid_argument("AICH requires a non-empty file");
    }
    validate_hash_size(data.size());

    std::vector<AichHash> leaves;
    leaves.reserve(aich_leaf_count(data.size()));
    for (size_t part = 0; part < part_count(data.size()); ++part) {
        const auto begin = static_cast<size_t>(part_offset(data.size(), part));
        const auto length = static_cast<size_t>(part_length(data.size(), part));
        size_t within_part = 0;
        while (within_part < length) {
            const auto block_length = std::min<size_t>(
                kBlockSize, length - within_part);
            leaves.push_back(sha1_digest(
                data.subspan(begin + within_part, block_length)));
            within_part += block_length;
        }
    }
    return leaves;
}

AichHash aich_root_from_leaves(
    uint64_t file_size, std::span<const AichHash> leaves) {
    if (file_size == 0) {
        throw std::invalid_argument("AICH requires a non-empty file");
    }
    const auto expected = aich_leaf_count(file_size);
    if (leaves.size() != expected) {
        throw std::invalid_argument("AICH leaf count does not match file size");
    }
    return AichTreeBuilder(file_size, leaves).build();
}

AichHash compute_aich_root(std::span<const uint8_t> data) {
    const auto leaves = compute_aich_leaves(data);
    return aich_root_from_leaves(data.size(), leaves);
}

} // namespace td::ed2k
