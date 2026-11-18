#include <turbodl/engine.h>
#include <chrono>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

static std::string quoted(const char* input){
    std::string value="\"";
    for(const auto* p=input;*p;++p){if(*p=='"' || *p=='\\')value+='\\';if(static_cast<unsigned char>(*p)>=32)value+=*p;}
    return value+'"';
}

// External measurement host: only the public ABI, no scheduler/test internals.
int main(int argc,char** argv){
    if(argc!=2)return 2;
    td_engine* engine=nullptr;
    td_engine_options options{};
    options.struct_size=sizeof(options);options.abi_version=TD_ABI_VERSION;
    options.checkpoint_directory=argv[1];options.max_active_tasks=1;
    options.max_total_connections=256;options.buffer_budget_bytes=128*1024*1024;
    options.flags=TD_TEST_ALLOW_LOOPBACK;
    if(td_engine_create(&options,&engine)!=TD_OK)return 3;
    std::string line;
    while(std::getline(std::cin,line) && line!="close"){
        std::vector<std::string> fields;std::stringstream input(line);std::string field;
        while(std::getline(input,field,'\t'))fields.push_back(field);
        if(fields.size()!=6)break;
        try{
            auto seq=std::stoull(fields[0]);auto connections=std::stoul(fields[3]);
            if(!fields[1].starts_with("http://127.0.0.1:8845/") || connections<1 || connections>256)break;
            auto key="bench-"+fields[0];int64_t known_size=std::stoll(fields[4]);
            td_task_options task{};task.struct_size=sizeof(task);task.abi_version=TD_ABI_VERSION;
            task.url=fields[1].c_str();task.destination=fields[2].c_str();task.stable_key=key.c_str();
            task.connections=static_cast<uint32_t>(connections);task.request_window_bytes=262144;
            task.expected_sha256=fields[5].empty()?nullptr:fields[5].c_str();
            task.expected_size=known_size>0?&known_size:nullptr;
            task.connect_timeout_ms=1500;task.read_timeout_ms=15000;
            using Clock=std::chrono::steady_clock;auto started=Clock::now();
            auto elapsed=[&]{return std::chrono::duration<double,std::milli>(Clock::now()-started).count();};
            td_task_id id=0;auto result=td_task_submit(engine,&task,&id);
            if(result!=TD_OK){std::cout<<"{\"seq\":"<<seq<<",\"ok\":false,\"submit_error\":"<<result<<"}\n"<<std::flush;continue;}
            td_snapshot s{};s.struct_size=sizeof(s);s.abi_version=TD_ABI_VERSION;
            double written=-1,durable=-1,verifying=-1;
            while(elapsed()<90000){
                if(td_task_query(engine,id,&s)!=TD_OK)break;
                if(s.total_bytes>0 && s.downloaded_bytes==static_cast<uint64_t>(s.total_bytes) && written<0)written=elapsed();
                if(s.total_bytes>0 && s.durable_bytes==static_cast<uint64_t>(s.total_bytes) && durable<0)durable=elapsed();
                if(s.state==TD_VERIFYING && verifying<0)verifying=elapsed();
                if(s.state==TD_COMPLETED || s.state==TD_FAILED || s.state==TD_CANCELLED)break;
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            auto total=elapsed();
            td_http_metrics metrics{};metrics.struct_size=sizeof(metrics);metrics.abi_version=TD_ABI_VERSION;
            td_task_http_metrics(engine,id,&metrics);
            const bool completed=s.state==TD_COMPLETED;
            if(!completed && s.state!=TD_FAILED && s.state!=TD_CANCELLED){
                td_task_control(engine,id,TD_CANCEL);
                while(elapsed()<95000){td_task_query(engine,id,&s);if(s.state==TD_CANCELLED || s.state==TD_FAILED)break;std::this_thread::sleep_for(std::chrono::milliseconds(2));}
            }
            auto retirement_started=Clock::now();
            auto retired=td_task_forget(engine,id);
            auto retirement=std::chrono::duration<double,std::milli>(Clock::now()-retirement_started).count();
            std::cout<<"{\"seq\":"<<seq<<",\"ok\":"<<(completed && retired==TD_OK?"true":"false")
                <<",\"engine_ms\":"<<total<<",\"retirement_ms\":"<<retirement<<",\"error_code\":"<<s.error<<",\"state\":"<<s.state
                <<",\"message\":"<<quoted(s.message)
                <<",\"reported_requests\":"<<s.request_count<<",\"reported_peak\":"<<s.peak_active_requests
                <<",\"reported_peak_connections\":"<<metrics.peak_connections<<",\"first_byte_us\":"<<metrics.task_first_byte_microseconds
                <<",\"all_bytes_written_ms\":"<<written<<",\"all_bytes_durable_ms\":"<<durable<<",\"verifying_ms\":"<<verifying<<"}\n"<<std::flush;
        }catch(...){td_engine_destroy(engine);return 4;}
    }
    return td_engine_destroy(engine)==TD_OK?0:5;
}
