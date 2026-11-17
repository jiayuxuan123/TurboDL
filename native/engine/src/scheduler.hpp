#pragma once
#include <algorithm>
#include <cstdint>
#include <map>
#include <optional>
#include <vector>

namespace td {
struct Range { uint64_t begin=0,end=0; };

// Persistent byte coverage is independent from the number and size of requests.
// End offsets are exclusive. No range is inferred from the logical file size.
class Coverage {
public:
    void add(uint64_t begin,uint64_t end) {
        if(begin>=end)return;
        auto it=ranges_.lower_bound(begin);
        if(it!=ranges_.begin()) {
            auto previous=std::prev(it);
            if(previous->second>=begin)it=previous;
        }
        while(it!=ranges_.end() && it->first<=end) {
            begin=std::min(begin,it->first);end=std::max(end,it->second);
            bytes_-=it->second-it->first;it=ranges_.erase(it);
        }
        ranges_[begin]=end;bytes_+=end-begin;
    }
    uint64_t bytes()const{return bytes_;}
    std::optional<Range> gap(uint64_t total,uint64_t window,const std::vector<Range>& reserved)const {
        std::vector<Range> occupied;occupied.reserve(ranges_.size()+reserved.size());
        for(auto [begin,end]:ranges_)occupied.push_back({begin,end});
        occupied.insert(occupied.end(),reserved.begin(),reserved.end());
        std::sort(occupied.begin(),occupied.end(),[](auto a,auto b){return a.begin<b.begin;});
        uint64_t cursor=0;
        for(auto range:occupied) {
            if(cursor>=total)return std::nullopt;
            if(range.begin>cursor)return Range{cursor,std::min({total,range.begin,cursor+std::min(window,total-cursor)})};
            cursor=std::max(cursor,range.end);
        }
        if(cursor>=total)return std::nullopt;
        return Range{cursor,cursor+std::min(window,total-cursor)};
    }
    void clear(){ranges_.clear();bytes_=0;}
private:
    std::map<uint64_t,uint64_t> ranges_;
    uint64_t bytes_=0;
};
}
