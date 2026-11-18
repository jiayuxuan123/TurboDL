#include "media_state.hpp"
#include <sqlite3.h>
#include <array>
#include <memory>
#ifdef _WIN32
#include <windows.h>
#include <fcntl.h>
#include <io.h>
#include <share.h>
#include <sys/stat.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

namespace td {
namespace {
void sql(sqlite3* db,const char* text){if(sqlite3_exec(db,text,nullptr,nullptr,nullptr)!=SQLITE_OK)throw std::runtime_error("Media state commit failed");}
struct Query {
    sqlite3_stmt* p=nullptr;
    Query(sqlite3* db,const char* text){if(sqlite3_prepare_v2(db,text,-1,&p,nullptr)!=SQLITE_OK)throw std::runtime_error("Media state query failed");}
    ~Query(){sqlite3_finalize(p);}
    void text(int index,const std::string& s){if(sqlite3_bind_text(p,index,s.c_str(),-1,SQLITE_TRANSIENT)!=SQLITE_OK)throw std::runtime_error("Media state binding failed");}
    void number(int index,uint64_t value){if(value>INT64_MAX || sqlite3_bind_int64(p,index,static_cast<int64_t>(value))!=SQLITE_OK)throw std::runtime_error("Media state size invalid");}
    std::string text(int column)const{auto* s=sqlite3_column_text(p,column);return s?reinterpret_cast<const char*>(s):"";}
    void done(){if(sqlite3_step(p)!=SQLITE_DONE)throw std::runtime_error("Media state write failed");}
};
using File=std::unique_ptr<FILE,decltype(&std::fclose)>;
FILE* create_exclusive(const fs::path& path){
#ifdef _WIN32
    int fd=-1;if(_wsopen_s(&fd,path.c_str(),_O_CREAT|_O_EXCL|_O_WRONLY|_O_BINARY,_SH_DENYRW,_S_IREAD|_S_IWRITE)!=0)return nullptr;
    auto* f=_fdopen(fd,"wb");if(!f)_close(fd);return f;
#else
    int fd=::open(path.c_str(),O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC|O_NOFOLLOW,0600);if(fd<0)return nullptr;
    auto* f=fdopen(fd,"wb");if(!f)::close(fd);return f;
#endif
}
void publish_no_replace(const fs::path& temporary,const fs::path& target){
#ifdef _WIN32
    if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Media target publication failed");
#else
    if(::link(temporary.c_str(),target.c_str())!=0)throw std::runtime_error("Media target publication failed");
    if(::unlink(temporary.c_str())!=0)throw std::runtime_error("Media temporary cleanup failed");
    auto parent=target.parent_path();if(parent.empty())parent=".";
    int directory=::open(parent.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if(directory<0)throw std::runtime_error("Media directory synchronization failed");
    int result=::fsync(directory);::close(directory);if(result!=0)throw std::runtime_error("Media directory synchronization failed");
#endif
}
}

MediaState::MediaState(const fs::path& root,const std::string& target,const std::string& identity){
    auto path=root/"state.sqlite3";
    if(sqlite3_open_v2(utf8(path).c_str(),&db_,SQLITE_OPEN_CREATE|SQLITE_OPEN_READWRITE|SQLITE_OPEN_FULLMUTEX,nullptr)!=SQLITE_OK){if(db_)sqlite3_close(db_);db_=nullptr;throw std::runtime_error("Cannot open media state");}
    try{
        sqlite3_busy_timeout(db_,3000);
        {Query version(db_,"PRAGMA user_version");if(sqlite3_step(version.p)!=SQLITE_ROW || sqlite3_column_int(version.p,0)>1)throw IntegrityError("Unsupported media state version");}
        sql(db_,"PRAGMA journal_mode=WAL;PRAGMA synchronous=FULL;CREATE TABLE IF NOT EXISTS owner(id INTEGER PRIMARY KEY CHECK(id=1),target TEXT NOT NULL,identity TEXT NOT NULL,phase TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL);CREATE TABLE IF NOT EXISTS units(id TEXT PRIMARY KEY,sha256 TEXT NOT NULL,size INTEGER NOT NULL,network_size INTEGER NOT NULL);PRAGMA user_version=1;");
        Query q(db_,"SELECT target,identity,phase,sha256,size FROM owner WHERE id=1");auto result=sqlite3_step(q.p);
        if(result==SQLITE_ROW){
            if(q.text(0)!=target || q.text(1)!=identity)throw IntegrityError("Media task ownership or representation changed");
            phase_=q.text(2);product_={static_cast<uint64_t>(sqlite3_column_int64(q.p,4)),q.text(3)};
            if((phase_!="NETWORK" && phase_!="READY" && phase_!="COMPLETED") || product_.size>INT64_MAX || (!product_.sha256.empty() && product_.sha256.size()!=64))throw IntegrityError("Invalid media state");
        }else if(result==SQLITE_DONE){Query create(db_,"INSERT INTO owner VALUES(1,?,?,'NETWORK','',0)");create.text(1,target);create.text(2,identity);create.done();}
        else throw std::runtime_error("Cannot read media state");
    }catch(...){sqlite3_close(db_);db_=nullptr;throw;}
}
MediaState::~MediaState(){if(db_)sqlite3_close(db_);}
std::map<std::string,StoredMediaUnit> MediaState::units(){
    Query q(db_,"SELECT id,sha256,size,network_size FROM units");std::map<std::string,StoredMediaUnit> values;int result;
    while((result=sqlite3_step(q.p))==SQLITE_ROW){
        auto id=q.text(0),sha=q.text(1);auto size=sqlite3_column_int64(q.p,2),network=sqlite3_column_int64(q.p,3);
        if(id.size()!=64 || sha.size()!=64 || size<0 || network<0 || values.size()>=12000)throw IntegrityError("Invalid media unit state");
        values.emplace(std::move(id),StoredMediaUnit{std::move(sha),static_cast<uint64_t>(size),static_cast<uint64_t>(network)});
    }
    if(result!=SQLITE_DONE)throw std::runtime_error("Cannot read media unit state");return values;
}
void MediaState::unit(const std::string& id,const StoredMediaUnit& value){
    Query q(db_,"INSERT INTO units VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET sha256=excluded.sha256,size=excluded.size,network_size=excluded.network_size");q.text(1,id);q.text(2,value.sha256);q.number(3,value.size);q.number(4,value.network_size);q.done();
}
void MediaState::ready(const MediaProduct& product){
    Query q(db_,"UPDATE owner SET phase='READY',sha256=?,size=? WHERE id=1");q.text(1,product.sha256);q.number(2,product.size);q.done();product_=product;phase_="READY";
}
void MediaState::completed(){sql(db_,"UPDATE owner SET phase='COMPLETED' WHERE id=1;");phase_="COMPLETED";}

void publish_media_product(const fs::path& source,const fs::path& target,const MediaProduct& product,const std::string& owner,const std::atomic<bool>& stop){
    auto check_stop=[&]{if(stop.load())throw MediaCancelled();};check_stop();
    if(fs::exists(target)){
        if(target_size(target)==product.size && sha256_file(target,[&]{return stop.load();})==product.sha256)return;
        throw IntegrityError("Refusing to overwrite a different media target");
    }
    if(!target.parent_path().empty())fs::create_directories(target.parent_path());
    if(target_size(source)!=product.size || sha256_file(source,[&]{return stop.load();})!=product.sha256)throw IntegrityError("Processed media cache failed verification");
    auto temporary=target;temporary+=".turbodl-"+owner.substr(0,24)+".part";
    if(fs::is_symlink(fs::symlink_status(temporary)))throw IntegrityError("Unsafe media target temporary file");
    // This name is reserved by the durable READY record and destination lock.
    if(fs::exists(temporary))fs::remove(temporary);
    bool created=false;
    try{
        File output(create_exclusive(temporary),&std::fclose),input(open_target(source,true),&std::fclose);
        created=static_cast<bool>(output);if(!output || !input)throw std::runtime_error("Cannot open media save target");
        std::array<char,65536> data{};size_t n;uint64_t copied=0;
        while((n=std::fread(data.data(),1,data.size(),input.get()))){check_stop();if(std::fwrite(data.data(),1,n,output.get())!=n)throw std::runtime_error("Media target copy failed");copied+=n;}
        if(std::ferror(input.get()))throw std::runtime_error("Processed media read failed");
        if(copied!=product.size)throw IntegrityError("Media target copy was incomplete");
        sync_target(output.get());output.reset();check_stop();
        if(target_size(temporary)!=product.size || sha256_file(temporary,[&]{return stop.load();})!=product.sha256)throw IntegrityError("Saved media target failed verification");
        publish_no_replace(temporary,target);created=false;
    }catch(...){if(created){std::error_code ignored;fs::remove(temporary,ignored);}throw;}
}
}
