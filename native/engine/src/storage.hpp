#pragma once
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <stdexcept>
#include <vector>
struct sqlite3;
namespace td {
namespace fs = std::filesystem;
inline constexpr uint32_t max_block_bytes=1024*1024;
std::string sha256(std::string_view data);
std::string sha256_file(const fs::path& path,const std::function<bool()>& cancelled={});

// Reusable SHA-256 state for a sequential stream. This lets a single-range
// transfer hash bytes while they are already in the write buffer instead of
// reading the completed file back from storage.
class Sha256State {
public:
    Sha256State();
    ~Sha256State();
    Sha256State(const Sha256State&)=delete;
    Sha256State& operator=(const Sha256State&)=delete;
    void update(const void* data,size_t size);
    std::string finish();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
std::string block_digest(std::string_view data);
std::string utf8(const fs::path& path);
fs::path native_path(const fs::path& path);
fs::path canonical_target(const fs::path& path);
std::string logical_path(const fs::path& path);
uint64_t target_size(const fs::path& path);
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
struct BlockDigest {uint64_t offset;uint32_t length;std::string digest;};
struct IntegrityError:std::runtime_error {using std::runtime_error::runtime_error;};
class Journal {
public:
    explicit Journal(const fs::path& path);
    ~Journal();
    std::optional<Saved> load(const std::string& key);
    void save(const std::string& key,const Saved& record,const std::vector<BlockDigest>& blocks={});
    bool verify(const std::string& key,const fs::path& path,uint64_t durable);
    std::vector<BlockDigest> blocks(const std::string& key);
    void erase(const std::string& key);
private:
    sqlite3* db_=nullptr;
};
FILE* open_target(const fs::path& path,bool existing);
void seek_target(FILE* file,uint64_t offset);
void sync_target(FILE* file);
}
