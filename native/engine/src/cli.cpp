#include <turbodl/engine.h>
#include <algorithm>
#include <chrono>
#include <csignal>
#include <charconv>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
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
std::string quote(const char* p){std::string out="\"";for(const auto* c=reinterpret_cast<const unsigned char*>(p);*c;++c){if(*c=='"' || *c=='\\'){out+='\\';out+=static_cast<char>(*c);}else if(*c=='\n')out+="\\n";else if(*c=='\r')out+="\\r";else if(*c>=32)out+=static_cast<char>(*c);}return out+'"';}
void report(const td_snapshot& s,uint64_t elapsed){
    std::cout<<"{\"task_id\":"<<s.task_id<<",\"generation\":"<<s.generation<<",\"state\":"<<s.state<<",\"error_code\":"<<s.error<<",\"http_status\":"<<s.http_status<<",\"downloaded_bytes\":"<<s.downloaded_bytes<<",\"durable_bytes\":"<<s.durable_bytes<<",\"wire_bytes\":"<<s.wire_bytes<<",\"total_bytes\":"<<s.total_bytes<<",\"requests\":"<<s.request_count<<",\"active_requests\":"<<s.active_requests<<",\"peak_active_requests\":"<<s.peak_active_requests<<",\"elapsed_ms\":"<<elapsed<<",\"sha256\":"<<quote(s.sha256)<<",\"message\":"<<quote(s.message)<<"}\n"<<std::flush;
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
        std::string url,output,key="cli-task",hash,journal,metalink,base,file_name;
        bool torrent=false;uint32_t seed_seconds=0;uint64_t upload_limit=0;
        uint32_t connections=16,flags=0;uint64_t deadline_seconds=300,window=1048576,speed=0;
        for(int i=1;i<argc;++i){std::string arg=argv[i];auto next=[&](){if(++i>=argc)throw std::invalid_argument("missing argument value");return std::string(argv[i]);};
            if(arg=="--version"){std::cout<<"TurboDL experimental shared core, ABI "<<TD_ABI_VERSION<<"\n";return 0;}
            if(arg=="--capabilities"){std::cout<<td_engine_capabilities()<<"\n";return 0;}
            if(arg=="--help"){std::cout<<"tdl-vnext URL -o FILE [--connections N] [--sha256 HEX] [--stable-key KEY] [--checkpoint-dir DIR] [--timeout SECONDS] [--window-bytes N] [--speed-limit BYTES_PER_SECOND]\n"
                <<"tdl-vnext --torrent FILE_OR_MAGNET -o DIRECTORY [--seed-seconds N] [--upload-limit BYTES_PER_SECOND]\n"
                <<"tdl-vnext --metalink FILE -o FILE [--base-url URL] [--file-name NAME]\n";return 0;}
            if(arg=="-o" || arg=="--output")output=next();
            else if(arg=="--connections" || arg=="-c"){auto n=number(next());if(n<1 || n>512)throw std::invalid_argument("connections must be 1..512");connections=static_cast<uint32_t>(n);}
            else if(arg=="--sha256")hash=next();else if(arg=="--stable-key")key=next();else if(arg=="--checkpoint-dir")journal=next();
            else if(arg=="--timeout")deadline_seconds=number(next());else if(arg=="--window-bytes")window=number(next());else if(arg=="--speed-limit")speed=number(next());
            else if(arg=="--torrent"){torrent=true;url=next();}
            else if(arg=="--metalink")metalink=next();else if(arg=="--base-url")base=next();else if(arg=="--file-name")file_name=next();
            else if(arg=="--seed-seconds"){auto value=number(next());if(value>86400)throw std::invalid_argument("seed duration exceeds limit");seed_seconds=static_cast<uint32_t>(value);}
            else if(arg=="--upload-limit")upload_limit=number(next());
            else if(arg=="--allow-loopback-fixture")flags|=TD_TEST_ALLOW_LOOPBACK;
            else if(arg=="--json"){}else if(arg.starts_with('-'))throw std::invalid_argument("unknown option");else if(url.empty())url=arg;else throw std::invalid_argument("only one URL is accepted");
        }
        if((url.empty() && metalink.empty()) || (!url.empty() && !metalink.empty()) || output.empty() || deadline_seconds<1 || deadline_seconds>86400)throw std::invalid_argument("one source, output target and valid timeout are required");
        torrent=torrent || url.starts_with("magnet:");
        if(journal.empty())journal=output+".turbodl-state";
        td_engine_options options{sizeof(options),TD_ABI_VERSION,journal.c_str(),1,connections,32*1024*1024,flags,0,speed};
        if(td_engine_create(&options,&engine)!=TD_OK)throw std::runtime_error("native engine initialization failed");
        td_task_options task{sizeof(task),TD_ABI_VERSION,url.c_str(),output.c_str(),key.c_str(),hash.empty()?nullptr:hash.c_str(),nullptr,0,connections,window,15000,30000,nullptr,nullptr};
        td_task_id id=0;int submitted=TD_INVALID;
        if(!metalink.empty()){
            auto path=std::filesystem::path(std::u8string(reinterpret_cast<const char8_t*>(metalink.data()),metalink.size()));
            if(std::filesystem::file_size(path)>1048576)throw std::invalid_argument("Metalink document exceeds limit");
            std::ifstream input(path,std::ios::binary);if(!input)throw std::invalid_argument("Metalink document could not be opened");
            std::string document((std::istreambuf_iterator<char>(input)),std::istreambuf_iterator<char>());
            submitted=td_metalink_submit(engine,document.data(),static_cast<uint32_t>(document.size()),base.c_str(),file_name.empty()?nullptr:file_name.c_str(),&task,&id);
        }else if(torrent){
            td_torrent_limits bt{};bt.base={sizeof(bt),TD_ABI_VERSION,url.c_str(),output.c_str(),key.c_str(),std::min(connections,256u),seed_seconds,nullptr,0,0};bt.upload_bytes_per_second=upload_limit;
            submitted=td_torrent_submit(engine,&bt.base,&id);
        }else submitted=td_task_submit(engine,&task,&id);
        if(submitted!=TD_OK)throw std::invalid_argument("native task rejected its inputs (code "+std::to_string(submitted)+")");
        std::signal(SIGINT,signal_handler);std::signal(SIGTERM,signal_handler);
        auto started=std::chrono::steady_clock::now();uint64_t previous=UINT64_MAX;uint32_t state=UINT32_MAX;bool stopping=false;int result=3;
        while(true){
            td_snapshot s{};s.struct_size=sizeof(s);s.abi_version=TD_ABI_VERSION;
            if(td_task_query(engine,id,&s)!=TD_OK)throw std::runtime_error("native task query failed");
            auto elapsed=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count());
            if(s.downloaded_bytes!=previous || s.state!=state){report(s,elapsed);previous=s.downloaded_bytes;state=s.state;}
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
