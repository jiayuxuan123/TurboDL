#include "resources.hpp"
#include <algorithm>
#include <limits>

namespace td {
ResourceDomain& process_resources(){static ResourceDomain domain;return domain;}
uint64_t ResourceDomain::join(uint32_t connections,uint64_t buffers,uint64_t speed,uint32_t tasks,std::function<void()> wake){
    std::lock_guard lock(mutex_);auto id=++next_;owners_[id]={connections,buffers,speed,tasks,std::move(wake)};wake_locked();return id;
}
void ResourceDomain::wake_locked()const{for(const auto& [id,limit]:owners_){(void)id;if(limit.wake)limit.wake();}}
void ResourceDomain::leave(uint64_t owner){std::lock_guard lock(mutex_);owners_.erase(owner);wake_locked();}
uint64_t ResourceDomain::acquire(const std::string& origin,uint32_t host_limit,uint32_t weight){
    std::lock_guard lock(mutex_);
    uint32_t connections=std::numeric_limits<uint32_t>::max();
    uint64_t buffers=std::numeric_limits<uint64_t>::max();
    for(const auto& [id,limit]:owners_){(void)id;connections=std::min(connections,limit.connections);buffers=std::min(buffers,limit.buffers);}
    auto host=hosts_.find(origin);uint32_t active=host==hosts_.end()?0:host->second.active;
    if(host!=hosts_.end() && !host->second.caps.empty())host_limit=std::min(host_limit,*host->second.caps.begin());
    auto cooling=cooldowns_.find(origin);
    if(cooling!=cooldowns_.end()){
        if(cooling->second>std::chrono::steady_clock::now())return 0;
        cooldowns_.erase(cooling);
    }
    if(!weight || weight>connections || connections_>connections-weight || buffers<request_buffer_bytes || buffers_>buffers-request_buffer_bytes || weight>host_limit || active>host_limit-weight)return 0;
    auto id=++next_lease_;leases_[id]={origin,host_limit,weight};connections_+=weight;buffers_+=request_buffer_bytes;auto& entry=hosts_[origin];entry.active+=weight;entry.caps.insert(host_limit);return id;
}
void ResourceDomain::release(uint64_t id){
    std::lock_guard lock(mutex_);auto lease=leases_.find(id);if(lease==leases_.end())return;
    auto it=hosts_.find(lease->second.origin);
    if(it!=hosts_.end()){it->second.active-=lease->second.weight;auto cap=it->second.caps.find(lease->second.cap);if(cap!=it->second.caps.end())it->second.caps.erase(cap);if(!it->second.active)hosts_.erase(it);}
    connections_-=lease->second.weight;buffers_-=request_buffer_bytes;leases_.erase(lease);wake_locked();
}
uint64_t ResourceDomain::used_buffers(){std::lock_guard lock(mutex_);return buffers_;}
void ResourceDomain::cooldown(const std::string& origin,uint64_t milliseconds){
    std::lock_guard lock(mutex_);
    if(cooldowns_.size()>=1024){auto now=std::chrono::steady_clock::now();std::erase_if(cooldowns_,[&](auto& p){return p.second<=now;});if(cooldowns_.size()>=1024)return;}
    auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(milliseconds);
    auto it=cooldowns_.find(origin);
    if(it==cooldowns_.end())cooldowns_[origin]=until;else it->second=std::max(it->second,until);
    wake_locked();
}
long ResourceDomain::next_timer(long maximum){
    std::lock_guard lock(mutex_);auto now=std::chrono::steady_clock::now();
    std::erase_if(cooldowns_,[&](const auto& entry){return entry.second<=now;});
    for(const auto& [origin,until]:cooldowns_){(void)origin;auto delay=std::chrono::duration_cast<std::chrono::milliseconds>(until-now).count();maximum=std::min(maximum,static_cast<long>(std::max<int64_t>(1,delay)));}
    return maximum;
}
bool ResourceDomain::receive(uint64_t bytes){
    std::lock_guard lock(mutex_);uint64_t speed=speed_locked();
    if(speed)speed=std::max<uint64_t>(1,speed/(external_transfers_.size()+1));
    auto now=std::chrono::steady_clock::now();
    if(!speed){token_time_=now;tokens_=0;return true;}
    double capacity=std::max(16384.0,static_cast<double>(speed)/10.0);
    tokens_=std::min(capacity,tokens_+std::chrono::duration<double>(now-token_time_).count()*static_cast<double>(speed));token_time_=now;
    if(tokens_<static_cast<double>(bytes))return false;
    tokens_-=static_cast<double>(bytes);return true;
}
void ResourceDomain::set_speed(uint64_t owner,uint64_t speed){
    std::lock_guard lock(mutex_);auto it=owners_.find(owner);if(it!=owners_.end())it->second.speed=speed;
    tokens_=0;token_time_=std::chrono::steady_clock::now();
    wake_locked();
}
uint64_t ResourceDomain::speed_locked()const{
    uint64_t speed=0;
    for(const auto& [id,limit]:owners_){(void)id;if(limit.speed && (!speed || limit.speed<speed))speed=limit.speed;}
    return speed;
}
uint64_t ResourceDomain::register_external_transfer(){
    std::lock_guard lock(mutex_);auto id=++next_external_;external_transfers_.insert(id);wake_locked();return id;
}
void ResourceDomain::unregister_external_transfer(uint64_t id){
    std::lock_guard lock(mutex_);external_transfers_.erase(id);wake_locked();
}
uint64_t ResourceDomain::external_speed(uint64_t id){
    std::lock_guard lock(mutex_);auto speed=speed_locked();
    if(!speed || !external_transfers_.contains(id))return 0;
    bool http=std::any_of(leases_.begin(),leases_.end(),[](const auto& p){return p.second.origin!="bittorrent";});
    return std::max<uint64_t>(1,speed/(external_transfers_.size()+(http?1:0)));
}
bool ResourceDomain::admit_task(uint64_t& ticket){
    std::lock_guard lock(mutex_);uint32_t limit=64;
    for(const auto& [owner,value]:owners_){(void)owner;limit=std::min(limit,value.tasks);}
    if(ticket && task_leases_.contains(ticket))return true;
    if(!ticket){ticket=++next_task_;task_queue_.push_back(ticket);}
    if(task_leases_.size()>=limit || task_queue_.empty() || task_queue_.front()!=ticket)return false;
    task_queue_.pop_front();task_leases_.insert(ticket);wake_locked();return true;
}
void ResourceDomain::release_task(uint64_t id){
    std::lock_guard lock(mutex_);task_leases_.erase(id);std::erase(task_queue_,id);wake_locked();
}
ResourceDomain::Snapshot ResourceDomain::snapshot(){
    std::lock_guard lock(mutex_);return {task_leases_.size(),connections_,buffers_,external_transfers_.size(),task_queue_.size()};
}
}
