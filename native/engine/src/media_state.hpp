#pragma once
#include "media_mux.hpp"
#include <map>
struct sqlite3;

namespace td {
struct StoredMediaUnit {std::string sha256;uint64_t size=0,network_size=0;};
class MediaState {
public:
    MediaState(const fs::path& root,const std::string& target,const std::string& identity);
    ~MediaState();
    std::map<std::string,StoredMediaUnit> units();
    void unit(const std::string& id,const StoredMediaUnit& value);
    void ready(const MediaProduct& product);
    void completed();
    const std::string& phase()const{return phase_;}
    const MediaProduct& product()const{return product_;}
private:
    sqlite3* db_=nullptr;
    std::string phase_="NETWORK";
    MediaProduct product_;
};
void publish_media_product(const fs::path& source,const fs::path& destination,
    const MediaProduct&,const std::string& owner,const std::atomic<bool>& stop);
}
