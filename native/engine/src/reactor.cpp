#include <turbodl/engine.h>
#include "resources.hpp"
#include "io_queue.hpp"
#include "scheduler.hpp"
#include "storage.hpp"
#include "torrent.hpp"
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
bool terminal(uint32_t s){return s==TD_COMPLETED || s==TD_FAILED || s==TD_CANCELLED;}
std::string lower(std::string s){for(auto& c:s)c=static_cast<char>(std::tolower(static_cast<unsigned char>(c)));return s;}
std::string trim(std::string s){auto a=s.find_first_not_of(" \t\r\n");return a==std::string::npos?"":s.substr(a,s.find_last_not_of(" \t\r\n")-a+1);}
template<size_t N>void text(char(&dst)[N],std::string_view value){auto n=std::min(N-1,value.size());std::memcpy(dst,value.data(),n);dst[n]=0;}
td::fs::path path_of(std::string_view s){return td::fs::path(std::u8string(reinterpret_cast<const char8_t*>(s.data()),s.size()));}
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
struct Source {
    std::string url,representation;
    Headers headers;
    uint32_t redirects=0,failures=0;
    bool disabled=false;
};
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
    bool source_replaced=false;
    Headers headers;
    std::vector<Source> sources;
    size_t next_source=0;
    uint32_t connections=1,connect_ms=10000,read_ms=30000,failures=0,redirects=0,retry_count=3,host_limit=512,http_version=0;
    uint64_t window=1048576,checkpoint_bytes=0;
    uint64_t source_offset=0,maximum_bytes=0;
    int64_t source_length=-1;
    int64_t total=-1;
    int64_t expected_length=-1;
    bool prepared=false,identity_checked=false,range_supported=false,loopback=false,whole_file=false,checkpoint_failed=false;
    FILE* file=nullptr;
    std::unique_ptr<td::FileLock> lock;
    td::Coverage coverage;
    std::map<uint64_t,td::BlockDigest> pending;
    std::vector<std::unique_ptr<Request>> requests;
    std::future<std::string> io;
    uint32_t io_purpose=0,failure_code=0;
    std::string failure_message;
    uint64_t committing_bytes=0;
    std::vector<uint64_t> committing_offsets;
    bool torrent=false;
    td::TorrentOptions torrent_options;
    std::unique_ptr<td::TorrentJob> torrent_job;
    std::vector<std::pair<std::string,uint16_t>> peer_requests;
    Clock::time_point retry_at{},checkpoint_at=Clock::now();
    ~Task(){torrent_job.reset();if(file)std::fclose(file);if(task_lease)td::process_resources().release_task(task_lease);}
};
struct Request {
    Task* task=nullptr;
    CURL* easy=nullptr;
    curl_slist* curl_headers=nullptr;
    td::Range assigned{};
    uint64_t cursor=0,body_bytes=0,header_bytes=0,retry_ms=0;
    uint64_t response_end=0;
    std::string url,source_origin,source_identity,budget_key,etag,location,range_value,content_range;
    Headers headers;
    std::array<char,65536> block{};
    size_t block_used=0;
    uint32_t error=0,redirects=0;
    long status=0;
    std::string message;
    bool headers_ready=false,redirect=false,paused=false,restart_whole=false,http=true;
    bool empty_representation=false;
    uint64_t lease_id=0;
    size_t source_index=0;
    bool network_done=false;
    CURLcode network_result=CURLE_OK;
    std::future<td::BlockDigest> write;
    uint64_t generation=0;
    ~Request(){if(easy)curl_easy_cleanup(easy);if(curl_headers)curl_slist_free_all(curl_headers);if(lease_id)td::process_resources().release(lease_id);}
};
}

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
    uint32_t max_tasks=3,flags=0;
    uint64_t next_id=0,sequence=0,domain_id=0,rotation=0;

    explicit td_engine(const td_engine_options& o):directory(path_of(o.checkpoint_directory)),disk([this]{if(multi)curl_multi_wakeup(multi);}),max_tasks(o.max_active_tasks),flags(o.flags){
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
    void flush(Request& r){
        if(r.write.valid() || !r.block_used)return;
        auto offset=r.cursor-r.block_used;auto length=r.block_used;auto* data=r.block.data();auto* file=r.task->file;
        r.write=disk.submit([offset,length,data,file]{
            td::seek_target(file,offset);if(std::fwrite(data,1,length,file)!=length)throw std::runtime_error("Destination write failed");
            return td::BlockDigest{offset,static_cast<uint32_t>(length),td::sha256(std::string_view(data,length))};
        });
    }
    void collect_write(Request& r,bool wait=false){
        if(!r.write.valid() || (!wait && r.write.wait_for(std::chrono::seconds(0))!=std::future_status::ready))return;
        td::BlockDigest block;
        try{block=r.write.get();}catch(...){r.block_used=0;throw;}
        r.block_used=0;
        auto& t=*r.task;t.pending[block.offset]=block;t.coverage.add(block.offset,block.offset+block.length);
        std::lock_guard lock(t.mutex);t.view.downloaded_bytes=t.coverage.bytes();
    }
    void detach(Request& r){
        if(r.easy){
            curl_off_t downloaded=0,dns=0,connected=0,tls=0,first=0;long connections=0,version=0;
            curl_easy_getinfo(r.easy,CURLINFO_SIZE_DOWNLOAD_T,&downloaded);
            curl_easy_getinfo(r.easy,CURLINFO_NAMELOOKUP_TIME_T,&dns);curl_easy_getinfo(r.easy,CURLINFO_CONNECT_TIME_T,&connected);
            curl_easy_getinfo(r.easy,CURLINFO_APPCONNECT_TIME_T,&tls);curl_easy_getinfo(r.easy,CURLINFO_STARTTRANSFER_TIME_T,&first);
            curl_easy_getinfo(r.easy,CURLINFO_NUM_CONNECTS,&connections);curl_easy_getinfo(r.easy,CURLINFO_HTTP_VERSION,&version);
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
            curl_multi_remove_handle(multi,r.easy);curl_easy_cleanup(r.easy);r.easy=nullptr;
        }
        if(r.curl_headers){curl_slist_free_all(r.curl_headers);r.curl_headers=nullptr;}
        r.network_done=true;
    }
    void stop_requests(Task& t){for(auto& r:t.requests){detach(*r);collect_write(*r,true);flush(*r);collect_write(*r,true);}t.requests.clear();active(t);}
    void release_task(Task& t){if(t.task_lease)td::process_resources().release_task(t.task_lease);t.task_lease=0;}
    bool admit_task(Task& t){return td::process_resources().admit_task(t.task_lease);}
    void close_target(Task& t){if(t.file){std::fclose(t.file);t.file=nullptr;}t.lock.reset();t.prepared=false;release_task(t);}
    void checkpoint(Task& t){
        if(!t.file)return;
        std::vector<td::BlockDigest> blocks;blocks.reserve(t.pending.size());for(auto& [offset,b]:t.pending){(void)offset;blocks.push_back(b);}
        td::sync_target(t.file);
        journal->save(t.key,{t.target,t.etag,t.expected,t.validator_origin,t.coverage.bytes(),t.total},blocks);
        t.pending.clear();t.checkpoint_bytes=t.coverage.bytes();t.checkpoint_at=Clock::now();
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
        if(saved){
            if(saved->target!=t.target || saved->expected!=t.expected)throw td::IntegrityError("Checkpoint owner changed");
            if(t.expected_length>=0 && saved->total!=t.expected_length)throw td::IntegrityError("Expected source length changed");
            if(saved->durable && (!exists || !journal->verify(t.key,path,saved->durable)))throw td::IntegrityError("Saved bytes failed integrity verification");
            t.total=saved->total;t.etag=saved->etag;t.validator_origin=saved->source_origin;
            if(t.source_length>=0 && t.total!=t.source_length)throw td::IntegrityError("Source projection changed");
            for(auto& block:journal->blocks(t.key)){if(t.total>=0 && block.offset+block.length>static_cast<uint64_t>(t.total))throw td::IntegrityError("Saved extent exceeds source length");t.coverage.add(block.offset,block.offset+block.length);}
        }else{
            if(exists)throw std::runtime_error("Refusing to overwrite an unowned file");
            journal->save(t.key,{t.target,"",t.expected,"",0,t.source_length>=0?t.source_length:t.expected_length});t.total=t.source_length>=0?t.source_length:t.expected_length;t.etag.clear();t.validator_origin.clear();
        }
        t.file=td::open_target(path,exists);if(!t.file)throw std::runtime_error("Destination open failed");
        t.identity_checked=false;t.range_supported=false;t.checkpoint_bytes=t.coverage.bytes();
        std::lock_guard l(t.mutex);t.view.downloaded_bytes=t.coverage.bytes();t.view.durable_bytes=t.coverage.bytes();t.view.total_bytes=t.total;
    }
    static curl_socket_t socket_cb(void* user,curlsocktype,curl_sockaddr* address){
        auto& r=*static_cast<Request*>(user);
        if(!socket_allowed(address,r.task->loopback)){r.error=TD_ERROR_POLICY;r.message="Destination rejected by network policy";return CURL_SOCKET_BAD;}
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
                else if((t.etag.empty() || t.validator_origin!=r.source_identity) && t.expected.empty()){r.error=TD_ERROR_IDENTITY_UNVERIFIABLE;r.message="Resume identity cannot be verified";}
            }
            if(!r.error && r.status==206 && !t.whole_file && t.coverage.bytes()==0 && t.expected.empty() && (r.etag.empty() || r.etag.rfind("W/",0)==0))r.restart_whole=true;
            if(!r.error && !r.restart_whole){if(t.etag.empty() && !r.etag.empty() && r.etag.rfind("W/",0)!=0)t.etag=r.etag;t.validator_origin=r.source_identity;t.identity_checked=true;}
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
            if(r.write.valid() || n>r.block.size()-r.block_used){r.paused=true;return CURL_WRITEFUNC_PAUSE;}
            if(r.cursor>r.assigned.end || n>r.assigned.end-r.cursor){r.error=TD_ERROR_RANGE;r.message="Body exceeds requested extent";return 0;}
            if(t.maximum_bytes && (r.cursor>t.maximum_bytes || n>t.maximum_bytes-r.cursor)){r.error=TD_ERROR_POLICY;r.message="Response exceeds task byte limit";return 0;}
            if(!td::process_resources().receive(n)){r.paused=true;return CURL_WRITEFUNC_PAUSE;}
            std::memcpy(r.block.data()+r.block_used,p,n);r.block_used+=n;r.cursor+=n;r.body_bytes+=n;
            {std::lock_guard l(t.mutex);if(t.view.state!=TD_PAUSING)t.view.state=TD_TRANSFERRING;if(!t.metrics.task_first_byte_microseconds)t.metrics.task_first_byte_microseconds=static_cast<uint64_t>(std::max<int64_t>(1,std::chrono::duration_cast<std::chrono::microseconds>(Clock::now()-t.submitted_at).count()));}
            return n;
        }catch(...){r.error=TD_ERROR_STORAGE;r.message="Destination write failed";return 0;}
    }
    static int progress_cb(void* user,curl_off_t,curl_off_t,curl_off_t,curl_off_t){return static_cast<Request*>(user)->task->command.load()!=0;}
    void start(Task& t,td::Range range){
        size_t selected=0;uint64_t lease=0;std::string source_origin,budget_key;
        for(size_t i=0;i<t.sources.size();++i){
            selected=(t.next_source+i)%t.sources.size();auto& source=t.sources[selected];if(source.disabled)continue;
            source_origin=origin(source.url);budget_key=source_origin+":"+t.cooldown_group;
            lease=td::process_resources().acquire(budget_key,t.host_limit,url_part(source.url,CURLUPART_SCHEME)=="ftp"?2:1);
            if(lease){t.next_source=(selected+1)%t.sources.size();break;}
        }
        if(!lease)return;
        auto& source=t.sources[selected];
        std::unique_ptr<Request> request;try{request=std::make_unique<Request>();}catch(...){td::process_resources().release(lease);throw;}
        auto& r=*request;r.task=&t;r.source_index=selected;r.url=source.url;r.source_origin=source_origin;r.source_identity=source_origin+":"+td::sha256(source.representation.empty()?source.url:"host:"+source.representation)+":"+t.cooldown_group+":"+std::to_string(t.source_offset)+":"+std::to_string(t.source_length);r.budget_key=budget_key;r.lease_id=lease;r.headers=source.headers;r.assigned=range;r.cursor=range.begin;r.response_end=range.end;r.http=http_url(source.url);r.headers_ready=!r.http;
        {std::lock_guard l(t.mutex);r.generation=t.view.generation;}
        r.easy=curl_easy_init();if(!r.easy)throw std::bad_alloc();
        auto set=[&](CURLoption option,auto value){if(curl_easy_setopt(r.easy,option,value)!=CURLE_OK)throw std::runtime_error("Unsupported HTTP option");};
        set(CURLOPT_URL,r.url.c_str());set(CURLOPT_PROTOCOLS_STR,"http,https,ftp,sftp");set(CURLOPT_FOLLOWLOCATION,0L);set(CURLOPT_PROXY,t.proxy.c_str());set(CURLOPT_NOSIGNAL,1L);
        set(CURLOPT_SUPPRESS_CONNECT_HEADERS,1L);
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
        r.range_value=std::to_string(t.source_offset+range.begin)+"-"+std::to_string(t.source_offset+range.end-1);if(r.http && (!t.whole_file || t.source_length>=0))set(CURLOPT_RANGE,r.range_value.c_str());
        if(!r.http && range.begin>0)set(CURLOPT_RESUME_FROM_LARGE,static_cast<curl_off_t>(range.begin));
        for(auto& [name,value]:r.headers){auto* list=curl_slist_append(r.curl_headers,(name+": "+value).c_str());if(!list)throw std::bad_alloc();r.curl_headers=list;}
        if(range.begin>0 && !t.etag.empty() && r.source_identity==t.validator_origin){auto* list=curl_slist_append(r.curl_headers,("If-Range: "+t.etag).c_str());if(!list)throw std::bad_alloc();r.curl_headers=list;}
        set(CURLOPT_HTTPHEADER,r.curl_headers);
        if(curl_multi_add_handle(multi,r.easy)!=CURLM_OK)throw std::runtime_error("Cannot schedule request");
        t.requests.push_back(std::move(request));{std::lock_guard l(t.mutex);++t.view.request_count;}active(t);
    }
    void verify(Task& t){
        checkpoint(t);state(t,TD_VERIFYING);if(t.file){std::fclose(t.file);t.file=nullptr;}
        td::fs::resize_file(path_of(t.target),static_cast<uint64_t>(t.total));
        auto digest=td::sha256_file(path_of(t.target),[&]{return stopping.load() || t.command.load()!=0;});
        {std::lock_guard l(t.mutex);text(t.view.sha256,digest);}
        close_target(t);
        if(!t.expected.empty() && digest!=t.expected)state(t,TD_FAILED,TD_ERROR_INTEGRITY,"SHA-256 mismatch; file is not complete");else state(t,TD_COMPLETED);
    }
    void completion(Request& r,CURLcode result){
        Task& t=*r.task;auto& source=t.sources.at(r.source_index);active(t);
        if(t.command.load()!=0)return;
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
        if(r.error){if(alternative()){source.disabled=true;return;}fail(t,r.error,r.message);return;}
        if(r.empty_representation)return;
        if(r.status==429 || r.status==503){
            auto wait=std::max<uint64_t>(r.retry_ms,250);td::process_resources().cooldown(r.budget_key,wait);
            if(wait>30000 || ++source.failures>t.retry_count){if(alternative()){source.disabled=true;return;}fail(t,TD_ERROR_RATE_LIMITED,"Source rate limited; saved bytes retained");return;}
            if(!alternative())t.retry_at=Clock::now()+std::chrono::milliseconds(wait);return;
        }
        if(r.status>=400){if(alternative()){source.disabled=true;return;}fail(t,TD_ERROR_HTTP,"Source returned an HTTP error");return;}
        if(result!=CURLE_OK){
            if(result==CURLE_PEER_FAILED_VERIFICATION || result==CURLE_SSL_CACERT_BADFILE || result==CURLE_SSL_ISSUER_ERROR){fail(t,TD_ERROR_TRUST,"Server identity verification failed");return;}
            if(result==CURLE_LOGIN_DENIED){fail(t,TD_ERROR_AUTH,"Source authentication failed");return;}
            if(alternative()){if(++source.failures>t.retry_count)source.disabled=true;else td::process_resources().cooldown(r.budget_key,500);return;}
            if(++t.failures>t.retry_count){fail(t,TD_ERROR_NETWORK,"Network retry limit reached; saved bytes retained");return;}
            t.retry_at=Clock::now()+std::chrono::milliseconds(250u<<std::min(t.failures,5u));return;
        }
        if(t.total<0){t.total=static_cast<int64_t>(r.cursor);std::lock_guard lock(t.mutex);t.view.total_bytes=t.total;}
        if(r.body_bytes==0 && t.total!=0){fail(t,TD_ERROR_RANGE,"Response made no progress");return;}
    }
    void start_checkpoint(Task& t){
        if(t.io.valid() || !t.file)return;
        std::vector<td::BlockDigest> blocks;t.committing_offsets.clear();
        for(auto& [offset,b]:t.pending){blocks.push_back(b);t.committing_offsets.push_back(offset);}
        auto saved=td::Saved{t.target,t.etag,t.expected,t.validator_origin,t.coverage.bytes(),t.total};
        t.committing_bytes=saved.durable;t.io_purpose=2;auto* file=t.file;auto* db=journal.get();auto key=t.key;
        t.io=disk.submit([db,key,saved,file,blocks=std::move(blocks)]{td::sync_target(file);db->save(key,saved,blocks);return std::string{};});
    }
    bool collect_io(Task& t,bool wait=false){
        if(!t.io.valid())return true;
        if(!wait && t.io.wait_for(std::chrono::seconds(0))!=std::future_status::ready)return false;
        auto purpose=t.io_purpose;t.io_purpose=0;
        try{
            auto value=t.io.get();
            if(purpose==1)t.prepared=true;
            else if(purpose==4)t.prepared=true;
            else if(purpose==2){for(auto offset:t.committing_offsets)t.pending.erase(offset);t.checkpoint_bytes=t.committing_bytes;t.checkpoint_at=Clock::now();std::lock_guard lock(t.mutex);t.view.durable_bytes=t.checkpoint_bytes;}
            else if(purpose==3 && !t.command.load() && !stopping.load()){
                {std::lock_guard lock(t.mutex);text(t.view.sha256,value);}
                close_target(t);
                if(!t.expected.empty() && value!=t.expected)state(t,TD_FAILED,TD_ERROR_INTEGRITY,"SHA-256 mismatch; file is not complete");else state(t,TD_COMPLETED);
            }
        }catch(const td::ResourceBusy&){t.prepared=false;release_task(t);t.retry_at=Clock::now()+std::chrono::milliseconds(50);state(t,TD_QUEUED);}
        catch(const td::IntegrityError&){fail(t,TD_ERROR_INTEGRITY,"Saved bytes failed integrity verification");}
        catch(const std::invalid_argument&){fail(t,purpose==4?TD_ERROR_POLICY:TD_ERROR_STORAGE,purpose==4?"Invalid torrent metadata or selection":"Invalid storage request");}
        catch(...){if(purpose==2){t.checkpoint_failed=true;t.failure_code=TD_ERROR_STORAGE;t.failure_message="Disk checkpoint failed; prior checkpoint retained";t.command=4;}else if(!t.command.load())fail(t,TD_ERROR_STORAGE,"Disk operation failed; prior checkpoint retained");}
        return true;
    }
    void controls(Task& t){
        auto command=t.command.load();if(!command)return;
        if(command==TD_PAUSE || command==TD_CANCEL || command==4){
            bool pending=false;
            for(auto& r:t.requests){detach(*r);collect_write(*r);flush(*r);pending=pending || r->write.valid();}
            if(pending)return;
            t.requests.clear();active(t);
            if(t.file && !t.checkpoint_failed && t.coverage.bytes()!=t.checkpoint_bytes){start_checkpoint(t);return;}
            close_target(t);t.command=0;
            if(command==4)state(t,TD_FAILED,t.failure_code,t.failure_message);else state(t,command==TD_PAUSE?TD_PAUSED:TD_CANCELLED);
        }
        else if(command==TD_RESUME){t.command=0;t.failures=0;t.checkpoint_failed=false;t.retry_at={};{std::lock_guard l(t.mutex);if(t.source_replaced){t.url=std::move(t.replacement_url);t.headers=std::move(t.replacement_headers);t.representation_id=std::move(t.replacement_representation);t.sources={{t.url,t.representation_id,t.headers}};t.source_replaced=false;t.redirects=0;t.whole_file=false;}for(auto& source:t.sources){source.disabled=false;source.failures=0;}++t.view.generation;}state(t,TD_QUEUED);}
    }
    void step(Task& t,bool allow_task){
        if(!collect_io(t))return;
        if(t.torrent){step_torrent(t,allow_task);return;}
        for(auto& r:t.requests){collect_write(*r);if(!r->write.valid() && (r->network_done || r->paused || r->block_used>=49152))flush(*r);}
        controls(t);auto s=state_of(t);if(terminal(s) || s==TD_PAUSED)return;
        if(t.command.load()!=0 || t.io.valid())return;
        if(!allow_task && !t.prepared)return;
        if(!t.prepared){if(!admit_task(t))return;state(t,TD_PROBING);t.io_purpose=1;t.io=disk.submit([this,&t]{prepare(t);return std::string{};});return;}
        for(auto& r:t.requests){
            if(r->network_done && !r->write.valid() && !r->block_used)completion(*r,r->network_result);
            if(t.command.load()!=0)break;
            if(r->paused && !r->write.valid() && !r->block_used && r->easy){r->paused=false;curl_easy_pause(r->easy,CURLPAUSE_CONT);}
        }
        std::erase_if(t.requests,[](auto& r){return r->network_done && !r->write.valid() && !r->block_used;});
        if(t.command.load()!=0)return;
        if(t.coverage.bytes()>0 && t.etag.empty() && t.expected.empty() && !t.identity_checked){fail(t,TD_ERROR_IDENTITY_UNVERIFIABLE,"Resume identity cannot be verified; saved bytes retained");return;}
        if(t.total>=0 && t.coverage.bytes()==static_cast<uint64_t>(t.total) && t.requests.empty()){
            if(t.coverage.bytes()!=t.checkpoint_bytes){start_checkpoint(t);return;}
            state(t,TD_VERIFYING);t.io_purpose=3;
            t.io=disk.submit([this,&t]{if(t.file)td::sync_target(t.file);td::fs::resize_file(path_of(t.target),static_cast<uint64_t>(t.total));return td::sha256_file(path_of(t.target),[this,&t]{return stopping.load() || t.command.load()!=0;});});return;
        }
        if(Clock::now()<t.retry_at)return;
        if(t.coverage.bytes()>t.checkpoint_bytes && (t.pending.size()>=16 || Clock::now()-t.checkpoint_at>std::chrono::seconds(1))){start_checkpoint(t);return;}
        uint32_t ceiling=t.identity_checked && t.range_supported && (!t.etag.empty() || !t.expected.empty())?t.connections:1;
        std::vector<td::Range> reserved;for(auto& r:t.requests)reserved.push_back(r->assigned);
        if(reserved.size()>=ceiling)return;
        auto total=t.total>=0?static_cast<uint64_t>(t.total):max_offset;
        while(reserved.size()<ceiling){
            auto gap=t.coverage.gap(total,(t.whole_file || !http_url(t.url))?total:t.window,reserved);
            if(!gap)break;
            auto before=t.requests.size();start(t,*gap);
            if(t.requests.size()==before)break;
            reserved.push_back(*gap);
        }
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
                for(auto& t:current){try{bool before=t->prepared || t->io_purpose==1 || t->io_purpose==4;step(*t,admitted<max_tasks);bool after=t->prepared || t->io_purpose==1 || t->io_purpose==4;if(!before && after)++admitted;if(before && !after && admitted)--admitted;}catch(const td::IntegrityError&){fail(*t,TD_ERROR_INTEGRITY,"Saved bytes failed integrity verification");}catch(...){if(t->command.load()==0 && !stopping.load())fail(*t,TD_ERROR_STORAGE,"Storage operation failed; checkpoint retained");}}
                int running=0;if(curl_multi_perform(multi,&running)!=CURLM_OK)throw std::runtime_error("HTTP event loop failed");
                int left=0;
                while(auto* message=curl_multi_info_read(multi,&left))if(message->msg==CURLMSG_DONE){Request* r=nullptr;curl_easy_getinfo(message->easy_handle,CURLINFO_PRIVATE,&r);if(!r)continue;r->network_result=message->data.result;detach(*r);flush(*r);active(*r->task);}
                for(auto& t:current)if(!t->requests.empty()){active(*t);emit(*t,TD_EVENT_PROGRESS);}
                // Network, completed disk work and resource release wake the
                // reactor. Only protocol/rate/retry timers require a deadline.
                long timeout=1000;auto now=Clock::now();
                for(const auto& t:current){
                    auto status=state_of(*t);if(terminal(status) || status==TD_PAUSED)continue;
                    if(t->torrent_job)timeout=std::min(timeout,50L);
                    if(t->retry_at>now)timeout=std::min(timeout,static_cast<long>(std::max<int64_t>(1,std::chrono::duration_cast<std::chrono::milliseconds>(t->retry_at-now).count())));
                    if(t->command.load() && !t->io.valid() && t->requests.empty())timeout=0;
                    for(const auto& request:t->requests){
                        if(request->paused && !request->write.valid())timeout=std::min(timeout,10L);
                        if(request->network_done && !request->write.valid() && !request->block_used)timeout=0;
                    }
                }
                timeout=td::process_resources().next_timer(timeout);
                int n=0;curl_multi_poll(multi,nullptr,0,timeout,&n);
            }
        }catch(...){for(auto& t:list())if(!terminal(state_of(*t)))try{fail(*t,TD_ERROR_NETWORK,"Engine loop failed");}catch(...) {}}
        for(auto& t:list()){try{collect_io(*t,true);if(t->torrent_job){auto s=t->torrent_job->snapshot();if(!terminal(s.state) && s.state!=TD_PAUSED){t->torrent_job->control(TD_PAUSE);auto deadline=Clock::now()+std::chrono::seconds(5);while(Clock::now()<deadline){t->torrent_job->tick();if(t->torrent_job->snapshot().state==TD_PAUSED)break;std::this_thread::sleep_for(std::chrono::milliseconds(5));}}t->torrent_job.reset();}stop_requests(*t);checkpoint(*t);close_target(*t);if(!terminal(state_of(*t)) && state_of(*t)!=TD_PAUSED)state(*t,TD_PAUSED);}catch(...) {close_target(*t);}}
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
int32_t td_task_submit(td_engine* e,const td_task_options* o,td_task_id* out){
    if(!e || !o || !out || o->struct_size<offsetof(td_task_options,network) || o->abi_version!=TD_ABI_VERSION || !o->url || !o->destination || !*o->destination || !o->stable_key || !*o->stable_key || o->connections<1 || o->connections>512 || o->request_window_bytes<8192 || o->request_window_bytes>67108864 || o->header_count>64 || (o->header_count && !o->headers))return TD_INVALID;
    try{
        if(!url_ok(o->url))return TD_UNSUPPORTED;
        auto t=std::make_shared<Task>();t->url=o->url;t->target=td::utf8(td::fs::weakly_canonical(td::fs::absolute(path_of(o->destination))));t->key=td::sha256(o->stable_key);t->expected=o->expected_sha256?lower(o->expected_sha256):"";
        if(!t->expected.empty() && (t->expected.size()!=64 || t->expected.find_first_not_of("0123456789abcdef")!=std::string::npos))return TD_INVALID;
        t->headers=copy_headers(o->headers,o->header_count);
        t->authentication_origin=origin(t->url);
        if(o->struct_size>=offsetof(td_task_options,network)+sizeof(o->network) && o->network){const auto& n=*o->network;if(n.struct_size<sizeof(n) || n.abi_version!=TD_ABI_VERSION || n.http_version>3)return TD_INVALID;auto copy=[](const char* value){return value?std::string(value):std::string{};};t->username=copy(n.username);t->password=copy(n.password);t->proxy=copy(n.proxy);t->ca_bundle=copy(n.ca_bundle);t->known_hosts=copy(n.ssh_known_hosts);t->public_key=copy(n.ssh_public_key);t->private_key=copy(n.ssh_private_key);t->doh=copy(n.doh_url);t->http_version=n.http_version;}
        if(o->struct_size>=offsetof(td_task_options,policy)+sizeof(o->policy) && o->policy){auto& p=*o->policy;if(p.struct_size<sizeof(p) || p.abi_version!=TD_ABI_VERSION || p.retry_count>50 || p.max_connections_per_host>4096)return TD_INVALID;t->retry_count=p.retry_count;t->host_limit=p.max_connections_per_host?p.max_connections_per_host:512;if(p.cooldown_group)t->cooldown_group=td::sha256(p.cooldown_group);}
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
        if(o->struct_size>=sizeof(td_task_options) && o->expected_size){if(*o->expected_size<0)return TD_INVALID;t->expected_length=*o->expected_size;}
        if(url_part(t->url,CURLUPART_SCHEME)=="sftp" && t->known_hosts.empty())return TD_INVALID;
        t->connections=o->connections;t->window=o->request_window_bytes;t->connect_ms=o->connect_timeout_ms?o->connect_timeout_ms:10000;t->read_ms=o->read_timeout_ms?o->read_timeout_ms:30000;t->loopback=(e->flags&TD_TEST_ALLOW_LOOPBACK)!=0;
        std::lock_guard lock(e->mutex);if(e->tasks.size()>=128 || e->events.size()>1900)return TD_BUSY;
        for(auto& [id,old]:e->tasks){(void)id;std::lock_guard task_lock(old->mutex);if(!terminal(old->view.state) && (old->key==t->key || old->target==t->target))return TD_BUSY;}
        t->id=++e->next_id;t->view.struct_size=sizeof(td_snapshot);t->view.abi_version=TD_ABI_VERSION;t->view.task_id=t->id;t->view.generation=1;t->view.total_bytes=-1;e->tasks[t->id]=t;*out=t->id;curl_multi_wakeup(e->multi);return TD_OK;
    }catch(const std::invalid_argument&){return TD_INVALID;}catch(...){return TD_INTERNAL;}
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
    try{if(!url_ok(source->url))return TD_UNSUPPORTED;auto headers=copy_headers(source->headers,source->header_count);std::lock_guard lock(e->mutex);auto it=e->tasks.find(id);if(it==e->tasks.end())return TD_NOT_FOUND;auto& t=*it->second;std::lock_guard task_lock(t.mutex);if(t.torrent)return TD_UNSUPPORTED;if(http_url(t.url)!=http_url(source->url) || (!http_url(t.url) && url_part(t.url,CURLUPART_SCHEME)!=url_part(source->url,CURLUPART_SCHEME)) || (url_part(t.url,CURLUPART_SCHEME)=="https" && url_part(source->url,CURLUPART_SCHEME)!="https"))return TD_UNSUPPORTED;if(t.view.state!=TD_PAUSED && t.view.state!=TD_FAILED)return TD_BUSY;if(t.command.load())return TD_BUSY;t.replacement_url=source->url;t.replacement_headers=std::move(headers);t.replacement_representation=(source->struct_size>=sizeof(*source) && source->representation_id)?source->representation_id:t.representation_id;t.source_replaced=true;return TD_OK;}catch(const std::invalid_argument&){return TD_INVALID;}catch(...){return TD_INTERNAL;}
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
