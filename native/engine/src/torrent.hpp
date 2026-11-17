#pragma once
#include <turbodl/engine.h>
#include "storage.hpp"
#include <memory>
#include <string>
#include <vector>

namespace td {
struct TorrentOptions {
    std::string source,destination,key;
    uint32_t peers=16,seed_seconds=0;
    uint64_t download_limit=0,upload_limit=0;
    bool test_loopback=false;
    std::function<void()> notify;
    std::vector<int> selected_files;
};
class TorrentJob {
public:
    virtual ~TorrentJob()=default;
    virtual void tick()=0;
    virtual void control(uint32_t command)=0;
    virtual td_snapshot snapshot()const=0;
    virtual void connect_peer(const std::string& address,uint16_t port)=0;
};
std::unique_ptr<TorrentJob> create_torrent_job(const TorrentOptions&,const fs::path& checkpoint_directory);
}
