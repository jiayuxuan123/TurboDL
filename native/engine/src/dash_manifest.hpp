#pragma once
#include "hls_manifest.hpp"

namespace td {
struct DashSegment {
    std::string url;
    std::optional<MediaByteRange> range;
    std::optional<HlsMap> init;
    uint64_t number=0,time=0;
    double duration=0;
};
struct DashTrack {
    std::string id,kind,language,codecs;
    uint64_t bandwidth=0;
    std::vector<DashSegment> segments;
};
struct DashManifest {
    std::vector<DashTrack> tracks;
    bool dynamic=false;
    double minimum_update_seconds=1;
};
struct DashSelection {
    uint64_t maximum_bandwidth=0;
    std::string audio_language,video_id,audio_id;
};
DashManifest parse_dash_manifest(const std::string&,const std::string& base,const DashSelection& selection = {});
}
