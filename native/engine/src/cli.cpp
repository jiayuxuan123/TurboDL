#include <turbodl/engine.h>
#include <algorithm>
#include <chrono>
#include <csignal>
#include <charconv>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <utility>
#include <thread>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#endif

namespace {
volatile std::sig_atomic_t interrupted=0;
void signal_handler(int){interrupted=1;}
uint64_t number(const std::string& value){uint64_t n=0;auto [end,error]=std::from_chars(value.data(),value.data()+value.size(),n);if(error!=std::errc{} || end!=value.data()+value.size())throw std::invalid_argument("invalid numeric argument");return n;}
uint32_t bounded(const std::string& value, uint32_t minimum, uint32_t maximum) {
    auto parsed = number(value);
    if(parsed < minimum || parsed > maximum) throw std::invalid_argument("numeric option is out of range");
    return static_cast<uint32_t>(parsed);
}
uint32_t http_policy(const std::string& value) {
    if(value == "auto") return 0;
    if(value == "http1" || value == "h1") return 1;
    if(value == "http2" || value == "h2") return 2;
    if(value == "http3" || value == "h3") return 3;
    throw std::invalid_argument("HTTP policy must be auto, http1, http2, or http3");
}
const char* optional(const std::string& value) { return value.empty() ? nullptr : value.c_str(); }
struct Source {
    std::string url;
    std::vector<std::pair<std::string, std::string>> headers;

    void add_header(const std::string& line) {
        auto separator = line.find(':');
        if(separator == std::string::npos || separator == 0)
            throw std::invalid_argument("header must have the form Name: Value");
        auto start = line.find_first_not_of(" \t", separator + 1);
        headers.emplace_back(line.substr(0, separator), start == std::string::npos ? "" : line.substr(start));
    }
    std::vector<td_header> view() const {
        std::vector<td_header> result;
        result.reserve(headers.size());
        for(const auto& [name, value] : headers) result.push_back({name.c_str(), value.c_str()});
        return result;
    }
};
void help() {
    std::cout
        << "tdl-vnext URL -o FILE [OPTIONS]\n"
        << "tdl-vnext --hls URL | --dash URL -o FILE [OPTIONS]\n"
        << "tdl-vnext --torrent FILE_OR_MAGNET -o DIRECTORY [OPTIONS]\n"
        << "tdl-vnext --metalink FILE -o FILE [--base-url URL] [--file-name NAME]\n"
        << "Transfer: --connections N --sha256 HEX --known-size BYTES --stable-key KEY\n"
        << "          --checkpoint-dir DIR --retry N --window-bytes N --speed-limit BYTES_PER_SECOND\n"
        << "          --deadline SECONDS --connect-timeout-ms MS --read-timeout-ms MS\n"
        << "Network:  --http-policy auto|http1|http2|http3 --proxy URL --doh URL --ca-bundle FILE\n"
        << "          --username NAME --password VALUE --ssh-known-hosts FILE\n"
        << "          --ssh-private-key FILE --ssh-public-key FILE\n"
        << "Sources:  --header 'NAME: VALUE' --mirror URL --mirror-header 'NAME: VALUE'\n"
        << "          Repeated --mirror-header applies to the preceding mirror. Mirrors require --sha256.\n"
        << "Media:    --segments N --max-bandwidth BPS --max-segment-bytes BYTES --record-seconds SECONDS\n"
        << "          --audio-language LANG --video-representation ID --audio-representation ID\n"
        << "Torrent:  --select-file INDEX (repeatable, zero-based) --seed-seconds N --upload-limit BYTES_PER_SECOND\n"
        << "          --capabilities prints the protocols enabled in this build.\n";
}
std::string quote(const char* p){std::string out="\"";for(const auto* c=reinterpret_cast<const unsigned char*>(p);*c;++c){if(*c=='"' || *c=='\\'){out+='\\';out+=static_cast<char>(*c);}else if(*c=='\n')out+="\\n";else if(*c=='\r')out+="\\r";else if(*c>=32)out+=static_cast<char>(*c);}return out+'"';}
void report(td_engine* engine, const td_snapshot& s, uint64_t elapsed){
    td_http_metrics metrics{};
    metrics.struct_size = sizeof(metrics);
    metrics.abi_version = TD_ABI_VERSION;
    td_task_http_metrics(engine, s.task_id, &metrics);
    std::cout<<"{\"task_id\":"<<s.task_id<<",\"generation\":"<<s.generation<<",\"state\":"<<s.state<<",\"error_code\":"<<s.error<<",\"http_status\":"<<s.http_status<<",\"downloaded_bytes\":"<<s.downloaded_bytes<<",\"durable_bytes\":"<<s.durable_bytes<<",\"wire_bytes\":"<<s.wire_bytes<<",\"total_bytes\":"<<s.total_bytes<<",\"requests\":"<<s.request_count<<",\"active_requests\":"<<s.active_requests<<",\"peak_active_requests\":"<<s.peak_active_requests<<",\"negotiated_http_versions\":"<<metrics.negotiated_versions<<",\"peak_connections\":"<<metrics.peak_connections<<",\"elapsed_ms\":"<<elapsed<<",\"sha256\":"<<quote(s.sha256)<<",\"message\":"<<quote(s.message)<<"}\n"<<std::flush;
}
}
int main(int argc,char** argv){
#ifdef _WIN32
    // main() otherwise receives the Windows ANSI codepage, losing non-ASCII
    // file paths before the core can apply its UTF-8 contract.
    int count=0;auto wide=CommandLineToArgvW(GetCommandLineW(),&count);
    if(!wide)return 2;
    std::vector<std::string> utf8_args;std::vector<char*> pointers;
    utf8_args.reserve(static_cast<size_t>(count));pointers.reserve(static_cast<size_t>(count));
    for(int i=0;i<count;++i){
        auto length=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,wide[i],-1,nullptr,0,nullptr,nullptr);
        if(length<=0){LocalFree(wide);return 2;}
        std::string value(static_cast<size_t>(length),'\0');WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,wide[i],-1,value.data(),length,nullptr,nullptr);value.pop_back();utf8_args.push_back(std::move(value));
    }
    LocalFree(wide);for(auto& value:utf8_args)pointers.push_back(value.data());argc=count;argv=pointers.data();
