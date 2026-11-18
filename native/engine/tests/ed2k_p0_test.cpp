#include "ed2k/ed2k_hash.hpp"
#include "ed2k/ed2k_link.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {

using td::ed2k::AichHash;
using td::ed2k::Ed2kHash;

void check(bool condition, std::string_view message) {
    if (!condition) {
        throw std::runtime_error(std::string(message));
    }
}

template <typename Function>
void expect_link_error(Function&& function, std::string_view message) {
    try {
        function();
    } catch (const td::ed2k::Ed2kLinkError&) {
        return;
    }
    throw std::runtime_error(
        "expected Ed2kLinkError: " + std::string(message));
}

std::string hex(std::span<const uint8_t> value) {
    constexpr char digits[] = "0123456789abcdef";
    std::string output;
    output.reserve(value.size() * 2);
    for (const auto byte : value) {
        output.push_back(digits[byte >> 4]);
        output.push_back(digits[byte & 15]);
    }
    return output;
}

template <size_t Size>
std::array<uint8_t, Size> from_hex(std::string_view text) {
    check(text.size() == Size * 2, "test hash has invalid length");
    std::array<uint8_t, Size> output{};
    const auto digit = [](char value) -> uint8_t {
        if (value >= '0' && value <= '9') {
            return static_cast<uint8_t>(value - '0');
        }
        if (value >= 'a' && value <= 'f') {
            return static_cast<uint8_t>(value - 'a' + 10);
        }
        return static_cast<uint8_t>(value - 'A' + 10);
    };
    for (size_t index = 0; index < Size; ++index) {
        output[index] = static_cast<uint8_t>(
            (digit(text[index * 2]) << 4) | digit(text[index * 2 + 1]));
    }
    return output;
}

std::vector<uint8_t> patterned_bytes(size_t size) {
    std::vector<uint8_t> output(size);
    for (size_t index = 0; index < size; ++index) {
        output[index] = static_cast<uint8_t>((index * 37 + 11) & 0xff);
    }
    return output;
}

void test_md4() {
    check(hex(td::ed2k::md4_digest({})) ==
              "31d6cfe0d16ae931b73c59d7e0c089c0",
          "MD4 empty vector");

    const std::string abc = "abc";
    check(hex(td::ed2k::md4_digest(std::span<const uint8_t>(
              reinterpret_cast<const uint8_t*>(abc.data()), abc.size()))) ==
              "a448017aaf21d8525fc10ae87aa6729d",
          "MD4 abc vector");

    const std::string message = "message digest";
    td::ed2k::Md4 incremental;
    incremental.update(std::span<const uint8_t>(
        reinterpret_cast<const uint8_t*>(message.data()), 1));
    incremental.update(std::span<const uint8_t>(
        reinterpret_cast<const uint8_t*>(message.data() + 1), 6));
    incremental.update(std::span<const uint8_t>(
        reinterpret_cast<const uint8_t*>(message.data() + 7),
        message.size() - 7));
    check(hex(incremental.finish()) ==
              "d9130a8164549fe818874806e1c7014b",
          "MD4 incremental vector");

    const std::string million(1'000'000, 'a');
    td::ed2k::Md4 large;
    for (size_t offset = 0; offset < million.size(); offset += 9973) {
        const auto count = std::min<size_t>(9973, million.size() - offset);
        large.update(std::span<const uint8_t>(
            reinterpret_cast<const uint8_t*>(million.data() + offset), count));
    }
    check(hex(large.finish()) ==
              "bbce80cc6bb65e5c6745e30d4eeca9a4",
          "MD4 million-byte vector");
}

void test_sha1() {
    check(hex(td::ed2k::sha1_digest({})) ==
              "da39a3ee5e6b4b0d3255bfef95601890afd80709",
          "SHA-1 empty vector");

    const std::string abc = "abc";
    td::ed2k::Sha1 incremental;
    incremental.update(std::span<const uint8_t>(
        reinterpret_cast<const uint8_t*>(abc.data()), 1));
    incremental.update(std::span<const uint8_t>(
        reinterpret_cast<const uint8_t*>(abc.data() + 1), 2));
    check(hex(incremental.finish()) ==
              "a9993e364706816aba3e25717850c26c9cd0d89d",
          "SHA-1 incremental vector");

    const std::string million(1'000'000, 'a');
    td::ed2k::Sha1 large;
    for (size_t offset = 0; offset < million.size(); offset += 8191) {
        const auto count = std::min<size_t>(8191, million.size() - offset);
        large.update(std::span<const uint8_t>(
            reinterpret_cast<const uint8_t*>(million.data() + offset), count));
    }
    check(hex(large.finish()) ==
              "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
          "SHA-1 million-byte vector");
}

