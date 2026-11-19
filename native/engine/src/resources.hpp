#pragma once
#include <cstdint>
#include <deque>
#include <functional>
#include <chrono>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <stdexcept>

namespace td {
struct ResourceBusy:std::runtime_error {using std::runtime_error::runtime_error;};
// Budgets cover engine-owned payload buffers. TLS, SQLite and OS socket memory
// are separately observable process costs and are not called buffer leases.
class ResourceDomain {
public:
    static constexpr uint64_t request_buffer_bytes=81920;
    uint64_t join(uint32_t connections,uint64_t buffers,uint64_t bytes_per_second=0,uint32_t tasks=64,std::function<void()> wake={});
    void leave(uint64_t owner);
    uint64_t acquire(const std::string& origin,uint32_t host_limit,uint32_t socket_weight=1,uint64_t payload_bytes=request_buffer_bytes);
    uint64_t buffer_ceiling();
    void release(uint64_t lease);
    uint64_t used_buffers();
    void cooldown(const std::string& origin,uint64_t milliseconds);
    long next_timer(long maximum_ms);
    bool receive(uint64_t bytes);
    void set_speed(uint64_t owner,uint64_t bytes_per_second);
    bool speed_limited();
    uint64_t register_external_transfer();
    void unregister_external_transfer(uint64_t id);
    uint64_t external_speed(uint64_t id);
    bool admit_task(uint64_t& ticket);
    void release_task(uint64_t id);
    uint64_t acquire_processing(uint64_t bytes);
    void release_processing(uint64_t lease);
    struct Snapshot {uint64_t tasks,connections,buffers,external_transfers,queued_tasks;};
    Snapshot snapshot();
private:
    uint64_t speed_locked() const;
    void wake_locked() const;
    std::mutex mutex_;
    struct Limit {uint32_t connections;uint64_t buffers;uint64_t speed;uint32_t tasks;std::function<void()> wake;};
    std::map<uint64_t,Limit> owners_;
    struct Host {uint32_t active=0;std::multiset<uint32_t> caps;};
    struct Lease {std::string origin;uint32_t cap,weight;uint64_t bytes;};
    std::map<std::string,Host> hosts_;
    std::map<uint64_t,Lease> leases_;
    std::map<std::string,std::chrono::steady_clock::time_point> cooldowns_;
    uint64_t next_=0,buffers_=0;
    uint64_t next_lease_=0;
    std::set<uint64_t> external_transfers_;
    uint64_t next_external_=0;
    std::set<uint64_t> task_leases_;
    std::deque<uint64_t> task_queue_;
    uint64_t next_task_=0;
    std::map<uint64_t,uint64_t> processing_;
    uint32_t connections_=0;
    double tokens_=0;
    std::chrono::steady_clock::time_point token_time_=std::chrono::steady_clock::now();
};
ResourceDomain& process_resources();
}
