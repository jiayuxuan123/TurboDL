#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/file_storage.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <chrono>
#include <csignal>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <thread>

namespace lt=libtorrent;
volatile std::sig_atomic_t stopped=0;
void interrupt(int){stopped=1;}
int main(int argc,char** argv){
    if(argc!=4 && argc!=5)return 2;
    try{
        auto directory=std::filesystem::path(argv[1]);int port=std::stoi(argv[2]);
        lt::file_storage files;
        if(argc==5){
            files.add_file("bundle/first.bin",static_cast<int64_t>(std::filesystem::file_size(directory/"bundle"/"first.bin")));
            files.add_file("bundle/second.bin",static_cast<int64_t>(std::filesystem::file_size(directory/"bundle"/"second.bin")));
        }else files.add_file("fixture.bin",static_cast<int64_t>(std::filesystem::file_size(directory/"fixture.bin")));
        lt::create_torrent creator(files,65536,lt::create_torrent::v1_only);
        lt::error_code error;lt::set_piece_hashes(creator,directory.string(),error);if(error)throw std::runtime_error("Fixture hashing failed");
        std::vector<char> encoded;lt::bencode(std::back_inserter(encoded),creator.generate());
        std::ofstream output(argv[3],std::ios::binary);output.write(encoded.data(),static_cast<std::streamsize>(encoded.size()));output.close();
        lt::settings_pack settings;settings.set_str(lt::settings_pack::listen_interfaces,"127.0.0.1:"+std::to_string(port));
        settings.set_bool(lt::settings_pack::enable_dht,false);settings.set_bool(lt::settings_pack::enable_lsd,false);settings.set_bool(lt::settings_pack::enable_upnp,false);settings.set_bool(lt::settings_pack::enable_natpmp,false);
        settings.set_bool(lt::settings_pack::allow_multiple_connections_per_ip,true);settings.set_int(lt::settings_pack::upload_rate_limit,1048576);
        lt::session session(settings);lt::add_torrent_params params;params.ti=std::make_shared<lt::torrent_info>(std::string(argv[3]),error);if(error)throw std::runtime_error("Fixture torrent metadata invalid");
        params.save_path=directory.string();params.flags&=~lt::torrent_flags::paused;params.flags&=~lt::torrent_flags::auto_managed;params.flags|=lt::torrent_flags::seed_mode;
        auto handle=session.add_torrent(params,error);if(error)throw std::runtime_error("Fixture seeding failed");
        std::signal(SIGINT,interrupt);std::signal(SIGTERM,interrupt);
        std::cout<<"TORRENT_FIXTURE_READY "<<port<<" "<<lt::make_magnet_uri(*params.ti)<<"\n"<<std::flush;
        while(!stopped){std::vector<lt::alert*> alerts;session.pop_alerts(&alerts);std::this_thread::sleep_for(std::chrono::milliseconds(25));}
        handle.pause();return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
