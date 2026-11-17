#include "torrent.hpp"
#include "resources.hpp"
#include "io_queue.hpp"
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/ip_filter.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/read_resume_data.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/write_resume_data.hpp>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <iterator>
#include <thread>

namespace td {
namespace lt=libtorrent;
namespace {
class PeerLeases {
public:
    ~PeerLeases(){clear();}
    void push_back(uint64_t id){values_.push_back(id);}
    size_t size()const{return values_.size();}
    void clear(){for(auto id:values_)process_resources().release(id);values_.clear();}
private:std::vector<uint64_t> values_;
};
class RateShare {
public:
    ~RateShare(){clear();}
    void start(){if(!id_)id_=process_resources().register_external_transfer();}
    void clear(){if(id_)process_resources().unregister_external_transfer(id_);id_=0;}
    uint64_t speed()const{return process_resources().external_speed(id_);}
private:uint64_t id_=0;
};

lt::ip_filter network_filter(bool loopback){
    lt::ip_filter filter;
    auto rule=[&](const char* begin,const char* end,bool blocked){
        filter.add_rule(lt::make_address(begin),lt::make_address(end),blocked?lt::ip_filter::blocked:0);
    };
    for(auto range:{std::pair{"0.0.0.0","0.255.255.255"},{"10.0.0.0","10.255.255.255"},
        {"100.64.0.0","100.127.255.255"},{"127.0.0.0","127.255.255.255"},
        {"169.254.0.0","169.254.255.255"},{"172.16.0.0","172.31.255.255"},
        {"192.0.0.0","192.0.0.255"},{"192.0.2.0","192.0.2.255"},
        {"192.168.0.0","192.168.255.255"},{"198.18.0.0","198.19.255.255"},
        {"198.51.100.0","198.51.100.255"},{"203.0.113.0","203.0.113.255"},
        {"224.0.0.0","255.255.255.255"}})rule(range.first,range.second,true);
    rule("::","ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",true);
    rule("2000::","3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",false);
    rule("2001:db8::","2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",true);
    rule("2002::","2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff",true);
    if(loopback){rule("127.0.0.0","127.255.255.255",false);rule("::1","::1",false);}
    return filter;
}
template<size_t N>void message(char(&dst)[N],const char* value){std::strncpy(dst,value,N-1);dst[N-1]=0;}
std::vector<char> read_resume(const fs::path& path){
    if(!fs::exists(path))return {};
    if(fs::file_size(path)>16*1024*1024)throw IntegrityError("Torrent resume record exceeds limit");
    std::ifstream f(path,std::ios::binary);if(!f)throw std::runtime_error("Torrent resume read failed");return {std::istreambuf_iterator<char>(f),std::istreambuf_iterator<char>()};
}
void write_resume(const fs::path& path,const std::vector<char>& data){
    auto temporary=path;temporary+=".tmp";
    auto* f=open_target(temporary,false);if(!f)throw std::runtime_error("Torrent checkpoint open failed");
    try{if(std::fwrite(data.data(),1,data.size(),f)!=data.size())throw std::runtime_error("Torrent checkpoint write failed");sync_target(f);std::fclose(f);}catch(...){std::fclose(f);throw;}
    auto backup=path;backup+=".bak";
    if(fs::exists(backup))fs::remove(backup);
    if(fs::exists(path))fs::rename(path,backup);
    try{fs::rename(temporary,path);}catch(...){if(fs::exists(backup) && !fs::exists(path))fs::rename(backup,path);throw;}
}
class Job final:public TorrentJob {
public:
    Job(const TorrentOptions& options,const fs::path& root):options_(options),root_(root),disk_(options.notify){
        view_.struct_size=sizeof(view_);view_.abi_version=TD_ABI_VERSION;view_.generation=1;view_.state=TD_QUEUED;view_.total_bytes=-1;
        fs::create_directories(root_);resume_=root_/(sha256(options.key)+".torrent-resume");
        auto destination=fs::weakly_canonical(fs::absolute(fs::path(std::u8string(reinterpret_cast<const char8_t*>(options.destination.data()),options.destination.size()))));
        fs::create_directories(destination);auto lock_path=destination;lock_path+=".turbodl.lock";lock_=std::make_unique<FileLock>(lock_path);
        for(uint32_t i=0;i<options.peers+2;++i){auto lease=process_resources().acquire("bittorrent",4096);if(!lease)break;leases_.push_back(lease);}
        if(leases_.size()<3)throw ResourceBusy("Insufficient shared peer budget");
        rate_.start();
        lt::settings_pack settings;
        settings.set_str(lt::settings_pack::listen_interfaces,options.test_loopback?"127.0.0.1:0":"0.0.0.0:0,[::]:0");
        settings.set_bool(lt::settings_pack::enable_dht,!options.test_loopback);
        settings.set_bool(lt::settings_pack::enable_lsd,false);settings.set_bool(lt::settings_pack::enable_upnp,false);settings.set_bool(lt::settings_pack::enable_natpmp,false);
        settings.set_bool(lt::settings_pack::allow_multiple_connections_per_ip,options.test_loopback);
        settings.set_bool(lt::settings_pack::apply_ip_filter_to_trackers,true);
        settings.set_bool(lt::settings_pack::listen_system_port_fallback,false);
        settings.set_int(lt::settings_pack::connections_limit,static_cast<int>(leases_.size()-2));
        settings.set_int(lt::settings_pack::active_downloads,1);settings.set_int(lt::settings_pack::active_seeds,1);
        settings.set_int(lt::settings_pack::max_queued_disk_bytes,1024*1024);
        settings.set_int(lt::settings_pack::alert_mask,static_cast<int>(static_cast<uint32_t>(lt::alert_category::error|lt::alert_category::storage|lt::alert_category::status)));
        auto speed=effective_speed();last_speed_=speed;
        settings.set_int(lt::settings_pack::download_rate_limit,static_cast<int>(speed));
        settings.set_int(lt::settings_pack::upload_rate_limit,static_cast<int>(options.upload_limit));
        lt::session_params session_options(settings);session_options.ip_filter=network_filter(options.test_loopback);
        session_=std::make_unique<lt::session>(std::move(session_options));
        if(options.notify)session_->set_alert_notify(options.notify);
        // libtorrent exempts local peers from the global class by default.
        // All addresses must use our class, including loopback test peers.
        lt::ip_filter classes;
        classes.add_rule(lt::make_address("0.0.0.0"),lt::make_address("255.255.255.255"),1);
        classes.add_rule(lt::make_address("::"),lt::make_address("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),1);
        session_->set_peer_class_filter(classes);
        lt::error_code error;lt::add_torrent_params params;
        auto checkpoint=read_resume(resume_);
        if(checkpoint.empty() && fs::exists(resume_.string()+".bak"))checkpoint=read_resume(resume_.string()+".bak");
        if(!checkpoint.empty()){params=lt::read_resume_data(checkpoint,error);if(error)throw IntegrityError("Torrent resume record is invalid");}
        else if(options.source.rfind("magnet:",0)==0){params=lt::parse_magnet_uri(options.source,error);if(error)throw std::invalid_argument("Invalid magnet URI");}
        else{params.ti=std::make_shared<lt::torrent_info>(options.source,error);if(error)throw std::invalid_argument("Invalid torrent metadata");}
        if(!checkpoint.empty()){
            lt::add_torrent_params requested;
            if(options.source.rfind("magnet:",0)==0)requested=lt::parse_magnet_uri(options.source,error);else requested.ti=std::make_shared<lt::torrent_info>(options.source,error);
            if(error)throw std::invalid_argument("Invalid torrent source");
            auto expected=requested.ti?requested.ti->info_hashes():requested.info_hashes;
            auto actual=params.ti?params.ti->info_hashes():params.info_hashes;
            if(expected!=actual)throw IntegrityError("Torrent identity changed");
        }
        if(!checkpoint.empty() && params.save_path!=utf8(destination))throw IntegrityError("Torrent checkpoint destination differs");
        if(checkpoint.empty() && fs::directory_iterator(destination)!=fs::directory_iterator{})throw std::runtime_error("Refusing to overwrite an unowned torrent directory");
        params.save_path=utf8(destination);params.flags&=~lt::torrent_flags::auto_managed;params.flags&=~lt::torrent_flags::paused;params.flags&=~lt::torrent_flags::seed_mode;
        rechecking_=!checkpoint.empty();recheck_pending_=rechecking_;
        if(params.ti){params.file_priorities.assign(static_cast<size_t>(params.ti->num_files()),options.selected_files.empty()?lt::default_priority:lt::dont_download);for(int index:options.selected_files){if(index<0 || index>=params.ti->num_files())throw std::invalid_argument("Torrent file index out of range");params.file_priorities[static_cast<size_t>(index)]=lt::default_priority;}}
        if(options.selected_files.size() && !params.ti)params.flags|=lt::torrent_flags::default_dont_download;
        selection_applied_=options.selected_files.empty() || params.ti!=nullptr;
        handle_=session_->add_torrent(params,error);if(error)throw std::runtime_error("Torrent could not be added");view_.state=TD_PROBING;
    }
    ~Job()override{if(checkpoint_.valid())try{checkpoint_.get();}catch(...){}release();}
    void tick()override{
        if(checkpoint_.valid() && checkpoint_.wait_for(std::chrono::seconds(0))==std::future_status::ready){
            try{checkpoint_.get();saving_=false;view_.durable_bytes=checkpoint_bytes_;last_saved_=std::chrono::steady_clock::now();if(pending_ && final_checkpoint_){view_.state=pending_==TD_CANCEL?TD_CANCELLED:pending_==4?TD_COMPLETED:TD_PAUSED;pending_=0;}}
            catch(...){saving_=false;view_.state=TD_FAILED;view_.error=TD_ERROR_STORAGE;message(view_.message,"Torrent checkpoint commit failed");}
        }
        if(view_.state==TD_COMPLETED || view_.state==TD_FAILED || view_.state==TD_CANCELLED || view_.state==TD_PAUSED){
            release();return;
        }
        auto speed=effective_speed();
        if(speed!=last_speed_){lt::settings_pack settings;settings.set_int(lt::settings_pack::download_rate_limit,static_cast<int>(speed));session_->apply_settings(settings);last_speed_=speed;}
        std::vector<lt::alert*> alerts;session_->pop_alerts(&alerts);
        for(auto* alert:alerts){
            if(auto* saved=lt::alert_cast<lt::save_resume_data_alert>(alert)){
                checkpoint_bytes_=saved_bytes(saved->params);
                auto bytes=lt::write_resume_data_buf(saved->params);auto target=resume_;
                checkpoint_=disk_.submit([target,bytes=std::move(bytes)]{write_resume(target,bytes);});
            }else if(lt::alert_cast<lt::save_resume_data_failed_alert>(alert)){
                saving_=false;view_.state=TD_FAILED;view_.error=TD_ERROR_STORAGE;message(view_.message,"Torrent checkpoint failed");
            }else if(lt::alert_cast<lt::torrent_paused_alert>(alert)){
                paused_=true;
            }else if(lt::alert_cast<lt::torrent_checked_alert>(alert)){
                // force_recheck() is ignored while libtorrent is validating
                // resume data. Start it only after that initial check ends.
                if(recheck_pending_){recheck_pending_=false;handle_.force_recheck();}
                else rechecking_=false;
            }else if(lt::alert_cast<lt::file_prio_alert>(alert)){
                if(selection_pending_){
                    auto priorities=handle_.get_file_priorities();auto info=handle_.torrent_file();bool matches=info && priorities.size()==static_cast<size_t>(info->num_files());
                    for(size_t i=0;matches && i<priorities.size();++i){
                        bool wanted=std::find(options_.selected_files.begin(),options_.selected_files.end(),static_cast<int>(i))!=options_.selected_files.end();
                        if(info->files().pad_file_at(lt::file_index_t(static_cast<int>(i))))wanted=false;
                        matches=(priorities[i]>lt::dont_download)==wanted;
                    }
                    if(matches)selection_pending_=false;
                }
            }else if(lt::alert_cast<lt::file_error_alert>(alert)){
                view_.state=TD_FAILED;view_.error=TD_ERROR_STORAGE;message(view_.message,"Torrent storage failed");handle_.pause();
            }else if(lt::alert_cast<lt::torrent_error_alert>(alert)){
                view_.state=TD_FAILED;view_.error=TD_ERROR_NETWORK;message(view_.message,"Torrent transfer failed");handle_.pause();
            }
        }
        if(view_.state==TD_COMPLETED || view_.state==TD_FAILED || view_.state==TD_CANCELLED || view_.state==TD_PAUSED){release();return;}
        if(pending_ && paused_ && !saving_ && !final_checkpoint_){
            final_checkpoint_=true;handle_.save_resume_data(lt::torrent_handle::flush_disk_cache|lt::torrent_handle::save_info_dict);saving_=true;
        }
        auto status=handle_.status();
        if(status.has_metadata && !selection_applied_){
            auto info=handle_.torrent_file();std::vector<lt::download_priority_t> priorities(static_cast<size_t>(info->num_files()),lt::dont_download);
            for(int index:options_.selected_files){if(index<0 || index>=info->num_files()){view_.state=TD_FAILED;view_.error=TD_ERROR_POLICY;message(view_.message,"Torrent selection is invalid");handle_.pause();return;}priorities[static_cast<size_t>(index)]=lt::default_priority;}
            selection_pending_=true;handle_.prioritize_files(priorities);selection_applied_=true;return;
        }
        view_.downloaded_bytes=static_cast<uint64_t>(std::max<int64_t>(status.total_wanted_done,0));view_.total_bytes=status.has_metadata?status.total_wanted:-1;
        if(status.has_metadata && !options_.selected_files.empty()){
            auto info=handle_.torrent_file();auto progress=handle_.file_progress(lt::torrent_handle::piece_granularity);
            view_.downloaded_bytes=0;view_.total_bytes=0;
            for(auto index:options_.selected_files){view_.downloaded_bytes+=static_cast<uint64_t>(std::max<int64_t>(progress.at(static_cast<size_t>(index)),0));view_.total_bytes+=info->files().file_size(lt::file_index_t(index));}
        }
        view_.wire_bytes=static_cast<uint64_t>(std::max<int64_t>(status.total_payload_download,0));view_.active_requests=static_cast<uint32_t>(std::max(status.num_peers,0));view_.peak_active_requests=std::max(view_.peak_active_requests,view_.active_requests);
        if(!pending_)view_.state=rechecking_?TD_VERIFYING:status.has_metadata?TD_TRANSFERRING:TD_PROBING;
        if(status.is_finished && !pending_ && !rechecking_ && !selection_pending_ && view_.total_bytes>=0 && view_.downloaded_bytes==static_cast<uint64_t>(view_.total_bytes)){if(!finished_at_)finished_at_=std::chrono::steady_clock::now();if(std::chrono::steady_clock::now()-*finished_at_>=std::chrono::seconds(options_.seed_seconds)){pending_=4;view_.state=TD_VERIFYING;handle_.pause();}}
        if(!pending_ && !saving_ && std::chrono::steady_clock::now()-last_saved_>std::chrono::seconds(2)){handle_.save_resume_data(lt::torrent_handle::flush_disk_cache|lt::torrent_handle::save_info_dict);saving_=true;}
    }
    void control(uint32_t command)override{
        if(command==TD_RESUME){throw std::logic_error("Resume requires recreating the protocol job");}
        else{if(!session_){view_.state=command==TD_CANCEL?TD_CANCELLED:TD_PAUSED;return;}pending_=command;final_checkpoint_=false;view_.state=TD_PAUSING;handle_.pause();}
    }
    td_snapshot snapshot()const override{return view_;}
    void connect_peer(const std::string& host,uint16_t port)override{
        auto address=lt::make_address(host);
        if(!options_.test_loopback && (address.is_loopback() || address.is_unspecified()))throw std::invalid_argument("Peer address rejected");
        handle_.connect_peer({address,port});
    }
private:
    uint64_t effective_speed()const{
        auto shared=rate_.speed(),local=options_.download_limit;
        return std::min<uint64_t>(INT32_MAX,shared && local?std::min(shared,local):shared?shared:local);
    }
    uint64_t saved_bytes(const lt::add_torrent_params& params)const{
        auto info=handle_.torrent_file();if(!info)return 0;
        // Checkpoints only claim complete, hash-verified pieces. Incomplete
        // block maps may help libtorrent resume, but are not durable byte claims.
        uint64_t count=0;
        for(int i=0;i<params.have_pieces.size();++i)if(params.have_pieces[lt::piece_index_t(i)]){
            int64_t begin=static_cast<int64_t>(i)*info->piece_length(),end=begin+info->piece_size(lt::piece_index_t(i));
            if(options_.selected_files.empty())count+=static_cast<uint64_t>(end-begin);
            else for(auto index:options_.selected_files){
                auto offset=info->files().file_offset(lt::file_index_t(index));
                auto length=info->files().file_size(lt::file_index_t(index));
                if(offset<end && offset+length>begin)count+=static_cast<uint64_t>(std::min(end,offset+length)-std::max(begin,offset));
            }
        }
        return count;
    }
    void release(){
        if(session_){handle_.pause();session_.reset();}
        leases_.clear();rate_.clear();lock_.reset();view_.active_requests=0;
    }
    TorrentOptions options_;fs::path root_,resume_;std::unique_ptr<FileLock> lock_;
    std::unique_ptr<lt::session> session_;lt::torrent_handle handle_;td_snapshot view_{};
    uint32_t pending_=0;bool saving_=false,selection_applied_=false,selection_pending_=false,paused_=false,final_checkpoint_=false,rechecking_=false,recheck_pending_=false;
    uint64_t last_speed_=0,checkpoint_bytes_=0;
    std::optional<std::chrono::steady_clock::time_point> finished_at_;
    std::chrono::steady_clock::time_point last_saved_=std::chrono::steady_clock::now();
    PeerLeases leases_;
    RateShare rate_;
    IoQueue disk_;
    std::future<void> checkpoint_;
};
}
std::unique_ptr<TorrentJob> create_torrent_job(const TorrentOptions& options,const fs::path& root){return std::make_unique<Job>(options,root);}
}
