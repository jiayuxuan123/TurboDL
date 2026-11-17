#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace td {
struct MediaByteRange {uint64_t offset=0,length=0;};
struct HlsKey {
    std::string url;
    std::array<uint8_t,16> iv{};
    bool explicit_iv=false;
};
struct HlsMap {
    std::string url;
    std::optional<MediaByteRange> range;
    std::optional<HlsKey> key;
};
struct HlsSegment {
    std::string url;
    uint64_t sequence=0,discontinuity=0;
    double duration=0;
    bool gap=false;
    std::optional<MediaByteRange> range;
    std::optional<HlsMap> init;
    std::optional<HlsKey> key;
};
struct HlsVariant {std::string url,audio_group,codecs;uint64_t bandwidth=0;};
struct HlsRendition {std::string url,group,name,language;bool preferred=false;};
struct HlsManifest {
    std::vector<HlsSegment> segments;
    std::vector<HlsVariant> variants;
    std::vector<HlsRendition> audio;
    uint64_t media_sequence=0,target_duration=0;
    bool ended=false;
};
HlsManifest parse_hls_manifest(const std::string& content,const std::string& base_url);
}
