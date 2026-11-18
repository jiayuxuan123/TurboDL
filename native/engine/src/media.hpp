#pragma once
#include <turbodl/engine.h>
#include "dash_manifest.hpp"
#include "io_queue.hpp"
#include "storage.hpp"
#include <functional>
#include <memory>

namespace td {
struct MediaOptions {
    std::string url,target,key,identity,expected_sha256;
    uint32_t protocol=0,parallel=4;
    uint64_t maximum_bandwidth=0,maximum_segment_bytes=67108864;
    std::string audio_language,video_id,audio_id;
    uint32_t record_seconds=0;
    int64_t expected_size=-1;
};
struct MediaFetch {
    std::string url,key;
    fs::path path;
    std::optional<MediaByteRange> range;
    uint64_t maximum_bytes=0;
    int64_t expected_size=-1;
};
struct MediaFetchState {
    td_snapshot snapshot{};
    td_http_metrics metrics{};
    std::string effective_url;
    std::vector<int64_t> connections;
};
struct MediaHost {
    std::function<uint64_t(const MediaFetch&)> submit;
    std::function<MediaFetchState(uint64_t)> query;
    std::function<void(uint64_t,uint32_t)> control;
    std::function<void(uint64_t)> forget;
    // Called only by the owning engine's disk queue, never on the reactor.
    std::function<void(const std::string&)> erase_checkpoint;
};
class MediaJob {
public:
    virtual ~MediaJob()=default;
    virtual void tick()=0;
    virtual void control(uint32_t)=0;
    virtual void finish()=0;
    virtual void fail(uint32_t,const std::string&)=0;
    virtual void shutdown()=0;
    virtual td_snapshot snapshot()const=0;
    virtual td_http_metrics metrics()const=0;
    virtual td_media_details details()const=0;
    virtual long next_timer()const=0;
};
std::unique_ptr<MediaJob> create_media_job(const MediaOptions&,const fs::path&,IoQueue&,MediaHost);
}
