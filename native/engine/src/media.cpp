#include "media.hpp"
#include "media_state.hpp"
#include "resources.hpp"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <map>
#include <set>

namespace td {
namespace {
using Clock=std::chrono::steady_clock;
bool inactive(uint32_t state){return state==TD_COMPLETED || state==TD_FAILED || state==TD_CANCELLED || state==TD_PAUSED;}
template<size_t N>void text(char(&out)[N],const std::string& s){auto n=std::min(s.size(),N-1);std::memcpy(out,s.data(),n);out[n]=0;}
std::string read_text(const fs::path& file){
    if(target_size(file)>1048576)throw std::invalid_argument("Media manifest exceeds limit");
    std::ifstream input(file,std::ios::binary);if(!input)throw std::runtime_error("Cannot read media manifest");
    return {std::istreambuf_iterator<char>(input),std::istreambuf_iterator<char>()};
}
std::string range_id(const std::optional<MediaByteRange>& range){return range?std::to_string(range->offset)+":"+std::to_string(range->length):"all";}
std::string iv_id(const std::array<uint8_t,16>& iv){std::string s;const char* hex="0123456789abcdef";for(auto c:iv){s+=hex[c>>4];s+=hex[c&15];}return s;}
std::string fingerprint(std::initializer_list<std::string> parts){std::string input;for(const auto& part:parts){input+=std::to_string(part.size())+":"+part;}return sha256(input);}
struct Grant {explicit Grant(uint64_t value):id(value){}Grant(const Grant&)=delete;uint64_t id;~Grant(){process_resources().release_processing(id);}};
std::shared_ptr<Grant> processing(uint64_t bytes){auto id=process_resources().acquire_processing(bytes);if(!id)return {};try{return std::make_shared<Grant>(id);}catch(...){process_resources().release_processing(id);throw;}}
struct Unit {
    std::string id,url,fetch_key;
    fs::path raw,plain;
    std::optional<MediaByteRange> range;
    std::optional<size_t> key;
    std::array<uint8_t,16> iv{};
    uint64_t child=0,network_size=0,downloaded=0,durable=0;
    bool is_key=false,ready=false,raw_done=false,checked=false;
};
struct Track {std::string name;std::vector<size_t> pieces;std::map<std::string,std::string> seen;double duration=0;uint64_t last_sequence=0;bool has_sequence=false,allow_resume_gap=false;};
struct Node {
    std::string id,url,role,fetch_key,effective;
    fs::path raw;
    uint32_t depth=0;
    uint64_t child=0,revision=0;
    bool master=false,ended=false,loaded=false,live=false;
    Clock::time_point poll_at{};
};
struct Initialized {
    std::shared_ptr<MediaState> state;
    std::shared_ptr<FileLock> root_lock,target_lock;
    std::map<std::string,StoredMediaUnit> stored;
    bool published=false,ready=false,resumed=false;
    MediaProduct product;
};
struct Parsed {std::optional<HlsManifest> hls;std::optional<DashManifest> dash;};

class Job final:public MediaJob {
public:
    Job(const MediaOptions& options,const fs::path& directory,IoQueue& disk,MediaHost host)
        :options_(options),root_(directory/options.key),disk_(disk),host_(std::move(host)){
        view_.struct_size=sizeof(view_);view_.abi_version=TD_ABI_VERSION;view_.generation=1;view_.state=TD_PROBING;view_.total_bytes=-1;
        detail_.struct_size=sizeof(detail_);detail_.abi_version=TD_ABI_VERSION;detail_.stage=TD_MEDIA_RESOLVING;
        target_=native_path(fs::path(std::u8string(reinterpret_cast<const char8_t*>(options.target.data()),options.target.size())));
        product_path_=root_/"product.mp4";
        run_id_=sha256(std::to_string(Clock::now().time_since_epoch().count())+options.key).substr(0,16);
        initialize();
    }
    ~Job()override{shutdown();}
    void shutdown()override{
        stop_.store(true);
        if(io_.valid())try{io_.get();}catch(...){}
        std::set<uint64_t> child_ids;
        for(auto id:children())child_ids.insert(id);
        for(auto id:child_ids){
            try{
                auto value=host_.query(id);
                if(inactive(value.snapshot.state))host_.forget(id);
                else host_.control(id,TD_CANCEL);
            }catch(...){}
        }
        state_.reset();root_lock_.reset();target_lock_.reset();
    }
    td_snapshot snapshot()const override{return view_;}
    td_http_metrics metrics()const override{return metrics_;}
    td_media_details details()const override{return detail_;}
    void control(uint32_t command)override{
        if(inactive(view_.state))return;
        pending_=command;stop_.store(true);view_.state=TD_PAUSING;
        for(auto id:children())host_.control(id,TD_PAUSE);
    }
    void finish()override{finish_=true;}
    void fail(uint32_t error,const std::string& reason)override{
        if(pending_)return;pending_=4;stop_.store(true);view_.state=TD_PAUSING;view_.error=error;text(view_.message,reason);detail_.failure_stage=detail_.stage;
        for(auto id:children())host_.control(id,TD_PAUSE);
    }
    long next_timer()const override{
        if(inactive(view_.state))return 1000;
        auto now=Clock::now();long timeout=1000;
        auto consider=[&](Clock::time_point when){if(when!=Clock::time_point{})timeout=std::min(timeout,static_cast<long>(std::max<int64_t>(0,std::chrono::duration_cast<std::chrono::milliseconds>(when-now).count())));};
        if(!io_.valid() && !pending_){
            consider(retry_at_);
            if(!finish_)for(const auto& n:nodes_)if(n.loaded && n.live && !n.ended && !n.child)consider(n.poll_at);
            if(record_started_ && options_.record_seconds)consider(*record_started_+std::chrono::seconds(options_.record_seconds));
        }
        return timeout;
    }
    void tick()override{
        if(inactive(view_.state))return;
        if(io_.valid()){
            if(io_.wait_for(std::chrono::seconds(0))!=std::future_status::ready){observe();return;}
            try{auto apply=io_.get();apply();}
            catch(const IntegrityError& e){if(!pending_)fail(TD_ERROR_INTEGRITY,e.what());}
            catch(const std::invalid_argument& e){if(!pending_)fail(TD_ERROR_POLICY,e.what());}
            catch(const MediaCancelled&){if(!pending_)fail(TD_ERROR_STORAGE,"Media processing interrupted; cache retained");}
            catch(...){if(!pending_)fail(TD_ERROR_STORAGE,"Media I/O failed; cache retained");}
        }
        observe();
        if(pending_){settle_stop();return;}
        if(!initialized_)return;
        if(published_){view_.state=TD_COMPLETED;view_.error=0;view_.total_bytes=static_cast<int64_t>(product_.size);view_.downloaded_bytes=view_.durable_bytes=product_.size;text(view_.sha256,product_.sha256);detail_.stage=TD_MEDIA_DONE;release();return;}
        if(ready_product_){save_product();return;}
        if(record_started_ && options_.record_seconds && Clock::now()-*record_started_>=std::chrono::seconds(options_.record_seconds))finish_=true;
        if(Clock::now()<retry_at_)return;
        try{
            if(collect_manifest())return;
            if(collect_units())return;
            if(io_.valid() || pending_)return;
            if(validate_units())return;
            if(process_unit())return;
            if((!finish_ || !discovery_complete()) && start_manifest())return;
            start_units();
            if(!io_.valid() && children().empty() && all_units_ready() && discovery_complete() && recording_complete())start_mux();
        }catch(const ResourceBusy&){retry_at_=Clock::now()+std::chrono::milliseconds(20);}
        catch(const std::invalid_argument& e){fail(TD_ERROR_POLICY,e.what());}
        catch(const IntegrityError& e){fail(TD_ERROR_INTEGRITY,e.what());}
        catch(...){fail(TD_ERROR_STORAGE,"Media controller operation failed; cache retained");}
    }
private:
    template<class Work,class Apply>void schedule(Work work,Apply apply){
        if(io_.valid())throw std::logic_error("Concurrent media disk jobs");
        io_=disk_.submit([work=std::move(work),apply=std::move(apply)]()mutable->std::function<void()>{
            using Result=std::invoke_result_t<Work>;auto result=std::make_shared<Result>(work());
            return [result,apply=std::move(apply)]()mutable{apply(std::move(*result));};
        });
    }
    void initialize(){
        auto root=root_,target=target_;auto options=options_;auto* stop=&stop_;
        schedule([root,target,options,stop]{
            fs::create_directories(root);if(!target.parent_path().empty())fs::create_directories(target.parent_path());
            Initialized r;r.resumed=fs::exists(root/"state.sqlite3");r.root_lock=std::make_shared<FileLock>(root/"controller.lock");
            auto lock=target;lock+=".turbodl.lock";r.target_lock=std::make_shared<FileLock>(lock);
            r.state=std::make_shared<MediaState>(root,options.target,options.identity);r.stored=r.state->units();r.product=r.state->product();
            if(fs::exists(target)){
                if(r.state->phase()=="NETWORK" || r.product.sha256.empty() || target_size(target)!=r.product.size || sha256_file(target,[&]{return stop->load();})!=r.product.sha256)throw IntegrityError("Refusing to overwrite an unowned or changed media target");
                r.state->completed();r.published=true;
            }else if(r.state->phase()!="NETWORK" && fs::exists(root/"product.mp4")){
                r.ready=target_size(root/"product.mp4")==r.product.size && sha256_file(root/"product.mp4",[&]{return stop->load();})==r.product.sha256;
            }
            return r;
        },[this](Initialized r){state_=std::move(r.state);root_lock_=std::move(r.root_lock);target_lock_=std::move(r.target_lock);stored_=std::move(r.stored);resumed_=r.resumed;product_=std::move(r.product);published_=r.published;ready_product_=r.ready;initialized_=true;nodes_.push_back({"root",options_.url,"auto"});});
    }
    std::vector<uint64_t> children()const{
        std::vector<uint64_t> ids;for(const auto& n:nodes_)if(n.child)ids.push_back(n.child);for(const auto& u:units_)if(u.child)ids.push_back(u.child);return ids;
    }
    void add_metrics(const MediaFetchState& value){
        retired_wire_+=value.snapshot.wire_bytes;retired_requests_+=value.snapshot.request_count;
        retired_metrics_.finished_requests+=value.metrics.finished_requests;retired_metrics_.new_connections+=value.metrics.new_connections;
        retired_metrics_.dns_microseconds_sum+=value.metrics.dns_microseconds_sum;retired_metrics_.connect_microseconds_sum+=value.metrics.connect_microseconds_sum;retired_metrics_.tls_microseconds_sum+=value.metrics.tls_microseconds_sum;
        retired_metrics_.request_first_byte_microseconds_sum+=value.metrics.request_first_byte_microseconds_sum;retired_metrics_.negotiated_versions|=value.metrics.negotiated_versions;
    }
    void retire(uint64_t id,const MediaFetchState& state){
        add_metrics(state);
        if(!pending_ && state.snapshot.http_status)view_.http_status=state.snapshot.http_status;
        host_.forget(id);
    }
    void observe(){
        uint64_t wire=retired_wire_,requests=retired_requests_,downloaded=0,durable=0;uint32_t active=0;std::set<int64_t> connections;
        metrics_=retired_metrics_;uint64_t verified=0,media=0;
        for(auto id:children()){
            auto s=host_.query(id);wire+=s.snapshot.wire_bytes;requests+=s.snapshot.request_count;active+=s.snapshot.active_requests;
            if(!pending_ && s.snapshot.http_status)view_.http_status=s.snapshot.http_status;
            connections.insert(s.connections.begin(),s.connections.end());
            metrics_.negotiated_versions|=s.metrics.negotiated_versions;metrics_.active_streams+=s.metrics.active_streams;
            metrics_.finished_requests+=s.metrics.finished_requests;metrics_.new_connections+=s.metrics.new_connections;
            metrics_.dns_microseconds_sum+=s.metrics.dns_microseconds_sum;metrics_.connect_microseconds_sum+=s.metrics.connect_microseconds_sum;metrics_.tls_microseconds_sum+=s.metrics.tls_microseconds_sum;metrics_.request_first_byte_microseconds_sum+=s.metrics.request_first_byte_microseconds_sum;
        }
        for(const auto& u:units_)if(!u.is_key){
            ++media;if(u.ready)++verified;
            if(u.child){auto s=host_.query(u.child).snapshot;downloaded+=s.downloaded_bytes;durable+=s.durable_bytes;}
            else{downloaded+=u.ready?u.network_size:u.downloaded;durable+=u.ready?u.network_size:u.durable;}
        }
        view_.wire_bytes=wire;view_.request_count=requests;view_.active_requests=active;view_.peak_active_requests=std::max(view_.peak_active_requests,active);
        if(!published_){view_.downloaded_bytes=downloaded;view_.durable_bytes=durable;}
        if(downloaded && !first_byte_us_)first_byte_us_=static_cast<uint64_t>(std::max<int64_t>(1,std::chrono::duration_cast<std::chrono::microseconds>(Clock::now()-submitted_).count()));
        metrics_.task_first_byte_microseconds=first_byte_us_;
        metrics_.active_connections=static_cast<uint32_t>(connections.size());peak_connections_=std::max(peak_connections_,metrics_.active_connections);metrics_.peak_connections=peak_connections_;
        detail_.media_units=media;detail_.verified_units=verified;detail_.tracks=static_cast<uint32_t>(tracks_.size());
        if(!tracks_.empty())detail_.recorded_milliseconds=static_cast<uint64_t>(std::max(0.0,tracks_.front().duration)*1000);
    }
    void settle_stop(){
        bool active=false;
        for(auto& node:nodes_)if(node.child){auto s=host_.query(node.child);if(inactive(s.snapshot.state)){retire(node.child,s);node.child=0;}else{host_.control(node.child,TD_PAUSE);active=true;}}
        for(auto& unit:units_)if(unit.child){auto s=host_.query(unit.child);unit.downloaded=s.snapshot.downloaded_bytes;unit.durable=s.snapshot.durable_bytes;if(inactive(s.snapshot.state)){retire(unit.child,s);unit.child=0;}else{host_.control(unit.child,TD_PAUSE);active=true;}}
        if(active || io_.valid())return;
        view_.active_requests=0;view_.state=pending_==4?TD_FAILED:pending_==TD_CANCEL?TD_CANCELLED:TD_PAUSED;
        metrics_.active_streams=0;metrics_.active_connections=0;
        if(pending_!=4){view_.error=0;view_.message[0]=0;}release();
    }
    void release(){state_.reset();root_lock_.reset();target_lock_.reset();}
    size_t track(const std::string& name){for(size_t i=0;i<tracks_.size();++i)if(tracks_[i].name==name)return i;tracks_.push_back(Track{name});tracks_.back().allow_resume_gap=resumed_;return tracks_.size()-1;}
    size_t unit(const std::string& url,const std::optional<MediaByteRange>& range,const std::optional<HlsKey>& key,const std::string& key_scope,bool is_key=false){
        std::optional<size_t> key_index;
        if(key)key_index=unit(key->url,std::nullopt,std::nullopt,key_scope+":"+std::to_string(key->generation),true);
        auto id=fingerprint({is_key?"key":key?"aes":"plain",url,range_id(range),key?units_.at(*key_index).id:is_key?key_scope:"",key?iv_id(key->iv):""});
        if(auto found=unit_index_.find(id);found!=unit_index_.end())return found->second;
        if(units_.size()>=12000)throw std::invalid_argument("Media unit count exceeds limit");
        Unit u;u.id=id;u.url=url;u.range=range;u.key=key_index;u.is_key=is_key;if(key)u.iv=key->iv;
        u.raw=root_/(id+".raw");u.plain=key?root_/(id+".media"):u.raw;u.fetch_key="media:"+options_.key+":"+id;
        auto index=units_.size();unit_index_[id]=index;units_.push_back(std::move(u));return index;
    }
    void append_hls(size_t index,const HlsManifest& plan,const std::string& effective){
        auto role=nodes_.at(index).role;
        if(!plan.variants.empty()){
            if(nodes_[index].depth>=4)throw std::invalid_argument("HLS master nesting exceeds limit");
            const HlsVariant* selected=nullptr;
            for(const auto& v:plan.variants){bool ok=!options_.maximum_bandwidth || v.bandwidth<=options_.maximum_bandwidth;bool best=selected && (!options_.maximum_bandwidth || selected->bandwidth<=options_.maximum_bandwidth);if(!selected || (ok&&!best) || (ok==best && (ok?v.bandwidth>selected->bandwidth:v.bandwidth<selected->bandwidth)))selected=&v;}
            const HlsRendition* audio=nullptr;
            for(const auto& a:plan.audio)if(a.group==selected->audio_group){if(!audio || (a.preferred&&!audio->preferred) || (!options_.audio_language.empty() && a.language==options_.audio_language))audio=&a;}
            std::vector<std::pair<std::string,std::string>> wanted{{selected->url,role=="auto"?(audio?"video":"auto"):role}};
            if(audio)wanted.emplace_back(audio->url,"audio");
            auto depth=nodes_[index].depth+1;nodes_[index].master=true;
            for(const auto& [url,kind]:wanted){
                if(nodes_.size()>=12 || std::any_of(nodes_.begin(),nodes_.end(),[&](const auto& n){return n.url==url;}))throw std::invalid_argument("Cyclic or excessive HLS master");
                nodes_.push_back({sha256(url).substr(0,16),url,kind,"","",{},depth});
            }
            return;
        }
        auto t=track(role);auto& destination=tracks_[t];auto previous=nodes_[index].loaded;
        nodes_[index].ended=plan.ended;nodes_[index].live=!plan.ended;nodes_[index].poll_at=Clock::now()+std::chrono::milliseconds(std::max<uint64_t>(250,plan.target_duration*500));
        if(!plan.ended){detail_.live=1;if(!record_started_)record_started_=Clock::now();}
        for(const auto& segment:plan.segments){
            auto sequence=std::to_string(segment.discontinuity)+":"+std::to_string(segment.sequence);
            auto descriptor=fingerprint({segment.url,range_id(segment.range),segment.key?segment.key->url:"",segment.key?iv_id(segment.key->iv):"",segment.init?segment.init->url:"",segment.init?range_id(segment.init->range):""});
            auto found=destination.seen.find(sequence);
            if(found!=destination.seen.end()){if(found->second!=descriptor)throw IntegrityError("Live HLS changed an existing sequence");continue;}
            if(destination.has_sequence && segment.sequence>destination.last_sequence+1 && previous && !destination.allow_resume_gap)throw IntegrityError("Live HLS window expired before segments were recorded");
            destination.allow_resume_gap=false;destination.has_sequence=true;destination.last_sequence=segment.sequence;destination.seen[sequence]=descriptor;
            if(segment.gap)continue;
            auto scope=nodes_[index].id+":"+(nodes_[index].revision>1?run_id_+":":"")+std::to_string(nodes_[index].revision);
            if(segment.init){auto init=unit(segment.init->url,segment.init->range,segment.init->key,scope);if(destination.pieces.empty() || destination.pieces.back()!=init){if(!last_init_.contains(t) || last_init_[t]!=init){destination.pieces.push_back(init);last_init_[t]=init;}}}
            destination.pieces.push_back(unit(segment.url,segment.range,segment.key,scope));destination.duration+=segment.duration;
        }
        (void)effective;
    }
    void append_dash(size_t index,const DashManifest& plan){
        nodes_[index].ended=!plan.dynamic;nodes_[index].live=plan.dynamic;nodes_[index].poll_at=Clock::now()+std::chrono::milliseconds(static_cast<int64_t>(std::max(.25,plan.minimum_update_seconds)*1000));
        if(plan.dynamic){detail_.live=1;if(!record_started_)record_started_=Clock::now();}
        for(const auto& source:plan.tracks){
            auto t=track(source.kind);auto& destination=tracks_[t];
            for(const auto& segment:source.segments){
                auto id=source.id+":"+std::to_string(segment.number)+":"+std::to_string(segment.time);
                auto descriptor=fingerprint({segment.url,range_id(segment.range),segment.init?segment.init->url:"",segment.init?range_id(segment.init->range):""});
                auto found=destination.seen.find(id);if(found!=destination.seen.end()){if(found->second!=descriptor)throw IntegrityError("DASH changed an existing segment");continue;}
                destination.seen[id]=descriptor;
                if(segment.init){auto init=unit(segment.init->url,segment.init->range,std::nullopt,"");if(!last_init_.contains(t) || last_init_[t]!=init){destination.pieces.push_back(init);last_init_[t]=init;}}
                destination.pieces.push_back(unit(segment.url,segment.range,std::nullopt,""));destination.duration+=segment.duration;
            }
        }
    }
    bool collect_manifest(){
        for(size_t i=0;i<nodes_.size();++i)if(nodes_[i].child){
            auto status=host_.query(nodes_[i].child);if(!inactive(status.snapshot.state))continue;
            retire(nodes_[i].child,status);nodes_[i].child=0;
            if(status.snapshot.state!=TD_COMPLETED){view_.http_status=status.snapshot.http_status;fail(status.snapshot.error?status.snapshot.error:TD_ERROR_NETWORK,"Media manifest transfer failed");return true;}
            auto path=nodes_[i].raw;auto base=status.effective_url;auto protocol=options_.protocol;auto key=nodes_[i].fetch_key;auto ephemeral=nodes_[i].revision>1;auto erase=host_.erase_checkpoint;DashSelection selection{options_.maximum_bandwidth,options_.audio_language,options_.video_id,options_.audio_id};
            schedule([path,base,protocol,selection,key,ephemeral,erase]{
                auto cleanup=[&]{erase(key);std::error_code ignored;fs::remove(path,ignored);auto lock=path;lock+=".turbodl.lock";fs::remove(lock,ignored);};
                try{auto content=read_text(path);Parsed p;if(protocol==TD_MEDIA_HLS || (protocol==TD_MEDIA_AUTO && (content.starts_with("#EXTM3U") || content.starts_with("\xef\xbb\xbf#EXTM3U"))))p.hls=parse_hls_manifest(content,base);else p.dash=parse_dash_manifest(content,base,selection);if(ephemeral)cleanup();return p;}
                catch(...){cleanup();throw;}
            },[this,i,base](Parsed p){
                if(p.hls)append_hls(i,*p.hls,base);else append_dash(i,*p.dash);nodes_[i].loaded=true;nodes_[i].effective=base;detail_.stage=TD_MEDIA_DOWNLOADING;view_.state=TD_TRANSFERRING;
            });
            return true;
        }
        return false;
    }
    bool start_manifest(){
        for(auto& node:nodes_)if(!node.child && !node.master && (!node.loaded || (node.live && !node.ended && Clock::now()>=node.poll_at))){
            auto revision=node.revision;auto suffix=revision?"-"+run_id_+"-"+std::to_string(revision):"";
            node.raw=root_/("manifest-"+node.id+suffix+".raw");node.fetch_key="media:"+options_.key+":manifest:"+node.id+suffix;
            node.child=host_.submit({node.url,node.fetch_key,node.raw,std::nullopt,1048576,-1});++node.revision;return true;
        }
        return false;
    }
    bool collect_units(){
        for(auto& u:units_)if(u.child){
            auto s=host_.query(u.child);u.downloaded=s.snapshot.downloaded_bytes;u.durable=s.snapshot.durable_bytes;
            if(!inactive(s.snapshot.state))continue;
            retire(u.child,s);u.child=0;
            if(s.snapshot.state!=TD_COMPLETED){view_.http_status=s.snapshot.http_status;fail(s.snapshot.error?s.snapshot.error:TD_ERROR_NETWORK,u.is_key?"HLS key transfer failed":"Media segment transfer failed");return true;}
            u.raw_done=true;u.network_size=static_cast<uint64_t>(std::max<int64_t>(0,s.snapshot.total_bytes));
        }
        return false;
    }
    bool validate_units(){
        std::vector<std::pair<size_t,StoredMediaUnit>> checks;
        for(size_t i=0;i<units_.size();++i)if(!units_[i].checked){units_[i].checked=true;if(auto value=stored_.find(units_[i].id);value!=stored_.end())checks.emplace_back(i,value->second);}
        if(checks.empty())return false;
        auto paths=std::vector<fs::path>{};for(auto& [i,value]:checks){(void)value;paths.push_back(units_[i].plain);}auto* stop=&stop_;
        schedule([checks=std::move(checks),paths=std::move(paths),stop]{std::vector<std::pair<size_t,StoredMediaUnit>> valid;for(size_t n=0;n<checks.size();++n){if(stop->load())throw MediaCancelled();auto& value=checks[n].second;if(fs::is_regular_file(paths[n]) && target_size(paths[n])==value.size && sha256_file(paths[n],[&]{return stop->load();})==value.sha256)valid.push_back(checks[n]);}return valid;},[this](auto valid){for(const auto& [i,value]:valid){units_[i].ready=true;units_[i].network_size=value.network_size;}});
        return true;
    }
    bool process_unit(){
        for(size_t i=0;i<units_.size();++i){auto& u=units_[i];if(!u.raw_done || u.ready || (u.key && !units_[*u.key].ready))continue;
            auto grant=processing(262144);if(!grant)return false;
            detail_.stage=u.is_key?TD_MEDIA_RESOLVING:TD_MEDIA_PROCESSING;
            auto unit=u;auto key=u.key?units_[*u.key].plain:fs::path{};auto state=state_;auto* stop=&stop_;
            schedule([unit,key,state,stop,grant]{
                auto plain=unit.plain;auto temporary=plain;temporary+=".decrypting";
                try{
                    if(unit.key){decrypt_media_aes128(unit.raw,temporary,key,unit.iv,*stop);if(fs::exists(plain))fs::remove(plain);fs::rename(temporary,plain);}
                    if(stop->load())throw MediaCancelled();
                    StoredMediaUnit result{sha256_file(plain,[&]{return stop->load();}),target_size(plain),unit.network_size};
                    if(unit.is_key && result.size!=16)throw IntegrityError("HLS AES key has invalid length");state->unit(unit.id,result);return result;
                }catch(...){std::error_code ignored;if(unit.key)fs::remove(temporary,ignored);throw;}
            },[this,i](StoredMediaUnit value){units_[i].ready=true;units_[i].network_size=value.network_size;stored_[units_[i].id]=std::move(value);detail_.stage=TD_MEDIA_DOWNLOADING;});
            return true;
        }
        return false;
    }
    void start_units(){
        size_t active=children().size();
        for(auto& u:units_)if(active<options_.parallel && u.checked && !u.ready && !u.raw_done && !u.child){
            u.child=host_.submit({u.url,u.fetch_key,u.raw,u.range,u.is_key?16:options_.maximum_segment_bytes,u.is_key?16:-1});++active;
        }
    }
    bool all_units_ready()const{return !tracks_.empty() && !units_.empty() && std::all_of(units_.begin(),units_.end(),[](const auto& u){return u.ready;});}
    bool discovery_complete()const{return std::all_of(nodes_.begin(),nodes_.end(),[](const auto& n){return n.loaded;});}
    bool recording_complete()const{return finish_ || std::all_of(nodes_.begin(),nodes_.end(),[](const auto& n){return n.master || n.ended;});}
    void start_mux(){
        auto grant=processing(4*1024*1024);if(!grant)return;
        std::vector<MediaInput> inputs;
        for(const auto& track:tracks_){MediaInput input;input.kind=track.name;for(auto i:track.pieces)input.pieces.push_back(units_.at(i).plain);if(!input.pieces.empty())inputs.push_back(std::move(input));}
        detail_.stage=TD_MEDIA_PROCESSING;view_.state=TD_VERIFYING;auto target=product_path_;auto state=state_;auto expected=options_.expected_sha256;auto size=options_.expected_size;auto* stop=&stop_;
        schedule([inputs=std::move(inputs),target,state,expected,size,stop,grant]{auto product=mux_media(inputs,target,*stop);if((!expected.empty() && product.sha256!=expected) || (size>=0 && product.size!=static_cast<uint64_t>(size)))throw IntegrityError("Processed media does not match requested identity");state->ready(product);return product;},[this](MediaProduct product){product_=std::move(product);ready_product_=true;});
    }
    void save_product(){
        detail_.stage=TD_MEDIA_SAVING;view_.state=TD_VERIFYING;auto source=product_path_,target=target_;auto product=product_;auto state=state_;auto owner=options_.key;auto* stop=&stop_;
        schedule([source,target,product,state,owner,stop]{publish_media_product(source,target,product,owner,*stop);state->completed();return product;},[this](MediaProduct product){product_=std::move(product);published_=true;});
    }

    MediaOptions options_;fs::path root_,target_,product_path_;IoQueue& disk_;MediaHost host_;
    std::future<std::function<void()>> io_;std::atomic<bool> stop_{false};
    std::shared_ptr<MediaState> state_;std::shared_ptr<FileLock> root_lock_,target_lock_;
    std::map<std::string,StoredMediaUnit> stored_;std::map<std::string,size_t> unit_index_;std::vector<Unit> units_;std::vector<Node> nodes_;std::vector<Track> tracks_;std::map<size_t,size_t> last_init_;
    td_snapshot view_{};td_media_details detail_{};td_http_metrics metrics_{},retired_metrics_{};
    uint64_t retired_wire_=0,retired_requests_=0;uint32_t peak_connections_=0,pending_=0;
    bool initialized_=false,published_=false,ready_product_=false,resumed_=false,finish_=false;
    MediaProduct product_;std::string run_id_;std::optional<Clock::time_point> record_started_;Clock::time_point retry_at_{},submitted_=Clock::now();uint64_t first_byte_us_=0;
};
}
std::unique_ptr<MediaJob> create_media_job(const MediaOptions& options,const fs::path& root,IoQueue& disk,MediaHost host){return std::make_unique<Job>(options,root,disk,std::move(host));}
}
