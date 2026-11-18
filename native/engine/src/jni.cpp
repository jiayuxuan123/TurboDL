#include <jni.h>
#include <turbodl/engine.h>
#include <array>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Instance {td_engine* engine=nullptr;~Instance(){td_engine_destroy(engine);}};
std::mutex registry_mutex;
std::map<jlong,std::shared_ptr<Instance>> registry;
jlong next_handle=0;
std::shared_ptr<Instance> instance(jlong id){std::lock_guard lock(registry_mutex);auto it=registry.find(id);if(it==registry.end())throw std::invalid_argument("Native engine is closed or unknown");return it->second;}
std::string bytes(JNIEnv* env,jbyteArray input){
    if(!input)return {};
    auto length=env->GetArrayLength(input);if(length>1048576)throw std::invalid_argument("Native argument exceeds limit");
    std::string result(static_cast<size_t>(length),'\0');if(length)env->GetByteArrayRegion(input,0,length,reinterpret_cast<jbyte*>(result.data()));
    if(result.find('\0')!=std::string::npos)throw std::invalid_argument("Native argument contains a zero byte");return result;
}
std::vector<std::string> strings(JNIEnv* env,jobjectArray array){
    std::vector<std::string> result;if(!array)return result;auto length=env->GetArrayLength(array);if(length>64)throw std::invalid_argument("Too many native arguments");
    for(jsize i=0;i<length;++i){auto item=static_cast<jbyteArray>(env->GetObjectArrayElement(array,i));result.push_back(bytes(env,item));env->DeleteLocalRef(item);}return result;
}
struct HeaderInput {
    std::vector<std::string> names,values;
    std::vector<td_header> headers;
    HeaderInput(JNIEnv* env,jobjectArray n,jobjectArray v):names(strings(env,n)),values(strings(env,v)){
        if(names.size()!=values.size())throw std::invalid_argument("Header counts differ");
        for(size_t i=0;i<names.size();++i)headers.push_back({names[i].c_str(),values[i].c_str()});
    }
};
struct TransferInput {
    std::string url,target,key,sha,cooldown,representation;
    HeaderInput headers;
    std::vector<std::string> network;
    td_network_options transport{};
    td_transfer_policy policy{};
    td_task_options options{};

