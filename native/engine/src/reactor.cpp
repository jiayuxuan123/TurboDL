#include <turbodl/engine.h>
#include "resources.hpp"
#include "io_queue.hpp"
#include "scheduler.hpp"
#include "storage.hpp"
#include "torrent.hpp"
#include "media.hpp"
#include <curl/curl.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <charconv>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstring>
#include <deque>
#include <future>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <thread>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <sys/socket.h>
#endif

namespace {
using Clock=std::chrono::steady_clock;
using Headers=std::vector<std::pair<std::string,std::string>>;
constexpr uint64_t max_offset=static_cast<uint64_t>(INT64_MAX);
std::string allowed_protocols(){
    static const std::string value=[]{
        const auto* info=curl_version_info(CURLVERSION_NOW);
        std::set<std::string> available;
        if(info && info->protocols)for(auto protocol=info->protocols;*protocol;++protocol)available.emplace(*protocol);
        std::string result;
        for(const auto* protocol:{"http","https","ftp","sftp"}){
            if(!available.contains(protocol))continue;
            if(!result.empty())result+=',';
            result+=protocol;
        }
        return result.empty()?std::string("http"):result;
    }();
    return value;
}
bool terminal(uint32_t s){return s==TD_COMPLETED || s==TD_FAILED || s==TD_CANCELLED;}
std::string lower(std::string s){for(auto& c:s)c=static_cast<char>(std::tolower(static_cast<unsigned char>(c)));return s;}
std::string trim(std::string s){auto a=s.find_first_not_of(" \t\r\n");return a==std::string::npos?"":s.substr(a,s.find_last_not_of(" \t\r\n")-a+1);}
template<size_t N>void text(char(&dst)[N],std::string_view value){auto n=std::min(N-1,value.size());std::memcpy(dst,value.data(),n);dst[n]=0;}
td::fs::path path_of(std::string_view s){return td::native_path(td::fs::path(std::u8string(reinterpret_cast<const char8_t*>(s.data()),s.size())));}
std::string url_part(const std::string& url,CURLUPart part){
    auto* u=curl_url();if(!u)throw std::bad_alloc();char* p=nullptr;std::string result;
    if(curl_url_set(u,CURLUPART_URL,url.c_str(),0)==CURLUE_OK && curl_url_get(u,part,&p,CURLU_DEFAULT_PORT)==CURLUE_OK)result=p;
    curl_free(p);curl_url_cleanup(u);return result;
}
std::string origin(const std::string& url){return lower(url_part(url,CURLUPART_SCHEME)+"://"+url_part(url,CURLUPART_HOST)+":"+url_part(url,CURLUPART_PORT));}
bool http_url(const std::string& url){auto scheme=lower(url_part(url,CURLUPART_SCHEME));return scheme=="http" || scheme=="https";}
bool url_ok(const std::string& url){auto scheme=lower(url_part(url,CURLUPART_SCHEME));return (scheme=="http" || scheme=="https" || scheme=="ftp" || scheme=="sftp") && !url_part(url,CURLUPART_HOST).empty() && url_part(url,CURLUPART_USER).empty() && url_part(url,CURLUPART_PASSWORD).empty();}
Headers copy_headers(const td_header* headers,uint32_t count){
    if(count>64 || (count && !headers))throw std::invalid_argument("Invalid headers");Headers result;
    for(uint32_t i=0;i<count;++i){if(!headers[i].name || !headers[i].value)throw std::invalid_argument("Invalid header");std::string name=headers[i].name,value=headers[i].value;auto key=lower(name);if(name.empty() || name.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_")!=std::string::npos || value.find_first_of("\r\n")!=std::string::npos || key=="range" || key=="if-range" || key=="host" || key=="content-length" || key=="accept-encoding")throw std::invalid_argument("Invalid header");result.emplace_back(name,value);}
    return result;
}
std::string redirect_url(const std::string& base,const std::string& location){
    auto* u=curl_url();if(!u)throw std::bad_alloc();char* p=nullptr;std::string result;
    if(curl_url_set(u,CURLUPART_URL,base.c_str(),0)==CURLUE_OK && curl_url_set(u,CURLUPART_URL,location.c_str(),0)==CURLUE_OK && curl_url_get(u,CURLUPART_URL,&p,0)==CURLUE_OK)result=p;
    curl_free(p);curl_url_cleanup(u);return result;
}
bool number(const std::string& s,uint64_t& n){auto [p,e]=std::from_chars(s.data(),s.data()+s.size(),n);return e==std::errc{} && p==s.data()+s.size() && n<=max_offset;}
void curl_ready(){static const int initialized=[](){if(curl_global_init(CURL_GLOBAL_DEFAULT)!=CURLE_OK)throw std::runtime_error("curl initialization failed");return 1;}();(void)initialized;}
bool public_v4(uint32_t ip){return (ip>>24)!=0 && (ip>>24)!=10 && (ip>>24)!=127 && (ip>>16)!=0xa9fe && (ip>>20)!=0xac1 && (ip>>16)!=0xc0a8 && (ip>>22)!=0x191 && (ip>>28)<14;}
bool socket_allowed(curl_sockaddr* a,bool test_loopback){
    if(a->family==AF_INET){auto ip=ntohl(reinterpret_cast<sockaddr_in*>(&a->addr)->sin_addr.s_addr);return public_v4(ip) || (test_loopback && (ip>>24)==127);}
    if(a->family!=AF_INET6)return false;
    const auto* b=reinterpret_cast<sockaddr_in6*>(&a->addr)->sin6_addr.s6_addr;
    bool zero=std::all_of(b,b+15,[](auto c){return c==0;});
    if(zero)return test_loopback && b[15]==1;
    if(std::all_of(b,b+10,[](auto c){return c==0;}) && b[10]==255 && b[11]==255){uint32_t v=(uint32_t(b[12])<<24)|(uint32_t(b[13])<<16)|(uint32_t(b[14])<<8)|b[15];return public_v4(v) || (test_loopback && (v>>24)==127);}
    return (b[0]&0xe0)==0x20 && !(b[0]==0x20 && b[1]==0x01 && b[2]==0x0d && b[3]==0xb8);
}
struct Request;
struct RangeRetry {
    uint32_t attempts=0;
    uint64_t end=0;
    Clock::time_point ready_at{};
};
struct Source {
    std::string url,representation;
    Headers headers;
    uint32_t redirects=0,failures=0;
    bool disabled=false;
};
struct ResumeIdentityError:std::runtime_error {using std::runtime_error::runtime_error;};
struct Task {
    uint64_t id=0;
    uint64_t task_lease=0;
    std::mutex mutex;
    td_snapshot view{};
    td_http_metrics metrics{};
    Clock::time_point submitted_at=Clock::now();
    std::atomic<uint32_t> command{0};
    std::string url,target,key,expected,etag,validator_origin,username,password,proxy,ca_bundle,known_hosts,public_key,private_key,doh,cooldown_group,representation_id;
    std::string replacement_url;
    Headers replacement_headers;
    std::string replacement_representation;
    std::string authentication_origin;
    std::string caller_group;
    bool source_replaced=false;
    Headers headers;
    std::vector<Source> sources;
    size_t next_source=0;
    uint32_t connections=1,connect_ms=10000,read_ms=30000,redirects=0,retry_count=3,host_limit=512,http_version=0;
    std::map<uint64_t,RangeRetry> range_retries;
    uint64_t window=1048576,checkpoint_bytes=0;
    int64_t checkpoint_total=-1;
    uint64_t source_offset=0,maximum_bytes=0;
    int64_t source_length=-1;
    int64_t total=-1;
    int64_t expected_length=-1;
    bool prepared=false,identity_checked=false,range_supported=false,loopback=false,whole_file=false,checkpoint_failed=false,session_range_allowed=false;
    bool restart_unverifiable=false,internal=false,media=false;
    td::MediaOptions media_options;
    std::unique_ptr<td::MediaJob> media_job;
    td_media_details media_details{};
    std::atomic<bool> finish_media{false};
    FILE* file=nullptr;
    std::unique_ptr<td::FileLock> lock;
    td::Coverage coverage;
    std::map<uint64_t,td::BlockDigest> pending;
    std::unique_ptr<td::Sha256State> stream_hash;
    uint64_t stream_hash_offset=0;
    bool stream_hash_valid=false;
    std::vector<std::unique_ptr<Request>> requests;
    std::vector<CURL*> idle_easies;
    struct SourceSpeed {
        double bytes_per_second=0,startup_seconds=0;
        Clock::time_point last_completed_at{};
    };
    std::map<std::string,SourceSpeed> speeds;
    std::future<std::string> io;
    uint32_t io_purpose=0,failure_code=0;
    std::string failure_message;
    uint64_t committing_bytes=0;
    int64_t committing_total=-1;
    std::vector<uint64_t> committing_offsets;
    bool torrent=false;
    td::TorrentOptions torrent_options;
    std::unique_ptr<td::TorrentJob> torrent_job;
    std::vector<std::pair<std::string,uint16_t>> peer_requests;
    Clock::time_point retry_at{},checkpoint_at=Clock::now();
    ~Task(){
        // Request destructors wait for their disk futures. Drain them before
        // releasing the FILE* they captured; member destruction alone would
        // close the file first on an abnormal shutdown path.
        requests.clear();media_job.reset();torrent_job.reset();
        for(auto* easy:idle_easies)curl_easy_cleanup(easy);
        if(file)std::fclose(file);if(task_lease)td::process_resources().release_task(task_lease);
    }
};
struct Request {
    ::td_engine* engine=nullptr;
    Task* task=nullptr;
    CURL* easy=nullptr;
    curl_slist* curl_headers=nullptr;
    td::Range assigned{};
    uint64_t cursor=0,body_bytes=0,header_bytes=0,retry_ms=0;
    uint64_t response_end=0;
    std::string url,source_origin,source_identity,budget_key,etag,location,range_value,content_range;
    Headers headers;
    explicit Request(size_t capacity):blocks{std::vector<char>(capacity),std::vector<char>(capacity)}{}
    std::array<std::vector<char>,2> blocks;
    std::array<std::future<td::BlockDigest>,2> writes;
    size_t block_index=0,block_used=0;
    uint32_t error=0,redirects=0;
    long status=0;
    std::string message;
    bool headers_ready=false,redirect=false,paused=false,restart_whole=false,http=true;
    bool empty_representation=false;
    bool reused_connection=false;
    bool address_rejected=false,address_allowed=false;
    uint8_t tail_splits=0;
    bool open_ended=false;
    Clock::time_point started_at=Clock::now(),first_byte_at{},sample_at{},last_body_at{};
    uint64_t sampled_bytes=0;
    double bytes_per_second=0;
    uint64_t lease_id=0;
    size_t source_index=0;
    bool network_done=false;
    CURLcode network_result=CURLE_OK;
    uint64_t generation=0;
    ~Request(){
        for(auto& write:writes)if(write.valid())try{write.get();}catch(...){}
        if(easy)curl_easy_cleanup(easy);if(curl_headers)curl_slist_free_all(curl_headers);if(lease_id)td::process_resources().release(lease_id);
    }
};
std::string source_identity(const Task& task,const Source& source){return origin(source.url)+":"+td::sha256(source.representation.empty()?source.url:"host:"+source.representation)+":"+task.cooldown_group+":"+std::to_string(task.source_offset)+":"+std::to_string(task.source_length);}
}

extern "C" {static int32_t submit_task(td_engine*,const td_task_options*,td_task_id*,const td::MediaOptions*,bool);}

struct td_engine {
    std::mutex mutex;
    std::map<uint64_t,std::shared_ptr<Task>> tasks;
    std::deque<td_event> events;
    std::atomic<bool> stopping{false};
    std::thread reactor;
    td::fs::path directory;
    std::unique_ptr<td::Journal> journal;
    td::IoQueue disk;
    CURLM* multi=nullptr;
    bool dispatch_ready=false;
    uint32_t max_tasks=3,flags=0;
    uint64_t buffer_budget=0;
    uint64_t next_id=0,sequence=0,domain_id=0,rotation=0;

    explicit td_engine(const td_engine_options& o):directory(path_of(o.checkpoint_directory)),disk([this]{if(multi)curl_multi_wakeup(multi);}),max_tasks(o.max_active_tasks),flags(o.flags),buffer_budget(o.buffer_budget_bytes){
        td::fs::create_directories(directory);
        journal=std::make_unique<td::Journal>(directory/"checkpoints.sqlite3");
        multi=curl_multi_init();if(!multi)throw std::runtime_error("HTTP loop initialization failed");
        curl_multi_setopt(multi,CURLMOPT_MAX_TOTAL_CONNECTIONS,static_cast<long>(o.max_total_connections));
        curl_multi_setopt(multi,CURLMOPT_MAXCONNECTS,static_cast<long>(o.max_total_connections));
        domain_id=td::process_resources().join(o.max_total_connections,o.buffer_budget_bytes,o.struct_size>=sizeof(o)?o.global_speed_limit_bytes_per_second:0,o.max_active_tasks,[this]{curl_multi_wakeup(multi);});
        try{reactor=std::thread([this]{run();});}catch(...){td::process_resources().leave(domain_id);curl_multi_cleanup(multi);throw;}
    }
    ~td_engine(){stopping=true;if(multi)curl_multi_wakeup(multi);if(reactor.joinable())reactor.join();tasks.clear();disk.close();td::process_resources().leave(domain_id);curl_multi_cleanup(multi);multi=nullptr;}
    std::vector<std::shared_ptr<Task>> list(){std::lock_guard l(mutex);std::vector<std::shared_ptr<Task>> result;for(auto& [id,t]:tasks){(void)id;result.push_back(t);}return result;}
    uint32_t state_of(Task& t){std::lock_guard l(t.mutex);return t.view.state;}
    void emit(Task& t,uint32_t kind){
        if(t.internal)return;
        td_event e{};e.struct_size=sizeof(e);e.abi_version=TD_ABI_VERSION;e.kind=kind;
        {std::lock_guard l(t.mutex);e.snapshot=t.view;}
        std::lock_guard l(mutex);e.sequence=++sequence;
        if(kind==TD_EVENT_PROGRESS)std::erase_if(events,[&](const auto& old){return old.kind==TD_EVENT_PROGRESS && old.snapshot.task_id==t.id;});
        if(events.size()>=2048){auto it=std::find_if(events.begin(),events.end(),[](auto& old){return !terminal(old.snapshot.state);});if(it==events.end())return;events.erase(it);}
        events.push_back(e);
    }
    void state(Task& t,uint32_t value,uint32_t error=0,std::string_view message={}){
        {std::lock_guard l(t.mutex);t.view.state=value;t.view.error=error;text(t.view.message,message);}
        emit(t,TD_EVENT_STATE);
    }
    void active(Task& t){
        auto n=static_cast<uint32_t>(std::count_if(t.requests.begin(),t.requests.end(),[](auto& r){return r->easy!=nullptr;}));
        std::set<curl_off_t> connections;uint32_t streams=0,versions=0;
        for(const auto& request:t.requests)if(request->easy && request->http){
            curl_off_t id=-1;long version=0;
            curl_easy_getinfo(request->easy,CURLINFO_CONN_ID,&id);curl_easy_getinfo(request->easy,CURLINFO_HTTP_VERSION,&version);
            if(id>=0)connections.insert(id);
            if(version==CURL_HTTP_VERSION_3)versions|=4;else if(version==CURL_HTTP_VERSION_2_0)versions|=2;else if(version)versions|=1;
            if(version)++streams;
        }
        std::lock_guard l(t.mutex);t.view.active_requests=n;t.view.peak_active_requests=std::max(n,t.view.peak_active_requests);
        t.metrics.active_streams=streams;t.metrics.active_connections=static_cast<uint32_t>(connections.size());t.metrics.peak_connections=std::max(t.metrics.peak_connections,t.metrics.active_connections);t.metrics.negotiated_versions|=versions;
    }
    static bool pending_writes(const Request& r){
        return std::any_of(r.writes.begin(),r.writes.end(),[](const auto& write){return write.valid();});
    }
    static bool block_available(const Request& r){
        return r.block_index<r.writes.size() && !r.writes[r.block_index].valid();
    }
    static void select_block(Request& r){
        if(r.block_used)return;
        for(size_t i=0;i<r.writes.size();++i)if(!r.writes[i].valid()){r.block_index=i;return;}
    }
    void flush(Request& r){
        if(!r.block_used){select_block(r);return;}
        if(!block_available(r))return;
        auto slot=r.block_index;
        auto offset=r.cursor-r.block_used;auto length=r.block_used;auto* data=r.blocks[slot].data();auto* file=r.task->file;auto* task=r.task;
        r.writes[slot]=disk.submit([offset,length,data,file,task]{
            td::seek_target(file,offset);if(std::fwrite(data,1,length,file)!=length)throw std::runtime_error("Destination write failed");
            if(task->stream_hash_valid){
                std::lock_guard lock(task->mutex);
                if(task->stream_hash && task->stream_hash_offset==offset){
                    task->stream_hash->update(data,length);task->stream_hash_offset+=length;
                }else task->stream_hash_valid=false;
            }
            return td::BlockDigest{offset,static_cast<uint32_t>(length),td::block_digest(std::string_view(data,length))};
        });
        r.block_used=0;select_block(r);
    }
    void collect_write(Request& r,bool wait=false){
        bool collected=false;
        for(auto& write:r.writes){
            if(!write.valid() || (!wait && write.wait_for(std::chrono::seconds(0))!=std::future_status::ready))continue;
            td::BlockDigest block;
            try{block=write.get();}catch(...){select_block(r);throw;}
            auto& t=*r.task;t.pending[block.offset]=block;t.coverage.add(block.offset,block.offset+block.length);
            std::lock_guard lock(t.mutex);t.view.downloaded_bytes=t.coverage.bytes();
            collected=true;
        }
        if(collected)select_block(r);
    }
    void detach(Request& r){
        if(r.easy){
            curl_off_t downloaded=0,dns=0,connected=0,tls=0,first=0;long connections=0,version=0;
            curl_easy_getinfo(r.easy,CURLINFO_SIZE_DOWNLOAD_T,&downloaded);
            curl_easy_getinfo(r.easy,CURLINFO_NAMELOOKUP_TIME_T,&dns);curl_easy_getinfo(r.easy,CURLINFO_CONNECT_TIME_T,&connected);
            curl_easy_getinfo(r.easy,CURLINFO_APPCONNECT_TIME_T,&tls);curl_easy_getinfo(r.easy,CURLINFO_STARTTRANSFER_TIME_T,&first);
            curl_easy_getinfo(r.easy,CURLINFO_NUM_CONNECTS,&connections);curl_easy_getinfo(r.easy,CURLINFO_HTTP_VERSION,&version);
            r.reused_connection=r.http && connections==0;
            {
                std::lock_guard l(r.task->mutex);r.task->view.wire_bytes+=static_cast<uint64_t>(std::max<curl_off_t>(downloaded,0));
                if(r.http){auto& m=r.task->metrics;++m.finished_requests;m.new_connections+=static_cast<uint64_t>(std::max(connections,0L));
                    m.dns_microseconds_sum+=static_cast<uint64_t>(std::max<curl_off_t>(dns,0));
                    m.connect_microseconds_sum+=static_cast<uint64_t>(std::max<curl_off_t>(connected-dns,0));
                    m.tls_microseconds_sum+=static_cast<uint64_t>(std::max<curl_off_t>(tls-connected,0));
                    m.request_first_byte_microseconds_sum+=static_cast<uint64_t>(std::max<curl_off_t>(first,0));
                    if(version==CURL_HTTP_VERSION_3)m.negotiated_versions|=4;else if(version==CURL_HTTP_VERSION_2_0)m.negotiated_versions|=2;else if(version)m.negotiated_versions|=1;
                }
            }
            curl_multi_remove_handle(multi,r.easy);
            if(!stopping.load() && r.task->idle_easies.size()<std::min<uint32_t>(64,r.task->connections)){
                r.task->idle_easies.push_back(r.easy);r.easy=nullptr;
            }else{
                curl_easy_cleanup(r.easy);r.easy=nullptr;
            }
        }
        if(r.curl_headers){curl_slist_free_all(r.curl_headers);r.curl_headers=nullptr;}
        r.network_done=true;
    }
    void stop_requests(Task& t)noexcept{
        for(auto& r:t.requests){
            try{detach(*r);}catch(...){}
            try{flush(*r);}catch(...){}
            while(pending_writes(*r)){
                try{collect_write(*r,true);}catch(...){}
            }
            try{flush(*r);}catch(...){}
            while(pending_writes(*r)){
                try{collect_write(*r,true);}catch(...){}
            }
        }
        // Request destructors wait for any remaining disk futures. The target
        // file must stay open until this vector has been destroyed.
        t.requests.clear();
        try{active(t);}catch(...){}
    }
    void release_task(Task& t){if(t.task_lease)td::process_resources().release_task(t.task_lease);t.task_lease=0;}
    bool admit_task(Task& t){return td::process_resources().admit_task(t.task_lease);}
    void close_target(Task& t){if(t.file){std::fclose(t.file);t.file=nullptr;}t.lock.reset();t.prepared=false;t.session_range_allowed=false;release_task(t);}
    void checkpoint(Task& t){
        if(!t.file)return;
        std::vector<td::BlockDigest> blocks;blocks.reserve(t.pending.size());for(auto& [offset,b]:t.pending){(void)offset;blocks.push_back(b);}
        td::sync_target(t.file);
        journal->save(t.key,{t.target,t.etag,t.expected,t.validator_origin,t.coverage.bytes(),t.total},blocks);
        t.pending.clear();t.checkpoint_bytes=t.coverage.bytes();t.checkpoint_total=t.total;t.checkpoint_at=Clock::now();
        std::lock_guard l(t.mutex);t.view.durable_bytes=t.checkpoint_bytes;
    }
    void fail(Task& t,uint32_t code,std::string_view message){
        if(t.command.load()!=0 && t.command.load()!=4)return;
        t.failure_code=code;t.failure_message=message;t.command=4;
    }
    void prepare(Task& t){
        auto path=path_of(t.target);if(!path.parent_path().empty())td::fs::create_directories(path.parent_path());
        auto lock_path=path;lock_path+=".turbodl.lock";
        if(td::fs::is_symlink(td::fs::symlink_status(lock_path)))throw std::runtime_error("Unsafe target lock");
        t.lock=std::make_unique<td::FileLock>(lock_path);
        auto saved=journal->load(t.key);bool exists=td::fs::exists(path);t.coverage.clear();t.pending.clear();
        t.stream_hash.reset();
        t.stream_hash_valid=false;
        t.stream_hash_offset=0;
        if(saved){
            if(saved->target!=t.target || saved->expected!=t.expected)throw td::IntegrityError("Checkpoint owner changed");
            if(t.expected_length>=0 && saved->total>=0 && saved->total!=t.expected_length)throw td::IntegrityError("Expected source length changed");
            if(saved->durable && (!exists || !journal->verify(t.key,path,saved->durable)))throw td::IntegrityError("Saved bytes failed integrity verification");
            t.total=saved->total>=0?saved->total:t.expected_length;
            t.etag=saved->etag;t.validator_origin=saved->source_origin;
            if(t.source_length>=0 && t.total!=t.source_length)throw td::IntegrityError("Source projection changed");
            if(t.restart_unverifiable && saved->durable && saved->etag.empty() && saved->expected.empty() && (saved->total<0 || saved->durable<static_cast<uint64_t>(saved->total))){
                journal->erase(t.key);journal->save(t.key,{t.target,"",t.expected,"",0,t.source_length>=0?t.source_length:t.expected_length});
                t.total=t.source_length>=0?t.source_length:t.expected_length;t.etag.clear();t.validator_origin.clear();exists=false;
            }else if(saved->durable && (saved->total<0 || saved->durable<static_cast<uint64_t>(saved->total)))t.whole_file=false;
            for(auto& block:journal->blocks(t.key)){if(t.total>=0 && block.offset+block.length>static_cast<uint64_t>(t.total))throw td::IntegrityError("Saved extent exceeds source length");t.coverage.add(block.offset,block.offset+block.length);}
        }else{
            if(exists){
                if(!t.internal)throw std::runtime_error("Destination already exists without a matching checkpoint");
                // Only the engine-owned media staging area can have orphaned chunks.
                if(td::target_size(path)==0){
                    if(!td::fs::remove(path))throw std::runtime_error("Cannot clear unowned empty destination");
                }else{
                    auto orphan=path;orphan+=".orphan";
                    for(uint32_t i=1;td::fs::exists(orphan);++i){orphan=path;orphan+=".orphan."+std::to_string(i);}
                    td::fs::rename(path,orphan);
                }
                exists=false;
            }
            journal->save(t.key,{t.target,"",t.expected,"",0,t.source_length>=0?t.source_length:t.expected_length});t.total=t.source_length>=0?t.source_length:t.expected_length;t.etag.clear();t.validator_origin.clear();
        }
        t.file=td::open_target(path,exists);if(!t.file)throw std::runtime_error("Destination open failed");
        bool complete=t.total>=0 && t.coverage.bytes()==static_cast<uint64_t>(t.total);
        bool same_source=saved && !t.sources.empty() && saved->source_origin==source_identity(t,t.sources.front());
        if(saved && complete && t.expected.empty() && !same_source)throw ResumeIdentityError("Completed cache source identity changed");
        t.identity_checked=complete && same_source && t.restart_unverifiable;t.range_supported=false;t.session_range_allowed=false;t.checkpoint_bytes=t.coverage.bytes();
        t.checkpoint_total=exists && saved?saved->total:-1;
        std::lock_guard l(t.mutex);t.view.downloaded_bytes=t.coverage.bytes();t.view.durable_bytes=t.coverage.bytes();t.view.total_bytes=t.total;
    }
    static curl_socket_t socket_cb(void* user,curlsocktype,curl_sockaddr* address){
        auto& r=*static_cast<Request*>(user);
        if(!socket_allowed(address,r.task->loopback)){
            r.address_rejected=true;
            return CURL_SOCKET_BAD;
        }
        r.address_allowed=true;
        return ::socket(address->family,address->socktype,address->protocol);
    }
    static size_t header_cb(char* p,size_t a,size_t b,void* user){
        auto& r=*static_cast<Request*>(user);auto& t=*r.task;size_t n=a*b;
        try{
            r.header_bytes+=n;if(r.header_bytes>65536){r.error=TD_ERROR_HTTP;r.message="Response headers exceed limit";return 0;}
            std::string line(p,n);
            if(!r.http)return n;
            if(line.rfind("HTTP/",0)==0){auto i=line.find(' ');if(i==std::string::npos)return 0;uint64_t code=0;if(!number(line.substr(i+1,3),code))return 0;r.status=static_cast<long>(code);r.headers_ready=false;r.content_range.clear();r.etag.clear();r.location.clear();r.retry_ms=0;return n;}
            if(line!="\r\n"){
                auto i=line.find(':');if(i==std::string::npos)return n;auto key=lower(line.substr(0,i)),value=trim(line.substr(i+1));
                if(key=="etag")r.etag=value;
                else if(key=="location")r.location=value;
                else if(key=="content-range")r.content_range=value;
                else if(key=="retry-after"){uint64_t seconds=0;if(number(value,seconds))r.retry_ms=std::min<uint64_t>(seconds,86400)*1000;else{auto until=curl_getdate(value.c_str(),nullptr);auto now=std::time(nullptr);if(until>now)r.retry_ms=std::min<uint64_t>(static_cast<uint64_t>(until-now),86400)*1000;}}
                else if(key=="content-length" && r.status==200 && t.source_length<0){uint64_t length=0;if(!number(value,length))return 0;if(t.total>=0 && (t.coverage.bytes()>0 || t.expected_length>=0) && t.total!=static_cast<int64_t>(length)){r.error=TD_ERROR_SOURCE_CHANGED;r.message="Source length changed";}else t.total=static_cast<int64_t>(length);}
                return n;
            }
            r.headers_ready=true;
            {std::lock_guard l(t.mutex);t.view.http_status=static_cast<uint32_t>(r.status);}
            if(r.status>=300 && r.status<400){r.redirect=true;return n;}
            if(r.status==416 && trim(r.content_range)=="bytes */0"){
                if(t.total>0 || t.expected_length>0 || t.coverage.bytes()>0 || r.assigned.begin>0){r.error=TD_ERROR_SOURCE_CHANGED;r.message="Source became empty; saved bytes retained";}
                else if(t.source_length>=0){r.error=TD_ERROR_RANGE;r.message="Projected range is unavailable";}
                else{r.empty_representation=true;r.assigned.end=0;r.response_end=0;t.total=0;t.identity_checked=true;std::lock_guard lock(t.mutex);t.view.total_bytes=0;}
                return n;
            }
            if(r.status<200 || r.status>=300)return n;
            if(r.status==206){
                unsigned long long start=0,end=0,total=0;int consumed=0;
                if(std::sscanf(r.content_range.c_str(),"bytes %llu-%llu/%llu%n",&start,&end,&total,&consumed)!=3 || consumed!=static_cast<int>(r.content_range.size()) || start!=t.source_offset+r.assigned.begin || end<start || end>=total || end>=t.source_offset+r.assigned.end || total>max_offset || (t.source_length>=0 && total<t.source_offset+static_cast<uint64_t>(t.source_length))){r.error=TD_ERROR_RANGE;r.message="Invalid Content-Range";}
                else if(t.total>=0 && t.source_length<0 && t.total!=static_cast<int64_t>(total)){r.error=TD_ERROR_SOURCE_CHANGED;r.message="Source length changed";}
                else{t.total=t.source_length>=0?t.source_length:static_cast<int64_t>(total);r.response_end=end+1-t.source_offset;r.assigned.end=r.response_end;t.range_supported=true;}
            }else if(r.status==200){
                if(t.source_length>=0 || r.assigned.begin>0 || t.coverage.bytes()>0){r.error=TD_ERROR_RANGE;r.message="Server ignored requested Range";}
                else{t.whole_file=true;t.range_supported=false;r.assigned.end=t.total>=0?static_cast<uint64_t>(t.total):max_offset;r.response_end=r.assigned.end;}
            }else{r.error=TD_ERROR_HTTP;r.message="Unexpected success response";}
            if(!r.error && t.coverage.bytes()>0){
                if(t.expected.empty() && !t.etag.empty() && t.etag!=r.etag){r.error=TD_ERROR_SOURCE_CHANGED;r.message="Source validator changed";}
                else if(t.expected.empty() && (t.validator_origin!=r.source_identity || (t.etag.empty() && !t.session_range_allowed))){r.error=TD_ERROR_IDENTITY_UNVERIFIABLE;r.message="Resume identity cannot be verified";}
            }
            const bool allow_unvalidated_single_source=!t.whole_file && t.sources.size()==1 && (!t.representation_id.empty() || t.connections>1);
            if(!r.error && r.status==206 && !t.whole_file && t.coverage.bytes()==0 && t.expected.empty() && (r.etag.empty() || r.etag.rfind("W/",0)==0) && !allow_unvalidated_single_source)r.restart_whole=true;
            if(!r.error && !r.restart_whole){
                if(t.etag.empty() && !r.etag.empty() && r.etag.rfind("W/",0)!=0)t.etag=r.etag;
                if(t.etag.empty() && t.expected.empty() && r.status==206 && t.coverage.bytes()==0 && allow_unvalidated_single_source)t.session_range_allowed=true;
                t.validator_origin=r.source_identity;
                t.identity_checked=true;
                r.engine->dispatch_ready=true;
            }
            {std::lock_guard l(t.mutex);t.view.total_bytes=t.total;}
            return n;
        }catch(...){r.error=TD_ERROR_HTTP;r.message="Malformed response headers";return 0;}
    }
    static size_t write_cb(char* p,size_t a,size_t b,void* user){
        auto& r=*static_cast<Request*>(user);auto& t=*r.task;size_t n=a*b;
        try{
            if(r.redirect)return n;
            if(r.empty_representation)return 0; // stop an irrelevant error body
            if(t.command.load()!=0 || r.error || r.restart_whole || !r.headers_ready || (r.http && (r.status<200 || r.status>=300)))return 0;
            if(!r.http && t.total<0){curl_off_t length=-1;curl_easy_getinfo(r.easy,CURLINFO_CONTENT_LENGTH_DOWNLOAD_T,&length);if(length>=0 && static_cast<uint64_t>(length)<=max_offset-r.assigned.begin){t.total=static_cast<int64_t>(length+r.assigned.begin);std::lock_guard lock(t.mutex);t.view.total_bytes=t.total;}}
            if(!block_available(r) || n>r.blocks.front().size()-r.block_used){
                r.engine->flush(r);
                if(!block_available(r)){r.paused=true;return CURL_WRITEFUNC_PAUSE;}
            }
            if(r.cursor>r.assigned.end || (!r.tail_splits && n>r.assigned.end-r.cursor)){r.error=TD_ERROR_RANGE;r.message="Body exceeds requested extent";return 0;}
            const auto accepted=r.tail_splits?std::min<uint64_t>(n,r.assigned.end-r.cursor):n;
            if(t.maximum_bytes && (r.cursor>t.maximum_bytes || accepted>t.maximum_bytes-r.cursor)){r.error=TD_ERROR_POLICY;r.message="Response exceeds task byte limit";return 0;}
            if(!td::process_resources().receive(n)){r.paused=true;return CURL_WRITEFUNC_PAUSE;}
            std::memcpy(r.blocks[r.block_index].data()+r.block_used,p,accepted);r.block_used+=accepted;r.cursor+=accepted;r.body_bytes+=accepted;
            auto now=Clock::now();
            r.last_body_at=now;
            if(r.first_byte_at==Clock::time_point{}){r.first_byte_at=now;r.sample_at=now;}
            auto seconds=std::chrono::duration<double>(now-r.sample_at).count();
            if(seconds>=.05){r.bytes_per_second=static_cast<double>(r.body_bytes-r.sampled_bytes)/seconds;r.sampled_bytes=r.body_bytes;r.sample_at=now;}
            {std::lock_guard l(t.mutex);if(t.view.state!=TD_PAUSING)t.view.state=TD_TRANSFERRING;if(!t.metrics.task_first_byte_microseconds)t.metrics.task_first_byte_microseconds=static_cast<uint64_t>(std::max<int64_t>(1,std::chrono::duration_cast<std::chrono::microseconds>(Clock::now()-t.submitted_at).count()));}
            return accepted==n?n:0;
        }catch(...){r.error=TD_ERROR_STORAGE;r.message="Destination write failed";return 0;}
    }
    static int progress_cb(void* user,curl_off_t,curl_off_t,curl_off_t,curl_off_t){return static_cast<Request*>(user)->task->command.load()!=0;}
    void start(Task& t,td::Range range){
        const auto budget=td::process_resources().buffer_ceiling();
        constexpr auto minimum=td::ResourceDomain::request_buffer_bytes;
        if(budget<minimum)return;
        const auto slots=std::min(t.connections,t.host_limit);
        const auto request_budget=std::max(minimum,budget/slots);
        const auto capacity=std::clamp<uint64_t>((range.end-range.begin)/2,32768,
            std::min<uint64_t>(512*1024,(request_budget-16384)/2));
        const auto payload_bytes=capacity*2+16384;
        size_t selected=0;uint64_t lease=0;std::string source_origin,budget_key;
        for(size_t i=0;i<t.sources.size();++i){
            selected=(t.next_source+i)%t.sources.size();auto& source=t.sources[selected];if(source.disabled)continue;
            source_origin=origin(source.url);budget_key=source_origin+":"+t.cooldown_group;
            lease=td::process_resources().acquire(budget_key,t.host_limit,url_part(source.url,CURLUPART_SCHEME)=="ftp"?2:1,payload_bytes);
            if(lease){t.next_source=(selected+1)%t.sources.size();break;}
        }
        if(!lease)return;
        auto& source=t.sources[selected];
        std::unique_ptr<Request> request;try{request=std::make_unique<Request>(static_cast<size_t>(capacity));}catch(...){td::process_resources().release(lease);throw;}
        auto& r=*request;r.engine=this;r.task=&t;r.source_index=selected;r.url=source.url;r.source_origin=source_origin;r.source_identity=source_identity(t,source);r.budget_key=budget_key;r.lease_id=lease;r.headers=source.headers;r.assigned=range;r.cursor=range.begin;r.response_end=range.end;r.http=http_url(source.url);r.headers_ready=!r.http;
        if(t.connections==1 && t.source_offset==0 && range.begin==0 && t.coverage.bytes()==0 && !t.stream_hash){
            t.stream_hash=std::make_unique<td::Sha256State>();t.stream_hash_offset=0;t.stream_hash_valid=true;
        }
        {std::lock_guard l(t.mutex);r.generation=t.view.generation;}
        if(!t.idle_easies.empty()){r.easy=t.idle_easies.back();t.idle_easies.pop_back();curl_easy_reset(r.easy);}
        else{r.easy=curl_easy_init();if(!r.easy)throw std::bad_alloc();}
        auto set=[&](CURLoption option,auto value){
            auto result=curl_easy_setopt(r.easy,option,value);
            if(result!=CURLE_OK){
                auto* descriptor=curl_easy_option_by_id(option);
                std::string name=descriptor && descriptor->name?descriptor->name:"unknown";
                throw std::runtime_error("Unsupported HTTP option "+name+": "+curl_easy_strerror(result));
            }
        };
        set(CURLOPT_URL,r.url.c_str());set(CURLOPT_PROTOCOLS_STR,allowed_protocols().c_str());set(CURLOPT_FOLLOWLOCATION,0L);set(CURLOPT_PROXY,t.proxy.c_str());set(CURLOPT_NOSIGNAL,1L);
        set(CURLOPT_SUPPRESS_CONNECT_HEADERS,1L);
        if(t.range_retries.contains(range.begin))set(CURLOPT_FRESH_CONNECT,1L);
        if(r.source_index==0 && origin(r.url)==t.authentication_origin){
            if(!t.username.empty())set(CURLOPT_USERNAME,t.username.c_str());if(!t.password.empty())set(CURLOPT_PASSWORD,t.password.c_str());
            if(!t.public_key.empty())set(CURLOPT_SSH_PUBLIC_KEYFILE,t.public_key.c_str());if(!t.private_key.empty())set(CURLOPT_SSH_PRIVATE_KEYFILE,t.private_key.c_str());
        }
        if(!t.ca_bundle.empty())set(CURLOPT_CAINFO,t.ca_bundle.c_str());if(!t.doh.empty())set(CURLOPT_DOH_URL,t.doh.c_str());
        if(!t.known_hosts.empty())set(CURLOPT_SSH_KNOWNHOSTS,t.known_hosts.c_str());
        // SSH trust files may change without their path changing. A new task or
        // resume must authenticate a new SSH session against the current file.
        if(url_part(r.url,CURLUPART_SCHEME)=="sftp"){set(CURLOPT_FRESH_CONNECT,1L);set(CURLOPT_FORBID_REUSE,1L);}
        if(t.http_version==1)set(CURLOPT_HTTP_VERSION,static_cast<long>(CURL_HTTP_VERSION_1_1));else if(t.http_version==2)set(CURLOPT_HTTP_VERSION,static_cast<long>(CURL_HTTP_VERSION_2TLS));else if(t.http_version==3)set(CURLOPT_HTTP_VERSION,static_cast<long>(CURL_HTTP_VERSION_3));
        set(CURLOPT_CONNECTTIMEOUT_MS,static_cast<long>(t.connect_ms));set(CURLOPT_LOW_SPEED_LIMIT,1L);set(CURLOPT_LOW_SPEED_TIME,static_cast<long>((t.read_ms+999)/1000));set(CURLOPT_BUFFERSIZE,16384L);
        set(CURLOPT_HEADERFUNCTION,&header_cb);set(CURLOPT_HEADERDATA,&r);set(CURLOPT_WRITEFUNCTION,&write_cb);set(CURLOPT_WRITEDATA,&r);set(CURLOPT_XFERINFOFUNCTION,&progress_cb);set(CURLOPT_XFERINFODATA,&r);set(CURLOPT_NOPROGRESS,0L);set(CURLOPT_PRIVATE,&r);
        set(CURLOPT_OPENSOCKETFUNCTION,&socket_cb);set(CURLOPT_OPENSOCKETDATA,&r);set(CURLOPT_ACCEPT_ENCODING,"identity");set(CURLOPT_HTTP_CONTENT_DECODING,0L);
        set(CURLOPT_SSL_VERIFYPEER,1L);set(CURLOPT_SSL_VERIFYHOST,2L);
#ifdef _WIN32
        set(CURLOPT_SSL_OPTIONS,static_cast<long>(CURLSSLOPT_NATIVE_CA));
#endif
        r.open_ended=r.http && range.end==max_offset;
        r.range_value=std::to_string(t.source_offset+range.begin)+"-"+(r.open_ended?"":std::to_string(t.source_offset+range.end-1));
        if(r.http && (!t.whole_file || t.source_length>=0))set(CURLOPT_RANGE,r.range_value.c_str());
        if(!r.http && range.begin>0)set(CURLOPT_RESUME_FROM_LARGE,static_cast<curl_off_t>(range.begin));
        for(auto& [name,value]:r.headers){auto* list=curl_slist_append(r.curl_headers,(name+": "+value).c_str());if(!list)throw std::bad_alloc();r.curl_headers=list;}
        if(range.begin>0 && !t.etag.empty() && r.source_identity==t.validator_origin){auto* list=curl_slist_append(r.curl_headers,("If-Range: "+t.etag).c_str());if(!list)throw std::bad_alloc();r.curl_headers=list;}
        set(CURLOPT_HTTPHEADER,r.curl_headers);
        if(curl_multi_add_handle(multi,r.easy)!=CURLM_OK)throw std::runtime_error("Cannot schedule request");
        t.requests.push_back(std::move(request));{std::lock_guard l(t.mutex);++t.view.request_count;}active(t);
    }
    void completion(Request& r,CURLcode result){
        Task& t=*r.task;auto& source=t.sources.at(r.source_index);active(t);
        if(t.command.load()!=0)return;
        if(!r.error && result==CURLE_COULDNT_CONNECT && r.address_rejected && !r.address_allowed){
            r.error=TD_ERROR_POLICY;
            r.message="Destination rejected by network policy";
        }
        if(r.tail_splits && r.cursor==r.assigned.end && (result==CURLE_WRITE_ERROR || result==CURLE_ABORTED_BY_CALLBACK))result=CURLE_OK;
        if(r.restart_whole){t.whole_file=true;t.identity_checked=false;return;}
        if(r.redirect){
            auto url=redirect_url(r.url,r.location);
            if(r.location.empty() || ++source.redirects>10 || !http_url(url) || (url_part(r.url,CURLUPART_SCHEME)=="https" && url_part(url,CURLUPART_SCHEME)!="https")){r.error=TD_ERROR_POLICY;r.message="Redirect rejected";}
            else{
                if(origin(url)!=origin(r.url))std::erase_if(source.headers,[](auto& h){auto n=lower(h.first);return n!="user-agent" && n!="accept" && n!="accept-language";});
                source.url=std::move(url);return;
            }
        }
        auto alternative=[&](){return t.sources.size()>1 && std::any_of(t.sources.begin(),t.sources.end(),[&](const auto& other){return &other!=&source && !other.disabled;});};
        auto independent_alternative=[&](){
            return t.sources.size()>1 && std::any_of(t.sources.begin(),t.sources.end(),[&](const auto& other){
                return &other!=&source && !other.disabled && origin(other.url)!=r.source_origin;
            });
        };
        if(r.error){if(alternative()){source.disabled=true;return;}fail(t,r.error,r.message);return;}
        if(r.empty_representation)return;
        if(r.status==429 || r.status==503){
            auto wait=std::max<uint64_t>(r.retry_ms,250);td::process_resources().cooldown(r.budget_key,wait);
            if(wait>30000 || ++source.failures>t.retry_count){if(alternative()){source.disabled=true;return;}fail(t,TD_ERROR_RATE_LIMITED,"Source rate limited; saved bytes retained");return;}
            // A same-origin mirror cannot bypass the host cooldown. Wake the
            // task at the cooldown boundary instead of relying on the generic
            // one-second idle poll. An independent origin may proceed now.
            if(!independent_alternative())t.retry_at=Clock::now()+std::chrono::milliseconds(wait);return;
        }
        if(r.status>=400){
            if(alternative()){source.disabled=true;return;}
            if(r.status==401 || r.status==403)fail(t,TD_ERROR_AUTH,"Source authentication or authorization failed");
            else fail(t,TD_ERROR_HTTP,"Source returned an HTTP error");
            return;
        }
        if(result!=CURLE_OK){
            if(result==CURLE_PEER_FAILED_VERIFICATION || result==CURLE_SSL_CACERT_BADFILE || result==CURLE_SSL_ISSUER_ERROR){fail(t,TD_ERROR_TRUST,"Server identity verification failed");return;}
            if(result==CURLE_LOGIN_DENIED){fail(t,TD_ERROR_AUTH,"Source authentication failed");return;}
            if(alternative()){if(++source.failures>t.retry_count)source.disabled=true;else td::process_resources().cooldown(r.budget_key,500);return;}
            // Parallel ranges do not consume each other's retry allowance.
            // After verified writes advance the cursor, retire the old hole.
            if(r.cursor>r.assigned.begin)t.range_retries.erase(r.assigned.begin);
            auto& retry=t.range_retries[r.cursor];
            if(++retry.attempts>t.retry_count){fail(t,TD_ERROR_NETWORK,"Network retry limit reached; saved bytes retained");return;}
            retry.end=r.assigned.end;
            if(retry.attempts==1 && r.reused_connection && r.body_bytes==0 &&
               (result==CURLE_RECV_ERROR || result==CURLE_SEND_ERROR || result==CURLE_GOT_NOTHING)){
                retry.ready_at={};
                return;
            }
            retry.ready_at=Clock::now()+std::chrono::milliseconds(250u<<std::min(retry.attempts,5u));
            return;
        }
        if(t.total<0){t.total=static_cast<int64_t>(r.cursor);std::lock_guard lock(t.mutex);t.view.total_bytes=t.total;}
        if(r.body_bytes==0 && t.total!=0){fail(t,TD_ERROR_RANGE,"Response made no progress");return;}
        t.range_retries.erase(r.assigned.begin);
        if(r.body_bytes>=4096 && r.first_byte_at!=Clock::time_point{}){
            const auto finished_at=Clock::now();
            const auto seconds=std::chrono::duration<double>(finished_at-r.first_byte_at).count();
            auto& speed=t.speeds[r.source_identity];
            speed.last_completed_at=finished_at;
            speed.bytes_per_second=std::max(speed.bytes_per_second,r.body_bytes/std::max(.001,seconds));
            const auto startup=std::chrono::duration<double>(r.first_byte_at-r.started_at).count();
            if(speed.startup_seconds==0 || startup<speed.startup_seconds)speed.startup_seconds=startup;
        }
    }
    void reclaim_stalled(Task& t){
        if(!t.range_supported || !t.retry_count)return;
        if(td::process_resources().speed_limited())return;
        auto now=Clock::now();
        auto eligible=[](const Request& req){
            return req.easy && !req.network_done && !req.paused && req.http && req.headers_ready &&
                   !req.error && req.status==206 && req.first_byte_at!=Clock::time_point{} &&
                   req.last_body_at!=Clock::time_point{} && req.cursor<req.assigned.end;
        };
        size_t active_eligible=0;
        size_t all_stalled_count=0;
        const auto all_stalled_threshold=t.read_ms>=6000
            ? std::chrono::milliseconds(std::clamp<uint32_t>(t.read_ms/2,4000,10000))
            : std::chrono::milliseconds{};
        bool any_reclaimed=false;
        for(const auto& request:t.requests){
            auto& slow=*request;
            if(!eligible(slow))continue;
            ++active_eligible;
            const auto idle=now-slow.last_body_at;
            bool should_reclaim=false;
            auto found=t.speeds.find(slow.source_identity);
            if(found!=t.speeds.end() &&
               found->second.last_completed_at>slow.last_body_at &&
               idle>=std::chrono::seconds(2)){
                should_reclaim=true;
            }else if(idle>=std::chrono::seconds(4)){
                for(const auto& other:t.requests){
                    const auto& peer=*other;
                    if(&peer==&slow || !eligible(peer) || peer.source_identity!=slow.source_identity)continue;
                    if(peer.last_body_at>slow.last_body_at && now-peer.last_body_at<std::chrono::seconds(2)){
                        should_reclaim=true;
                        break;
                    }
                }
            }
            if(should_reclaim){
                slow.network_result=CURLE_OPERATION_TIMEDOUT;
                detach(slow);
                flush(slow);
                any_reclaimed=true;
            }else if(all_stalled_threshold.count()>0 && idle>=all_stalled_threshold){
                ++all_stalled_count;
            }
        }
        if(!any_reclaimed && active_eligible>0 && active_eligible==t.requests.size() && all_stalled_count==active_eligible){
            for(const auto& request:t.requests){
                auto& slow=*request;
                if(!eligible(slow))continue;
                slow.network_result=CURLE_OPERATION_TIMEDOUT;
                detach(slow);
                flush(slow);
            }
        }
    }
    bool assist_tail(Task& t){
        if(t.sources.size()!=1)return false;
        auto now=Clock::now();
        for(const auto& request:t.requests){
            auto& slow=*request;
            if(!slow.easy || slow.network_done || slow.paused || !slow.http || !slow.headers_ready || slow.error || slow.first_byte_at==Clock::time_point{} || !t.range_supported || slow.cursor>=slow.assigned.end)continue;
            auto found=t.speeds.find(slow.source_identity);
            const bool has_sample=found!=t.speeds.end();
            if(slow.tail_splits>=2 || slow.assigned.end-slow.cursor<8192 ||
               std::chrono::duration<double>(now-slow.first_byte_at).count()<.05 || slow.bytes_per_second<=0)continue;
            if(has_sample && found->second.bytes_per_second<slow.bytes_per_second*1.5)continue;
            if(!has_sample && (slow.tail_splits || t.requests.size()!=1 || slow.assigned.begin!=0 ||
                               t.total<0 || slow.assigned.end!=static_cast<uint64_t>(t.total)))continue;
            const auto remaining=slow.assigned.end-slow.cursor;
            const auto first_startup=std::chrono::duration<double>(slow.first_byte_at-slow.started_at).count();
            const auto startup=std::max(.005,has_sample?found->second.startup_seconds:first_startup);
            // A prefix may cover the entire object. Without a reference
            // sample, require enough remaining work for an equally fast peer.
            if(remaining/slow.bytes_per_second<=std::max(has_sample?.1:.25,startup*4))continue;
            const auto fast=has_sample?found->second.bytes_per_second:slow.bytes_per_second;
            const auto share=slow.bytes_per_second*(remaining+fast*startup)/(slow.bytes_per_second+fast);
            const auto retained=std::clamp<uint64_t>(static_cast<uint64_t>(share),4096,remaining-4096);
            const auto cut=slow.cursor+retained;
            auto count=t.requests.size();start(t,{cut,slow.assigned.end});
            if(t.requests.size()==count)return false;
            // The old response can only commit up to the new boundary. Its
            // buffered bytes precede cursor; in-flight disk jobs remain owned.
            slow.assigned.end=cut;
            ++slow.tail_splits;
            return true;
        }
        return false;
    }
    void start_checkpoint(Task& t){
        if(t.io.valid() || !t.file)return;
        std::vector<td::BlockDigest> blocks;t.committing_offsets.clear();
        for(auto& [offset,b]:t.pending){blocks.push_back(b);t.committing_offsets.push_back(offset);}
        auto saved=td::Saved{t.target,t.etag,t.expected,t.validator_origin,t.coverage.bytes(),t.total};
        t.committing_bytes=saved.durable;t.committing_total=saved.total;
        t.io_purpose=2;auto* file=t.file;auto* db=journal.get();auto key=t.key;
        t.io=disk.submit([db,key,saved,file,blocks=std::move(blocks)]{
            td::sync_target(file);
            db->save(key,saved,blocks);
            return std::string{};
        });
    }
    bool collect_io(Task& t,bool wait=false){
        if(!t.io.valid())return true;
        if(!wait && t.io.wait_for(std::chrono::seconds(0))!=std::future_status::ready)return false;
        auto purpose=t.io_purpose;t.io_purpose=0;
        try{
            auto value=t.io.get();
            if(purpose==1)t.prepared=true;
            else if(purpose==4)t.prepared=true;
            else if(purpose==2){
                for(auto offset:t.committing_offsets)t.pending.erase(offset);
                t.checkpoint_bytes=t.committing_bytes;
                t.checkpoint_total=t.committing_total;
                t.checkpoint_at=Clock::now();
                std::lock_guard lock(t.mutex);
                t.view.durable_bytes=t.checkpoint_bytes;
            }
            else if(purpose==3 && !t.command.load() && !stopping.load()){
                {std::lock_guard lock(t.mutex);text(t.view.sha256,value);}
                close_target(t);
                if(!t.expected.empty() && value!=t.expected)state(t,TD_FAILED,TD_ERROR_INTEGRITY,"SHA-256 mismatch; file is not complete");else state(t,TD_COMPLETED);
            }
        }catch(const td::ResourceBusy&){t.prepared=false;release_task(t);t.retry_at=Clock::now()+std::chrono::milliseconds(50);state(t,TD_QUEUED);}
        catch(const td::IntegrityError&){fail(t,TD_ERROR_INTEGRITY,"Saved bytes failed integrity verification");}
        catch(const ResumeIdentityError&){fail(t,TD_ERROR_IDENTITY_UNVERIFIABLE,"Saved source identity cannot be verified; cache retained");}
        catch(const std::invalid_argument&){fail(t,purpose==4?TD_ERROR_POLICY:TD_ERROR_STORAGE,purpose==4?"Invalid torrent metadata or selection":"Invalid storage request");}
        catch(...){if(purpose==2){t.checkpoint_failed=true;t.failure_code=TD_ERROR_STORAGE;t.failure_message="Disk checkpoint failed; prior checkpoint retained";t.command=4;}else if(!t.command.load())fail(t,TD_ERROR_STORAGE,"Disk operation failed; prior checkpoint retained");}
        return true;
    }
    void controls(Task& t){
        auto command=t.command.load();if(!command)return;
        if(command==TD_PAUSE || command==TD_CANCEL || command==4){
            bool pending=false;
            for(auto& r:t.requests){detach(*r);collect_write(*r);flush(*r);pending=pending || pending_writes(*r) || r->block_used;}
            if(pending)return;
            t.requests.clear();active(t);
            if(t.file && !t.checkpoint_failed && t.coverage.bytes()!=t.checkpoint_bytes){start_checkpoint(t);return;}
            close_target(t);t.command=0;
            if(command==4)state(t,TD_FAILED,t.failure_code,t.failure_message);else state(t,command==TD_PAUSE?TD_PAUSED:TD_CANCELLED);
        }
        else if(command==TD_RESUME){t.command=0;t.range_retries.clear();t.speeds.clear();t.checkpoint_failed=false;t.retry_at={};{std::lock_guard l(t.mutex);if(t.source_replaced){t.url=std::move(t.replacement_url);t.headers=std::move(t.replacement_headers);t.representation_id=std::move(t.replacement_representation);t.sources={{t.url,t.representation_id,t.headers}};t.source_replaced=false;t.redirects=0;t.whole_file=false;}for(auto& source:t.sources){source.disabled=false;source.failures=0;}++t.view.generation;}state(t,TD_QUEUED);}
    }
    void step(Task& t,bool allow_task){
        if(t.media){step_media(t);return;}
        if(!collect_io(t))return;
        if(t.torrent){step_torrent(t,allow_task);return;}
        for(auto& r:t.requests){collect_write(*r);if(r->network_done && r->block_used)flush(*r);}
        controls(t);auto s=state_of(t);if(terminal(s) || s==TD_PAUSED)return;
        if(t.command.load()!=0 || t.io.valid())return;
        if(!allow_task && !t.prepared)return;
        if(!t.prepared){if(!admit_task(t))return;state(t,TD_PROBING);t.io_purpose=1;t.io=disk.submit([this,&t]{prepare(t);return std::string{};});return;}
        reclaim_stalled(t);
        for(auto& r:t.requests){
            if(r->network_done && !pending_writes(*r) && !r->block_used)completion(*r,r->network_result);
            if(t.command.load()!=0)break;
            if(r->paused && block_available(*r) && r->easy){r->paused=false;r->last_body_at=Clock::now();curl_easy_pause(r->easy,CURLPAUSE_CONT);}
        }
        std::erase_if(t.requests,[](auto& r){return r->network_done && !pending_writes(*r) && !r->block_used;});
        if(t.command.load()!=0)return;
        if(t.coverage.bytes()>0 && t.etag.empty() && t.expected.empty() && !t.identity_checked){fail(t,TD_ERROR_IDENTITY_UNVERIFIABLE,"Resume identity cannot be verified; saved bytes retained");return;}
        if(t.total>=0 && t.coverage.bytes()==static_cast<uint64_t>(t.total) && t.requests.empty()){
            if(t.coverage.bytes()!=t.checkpoint_bytes || t.total!=t.checkpoint_total){start_checkpoint(t);return;}
            state(t,TD_VERIFYING);t.io_purpose=3;
            std::string stream_digest;bool stream_verified=false;
            if(t.stream_hash_valid && t.stream_hash && t.stream_hash_offset==static_cast<uint64_t>(t.total)){
                stream_digest=t.stream_hash->finish();stream_verified=true;
            }
            t.io=disk.submit([this,&t,stream_digest=std::move(stream_digest),stream_verified]{
                const auto target = path_of(t.target);
                const auto size = static_cast<uint64_t>(t.total);
                if(td::target_size(target)!=size){
                    td::fs::resize_file(target,size);
                    td::sync_target(t.file);
                }
                if(stream_verified)return stream_digest;
                return td::sha256_file(target,[this,&t]{return stopping.load() || t.command.load()!=0;});
            });return;
        }
        if(Clock::now()<t.retry_at)return;
        // Bound crash loss by bytes and time. A fixed small block count made a
        // fast single-stream transfer synchronize SQLite and the target every
        // 1 MiB, turning local disk latency into download throughput.
        // The one-second timer already bounds crash loss for slow links. A
        // larger byte interval avoids forcing a synchronous disk flush in the
        // middle of short fast transfers while still capping fast-link loss.
        constexpr uint64_t checkpoint_size_limit=64*1024*1024;
        if(t.coverage.bytes()>t.checkpoint_bytes && (t.coverage.bytes()-t.checkpoint_bytes>=checkpoint_size_limit || Clock::now()-t.checkpoint_at>std::chrono::seconds(1))){start_checkpoint(t);return;}
        uint32_t ceiling=t.identity_checked && t.range_supported && (!t.etag.empty() || !t.expected.empty() || t.session_range_allowed)?t.connections:1;
        if(t.requests.size()>=ceiling)return;
        std::vector<td::Range> reserved;for(auto& r:t.requests)reserved.push_back(r->assigned);
        const auto now=Clock::now();
        for(const auto& [begin,retry]:t.range_retries){
            if(retry.ready_at<=now)continue;
            if(ceiling==1)return;
            if(begin<retry.end)reserved.push_back({begin,retry.end});
        }
        auto total=t.total>=0?static_cast<uint64_t>(t.total):max_offset;
        while(t.requests.size()<ceiling){
            auto request_window=t.window;
            if(t.total<0 && !t.whole_file && http_url(t.url)){
                // The discovery request also carries useful payload. A single
                // connection asks for the rest of the object; parallel
                // discovery keeps a bounded prefix while peers start.
                request_window=t.connections==1?max_offset:std::min<uint64_t>(t.window,1024*1024);
            }
            if(t.total>0){
                // The first bounded range discovers the representation length.
                // Afterwards size ranges so the configured connections can
                // cover the object without issuing dozens of tiny requests.
                const auto per_connection=(static_cast<uint64_t>(t.total)+t.connections-1)/t.connections;
                request_window=std::clamp<uint64_t>(per_connection,64*1024,64*1024*1024);
                if(t.connections==1)request_window=static_cast<uint64_t>(t.total);
            }
            auto gap=t.coverage.gap(total,(t.whole_file || !http_url(t.url))?total:request_window,reserved);
            if(!gap){assist_tail(t);break;}
            if(auto retry=t.range_retries.lower_bound(gap->begin);retry!=t.range_retries.end()){
                const auto boundary=retry->first==gap->begin?retry->second.end:retry->first;
                if(boundary>gap->begin)gap->end=std::min(gap->end,boundary);
            }
            auto before=t.requests.size();start(t,*gap);
            if(t.requests.size()==before)break;
            reserved.push_back(*gap);
        }
    }
    void step_media(Task& t){
#ifdef TD_WITH_MEDIA
        auto command=t.command.exchange(0);
        if(command==TD_RESUME){t.media_job.reset();{std::lock_guard lock(t.mutex);++t.view.generation;}state(t,TD_QUEUED);}
        else if(command==TD_PAUSE || command==TD_CANCEL){if(t.media_job)t.media_job->control(command);else state(t,command==TD_CANCEL?TD_CANCELLED:TD_PAUSED);}
        else if(command==4){if(t.media_job)t.media_job->fail(t.failure_code,t.failure_message);else state(t,TD_FAILED,t.failure_code,t.failure_message);}
        auto current=state_of(t);if(terminal(current) || current==TD_PAUSED)return;
        if(!t.media_job){
            td::MediaHost host;
            host.submit=[this,&t](const td::MediaFetch& fetch){
                Headers headers=t.headers;bool same=origin(fetch.url)==t.authentication_origin;
                if(!same)std::erase_if(headers,[](const auto& h){auto n=lower(h.first);return n!="user-agent" && n!="accept" && n!="accept-language";});
                std::vector<td_header> values;for(const auto& [name,value]:headers)values.push_back({name.c_str(),value.c_str()});
                td_network_options network{sizeof(network),TD_ABI_VERSION,same?t.username.c_str():nullptr,same?t.password.c_str():nullptr,t.proxy.c_str(),t.ca_bundle.c_str(),nullptr,nullptr,nullptr,t.doh.c_str(),t.http_version,0};
                td_transfer_policy policy{sizeof(policy),TD_ABI_VERSION,t.retry_count,t.host_limit,t.caller_group.c_str()};
                td_request_slice slice{};slice.struct_size=sizeof(slice);slice.abi_version=TD_ABI_VERSION;
                if(fetch.range){slice.offset=fetch.range->offset;slice.length=fetch.range->length;}
                auto destination=td::utf8(fetch.path);td_task_options request{};request.struct_size=sizeof(request);request.abi_version=TD_ABI_VERSION;request.url=fetch.url.c_str();request.destination=destination.c_str();request.stable_key=fetch.key.c_str();request.headers=values.data();request.header_count=static_cast<uint32_t>(values.size());request.connections=1;request.request_window_bytes=67108864;request.connect_timeout_ms=t.connect_ms;request.read_timeout_ms=t.read_ms;request.network=&network;request.policy=&policy;request.maximum_response_bytes=fetch.maximum_bytes;request.representation_id=fetch.key.c_str();
                if(fetch.range)request.slice=&slice;else request.transfer_flags=TD_TRANSFER_WHOLE_FILE|TD_TRANSFER_RESTART_UNVERIFIABLE_CACHE;
                if(fetch.expected_size>=0)request.expected_size=&fetch.expected_size;
                td_task_id id=0;auto result=submit_task(this,&request,&id,nullptr,true);
                if(result==TD_BUSY)throw td::ResourceBusy("Media fetch admission is busy");if(result!=TD_OK)throw std::invalid_argument("Media fetch options rejected");return id;
            };
            host.query=[this](uint64_t id){
                std::lock_guard lock(mutex);auto found=tasks.find(id);if(found==tasks.end())throw std::runtime_error("Media child task missing");auto& child=*found->second;
                std::lock_guard child_lock(child.mutex);td::MediaFetchState result;result.snapshot=child.view;result.metrics=child.metrics;result.effective_url=child.sources.empty()?child.url:child.sources.front().url;
                for(const auto& request:child.requests)if(request->easy){curl_off_t connection=-1;curl_easy_getinfo(request->easy,CURLINFO_CONN_ID,&connection);if(connection>=0)result.connections.push_back(connection);}
                return result;
            };
            host.control=[this](uint64_t id,uint32_t value){auto result=td_task_control(this,id,value);if(result!=TD_OK && result!=TD_BUSY)throw std::runtime_error("Media child control failed");};
            host.forget=[this](uint64_t id){if(td_task_forget(this,id)!=TD_OK)throw std::runtime_error("Media child retirement failed");};
            host.erase_checkpoint=[this](const std::string& key){journal->erase(td::sha256(key));};
            t.media_job=td::create_media_job(t.media_options,directory/"media",disk,std::move(host));
        }
        if(t.finish_media.exchange(false))t.media_job->finish();
        auto old=state_of(t);t.media_job->tick();auto value=t.media_job->snapshot();
        {std::lock_guard lock(t.mutex);value.task_id=t.id;value.generation=t.view.generation;t.view=value;t.metrics=t.media_job->metrics();t.media_details=t.media_job->details();}
        emit(t,old==value.state?TD_EVENT_PROGRESS:TD_EVENT_STATE);
#else
        state(t,TD_FAILED,TD_ERROR_POLICY,"Media support was not built");
#endif
    }
    void step_torrent(Task& t,bool allow_task){
#ifdef TD_WITH_BITTORRENT
        auto command=t.command.load();
        if(command==TD_RESUME){t.torrent_job.reset();t.prepared=false;t.command=0;{std::lock_guard lock(t.mutex);++t.view.generation;}state(t,TD_QUEUED);}
        else if(command==4){t.torrent_job.reset();t.prepared=false;release_task(t);t.command=0;state(t,TD_FAILED,t.failure_code,t.failure_message);return;}
        else if(command==TD_PAUSE || command==TD_CANCEL){if(t.torrent_job)t.torrent_job->control(command);else{state(t,command==TD_PAUSE?TD_PAUSED:TD_CANCELLED);t.prepared=false;}t.command=0;}
        if(!t.torrent_job){if(terminal(state_of(t)) || state_of(t)==TD_PAUSED || !allow_task || Clock::now()<t.retry_at)return;if(!admit_task(t))return;state(t,TD_PROBING);t.io_purpose=4;t.io=disk.submit([this,&t]{t.torrent_job=td::create_torrent_job(t.torrent_options,directory/"torrent");return std::string{};});return;}
        std::vector<std::pair<std::string,uint16_t>> peers;{std::lock_guard lock(t.mutex);peers.swap(t.peer_requests);}
        for(auto& [host,port]:peers)t.torrent_job->connect_peer(host,port);
        auto old=state_of(t);t.torrent_job->tick();auto value=t.torrent_job->snapshot();
        {std::lock_guard lock(t.mutex);value.task_id=t.id;value.generation=t.view.generation;t.view=value;}
        if(value.state==TD_PAUSED || terminal(value.state)){t.prepared=false;release_task(t);}
        emit(t,old==value.state?TD_EVENT_PROGRESS:TD_EVENT_STATE);
#else
        (void)allow_task;state(t,TD_FAILED,TD_ERROR_POLICY,"BitTorrent module was not built");
#endif
    }
    void run() noexcept{
        try{
            while(!stopping){
                auto current=list();if(!current.empty())std::rotate(current.begin(),current.begin()+(rotation++%current.size()),current.end());
                uint32_t admitted=0;for(auto& t:current)if(t->prepared || t->io_purpose==1 || t->io_purpose==4)++admitted;
                for(auto& t:current){try{bool before=t->prepared || t->io_purpose==1 || t->io_purpose==4;step(*t,admitted<max_tasks);bool after=t->prepared || t->io_purpose==1 || t->io_purpose==4;if(!before && after)++admitted;if(before && !after && admitted)--admitted;}catch(const td::IntegrityError&){fail(*t,TD_ERROR_INTEGRITY,"Saved bytes failed integrity verification");}catch(const std::exception& e){if(t->command.load()==0 && !stopping.load())fail(*t,TD_ERROR_STORAGE,std::string("Storage operation failed: ")+e.what()+"; checkpoint retained");}catch(...){if(t->command.load()==0 && !stopping.load())fail(*t,TD_ERROR_STORAGE,"Storage operation failed; checkpoint retained");}}
                int running=0;auto performed=curl_multi_perform(multi,&running);if(performed!=CURLM_OK)throw std::runtime_error(std::string("HTTP event loop failed: ")+curl_multi_strerror(performed));
                int left=0;
                while(auto* message=curl_multi_info_read(multi,&left))if(message->msg==CURLMSG_DONE){Request* r=nullptr;curl_easy_getinfo(message->easy_handle,CURLINFO_PRIVATE,&r);if(!r)continue;r->network_result=message->data.result;detach(*r);flush(*r);active(*r->task);}
                for(auto& t:current)if(!t->requests.empty()){active(*t);emit(*t,TD_EVENT_PROGRESS);}
                // Network, completed disk work and resource release wake the
                // reactor. Only protocol/rate/retry timers require a deadline.
                long timeout=dispatch_ready?0:1000;dispatch_ready=false;auto now=Clock::now();
                for(const auto& t:current){
                    auto status=state_of(*t);if(terminal(status) || status==TD_PAUSED)continue;
                    if(t->torrent_job)timeout=std::min(timeout,50L);
                    if(t->media_job)timeout=std::min(timeout,t->media_job->next_timer());
                    if(t->retry_at>now)timeout=std::min(timeout,static_cast<long>(std::max<int64_t>(1,std::chrono::duration_cast<std::chrono::milliseconds>(t->retry_at-now).count())));
                    for(const auto& [offset,retry]:t->range_retries){
                        (void)offset;
                        if(retry.ready_at>now)timeout=std::min(timeout,static_cast<long>(std::max<int64_t>(
                            1,std::chrono::duration_cast<std::chrono::milliseconds>(retry.ready_at-now).count())));
                    }
                    if(t->command.load() && !t->io.valid() && t->requests.empty())timeout=0;
                    if(t->range_supported && !t->requests.empty())timeout=std::min(timeout,250L);
                    for(const auto& request:t->requests){
                        if(request->paused && block_available(*request))timeout=std::min(timeout,10L);
                        if(pending_writes(*request))timeout=std::min(timeout,1L);
                        if(request->network_done && !pending_writes(*request) && !request->block_used)timeout=0;
                    }
                }
                timeout=td::process_resources().next_timer(timeout);
                int n=0;curl_multi_poll(multi,nullptr,0,timeout,&n);
            }
        }catch(...){for(auto& t:list())if(!terminal(state_of(*t)))try{fail(*t,TD_ERROR_NETWORK,"Engine loop failed");}catch(...) {}}
        for(auto& t:list()){
            try{
                if(t->media_job)t->media_job->shutdown();
                collect_io(*t,true);
                if(t->torrent_job){
                    auto s=t->torrent_job->snapshot();
                    if(!terminal(s.state) && s.state!=TD_PAUSED){
                        t->torrent_job->control(TD_PAUSE);
                        auto deadline=Clock::now()+std::chrono::seconds(5);
                        while(Clock::now()<deadline){
                            t->torrent_job->tick();
                            if(t->torrent_job->snapshot().state==TD_PAUSED)break;
                            std::this_thread::sleep_for(std::chrono::milliseconds(5));
                        }
                    }
                    t->torrent_job.reset();
                }
                stop_requests(*t);
                checkpoint(*t);
                close_target(*t);
                if(!terminal(state_of(*t)) && state_of(*t)!=TD_PAUSED)state(*t,TD_PAUSED);
            }catch(...){
                stop_requests(*t);
                try{close_target(*t);}catch(...){}
            }
        }
    }
};

extern "C" {
#ifndef TD_WITH_METALINK
int32_t td_metalink_submit(td_engine*,const char*,uint32_t,const char*,const char*,const td_task_options*,td_task_id*){return TD_UNSUPPORTED;}
#endif
uint32_t td_abi_version(void){return TD_ABI_VERSION;}
int32_t td_engine_resources(td_resource_snapshot* out){
    if(!out || out->struct_size<offsetof(td_resource_snapshot,queued_tasks) || out->abi_version!=TD_ABI_VERSION)return TD_INVALID;
    try{auto value=td::process_resources().snapshot();out->admitted_tasks=value.tasks;out->reserved_connections=value.connections;out->admitted_payload_bytes=value.buffers;out->external_transfers=value.external_transfers;if(out->struct_size>=sizeof(*out))out->queued_tasks=value.queued_tasks;return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_engine_create(const td_engine_options* o,td_engine** out){
    if(!out)return TD_INVALID;*out=nullptr;
    if(!o || o->struct_size<offsetof(td_engine_options,global_speed_limit_bytes_per_second) || o->abi_version!=TD_ABI_VERSION || !o->checkpoint_directory || !*o->checkpoint_directory || o->max_active_tasks<1 || o->max_active_tasks>64 || o->max_total_connections<1 || o->max_total_connections>4096 || o->buffer_budget_bytes<td::ResourceDomain::request_buffer_bytes || (o->flags&~TD_TEST_ALLOW_LOOPBACK))return TD_INVALID;
    try{curl_ready();*out=new td_engine(*o);return TD_OK;}catch(...){return TD_IO_ERROR;}
}
int32_t td_engine_destroy(td_engine* e){try{delete e;return TD_OK;}catch(...){return TD_INTERNAL;}}
int32_t td_engine_set_speed_limit(td_engine* e,uint64_t speed){if(!e || speed>max_offset)return TD_INVALID;try{td::process_resources().set_speed(e->domain_id,speed);return TD_OK;}catch(...){return TD_INTERNAL;}}
static int32_t submit_task(td_engine* e,const td_task_options* o,td_task_id* out,const td::MediaOptions* media,bool internal){
    if(!e || !o || !out || o->struct_size<offsetof(td_task_options,network) || o->abi_version!=TD_ABI_VERSION || !o->url || !o->destination || !*o->destination || !o->stable_key || !*o->stable_key || o->connections<1 || o->connections>512 || o->request_window_bytes<8192 || o->request_window_bytes>67108864 || o->header_count>64 || (o->header_count && !o->headers))return TD_INVALID;
    try{
        if(!url_ok(o->url))return TD_UNSUPPORTED;
        auto t=std::make_shared<Task>();t->url=o->url;t->target=td::logical_path(td::canonical_target(path_of(o->destination)));t->key=td::sha256(o->stable_key);t->expected=o->expected_sha256?lower(o->expected_sha256):"";
        if(!t->expected.empty() && (t->expected.size()!=64 || t->expected.find_first_not_of("0123456789abcdef")!=std::string::npos))return TD_INVALID;
        t->headers=copy_headers(o->headers,o->header_count);
        t->authentication_origin=origin(t->url);
        if(o->struct_size>=offsetof(td_task_options,network)+sizeof(o->network) && o->network){const auto& n=*o->network;if(n.struct_size<sizeof(n) || n.abi_version!=TD_ABI_VERSION || n.http_version>3)return TD_INVALID;auto copy=[](const char* value){return value?std::string(value):std::string{};};t->username=copy(n.username);t->password=copy(n.password);t->proxy=copy(n.proxy);t->ca_bundle=copy(n.ca_bundle);t->known_hosts=copy(n.ssh_known_hosts);t->public_key=copy(n.ssh_public_key);t->private_key=copy(n.ssh_private_key);t->doh=copy(n.doh_url);t->http_version=n.http_version;}
        if(o->struct_size>=offsetof(td_task_options,policy)+sizeof(o->policy) && o->policy){auto& p=*o->policy;if(p.struct_size<sizeof(p) || p.abi_version!=TD_ABI_VERSION || p.retry_count>50 || p.max_connections_per_host>4096)return TD_INVALID;t->retry_count=p.retry_count;t->host_limit=p.max_connections_per_host?p.max_connections_per_host:512;if(p.cooldown_group){t->caller_group=p.cooldown_group;t->cooldown_group=td::sha256(t->caller_group);}}
        if(o->struct_size>=offsetof(td_task_options,slice)+sizeof(o->slice) && o->slice){auto& s=*o->slice;if(s.struct_size<sizeof(s) || s.abi_version!=TD_ABI_VERSION || !s.length || s.offset>max_offset || s.length>max_offset-s.offset || !http_url(t->url))return TD_INVALID;t->source_offset=s.offset;t->source_length=static_cast<int64_t>(s.length);}
        if(o->struct_size>=offsetof(td_task_options,maximum_response_bytes)+sizeof(o->maximum_response_bytes))t->maximum_bytes=o->maximum_response_bytes;
        if(o->struct_size>=offsetof(td_task_options,representation_id)+sizeof(o->representation_id) && o->representation_id)t->representation_id=o->representation_id;
        t->sources.push_back({t->url,t->representation_id,t->headers});
        if(o->struct_size>=offsetof(td_task_options,expected_size) && o->mirror_count){
            if(o->mirror_count>32 || !o->mirrors || t->expected.empty() || !http_url(t->url))return TD_INVALID;
            for(uint32_t i=0;i<o->mirror_count;++i){
                const auto& source=o->mirrors[i];
                if(source.struct_size<offsetof(td_source_options,representation_id) || source.abi_version!=TD_ABI_VERSION || !source.url || !http_url(source.url) || !url_ok(source.url))return TD_INVALID;
                t->sources.push_back({source.url,source.struct_size>=sizeof(source) && source.representation_id?source.representation_id:"",copy_headers(source.headers,source.header_count)});
            }
        }
        if(o->struct_size>=offsetof(td_task_options,transfer_flags) && o->expected_size){if(*o->expected_size<0)return TD_INVALID;t->expected_length=*o->expected_size;}
        if(o->struct_size>=sizeof(td_task_options)){if(o->transfer_flags&~(TD_TRANSFER_WHOLE_FILE|TD_TRANSFER_RESTART_UNVERIFIABLE_CACHE))return TD_INVALID;t->whole_file=(o->transfer_flags&TD_TRANSFER_WHOLE_FILE)!=0;t->restart_unverifiable=(o->transfer_flags&TD_TRANSFER_RESTART_UNVERIFIABLE_CACHE)!=0;if(t->whole_file && t->source_length>=0)return TD_INVALID;}
        t->internal=internal;
        if(media){
            if(e->buffer_budget<4*1024*1024)return TD_UNSUPPORTED;
            if(!http_url(t->url) || t->sources.size()>1 || t->source_length>=0)return TD_UNSUPPORTED;
            t->media=true;t->media_options=*media;t->media_options.url=t->url;t->media_options.target=t->target;t->media_options.key=t->key;t->media_options.expected_sha256=t->expected;t->media_options.expected_size=t->expected_length;
            t->media_options.parallel=std::min(t->media_options.parallel,o->connections);
            std::string identity;for(const auto& part:{t->representation_id.empty()?t->url:t->representation_id,t->cooldown_group,t->expected,std::to_string(t->expected_length),std::to_string(media->protocol),std::to_string(media->maximum_bandwidth),media->audio_language,media->video_id,media->audio_id})identity+=std::to_string(part.size())+":"+part;
            t->media_options.identity=td::sha256(identity);
        }
        if(url_part(t->url,CURLUPART_SCHEME)=="sftp" && t->known_hosts.empty())return TD_INVALID;
        t->connections=o->connections;t->window=o->request_window_bytes;t->connect_ms=o->connect_timeout_ms?o->connect_timeout_ms:10000;t->read_ms=o->read_timeout_ms?o->read_timeout_ms:30000;t->loopback=(e->flags&TD_TEST_ALLOW_LOOPBACK)!=0;
        std::lock_guard lock(e->mutex);if(e->tasks.size()>=128 || e->events.size()>1900)return TD_BUSY;
        for(auto& [id,old]:e->tasks){(void)id;std::lock_guard task_lock(old->mutex);if(!terminal(old->view.state) && (old->key==t->key || old->target==t->target))return TD_BUSY;}
        t->id=++e->next_id;t->view.struct_size=sizeof(td_snapshot);t->view.abi_version=TD_ABI_VERSION;t->view.task_id=t->id;t->view.generation=1;t->view.total_bytes=-1;e->tasks[t->id]=t;*out=t->id;curl_multi_wakeup(e->multi);return TD_OK;
    }catch(const std::invalid_argument&){return TD_INVALID;}catch(...){return TD_INTERNAL;}
}
int32_t td_task_submit(td_engine* e,const td_task_options* o,td_task_id* out){return submit_task(e,o,out,nullptr,false);}
int32_t td_media_submit(td_engine* e,const td_media_options* o,td_task_id* out){
#ifdef TD_WITH_MEDIA
    if(!e || !o || !out || o->struct_size<sizeof(*o) || o->abi_version!=TD_ABI_VERSION || !o->transfer || o->protocol>TD_MEDIA_DASH || o->parallel_segments>16 || o->maximum_segment_bytes>1073741824 || o->recording_seconds>604800)return TD_INVALID;
    try{
        td::MediaOptions media;media.protocol=o->protocol;media.parallel=o->parallel_segments?o->parallel_segments:4;media.maximum_bandwidth=o->maximum_bandwidth;media.maximum_segment_bytes=o->maximum_segment_bytes?o->maximum_segment_bytes:67108864;media.record_seconds=o->recording_seconds;
        if(media.maximum_segment_bytes<16)return TD_INVALID;
        if(o->audio_language)media.audio_language=o->audio_language;if(o->video_representation)media.video_id=o->video_representation;if(o->audio_representation)media.audio_id=o->audio_representation;
        if(media.audio_language.size()>32 || media.video_id.size()>128 || media.audio_id.size()>128)return TD_INVALID;
        return submit_task(e,o->transfer,out,&media,false);
    }catch(...){return TD_INTERNAL;}
#else
    (void)e;(void)o;(void)out;return TD_UNSUPPORTED;
#endif
}
int32_t td_media_finish(td_engine* e,td_task_id id){
    if(!e)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto found=e->tasks.find(id);if(found==e->tasks.end())return TD_NOT_FOUND;auto& t=*found->second;std::lock_guard task_lock(t.mutex);if(!t.media)return TD_UNSUPPORTED;if(terminal(t.view.state) || t.view.state==TD_PAUSED)return TD_BUSY;t.finish_media=true;curl_multi_wakeup(e->multi);return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_media_query(td_engine* e,td_task_id id,td_media_details* out){
    if(!e || !out || out->struct_size<sizeof(*out) || out->abi_version!=TD_ABI_VERSION)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto found=e->tasks.find(id);if(found==e->tasks.end())return TD_NOT_FOUND;std::lock_guard task_lock(found->second->mutex);if(!found->second->media)return TD_UNSUPPORTED;*out=found->second->media_details;out->struct_size=sizeof(*out);out->abi_version=TD_ABI_VERSION;return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_torrent_submit(td_engine* e,const td_torrent_options* o,td_task_id* out){
#ifdef TD_WITH_BITTORRENT
    if(!e || !o || !out || o->struct_size<sizeof(*o) || o->abi_version!=TD_ABI_VERSION || !o->torrent_file_or_magnet || !*o->torrent_file_or_magnet || !o->destination_directory || !*o->destination_directory || !o->stable_key || !*o->stable_key || o->peer_limit<1 || o->peer_limit>256 || o->seed_seconds>86400 || o->selected_file_count>10000 || (o->selected_file_count && !o->selected_files))return TD_INVALID;
    try{auto t=std::make_shared<Task>();t->torrent=true;t->key=td::sha256(o->stable_key);t->target=td::utf8(td::fs::weakly_canonical(td::fs::absolute(path_of(o->destination_directory))));t->torrent_options.source=o->torrent_file_or_magnet;t->torrent_options.destination=t->target;t->torrent_options.key=o->stable_key;t->torrent_options.peers=o->peer_limit;t->torrent_options.seed_seconds=o->seed_seconds;t->torrent_options.test_loopback=(e->flags&TD_TEST_ALLOW_LOOPBACK)!=0;t->torrent_options.notify=[e]{curl_multi_wakeup(e->multi);};for(uint32_t i=0;i<o->selected_file_count;++i){if(o->selected_files[i]<0)return TD_INVALID;t->torrent_options.selected_files.push_back(o->selected_files[i]);}
        auto& selected=t->torrent_options.selected_files;std::sort(selected.begin(),selected.end());selected.erase(std::unique(selected.begin(),selected.end()),selected.end());
        if(o->struct_size>=sizeof(td_torrent_limits)){auto* limits=reinterpret_cast<const td_torrent_limits*>(o);if(limits->download_bytes_per_second>INT32_MAX || limits->upload_bytes_per_second>INT32_MAX)return TD_INVALID;t->torrent_options.download_limit=limits->download_bytes_per_second;t->torrent_options.upload_limit=limits->upload_bytes_per_second;}
        std::lock_guard lock(e->mutex);if(e->tasks.size()>=128)return TD_BUSY;for(auto& [id,old]:e->tasks){(void)id;std::lock_guard task_lock(old->mutex);if(!terminal(old->view.state) && (old->key==t->key || old->target==t->target))return TD_BUSY;}
        t->id=++e->next_id;t->view.struct_size=sizeof(td_snapshot);t->view.abi_version=TD_ABI_VERSION;t->view.task_id=t->id;t->view.generation=1;t->view.total_bytes=-1;e->tasks[t->id]=t;*out=t->id;curl_multi_wakeup(e->multi);return TD_OK;
    }catch(...){return TD_INTERNAL;}
#else
    (void)e;(void)o;(void)out;return TD_UNSUPPORTED;
#endif
}
int32_t td_torrent_connect_peer(td_engine* e,td_task_id id,const char* address,uint16_t port){
    if(!e || !address || !*address || !port)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;auto& t=*it->second;std::lock_guard task_lock(t.mutex);if(!t.torrent)return TD_UNSUPPORTED;if(t.peer_requests.size()>=256)return TD_BUSY;t.peer_requests.emplace_back(address,port);curl_multi_wakeup(e->multi);return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_task_replace_source(td_engine* e,td_task_id id,const td_source_options* source){
    if(!e || !source || source->struct_size<offsetof(td_source_options,representation_id) || source->abi_version!=TD_ABI_VERSION || !source->url)return TD_INVALID;
    try{if(!url_ok(source->url))return TD_UNSUPPORTED;auto headers=copy_headers(source->headers,source->header_count);std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;auto& t=*it->second;std::lock_guard task_lock(t.mutex);if(t.torrent || t.media)return TD_UNSUPPORTED;if(http_url(t.url)!=http_url(source->url) || (!http_url(t.url) && url_part(t.url,CURLUPART_SCHEME)!=url_part(source->url,CURLUPART_SCHEME)) || (url_part(t.url,CURLUPART_SCHEME)=="https" && url_part(source->url,CURLUPART_SCHEME)!="https"))return TD_UNSUPPORTED;if(t.view.state!=TD_PAUSED && t.view.state!=TD_FAILED)return TD_BUSY;if(t.command.load())return TD_BUSY;t.replacement_url=source->url;t.replacement_headers=std::move(headers);t.replacement_representation=(source->struct_size>=sizeof(*source) && source->representation_id)?source->representation_id:t.representation_id;t.source_replaced=true;return TD_OK;}catch(const std::invalid_argument&){return TD_INVALID;}catch(...){return TD_INTERNAL;}
}
int32_t td_task_forget(td_engine* e,td_task_id id){
    if(!e)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;{std::lock_guard task_lock(it->second->mutex);if(!terminal(it->second->view.state) && it->second->view.state!=TD_PAUSED)return TD_BUSY;if(it->second->command.load())return TD_BUSY;}e->tasks.erase(it);std::erase_if(e->events,[&](auto& event){return event.snapshot.task_id==id;});return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_task_control(td_engine* e,td_task_id id,uint32_t command){
    if(!e || command<TD_PAUSE || command>TD_CANCEL)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;auto& t=*it->second;std::lock_guard task_lock(t.mutex);
        if(command==TD_RESUME){if(t.view.state!=TD_PAUSED && t.view.state!=TD_FAILED)return TD_BUSY;}else if(terminal(t.view.state))return TD_BUSY;
        if(t.command.load()!=0)return TD_BUSY;if(command==TD_PAUSE)t.view.state=TD_PAUSING;t.command=command;curl_multi_wakeup(e->multi);return TD_OK;
    }catch(...){return TD_INTERNAL;}
}
int32_t td_task_query(td_engine* e,td_task_id id,td_snapshot* out){
    if(!e || !out || out->struct_size<sizeof(*out) || out->abi_version!=TD_ABI_VERSION)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;std::lock_guard task_lock(it->second->mutex);*out=it->second->view;return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_task_http_metrics(td_engine* e,td_task_id id,td_http_metrics* out){
    if(!e || !out || out->struct_size<sizeof(*out) || out->abi_version!=TD_ABI_VERSION)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;std::lock_guard task_lock(it->second->mutex);if(it->second->torrent)return TD_UNSUPPORTED;*out=it->second->metrics;out->struct_size=sizeof(*out);out->abi_version=TD_ABI_VERSION;return TD_OK;}catch(...){return TD_INTERNAL;}
}
int32_t td_engine_poll(td_engine* e,td_event* out,uint32_t capacity,uint32_t* count){
    if(!e || !count || (capacity && !out) || capacity>2048)return TD_INVALID;
    try{std::lock_guard lock(e->mutex);*count=std::min(capacity,static_cast<uint32_t>(e->events.size()));for(uint32_t i=0;i<*count;++i){out[i]=e->events.front();e->events.pop_front();}return TD_OK;}catch(...){return TD_INTERNAL;}
}
}
