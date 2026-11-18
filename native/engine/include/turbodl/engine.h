#ifndef TURBODL_ENGINE_H
#define TURBODL_ENGINE_H
#include <stdint.h>
#if defined(_WIN32)
#if defined(TD_ENGINE_BUILD)
#define TD_API __declspec(dllexport)
#else
#define TD_API __declspec(dllimport)
#endif
#else
#define TD_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

#define TD_ABI_VERSION 1u
typedef struct td_engine td_engine;
typedef uint64_t td_task_id;
enum td_result { TD_OK=0, TD_INVALID=1, TD_NOT_FOUND=2, TD_BUSY=3, TD_IO_ERROR=4, TD_UNSUPPORTED=5, TD_INTERNAL=6 };
enum td_state { TD_QUEUED=0, TD_PROBING=1, TD_TRANSFERRING=2, TD_PAUSING=3, TD_PAUSED=4, TD_VERIFYING=5, TD_COMPLETED=6, TD_FAILED=7, TD_CANCELLED=8 };
enum td_command { TD_PAUSE=1, TD_RESUME=2, TD_CANCEL=3 };
enum td_error { TD_ERROR_NONE=0, TD_ERROR_NETWORK=1, TD_ERROR_HTTP=2, TD_ERROR_STORAGE=3, TD_ERROR_INTEGRITY=4, TD_ERROR_SOURCE_CHANGED=5, TD_ERROR_IDENTITY_UNVERIFIABLE=6, TD_ERROR_RANGE=7, TD_ERROR_LOCKED=8, TD_ERROR_RATE_LIMITED=9, TD_ERROR_POLICY=10, TD_ERROR_TRUST=11, TD_ERROR_AUTH=12 };
enum td_flags { TD_TEST_ALLOW_LOOPBACK=1u };
enum td_transfer_flags { TD_TRANSFER_WHOLE_FILE=1u, TD_TRANSFER_RESTART_UNVERIFIABLE_CACHE=2u };
enum td_event_kind { TD_EVENT_STATE=1, TD_EVENT_PROGRESS=2 };

typedef struct td_engine_options {
    uint32_t struct_size;
    uint32_t abi_version;
    const char* checkpoint_directory;
    uint32_t max_active_tasks;
    uint32_t max_total_connections;
    uint64_t buffer_budget_bytes;
    uint32_t flags;
    uint32_t reserved;
    uint64_t global_speed_limit_bytes_per_second;
} td_engine_options;

typedef struct td_header { const char* name; const char* value; } td_header;
typedef struct td_network_options {
    uint32_t struct_size;
    uint32_t abi_version;
    const char* username;
    const char* password;
    const char* proxy;
    const char* ca_bundle;
    const char* ssh_known_hosts;
    const char* ssh_public_key;
    const char* ssh_private_key;
    const char* doh_url;
    uint32_t http_version;
    uint32_t reserved;
} td_network_options;
typedef struct td_transfer_policy {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t retry_count;
    uint32_t max_connections_per_host;
    const char* cooldown_group;
} td_transfer_policy;
typedef struct td_request_slice {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t offset;
    uint64_t length;
} td_request_slice;
typedef struct td_source_options td_source_options;
typedef struct td_task_options {
    uint32_t struct_size;
    uint32_t abi_version;
    const char* url;
    const char* destination;
    const char* stable_key;
    const char* expected_sha256;
    const td_header* headers;
    uint32_t header_count;
    uint32_t connections;
    uint64_t request_window_bytes;
    uint32_t connect_timeout_ms;
    uint32_t read_timeout_ms;
    const td_network_options* network;
    const td_transfer_policy* policy;
    const td_request_slice* slice;
    uint64_t maximum_response_bytes;
    const char* representation_id;
    /* Concurrent mirrors require an independently supplied SHA-256. Each
     * source owns its headers; credentials never migrate between mirrors. */
    const td_source_options* mirrors;
    uint32_t mirror_count;
    uint32_t reserved;
    const int64_t* expected_size;
    uint32_t transfer_flags;
    uint32_t reserved2;
} td_task_options;
struct td_source_options {
    uint32_t struct_size;
    uint32_t abi_version;
    const char* url;
    const td_header* headers;
    uint32_t header_count;
    uint32_t reserved;
    const char* representation_id;
};
typedef struct td_torrent_options {
    uint32_t struct_size;
    uint32_t abi_version;
    const char* torrent_file_or_magnet;
    const char* destination_directory;
    const char* stable_key;
    uint32_t peer_limit;
    uint32_t seed_seconds;
    const int32_t* selected_files;
    uint32_t selected_file_count;
    uint32_t reserved;
} td_torrent_options;

/* Optional tail of td_torrent_options. Zero means no per-task rate limit.
 * The process download limit still applies when this extension is omitted. */
typedef struct td_torrent_limits {
    td_torrent_options base;
    uint64_t download_bytes_per_second;
    uint64_t upload_bytes_per_second;
} td_torrent_limits;

