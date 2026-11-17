#pragma once
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <stdexcept>
#include <vector>
struct sqlite3;
namespace td {
namespace fs = std::filesystem;
std::string sha256(std::string_view data);
std::string sha256_file(const fs::path& path,const std::function<bool()>& cancelled={});
std::string utf8(const fs::path& path);
class FileLock {
public:
    explicit FileLock(const fs::path& path);
    ~FileLock();
    FileLock(const FileLock&)=delete;
    FileLock& operator=(const FileLock&)=delete;
private:
    intptr_t handle_=-1;
};
struct Saved {
    std::string target;
    std::string etag;
    std::string expected;
    std::string source_origin;
    uint64_t durable=0;
    int64_t total=-1;
};
struct BlockDigest {uint64_t offset;uint32_t length;std::string sha256;};
struct IntegrityError:std::runtime_error {using std::runtime_error::runtime_error;};
class Journal {
public:
    explicit Journal(const fs::path& path);
    ~Journal();
    std::optional<Saved> load(const std::string& key);
    void save(const std::string& key,const Saved& record,const std::vector<BlockDigest>& blocks={});
    bool verify(const std::string& key,const fs::path& path,uint64_t durable);
    std::vector<BlockDigest> blocks(const std::string& key);
private:
    sqlite3* db_=nullptr;
};
FILE* open_target(const fs::path& path,bool existing);
void seek_target(FILE* file,uint64_t offset);
void sync_target(FILE* file);
}
