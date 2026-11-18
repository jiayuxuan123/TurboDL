#pragma once
#include "storage.hpp"
#include <array>
#include <atomic>

namespace td {
struct MediaInput {std::vector<fs::path> pieces;std::string kind="auto";};
struct MediaProduct {uint64_t size=0;std::string sha256;};
struct MediaCancelled:std::runtime_error {MediaCancelled():std::runtime_error("Media operation cancelled") {}};
void decrypt_media_aes128(const fs::path& source,const fs::path& destination,
    const fs::path& key,const std::array<uint8_t,16>& iv,const std::atomic<bool>& stop);
MediaProduct mux_media(const std::vector<MediaInput>& inputs,const fs::path& destination,const std::atomic<bool>& stop);
}