#endif
    td_engine* engine=nullptr;
    try{
        Source source;
        std::vector<Source> mirrors;
        std::string output,key="cli-task",hash,journal,metalink,base,file_name;
        std::string username,password,proxy,ca,known_hosts,public_key,private_key,doh;
        std::string audio_language,video_representation,audio_representation;
        uint32_t connections=16,flags=0,deadline_seconds=300,connect_timeout=15000,read_timeout=30000;
        uint32_t retry_count=3,http_version=0,media_protocol=TD_MEDIA_AUTO,segments=0,record_seconds=0;
        uint32_t seed_seconds=0;
        uint64_t upload_limit=0,window=1048576,speed=0,max_bandwidth=0,max_segment_bytes=0;
        std::optional<int64_t> known_size;
        std::set<int32_t> file_indices;
        bool torrent=false;
        auto set_source = [&](std::string url) {
            if(!source.url.empty() || !metalink.empty()) throw std::invalid_argument("only one source is accepted");
            source.url = std::move(url);
        };
        for(int i=1;i<argc;++i){std::string arg=argv[i];auto next=[&](){if(++i>=argc)throw std::invalid_argument("missing argument value");return std::string(argv[i]);};
            if(arg=="--version"){std::cout<<"TurboDL experimental shared core, ABI "<<TD_ABI_VERSION<<"\n";return 0;}
            if(arg=="--capabilities"){std::cout<<td_engine_capabilities()<<"\n";return 0;}
            if(arg=="--help" || arg=="-h"){help();return 0;}
            if(arg=="-o" || arg=="--output")output=next();
            else if(arg=="--connections" || arg=="--threads" || arg=="-c")connections=bounded(next(),1,512);
            else if(arg=="--sha256")hash=next();else if(arg=="--stable-key")key=next();else if(arg=="--checkpoint-dir")journal=next();
            else if(arg=="--known-size"){
                auto bytes=number(next());
                if(bytes>INT64_MAX)throw std::invalid_argument("known size is out of range");
                known_size=static_cast<int64_t>(bytes);
            }
            else if(arg=="--timeout" || arg=="--deadline")deadline_seconds=bounded(next(),1,86400);
            else if(arg=="--window-bytes")window=number(next());
            else if(arg=="--speed-limit")speed=number(next());
            else if(arg=="--retry" || arg=="-r")retry_count=bounded(next(),0,50);
            else if(arg=="--connect-timeout-ms")connect_timeout=bounded(next(),1,INT32_MAX);
            else if(arg=="--read-timeout-ms")read_timeout=bounded(next(),1,INT32_MAX);
            else if(arg=="--http-policy")http_version=http_policy(next());
            else if(arg=="--force-http1")http_version=1;
            else if(arg=="--username")username=next();
            else if(arg=="--password")password=next();
            else if(arg=="--proxy" || arg=="-p")proxy=next();
            else if(arg=="--doh")doh=next();
            else if(arg=="--ca-bundle")ca=next();
            else if(arg=="--ssh-known-hosts")known_hosts=next();
            else if(arg=="--ssh-public-key")public_key=next();
            else if(arg=="--ssh-private-key")private_key=next();
            else if(arg=="--header" || arg=="-H")source.add_header(next());
            else if(arg=="--user-agent" || arg=="-A")source.headers.emplace_back("User-Agent",next());
            else if(arg=="--mirror" || arg=="-M")mirrors.push_back(Source{next(),{}});
            else if(arg=="--mirror-header"){
                if(mirrors.empty())throw std::invalid_argument("--mirror-header requires a preceding --mirror");
                mirrors.back().add_header(next());
            }
            else if(arg=="--hls" || arg=="--dash"){
                media_protocol=arg=="--hls"?TD_MEDIA_HLS:TD_MEDIA_DASH;
                set_source(next());
            }
            else if(arg=="--segments")segments=bounded(next(),1,16);
            else if(arg=="--record-seconds")record_seconds=bounded(next(),1,86400);
            else if(arg=="--max-bandwidth")max_bandwidth=number(next());
            else if(arg=="--max-segment-bytes")max_segment_bytes=number(next());
            else if(arg=="--audio-language")audio_language=next();
            else if(arg=="--video-representation")video_representation=next();
            else if(arg=="--audio-representation")audio_representation=next();
            else if(arg=="--torrent"){torrent=true;set_source(next());}
            else if(arg=="--metalink"){
                if(!source.url.empty() || !metalink.empty())throw std::invalid_argument("only one source is accepted");
                metalink=next();
            }
            else if(arg=="--base-url")base=next();else if(arg=="--file-name")file_name=next();
            else if(arg=="--seed-seconds")seed_seconds=bounded(next(),0,86400);
            else if(arg=="--select-file")file_indices.insert(static_cast<int32_t>(bounded(next(),0,INT32_MAX)));
            else if(arg=="--upload-limit")upload_limit=number(next());
            else if(arg=="--allow-loopback-fixture")flags|=TD_TEST_ALLOW_LOOPBACK;
            else if(arg=="--json"){}else if(arg.starts_with('-'))throw std::invalid_argument("unknown option");else set_source(arg);
        }
        if((source.url.empty() && metalink.empty()) || output.empty())
            throw std::invalid_argument("a source and an output target are required");
        torrent=torrent || source.url.starts_with("magnet:");
        if(!mirrors.empty() && (torrent || media_protocol!=TD_MEDIA_AUTO || !metalink.empty()))
            throw std::invalid_argument("explicit mirrors apply to file downloads");
        if(!mirrors.empty() && hash.empty())
            throw std::invalid_argument("explicit mirrors require a SHA-256");
        if(torrent && media_protocol!=TD_MEDIA_AUTO)
            throw std::invalid_argument("media and torrent modes cannot be combined");
        if(journal.empty())journal=output+".turbodl-state";
        td_engine_options options{sizeof(options),TD_ABI_VERSION,journal.c_str(),1,connections,128*1024*1024,flags,0,speed};
        if(td_engine_create(&options,&engine)!=TD_OK)throw std::runtime_error("native engine initialization failed");
        const auto primary_headers=source.view();
        std::vector<std::vector<td_header>> mirror_headers;
        mirror_headers.reserve(mirrors.size());
        std::vector<td_source_options> mirror_options;
        mirror_options.reserve(mirrors.size());
        for(const auto& mirror : mirrors){
            const auto& headers=mirror_headers.emplace_back(mirror.view());
            mirror_options.push_back({sizeof(td_source_options),TD_ABI_VERSION,mirror.url.c_str(),headers.data(),static_cast<uint32_t>(headers.size()),0,nullptr});
        }
        td_network_options network{sizeof(network),TD_ABI_VERSION,optional(username),optional(password),optional(proxy),
            optional(ca),optional(known_hosts),optional(public_key),optional(private_key),optional(doh),http_version,0};
        td_transfer_policy policy{sizeof(policy),TD_ABI_VERSION,retry_count,connections,nullptr};
        td_task_options task{};
        task.struct_size=sizeof(task);
        task.abi_version=TD_ABI_VERSION;
        task.url=source.url.c_str();
        task.destination=output.c_str();
        task.stable_key=key.c_str();
        task.expected_sha256=optional(hash);
        task.headers=primary_headers.data();
        task.header_count=static_cast<uint32_t>(primary_headers.size());
        task.connections=connections;
        task.request_window_bytes=window;
        task.connect_timeout_ms=connect_timeout;
        task.read_timeout_ms=read_timeout;
        task.network=&network;
        task.policy=&policy;
        task.mirrors=mirror_options.data();
        task.mirror_count=static_cast<uint32_t>(mirror_options.size());
        task.expected_size=known_size ? &*known_size : nullptr;
        td_task_id id=0;int submitted=TD_INVALID;
        if(!metalink.empty()){
            auto path=std::filesystem::path(std::u8string(reinterpret_cast<const char8_t*>(metalink.data()),metalink.size()));
            if(std::filesystem::file_size(path)>1048576)throw std::invalid_argument("Metalink document exceeds limit");
            std::ifstream input(path,std::ios::binary);if(!input)throw std::invalid_argument("Metalink document could not be opened");
            std::string document((std::istreambuf_iterator<char>(input)),std::istreambuf_iterator<char>());
            submitted=td_metalink_submit(engine,document.data(),static_cast<uint32_t>(document.size()),base.c_str(),file_name.empty()?nullptr:file_name.c_str(),&task,&id);
        }else if(torrent){
            std::vector<int32_t> selected(file_indices.begin(),file_indices.end());
            td_torrent_limits bt{};
            bt.base={sizeof(bt),TD_ABI_VERSION,source.url.c_str(),output.c_str(),key.c_str(),std::min(connections,256u),
                seed_seconds,selected.data(),static_cast<uint32_t>(selected.size()),0};
            bt.upload_bytes_per_second=upload_limit;
            submitted=td_torrent_submit(engine,&bt.base,&id);
        }else if(media_protocol!=TD_MEDIA_AUTO){
            td_media_options media{sizeof(media),TD_ABI_VERSION,&task,media_protocol,segments,max_bandwidth,
                max_segment_bytes,optional(audio_language),optional(video_representation),
                optional(audio_representation),record_seconds,0};
            submitted=td_media_submit(engine,&media,&id);
        }else submitted=td_task_submit(engine,&task,&id);
        if(submitted!=TD_OK)throw std::invalid_argument("native task rejected its inputs (code "+std::to_string(submitted)+")");
        std::signal(SIGINT,signal_handler);std::signal(SIGTERM,signal_handler);
        auto started=std::chrono::steady_clock::now();uint64_t previous=UINT64_MAX;uint32_t state=UINT32_MAX;bool stopping=false;int result=3;
        while(true){
            td_snapshot s{};s.struct_size=sizeof(s);s.abi_version=TD_ABI_VERSION;
            if(td_task_query(engine,id,&s)!=TD_OK)throw std::runtime_error("native task query failed");
            auto elapsed=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count());
            if(s.downloaded_bytes!=previous || s.state!=state){report(engine,s,elapsed);previous=s.downloaded_bytes;state=s.state;}
            td_event events[32];uint32_t count=0;td_engine_poll(engine,events,32,&count);
            if(s.state==TD_COMPLETED){result=0;break;}if(s.state==TD_FAILED || s.state==TD_CANCELLED)break;
            if(!stopping && (interrupted || elapsed>deadline_seconds*1000)){td_task_control(engine,id,TD_PAUSE);stopping=true;}
            if(stopping && s.state==TD_PAUSED){result=interrupted?130:124;break;}
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        td_engine_destroy(engine);return result;
    }catch(const std::invalid_argument& e){if(engine)td_engine_destroy(engine);std::cerr<<e.what()<<"\n";return 2;}
    catch(const std::exception& e){if(engine)td_engine_destroy(engine);std::cerr<<e.what()<<"\n";return 3;}
}
