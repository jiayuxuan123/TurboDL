#include "storage.hpp"
#include <sqlite3.h>
#include <array>
#include <stdexcept>
#ifdef _WIN32
#include <windows.h>
#include <bcrypt.h>
#include <io.h>
#else
#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>
#include <openssl/evp.h>
#endif
namespace td {
namespace {
class Digest {
public:
#ifdef _WIN32
    Digest(){if(BCryptCreateHash(algorithm(),&hash_,nullptr,0,nullptr,0,0)<0)throw std::runtime_error("SHA-256 initialization failed");}
    ~Digest(){if(hash_)BCryptDestroyHash(hash_);}
    void update(const void* p,size_t n){if(BCryptHashData(hash_,reinterpret_cast<PUCHAR>(const_cast<void*>(p)),static_cast<ULONG>(n),0)<0)throw std::runtime_error("SHA-256 update failed");}
    void finish(unsigned char* p){if(BCryptFinishHash(hash_,p,32,0)<0)throw std::runtime_error("SHA-256 finalization failed");}
 private:
    static BCRYPT_ALG_HANDLE algorithm(){
        struct Provider {
            BCRYPT_ALG_HANDLE value=nullptr;
            Provider(){if(BCryptOpenAlgorithmProvider(&value,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0 || !value)throw std::runtime_error("SHA-256 provider initialization failed");}
            ~Provider(){BCryptCloseAlgorithmProvider(value,0);}
        };
        static Provider provider;
        return provider.value;
    }
    BCRYPT_HASH_HANDLE hash_=nullptr;
#else
    Digest(){ctx_=EVP_MD_CTX_new();if(!ctx_ || EVP_DigestInit_ex(ctx_,EVP_sha256(),nullptr)!=1)throw std::runtime_error("SHA-256 initialization failed");}
    ~Digest(){EVP_MD_CTX_free(ctx_);}
    void update(const void* p,size_t n){if(EVP_DigestUpdate(ctx_,p,n)!=1)throw std::runtime_error("SHA-256 update failed");}
    void finish(unsigned char* p){unsigned int n=0;if(EVP_DigestFinal_ex(ctx_,p,&n)!=1 || n!=32)throw std::runtime_error("SHA-256 finalization failed");}
private:EVP_MD_CTX* ctx_=nullptr;
#endif
};
std::string finish(Digest& d){std::array<unsigned char,32> raw{};d.finish(raw.data());static constexpr char hex[]="0123456789abcdef";std::string out;out.reserve(64);for(auto b:raw){out+=hex[b>>4];out+=hex[b&15];}return out;}
uint64_t read64le(const unsigned char* p){
    return static_cast<uint64_t>(p[0])|static_cast<uint64_t>(p[1])<<8|
        static_cast<uint64_t>(p[2])<<16|static_cast<uint64_t>(p[3])<<24|
        static_cast<uint64_t>(p[4])<<32|static_cast<uint64_t>(p[5])<<40|
        static_cast<uint64_t>(p[6])<<48|static_cast<uint64_t>(p[7])<<56;
}
uint32_t read32le(const unsigned char* p){
    return static_cast<uint32_t>(p[0])|static_cast<uint32_t>(p[1])<<8|
        static_cast<uint32_t>(p[2])<<16|static_cast<uint32_t>(p[3])<<24;
}
uint64_t rotl64(uint64_t value,unsigned count){return (value<<count)|(value>>(64-count));}
uint64_t xx_round(uint64_t accumulator,uint64_t input){
    constexpr uint64_t p1=11400714785074694791ULL,p2=14029467366897019727ULL;
    accumulator+=input*p2;accumulator=rotl64(accumulator,31);return accumulator*p1;
}
uint64_t xx_merge(uint64_t accumulator,uint64_t value){
    constexpr uint64_t p1=11400714785074694791ULL,p4=9650029242287828579ULL;
    accumulator^=xx_round(0,value);return accumulator*p1+p4;
}
uint64_t xxhash64(std::string_view data){
    constexpr uint64_t p1=11400714785074694791ULL,p2=14029467366897019727ULL;
    constexpr uint64_t p3=1609587929392839161ULL,p4=9650029242287828579ULL;
    constexpr uint64_t p5=2870177450012600261ULL;
    const auto* p=reinterpret_cast<const unsigned char*>(data.data());
    const auto* end=p+data.size();uint64_t hash;
    if(data.size()>=32){
        uint64_t v1=p1+p2,v2=p2,v3=0,v4=0-p1;
        const auto* limit=end-32;
        do{
            v1=xx_round(v1,read64le(p));p+=8;
            v2=xx_round(v2,read64le(p));p+=8;
            v3=xx_round(v3,read64le(p));p+=8;
            v4=xx_round(v4,read64le(p));p+=8;
        }while(p<=limit);
        hash=rotl64(v1,1)+rotl64(v2,7)+rotl64(v3,12)+rotl64(v4,18);
        hash=xx_merge(hash,v1);hash=xx_merge(hash,v2);hash=xx_merge(hash,v3);hash=xx_merge(hash,v4);
    }else hash=p5;
    hash+=data.size();
    while(end-p>=8){hash^=xx_round(0,read64le(p));hash=rotl64(hash,27)*p1+p4;p+=8;}
    if(end-p>=4){hash^=static_cast<uint64_t>(read32le(p))*p1;hash=rotl64(hash,23)*p2+p3;p+=4;}
    while(p<end){hash^=static_cast<uint64_t>(*p++)*p5;hash=rotl64(hash,11)*p1;}
    hash^=hash>>33;hash*=p2;hash^=hash>>29;hash*=p3;hash^=hash>>32;
    return hash;
}
class Statement {
public:
    Statement(sqlite3* db,const char* sql){if(sqlite3_prepare_v2(db,sql,-1,&s,nullptr)!=SQLITE_OK)throw std::runtime_error("Checkpoint statement failed");}
    ~Statement(){sqlite3_finalize(s);}
    void text(int n,const std::string& v){if(sqlite3_bind_text(s,n,v.c_str(),-1,SQLITE_TRANSIENT)!=SQLITE_OK)throw std::runtime_error("Checkpoint bind failed");}
    sqlite3_stmt* s=nullptr;
};
}
struct Sha256State::Impl {Digest digest;};
Sha256State::Sha256State():impl_(std::make_unique<Impl>()){}
Sha256State::~Sha256State()=default;
void Sha256State::update(const void* data,size_t size){impl_->digest.update(data,size);}
std::string Sha256State::finish(){return td::finish(impl_->digest);}
std::string utf8(const fs::path& path){auto s=path.u8string();return std::string(reinterpret_cast<const char*>(s.data()),s.size());}
fs::path native_path(const fs::path& path){
#ifdef _WIN32
    auto value=path.native();
    if(value.starts_with(L"\\\\?\\"))return fs::path(value);
    auto required=GetFullPathNameW(value.c_str(),0,nullptr,nullptr);
    if(!required)throw std::runtime_error("Cannot resolve target path");
    std::wstring absolute(required,L'\0');auto length=GetFullPathNameW(value.c_str(),required,absolute.data(),nullptr);
    if(!length || length>=required)throw std::runtime_error("Cannot resolve target path");
    absolute.resize(length);value=std::move(absolute);
    if(value.starts_with(L"\\\\"))return fs::path(L"\\\\?\\UNC\\"+value.substr(2));
    return fs::path(L"\\\\?\\"+value);
#else
    return path;
#endif
}
fs::path canonical_target(const fs::path& path){
#ifdef _WIN32
    auto candidate=native_path(path);std::vector<fs::path> missing;
    while(true){
        auto file=CreateFileW(candidate.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS,nullptr);
        if(file!=INVALID_HANDLE_VALUE){
            auto required=GetFinalPathNameByHandleW(file,nullptr,0,FILE_NAME_NORMALIZED);
            if(!required){CloseHandle(file);throw std::runtime_error("Cannot canonicalize target path");}
            std::wstring name(required,L'\0');auto length=GetFinalPathNameByHandleW(file,name.data(),required,FILE_NAME_NORMALIZED);CloseHandle(file);
            if(!length || length>=required)throw std::runtime_error("Cannot canonicalize target path");
            name.resize(length);auto result=fs::path(name);
            for(auto it=missing.rbegin();it!=missing.rend();++it)result/=*it;
            return result;
        }
        auto error=GetLastError();
        if(error!=ERROR_FILE_NOT_FOUND && error!=ERROR_PATH_NOT_FOUND)throw std::runtime_error("Cannot inspect target path");
        auto parent=candidate.parent_path();
        if(parent==candidate || parent.empty())throw std::runtime_error("Cannot resolve target parent");
        missing.push_back(candidate.filename());candidate=std::move(parent);
    }
#else
    return fs::weakly_canonical(fs::absolute(path));
#endif
}
std::string logical_path(const fs::path& path){
    auto value=utf8(path);
#ifdef _WIN32
    if(value.starts_with("\\\\?\\UNC\\"))return "\\\\"+value.substr(8);
    if(value.starts_with("\\\\?\\"))return value.substr(4);
#endif
    return value;
}
uint64_t target_size(const fs::path& path){
#ifdef _WIN32
    auto native=native_path(path);
    auto file=CreateFileW(native.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot query target size");
    LARGE_INTEGER size{};auto success=GetFileSizeEx(file,&size);CloseHandle(file);
    if(!success || size.QuadPart<0)throw std::runtime_error("Cannot query target size");
    return static_cast<uint64_t>(size.QuadPart);
#else
    return fs::file_size(path);
#endif
}
std::string sha256(std::string_view value){Digest d;d.update(value.data(),value.size());return finish(d);}
std::string block_digest(std::string_view value){
    static constexpr char hex[]="0123456789abcdef";
    auto raw=xxhash64(value);std::string out;out.reserve(16);
    for(int shift=60;shift>=0;shift-=4)out+=hex[(raw>>shift)&15];
    return out;
}
std::string sha256_file(const fs::path& path,const std::function<bool()>& cancelled){
#ifdef _WIN32
    // FILE reads default to a small CRT buffer. Hash verification is on the
    // critical path for completed multi-range transfers, so use the native
    // sequential reader with a large buffer instead of rereading via fread.
    HANDLE file=CreateFileW(native_path(path).c_str(),GENERIC_READ,
        FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL|FILE_FLAG_SEQUENTIAL_SCAN,nullptr);
    if(file==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot open file for verification");
    try{
        Digest d;std::vector<unsigned char> buffer(1024*1024);
        DWORD count=0;
        for(;;){
            if(!ReadFile(file,buffer.data(),static_cast<DWORD>(buffer.size()),&count,nullptr)){
                if(GetLastError()==ERROR_HANDLE_EOF)break;
                throw std::runtime_error("File verification read failed");
            }
            if(!count)break;
            if(cancelled && cancelled())throw std::runtime_error("Verification cancelled");
            d.update(buffer.data(),count);
        }
        auto result=finish(d);
        CloseHandle(file);
        return result;
    }catch(...){CloseHandle(file);throw;}
#else
    FILE* file=open_target(path,true);if(!file)throw std::runtime_error("Cannot open file for verification");
    try{Digest d;std::array<unsigned char,65536> buf{};size_t n;while((n=std::fread(buf.data(),1,buf.size(),file))>0){if(cancelled && cancelled())throw std::runtime_error("Verification cancelled");d.update(buf.data(),n);}if(std::ferror(file))throw std::runtime_error("File verification read failed");auto result=finish(d);std::fclose(file);return result;}catch(...){std::fclose(file);throw;}
#endif
}
FileLock::FileLock(const fs::path& path){
#ifdef _WIN32
    HANDLE h=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE,0,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(h==INVALID_HANDLE_VALUE)throw std::runtime_error("Target already owned or lock unavailable");handle_=reinterpret_cast<intptr_t>(h);
#else
    int fd=::open(path.c_str(),O_CREAT|O_RDWR|O_CLOEXEC,0600);if(fd<0)throw std::runtime_error("Cannot open target lock");
    if(flock(fd,LOCK_EX|LOCK_NB)!=0){::close(fd);throw std::runtime_error("Target already owned");}handle_=fd;
#endif
}
FileLock::~FileLock(){if(handle_==-1)return;
#ifdef _WIN32
    CloseHandle(reinterpret_cast<HANDLE>(handle_));
#else
    flock(static_cast<int>(handle_),LOCK_UN);::close(static_cast<int>(handle_));
#endif
}
Journal::Journal(const fs::path& path){
    if(sqlite3_open_v2(utf8(path).c_str(),&db_,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_FULLMUTEX,nullptr)!=SQLITE_OK){if(db_)sqlite3_close(db_);db_=nullptr;throw std::runtime_error("Cannot open checkpoint database");}
    sqlite3_busy_timeout(db_,3000);
    try{Statement v(db_,"PRAGMA user_version");if(sqlite3_step(v.s)!=SQLITE_ROW || sqlite3_column_int(v.s,0)>1)throw std::runtime_error("Unsupported checkpoint version");}catch(...){sqlite3_close(db_);db_=nullptr;throw;}
    const char* setup="PRAGMA journal_mode=WAL;PRAGMA synchronous=FULL;CREATE TABLE IF NOT EXISTS task_checkpoints(stable_key TEXT PRIMARY KEY,target TEXT NOT NULL,etag TEXT NOT NULL,expected_sha256 TEXT NOT NULL,source_origin TEXT NOT NULL,durable INTEGER NOT NULL CHECK(durable>=0),total INTEGER NOT NULL);CREATE TABLE IF NOT EXISTS block_digests(stable_key TEXT NOT NULL,offset INTEGER NOT NULL,length INTEGER NOT NULL,sha256 TEXT NOT NULL,PRIMARY KEY(stable_key,offset));PRAGMA user_version=1;";
    if(sqlite3_exec(db_,setup,nullptr,nullptr,nullptr)!=SQLITE_OK){sqlite3_close(db_);db_=nullptr;throw std::runtime_error("Checkpoint initialization failed");}
}
Journal::~Journal(){if(db_)sqlite3_close(db_);}
std::optional<Saved> Journal::load(const std::string& key){
    Statement q(db_,"SELECT target,etag,expected_sha256,source_origin,durable,total FROM task_checkpoints WHERE stable_key=?");q.text(1,key);int rc=sqlite3_step(q.s);if(rc==SQLITE_DONE)return std::nullopt;if(rc!=SQLITE_ROW)throw std::runtime_error("Checkpoint read failed");
    auto text=[&](int n){const auto* p=sqlite3_column_text(q.s,n);return p?std::string(reinterpret_cast<const char*>(p)):std::string{};};
    Saved v;v.target=text(0);v.etag=text(1);v.expected=text(2);v.source_origin=text(3);v.durable=static_cast<uint64_t>(sqlite3_column_int64(q.s,4));v.total=sqlite3_column_int64(q.s,5);return v;
}
void Journal::save(const std::string& key,const Saved& r,const std::vector<BlockDigest>& blocks){
    if(sqlite3_exec(db_,"BEGIN IMMEDIATE",nullptr,nullptr,nullptr)!=SQLITE_OK)throw std::runtime_error("Checkpoint transaction failed");
    try{
    Statement q(db_,"INSERT INTO task_checkpoints(stable_key,target,etag,expected_sha256,source_origin,durable,total) VALUES(?,?,?,?,?,?,?) ON CONFLICT(stable_key) DO UPDATE SET target=excluded.target,etag=excluded.etag,expected_sha256=excluded.expected_sha256,source_origin=excluded.source_origin,durable=excluded.durable,total=excluded.total");
    q.text(1,key);q.text(2,r.target);q.text(3,r.etag);q.text(4,r.expected);q.text(5,r.source_origin);sqlite3_bind_int64(q.s,6,static_cast<sqlite3_int64>(r.durable));sqlite3_bind_int64(q.s,7,r.total);if(sqlite3_step(q.s)!=SQLITE_DONE)throw std::runtime_error("Checkpoint commit failed");
    if(!blocks.empty()){
        Statement insert(db_,"INSERT INTO block_digests(stable_key,offset,length,sha256) VALUES(?,?,?,?) ON CONFLICT(stable_key,offset) DO UPDATE SET length=excluded.length,sha256=excluded.sha256");
        insert.text(1,key);
        for(const auto& block:blocks){
            if(sqlite3_bind_int64(insert.s,2,static_cast<sqlite3_int64>(block.offset))!=SQLITE_OK ||
               sqlite3_bind_int(insert.s,3,static_cast<int>(block.length))!=SQLITE_OK)
                throw std::runtime_error("Checkpoint block bind failed");
            insert.text(4,block.digest);
            if(sqlite3_step(insert.s)!=SQLITE_DONE || sqlite3_reset(insert.s)!=SQLITE_OK)
                throw std::runtime_error("Checkpoint block commit failed");
        }
    }
    if(sqlite3_exec(db_,"COMMIT",nullptr,nullptr,nullptr)!=SQLITE_OK)throw std::runtime_error("Checkpoint commit failed");
    }catch(...){sqlite3_exec(db_,"ROLLBACK",nullptr,nullptr,nullptr);throw;}
}
std::vector<BlockDigest> Journal::blocks(const std::string& key){
    Statement q(db_,"SELECT offset,length,sha256 FROM block_digests WHERE stable_key=? ORDER BY offset");q.text(1,key);
    std::vector<BlockDigest> result;int rc;
    while((rc=sqlite3_step(q.s))==SQLITE_ROW){auto offset=sqlite3_column_int64(q.s,0);int length=sqlite3_column_int(q.s,1);const auto* p=sqlite3_column_text(q.s,2);if(offset<0 || length<=0 || static_cast<uint32_t>(length)>max_block_bytes || !p)throw IntegrityError("Invalid saved block");result.push_back({static_cast<uint64_t>(offset),static_cast<uint32_t>(length),reinterpret_cast<const char*>(p)});}
    if(rc!=SQLITE_DONE)throw std::runtime_error("Checkpoint block read failed");return result;
}
void Journal::erase(const std::string& key){
    if(sqlite3_exec(db_,"BEGIN IMMEDIATE",nullptr,nullptr,nullptr)!=SQLITE_OK)throw std::runtime_error("Checkpoint cleanup transaction failed");
    try{
        Statement blocks(db_,"DELETE FROM block_digests WHERE stable_key=?");blocks.text(1,key);if(sqlite3_step(blocks.s)!=SQLITE_DONE)throw std::runtime_error("Checkpoint block cleanup failed");
        Statement task(db_,"DELETE FROM task_checkpoints WHERE stable_key=?");task.text(1,key);if(sqlite3_step(task.s)!=SQLITE_DONE)throw std::runtime_error("Checkpoint cleanup failed");
        if(sqlite3_exec(db_,"COMMIT",nullptr,nullptr,nullptr)!=SQLITE_OK)throw std::runtime_error("Checkpoint cleanup commit failed");
    }catch(...){sqlite3_exec(db_,"ROLLBACK",nullptr,nullptr,nullptr);throw;}
}
bool Journal::verify(const std::string& key,const fs::path& path,uint64_t durable){
    FILE* file=open_target(path,true);if(!file)return false;
    try{
        std::vector<char> buffer(max_block_bytes);uint64_t end=0,sum=0;
        for(auto& block:blocks(key)){
            if(block.offset<end || block.offset>static_cast<uint64_t>(INT64_MAX)-block.length){std::fclose(file);return false;}
            seek_target(file,block.offset);
            if(std::fread(buffer.data(),1,block.length,file)!=block.length){std::fclose(file);return false;}
            std::string_view value(buffer.data(),block.length);
            bool matches=block.digest.size()==16?block_digest(value)==block.digest:
                block.digest.size()==64 && sha256(value)==block.digest;
            if(!matches){std::fclose(file);return false;}
            end=block.offset+block.length;sum+=block.length;
        }
        std::fclose(file);return sum==durable;
    }catch(...){std::fclose(file);throw;}
}
FILE* open_target(const fs::path& path,bool existing){
#ifdef _WIN32
    return _wfopen(path.c_str(),existing?L"r+b":L"w+b");
#else
    return std::fopen(path.c_str(),existing?"r+b":"w+b");
#endif
}
void seek_target(FILE* file,uint64_t offset){
#ifdef _WIN32
    if(_fseeki64(file,static_cast<__int64>(offset),SEEK_SET)!=0)throw std::runtime_error("Target seek failed");
#else
    if(fseeko(file,static_cast<off_t>(offset),SEEK_SET)!=0)throw std::runtime_error("Target seek failed");
#endif
}
void sync_target(FILE* file){if(std::fflush(file)!=0)throw std::runtime_error("Target flush failed");
#ifdef _WIN32
    if(_commit(_fileno(file))!=0)throw std::runtime_error("Target synchronization failed");
#else
    if(fsync(fileno(file))!=0)throw std::runtime_error("Target synchronization failed");
#endif
}
}