void test_geometry() {
    using namespace td::ed2k;
    check(part_count(0) == 0, "empty file part count");
    check(part_count(kPartSize - 1) == 1, "single part below boundary");
    check(part_count(kPartSize) == 1, "exact part count");
    check(part_count(kPartSize + 1) == 2, "part count after boundary");
    check(hash_count(kPartSize - 1) == 0, "small file hash count");
    check(hash_count(kPartSize) == 2, "exact part hash count");
    check(hash_count(2 * kPartSize) == 3, "multiple-part hash count");
    check(block_count(kPartSize, 0) == 53, "full part block count");
    check(block_count(kPartSize + 1, 1) == 1,
          "partial second part block count");
    check(part_length(kPartSize + 123, 0) == kPartSize,
          "full part length");
    check(part_length(kPartSize + 123, 1) == 123,
          "partial part length");
}

void test_ed2k_hash_set() {
    using namespace td::ed2k;
    const std::string abc = "abc";
    const auto small_bytes = std::span<const uint8_t>(
        reinterpret_cast<const uint8_t*>(abc.data()), abc.size());
    const auto small = compute_ed2k_hash_set(small_bytes);
    check(small.part_hashes.empty(), "small file must not have part hashes");
    check(small.file_hash == md4_digest(small_bytes),
          "small file hash must hash the data directly");

    std::vector<uint8_t> exact(kPartSize);
    const auto exact_hashes = compute_ed2k_hash_set(exact);
    check(exact_hashes.part_hashes.size() == 2,
          "exact part must retain empty trailing hash");
    check(exact_hashes.part_hashes[0] == md4_digest(exact),
          "full part hash mismatch");
    check(exact_hashes.part_hashes[1] == md4_digest({}),
          "empty trailing part hash mismatch");
    check(exact_hashes.file_hash ==
              build_ed2k_file_hash(exact_hashes.part_hashes),
          "exact part file hash mismatch");

    std::vector<uint8_t> remainder(kPartSize + 1);
    const auto remainder_hashes = compute_ed2k_hash_set(remainder);
    check(remainder_hashes.part_hashes.size() == 2,
          "one-byte second part hash count");
    check(remainder_hashes.part_hashes[1] == md4_digest(
              std::span<const uint8_t>(remainder).subspan(kPartSize, 1)),
          "one-byte second part hash mismatch");

    std::vector<uint8_t> two_parts(2 * kPartSize);
    const auto two_part_hashes = compute_ed2k_hash_set(two_parts);
    check(two_part_hashes.part_hashes.size() == 3,
          "two exact parts require trailing empty hash");
    check(two_part_hashes.part_hashes.back() == md4_digest({}),
          "two-part trailing empty hash mismatch");
}

void test_aich() {
    using namespace td::ed2k;
    struct Vector {
        size_t size;
        const char* root;
    };
    constexpr Vector vectors[] = {
        {kBlockSize, "b441c5e4f4e29528eca82912362ab26151620404"},
        {3 * kBlockSize, "97cfc6074b7f1bd3e1268e9a7baed2c89959a10c"},
        {4 * kBlockSize, "588c7f14fc97042aabe546ed936f6799bd9a5e6c"},
        {kPartSize, "abcee4992f1d051a5709877861ce9688b0601df1"},
        {kPartSize + kBlockSize,
         "7462d0402e8936e3b4cacd4255bb5565289bd8d1"},
    };
    for (const auto& vector : vectors) {
        const auto bytes = patterned_bytes(vector.size);
        const auto root = compute_aich_root(bytes);
        check(root == from_hex<20>(vector.root),
              "AICH root does not match the independent vector");
    }

    const auto abc = std::array<uint8_t, 3>{'a', 'b', 'c'};
    check(sha1_digest(abc) ==
              from_hex<20>("a9993e364706816aba3e25717850c26c9cd0d89d"),
          "AICH SHA-1 leaf vector");
    const auto leaves = compute_aich_leaves(abc);
    check(leaves.size() == 1 && leaves[0] == sha1_digest(abc),
          "single AICH leaf mismatch");
    check(aich_root_from_leaves(abc.size(), leaves) == leaves[0],
          "single AICH leaf must be the root");
}

std::string valid_part_link(bool include_optional) {
    using namespace td::ed2k;
    std::vector<uint8_t> zeros(kPartSize);
    const auto hashes = compute_ed2k_hash_set(zeros);
    std::string link =
        "ed2k://|file|sample.bin|" + std::to_string(kPartSize) + "|" +
        hex(hashes.file_hash) + "|";
    if (include_optional) {
        link += "h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|p=";
        for (size_t index = 0; index < hashes.part_hashes.size(); ++index) {
            if (index != 0) {
                link.push_back(':');
            }
            link += hex(hashes.part_hashes[index]);
        }
        link += "|/|sources,127.0.0.1:4662,[::1]:4662|";
    }
    link += "/";
    return link;
}