enum td_media_protocol { TD_MEDIA_AUTO=0, TD_MEDIA_HLS=1, TD_MEDIA_DASH=2 };
enum td_media_stage { TD_MEDIA_RESOLVING=1, TD_MEDIA_DOWNLOADING=2, TD_MEDIA_PROCESSING=3, TD_MEDIA_SAVING=4, TD_MEDIA_DONE=5 };
typedef struct td_media_options {
    uint32_t struct_size;
    uint32_t abi_version;
    const td_task_options* transfer;
    uint32_t protocol;
    uint32_t parallel_segments;
    uint64_t maximum_bandwidth;
    uint64_t maximum_segment_bytes;
    const char* audio_language;
    const char* video_representation;
    const char* audio_representation;
    uint32_t recording_seconds; /* zero records a live source until finish() */
    uint32_t reserved;
} td_media_options;
typedef struct td_media_details {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t stage;
    uint32_t failure_stage;
    uint32_t tracks;
    uint32_t live;
    uint64_t media_units;
    uint64_t verified_units;
    uint64_t recorded_milliseconds;
} td_media_details;

typedef struct td_snapshot {
    uint32_t struct_size;
    uint32_t abi_version;
    td_task_id task_id;
    uint64_t generation;
    uint32_t state;
    uint32_t error;
    uint64_t downloaded_bytes;
    uint64_t durable_bytes;
    uint64_t wire_bytes;
    int64_t total_bytes;
    uint64_t request_count;
    uint32_t active_requests;
    uint32_t peak_active_requests;
    uint32_t http_status;
    uint32_t reserved;
    char sha256[65];
    char message[192];
} td_snapshot;

typedef struct td_event {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t sequence;
    uint32_t kind;
    uint32_t reserved;
    td_snapshot snapshot;
} td_event;

typedef struct td_resource_snapshot {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t admitted_tasks;
    uint64_t reserved_connections;
    uint64_t admitted_payload_bytes;
    uint64_t external_transfers;
    uint64_t queued_tasks;
} td_resource_snapshot;
typedef struct td_http_metrics {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t finished_requests;
    uint64_t new_connections;
    uint64_t dns_microseconds_sum;
    uint64_t connect_microseconds_sum;
    uint64_t tls_microseconds_sum;
    uint64_t request_first_byte_microseconds_sum;
    uint64_t task_first_byte_microseconds;
    uint32_t negotiated_versions; /* bit 0 HTTP/1, bit 1 HTTP/2, bit 2 HTTP/3 */
    uint32_t active_streams;
    uint32_t active_connections;
    uint32_t peak_connections;
} td_http_metrics;

/* All UTF-8 strings and headers are copied before submit/create returns.
 * Handles belong to the caller until destroy. Calls may come from different
 * host threads. No callbacks or exceptions cross this ABI. poll is nonblocking.
 * Pause is acknowledged by TD_PAUSED only after I/O stops and progress is durable.
 * Destroy cancels outstanding I/O, joins the loop and releases all file locks.
 * TD_TEST_ALLOW_LOOPBACK is for local fixture hosts only, never public evidence.
 */
TD_API uint32_t td_abi_version(void);
TD_API int32_t td_engine_create(const td_engine_options*, td_engine** out);
TD_API int32_t td_engine_destroy(td_engine*);
TD_API int32_t td_engine_set_speed_limit(td_engine*, uint64_t bytes_per_second);
TD_API int32_t td_task_submit(td_engine*, const td_task_options*, td_task_id* out);
/* Parse a bounded UTF-8 Metalink v3/v4 document and submit the selected file
 * through the same task lifecycle, network policy and resource domain.
 * Destination/stable key/credentials come from transfer, never XML paths.
 * file_name may be null only for a manifest containing exactly one file. */
TD_API int32_t td_metalink_submit(td_engine*, const char* manifest_utf8,
    uint32_t manifest_length, const char* base_url, const char* file_name,
    const td_task_options* transfer, td_task_id* out);
TD_API int32_t td_task_control(td_engine*, td_task_id, uint32_t command);
/* Replaces a paused/failed task's source. Resume is a separate command. */
TD_API int32_t td_task_replace_source(td_engine*, td_task_id, const td_source_options*);
/* Removes an inactive in-memory task; durable data is retained for re-submission. */
TD_API int32_t td_task_forget(td_engine*, td_task_id);
TD_API int32_t td_torrent_submit(td_engine*, const td_torrent_options*, td_task_id* out);
TD_API int32_t td_torrent_connect_peer(td_engine*, td_task_id, const char* address, uint16_t port);
TD_API int32_t td_media_submit(td_engine*, const td_media_options*, td_task_id* out);
/* Gracefully stop live recording and publish the recorded output. */
TD_API int32_t td_media_finish(td_engine*, td_task_id);
TD_API int32_t td_media_query(td_engine*, td_task_id, td_media_details* out);
TD_API int32_t td_task_query(td_engine*, td_task_id, td_snapshot* out);
TD_API int32_t td_engine_poll(td_engine*, td_event* out, uint32_t capacity, uint32_t* count);
/* Feature report is a static, immutable UTF-8 JSON string owned by the library. */
TD_API const char* td_engine_capabilities(void);
/* Process-wide admissions, not a claim to measure OS sockets or total RSS. */
TD_API int32_t td_engine_resources(td_resource_snapshot* out);
TD_API int32_t td_task_http_metrics(td_engine*, td_task_id, td_http_metrics* out);
#ifdef __cplusplus
}
#endif
#endif