    TransferInput(JNIEnv* env,jbyteArray u,jbyteArray d,jbyteArray stable,jbyteArray expected,
                  jobjectArray names,jobjectArray values,jint connections,jlong window,
                  jint connect,jint read,jobjectArray transport_values,jint http,jint retries,
                  jint host,jbyteArray group,jbyteArray identity)
        :url(bytes(env,u)),target(bytes(env,d)),key(bytes(env,stable)),sha(bytes(env,expected)),
         cooldown(bytes(env,group)),representation(bytes(env,identity)),headers(env,names,values),
         network(strings(env,transport_values)){
        if(network.size()!=8 || connect<0 || read<0)
            throw std::invalid_argument("Invalid network options");
        transport={sizeof(transport),TD_ABI_VERSION,network[0].c_str(),network[1].c_str(),
                   network[2].c_str(),network[3].c_str(),network[4].c_str(),network[5].c_str(),
                   network[6].c_str(),network[7].c_str(),static_cast<uint32_t>(http),0};
        policy={sizeof(policy),TD_ABI_VERSION,static_cast<uint32_t>(retries),
                static_cast<uint32_t>(host),cooldown.empty()?nullptr:cooldown.c_str()};
        options.struct_size=sizeof(options);
        options.abi_version=TD_ABI_VERSION;
        options.url=url.c_str();
        options.destination=target.c_str();
        options.stable_key=key.c_str();
        options.expected_sha256=sha.empty()?nullptr:sha.c_str();
        options.headers=headers.headers.data();
        options.header_count=static_cast<uint32_t>(headers.headers.size());
        options.connections=static_cast<uint32_t>(connections);
        options.request_window_bytes=static_cast<uint64_t>(window);
        options.connect_timeout_ms=static_cast<uint32_t>(connect);
        options.read_timeout_ms=static_cast<uint32_t>(read);
        options.network=&transport;
        options.policy=&policy;
        options.representation_id=representation.empty()?nullptr:representation.c_str();
    }
};
void checked(int result){if(result!=TD_OK)throw std::runtime_error("Native operation failed with code "+std::to_string(result));}
void error(JNIEnv* env,const char* message){if(env->ExceptionCheck())return;auto c=env->FindClass("java/lang/IllegalStateException");if(c){env->ThrowNew(c,message);env->DeleteLocalRef(c);}}
jobject snapshot(JNIEnv* env,const td_snapshot& s,uint64_t sequence=0,uint32_t kind=0){
    jlong values[]={static_cast<jlong>(s.task_id),static_cast<jlong>(s.generation),s.state,s.error,static_cast<jlong>(s.downloaded_bytes),static_cast<jlong>(s.durable_bytes),static_cast<jlong>(s.wire_bytes),s.total_bytes,static_cast<jlong>(s.request_count),s.active_requests,s.peak_active_requests,s.http_status,static_cast<jlong>(sequence),kind};
    auto counters=env->NewLongArray(14);if(!counters)return nullptr;env->SetLongArrayRegion(counters,0,14,values);
    auto hash=env->NewStringUTF(s.sha256),message=env->NewStringUTF(s.message);
    auto cls=env->FindClass("dev/turbodl/nativebridge/NativeSnapshot");if(!cls)return nullptr;
    auto ctor=env->GetMethodID(cls,"<init>","([JLjava/lang/String;Ljava/lang/String;)V");
    auto result=ctor?env->NewObject(cls,ctor,counters,hash,message):nullptr;
    env->DeleteLocalRef(counters);env->DeleteLocalRef(hash);env->DeleteLocalRef(message);env->DeleteLocalRef(cls);return result;
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nCreate(JNIEnv* env,jclass,jbyteArray directory,jint tasks,jint connections,jlong buffer,jint flags){
    try{auto path=bytes(env,directory);td_engine_options options{sizeof(options),TD_ABI_VERSION,path.c_str(),static_cast<uint32_t>(tasks),static_cast<uint32_t>(connections),static_cast<uint64_t>(buffer),static_cast<uint32_t>(flags),0};auto value=std::make_shared<Instance>();checked(td_engine_create(&options,&value->engine));std::lock_guard lock(registry_mutex);auto id=++next_handle;registry[id]=std::move(value);return id;}catch(const std::exception& e){error(env,e.what());return 0;}catch(...){error(env,"Native engine creation failed");return 0;}
}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nDestroy(JNIEnv*,jclass,jlong id){std::shared_ptr<Instance> value;{std::lock_guard lock(registry_mutex);auto it=registry.find(id);if(it!=registry.end()){value=it->second;registry.erase(it);}}}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nSetSpeedLimit(JNIEnv* env,jclass,jlong id,jlong speed){try{auto value=instance(id);if(speed<0)throw std::invalid_argument("Speed limit cannot be negative");checked(td_engine_set_speed_limit(value->engine,static_cast<uint64_t>(speed)));}catch(const std::exception& e){error(env,e.what());}catch(...){error(env,"Speed limit update failed");}}
JNIEXPORT jlong JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nSubmitHttp(
    JNIEnv* env,jclass,jlong id,jbyteArray url,jbyteArray destination,jbyteArray stable,
    jbyteArray expected,jobjectArray names,jobjectArray values,jint connections,jlong window,
    jint connect,jint read,jobjectArray network,jint http,jint retries,jint host,jbyteArray group,
    jlong known_size,jlong slice_offset,jlong slice_length,jlong maximum,jbyteArray representation,
    jobjectArray mirror_urls,jobjectArray mirror_names,jobjectArray mirror_values){
    try{
        if(known_size < -1 || slice_offset<0 || slice_length<0 ||
           (slice_length==0 && slice_offset!=0) || maximum<0)
            throw std::invalid_argument("Invalid native transfer bounds");
        auto value=instance(id);
        TransferInput transfer(env,url,destination,stable,expected,names,values,connections,window,
                               connect,read,network,http,retries,host,group,representation);
        const auto urls=strings(env,mirror_urls);
        const auto count=static_cast<jsize>(urls.size());
        if(count>32 || (mirror_names?env->GetArrayLength(mirror_names):0)!=count ||
           (mirror_values?env->GetArrayLength(mirror_values):0)!=count)
            throw std::invalid_argument("Invalid mirror header arrays");
        std::vector<std::unique_ptr<HeaderInput>> mirror_headers;
        std::vector<td_source_options> mirrors;
        for(jsize i=0;i<count;++i){
            auto keys=static_cast<jobjectArray>(env->GetObjectArrayElement(mirror_names,i));
            auto entries=static_cast<jobjectArray>(env->GetObjectArrayElement(mirror_values,i));
            if(env->ExceptionCheck())return 0;
            mirror_headers.push_back(std::make_unique<HeaderInput>(env,keys,entries));
            env->DeleteLocalRef(keys);
            env->DeleteLocalRef(entries);
            const auto& fields=mirror_headers.back()->headers;
            mirrors.push_back({sizeof(td_source_options),TD_ABI_VERSION,urls[i].c_str(),
                               fields.data(),static_cast<uint32_t>(fields.size()),0,nullptr});
        }
        if(env->ExceptionCheck())return 0;
        const int64_t expected_size=known_size;
        const td_request_slice slice{sizeof(slice),TD_ABI_VERSION,static_cast<uint64_t>(slice_offset),
                                     static_cast<uint64_t>(slice_length)};
        auto& options=transfer.options;
        options.slice=slice_length?&slice:nullptr;
        options.maximum_response_bytes=static_cast<uint64_t>(maximum);
        options.mirrors=mirrors.data();
        options.mirror_count=static_cast<uint32_t>(mirrors.size());
        options.expected_size=known_size>=0?&expected_size:nullptr;
        td_task_id task=0;checked(td_task_submit(value->engine,&options,&task));return static_cast<jlong>(task);
    }catch(const std::exception& e){error(env,e.what());return 0;}catch(...){error(env,"Native task submission failed");return 0;}
}
JNIEXPORT jlong JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nSubmit(
    JNIEnv* env,jclass type,jlong id,jbyteArray url,jbyteArray destination,jbyteArray stable,
    jbyteArray expected,jobjectArray names,jobjectArray values,jint connections,jlong window,
    jint connect,jint read,jobjectArray network,jint http,jint retries,jint host,jbyteArray group){
    return Java_dev_turbodl_nativebridge_NativeBridge_nSubmitHttp(
        env,type,id,url,destination,stable,expected,names,values,connections,window,connect,read,
        network,http,retries,host,group,-1,0,0,0,nullptr,nullptr,nullptr,nullptr);
}
JNIEXPORT jobject JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nQuery(JNIEnv* env,jclass,jlong id,jlong task){
    try{auto value=instance(id);td_snapshot s{};s.struct_size=sizeof(s);s.abi_version=TD_ABI_VERSION;checked(td_task_query(value->engine,static_cast<td_task_id>(task),&s));return snapshot(env,s);}catch(const std::exception& e){error(env,e.what());return nullptr;}catch(...){error(env,"Native task query failed");return nullptr;}
}
JNIEXPORT jlong JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nSubmitMedia(
    JNIEnv* env,jclass,jlong id,jbyteArray url,jbyteArray destination,jbyteArray stable,
    jbyteArray expected,jobjectArray names,jobjectArray values,jint connections,jlong window,
    jint connect,jint read,jobjectArray network,jint http,jint retries,jint host,jbyteArray group,
    jbyteArray representation,jint protocol,jint segments,jlong bandwidth,jlong segment_bytes,
    jbyteArray language,jbyteArray video,jbyteArray audio,jint seconds){
    try{
        if(bandwidth<0 || segment_bytes<0)throw std::invalid_argument("Invalid media bounds");
        auto value=instance(id);
        TransferInput transfer(env,url,destination,stable,expected,names,values,connections,window,
                               connect,read,network,http,retries,host,group,representation);
        const auto lang=bytes(env,language),video_id=bytes(env,video),audio_id=bytes(env,audio);
        if(env->ExceptionCheck())return 0;
        const td_media_options options{sizeof(options),TD_ABI_VERSION,&transfer.options,
            static_cast<uint32_t>(protocol),static_cast<uint32_t>(segments),
            static_cast<uint64_t>(bandwidth),static_cast<uint64_t>(segment_bytes),
            lang.c_str(),video_id.c_str(),audio_id.c_str(),static_cast<uint32_t>(seconds),0};
        td_task_id task=0;
        checked(td_media_submit(value->engine,&options,&task));
        return static_cast<jlong>(task);
    }catch(const std::exception& e){error(env,e.what());return 0;}catch(...){error(env,"Native media submission failed");return 0;}
}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nFinishMedia(JNIEnv* env,jclass,jlong id,jlong task){
    try{auto value=instance(id);checked(td_media_finish(value->engine,static_cast<td_task_id>(task)));}
    catch(const std::exception& e){error(env,e.what());}catch(...){error(env,"Native recording finish failed");}
}
JNIEXPORT jlongArray JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nMediaDetails(JNIEnv* env,jclass,jlong id,jlong task){
    try{
        auto value=instance(id);
        td_media_details details{};
        details.struct_size=sizeof(details);
        details.abi_version=TD_ABI_VERSION;
        checked(td_media_query(value->engine,static_cast<td_task_id>(task),&details));
        const jlong fields[]{details.stage,details.failure_stage,details.tracks,details.live,
            static_cast<jlong>(details.media_units),static_cast<jlong>(details.verified_units),
            static_cast<jlong>(details.recorded_milliseconds)};
        auto result=env->NewLongArray(7);
        if(result)env->SetLongArrayRegion(result,0,7,fields);
        return result;
    }catch(const std::exception& e){error(env,e.what());return nullptr;}catch(...){error(env,"Native media details failed");return nullptr;}
}
JNIEXPORT jobjectArray JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nPoll(JNIEnv* env,jclass,jlong id,jint capacity){
    try{if(capacity<0 || capacity>256)throw std::invalid_argument("Event capacity is invalid");auto value=instance(id);std::vector<td_event> events(static_cast<size_t>(capacity));uint32_t count=0;checked(td_engine_poll(value->engine,events.data(),static_cast<uint32_t>(capacity),&count));auto cls=env->FindClass("dev/turbodl/nativebridge/NativeSnapshot");if(!cls)return nullptr;auto result=env->NewObjectArray(static_cast<jsize>(count),cls,nullptr);env->DeleteLocalRef(cls);if(!result)return nullptr;for(uint32_t i=0;i<count;++i){auto item=snapshot(env,events[i].snapshot,events[i].sequence,events[i].kind);if(!item)return nullptr;env->SetObjectArrayElement(result,static_cast<jsize>(i),item);env->DeleteLocalRef(item);}return result;}catch(const std::exception& e){error(env,e.what());return nullptr;}catch(...){error(env,"Native event query failed");return nullptr;}
}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nControl(JNIEnv* env,jclass,jlong id,jlong task,jint command){try{auto value=instance(id);checked(td_task_control(value->engine,static_cast<td_task_id>(task),static_cast<uint32_t>(command)));}catch(const std::exception& e){error(env,e.what());}catch(...){error(env,"Native task control failed");}}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nReplaceSource(JNIEnv* env,jclass,jlong id,jlong task,jbyteArray url,jobjectArray names,jobjectArray values){try{auto value=instance(id);auto source=bytes(env,url);HeaderInput headers(env,names,values);td_source_options options{sizeof(options),TD_ABI_VERSION,source.c_str(),headers.headers.data(),static_cast<uint32_t>(headers.headers.size()),0};checked(td_task_replace_source(value->engine,static_cast<td_task_id>(task),&options));}catch(const std::exception& e){error(env,e.what());}catch(...){error(env,"Source replacement failed");}}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nForget(JNIEnv* env,jclass,jlong id,jlong task){try{auto value=instance(id);checked(td_task_forget(value->engine,static_cast<td_task_id>(task)));}catch(const std::exception& e){error(env,e.what());}catch(...){error(env,"Task retirement failed");}}
JNIEXPORT jstring JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nCapabilities(JNIEnv* env,jclass){return env->NewStringUTF(td_engine_capabilities());}
JNIEXPORT jlong JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nSubmitTorrent(JNIEnv* env,jclass,jlong id,jbyteArray source,jbyteArray destination,jbyteArray key,jint peers,jint seed,jintArray selection,jlong download,jlong upload){
    try{
        auto value=instance(id);auto s=bytes(env,source),d=bytes(env,destination),k=bytes(env,key);
        if(download<0 || upload<0)throw std::invalid_argument("Torrent rate limits cannot be negative");
        auto count=selection?env->GetArrayLength(selection):0;
        if(count>10000)throw std::invalid_argument("Torrent file selection exceeds limit");
        std::vector<jint> indices(static_cast<size_t>(count));
        if(count)env->GetIntArrayRegion(selection,0,count,indices.data());
        if(env->ExceptionCheck())return 0;
        td_torrent_limits options{};
        options.base={sizeof(options),TD_ABI_VERSION,s.c_str(),d.c_str(),k.c_str(),static_cast<uint32_t>(peers),static_cast<uint32_t>(seed),indices.data(),static_cast<uint32_t>(count),0};
        options.download_bytes_per_second=static_cast<uint64_t>(download);options.upload_bytes_per_second=static_cast<uint64_t>(upload);
        td_task_id task=0;checked(td_torrent_submit(value->engine,&options.base,&task));return static_cast<jlong>(task);
    }catch(const std::exception& e){error(env,e.what());return 0;}catch(...){error(env,"Torrent submission failed");return 0;}
}
JNIEXPORT void JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nConnectPeer(JNIEnv* env,jclass,jlong id,jlong task,jbyteArray host,jint port){
    try{auto value=instance(id);auto address=bytes(env,host);if(port<1 || port>65535)throw std::invalid_argument("Invalid peer port");checked(td_torrent_connect_peer(value->engine,static_cast<td_task_id>(task),address.c_str(),static_cast<uint16_t>(port)));}catch(const std::exception& e){error(env,e.what());}catch(...){error(env,"Peer connection failed");}
}
JNIEXPORT jlongArray JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nHttpMetrics(JNIEnv* env,jclass,jlong id,jlong task){
    try{
        auto value=instance(id);td_http_metrics m{};m.struct_size=sizeof(m);m.abi_version=TD_ABI_VERSION;
        checked(td_task_http_metrics(value->engine,static_cast<td_task_id>(task),&m));
        jlong data[]={static_cast<jlong>(m.finished_requests),static_cast<jlong>(m.new_connections),static_cast<jlong>(m.dns_microseconds_sum),static_cast<jlong>(m.connect_microseconds_sum),static_cast<jlong>(m.tls_microseconds_sum),static_cast<jlong>(m.request_first_byte_microseconds_sum),static_cast<jlong>(m.task_first_byte_microseconds),m.negotiated_versions,m.active_streams,m.active_connections,m.peak_connections};
        auto result=env->NewLongArray(11);if(result)env->SetLongArrayRegion(result,0,11,data);return result;
    }catch(const std::exception& e){error(env,e.what());return nullptr;}catch(...){error(env,"HTTP metrics query failed");return nullptr;}
}
JNIEXPORT jlong JNICALL Java_dev_turbodl_nativebridge_NativeBridge_nSubmitMetalink(JNIEnv* env,jclass,jlong id,jbyteArray document,jbyteArray base,jbyteArray name,jbyteArray destination,jbyteArray stable,jint connections,jlong window,jint connect,jint read,jint retries,jint host,jbyteArray group){
    try{
        auto value=instance(id);auto xml=bytes(env,document),url=bytes(env,base),file=bytes(env,name),target=bytes(env,destination),key=bytes(env,stable),cooldown=bytes(env,group);
        td_transfer_policy policy{sizeof(policy),TD_ABI_VERSION,static_cast<uint32_t>(retries),static_cast<uint32_t>(host),cooldown.c_str()};
        td_task_options options{};options.struct_size=sizeof(options);options.abi_version=TD_ABI_VERSION;options.destination=target.c_str();options.stable_key=key.c_str();options.connections=static_cast<uint32_t>(connections);options.request_window_bytes=static_cast<uint64_t>(window);options.connect_timeout_ms=static_cast<uint32_t>(connect);options.read_timeout_ms=static_cast<uint32_t>(read);
        options.policy=&policy;
        td_task_id task=0;checked(td_metalink_submit(value->engine,xml.data(),static_cast<uint32_t>(xml.size()),url.c_str(),file.empty()?nullptr:file.c_str(),&options,&task));return static_cast<jlong>(task);
    }catch(const std::exception& e){error(env,e.what());return 0;}catch(...){error(env,"Metalink submission failed");return 0;}
}
}