void test_link_parser() {
    using namespace td::ed2k;

    const auto basic = parse_ed2k_file_link(
        "ED2K://|file|abc.bin|3|A448017AAF21D8525FC10AE87AA6729D|/");
    check(basic.name == "abc.bin" && basic.size == 3,
          "basic link fields");
    check(basic.file_hash ==
              from_hex<16>("a448017aaf21d8525fc10ae87aa6729d"),
          "basic link file hash");
    check(basic.part_hashes.empty() && !basic.aich_hash &&
              basic.sources.empty(),
          "basic link optional fields");

    const auto complete = parse_ed2k_file_link(valid_part_link(true));
    check(complete.part_hashes.size() == 2,
          "complete link part hash count");
    check(complete.aich_hash.has_value(), "complete link AICH hash");
    check(complete.sources.size() == 2, "complete link source count");
    check(!complete.sources[0].ipv6 &&
              complete.sources[0].address[0] == 127 &&
              complete.sources[0].address[1] == 0 &&
              complete.sources[0].address[2] == 0 &&
              complete.sources[0].address[3] == 1 &&
              complete.sources[0].port == 4662,
          "IPv4 source parsing");
    check(complete.sources[1].ipv6 &&
              complete.sources[1].address[15] == 1 &&
              complete.sources[1].port == 4662,
          "IPv6 source parsing");

    const auto escaped = parse_ed2k_file_link(
        "ed2k://|file|%E4%B8%AD%20file.bin|3|"
        "a448017aaf21d8525fc10ae87aa6729d|/");
    check(escaped.name == std::string("\xE4\xB8\xAD file.bin"),
          "percent-decoded UTF-8 file name");
    check(safe_ed2k_file_name(escaped.name),
          "decoded UTF-8 file name must be accepted");

    const auto optional_without_parts = parse_ed2k_file_link(
        "ed2k://|file|abc.bin|3|"
        "a448017aaf21d8525fc10ae87aa6729d|"
        "h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|/"
        "|sources,127.0.0.1:1|/");
    check(optional_without_parts.sources.size() == 1 &&
              optional_without_parts.aich_hash,
          "extension order is independent");

    for (const auto* name :
         {"../evil.bin", "..%2fevil.bin", "..%5cevil.bin", "a%00b",
          "%ff", "CON.bin", "LPT9.txt", "file.", "a:b", "a*b", ""}) {
        const std::string link =
            std::string("ed2k://|file|") + name +
            "|3|a448017aaf21d8525fc10ae87aa6729d|/";
        expect_link_error([&] { (void)parse_ed2k_file_link(link); }, name);
    }

    const std::string base =
        "ed2k://|file|abc.bin|3|a448017aaf21d8525fc10ae87aa6729d|";
    expect_link_error(
        [&] { (void)parse_ed2k_file_link(base); }, "missing slash");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|abc.bin|0|"
                "a448017aaf21d8525fc10ae87aa6729d|/");
        },
        "zero size");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|abc.bin|274877906945|"
                "a448017aaf21d8525fc10ae87aa6729d|/");
        },
        "oversized file");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|abc.bin|-1|"
                "a448017aaf21d8525fc10ae87aa6729d|/");
        },
        "negative size");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|abc.bin|3|"
                "a448017aaf21d8525fc10ae87aa672|/");
        },
        "short file hash");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|abc.bin|3|"
                "z448017aaf21d8525fc10ae87aa6729d|/");
        },
        "non-hex file hash");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                base + "h=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA|/");
        },
        "zero AICH hash");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base + "h=not-base32|/");
        },
        "short AICH hash");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base +
                "sources,127.0.0.1:0|/");
        },
        "zero source port");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base +
                "sources,127.0.0.1:65536|/");
        },
        "oversized source port");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base +
                "sources,example.com:4662|/");
        },
        "hostname source");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base +
                "sources,127.0.0.1:4662,|/");
        },
        "trailing source comma");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base +
                "sources,127.0.0.1:1|sources,127.0.0.2:2|/");
        },
        "duplicate sources");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                base + "h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|"
                       "h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|/");
        },
        "duplicate AICH hash");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base +
                "p=a448017aaf21d8525fc10ae87aa6729d|/");
        },
        "part hashes on a small file");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|sample.bin|9728000|"
                "a448017aaf21d8525fc10ae87aa6729d|"
                "p=a448017aaf21d8525fc10ae87aa6729d|/");
        },
        "wrong part hash count");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(
                "ed2k://|file|sample.bin|9728000|"
                "a448017aaf21d8525fc10ae87aa6729d|"
                "p=00000000000000000000000000000000:"
                "00000000000000000000000000000000|/");
        },
        "part hash set mismatch");
    expect_link_error(
        [&] {
            (void)parse_ed2k_file_link(base + "unknown=value|/");
        },
        "unknown extension");
}

} // namespace

int main() {
    try {
        test_md4();
        test_sha1();
        test_geometry();
        test_ed2k_hash_set();
        test_aich();
        test_link_parser();
        std::cout << "ed2k_p0_test: all tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "ed2k_p0_test: " << error.what() << '\n';
        return 1;
    }
}
