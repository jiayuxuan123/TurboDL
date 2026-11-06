#ifndef TURBODL_C_ABI_PROBE_H
#define TURBODL_C_ABI_PROBE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define TDL_CALL __cdecl
#else
#define TDL_CALL
#endif

enum {
  TDL_ABI_VERSION = 1,
  TDL_OK = 0,
  TDL_ERR_ARGUMENT = 1,
  TDL_ERR_IO = 2,
  TDL_ERR_RANGE = 3
};

typedef struct {
  uint32_t struct_size;
  uint32_t abi_version;
} tdl_abi_header;

typedef struct {
  uint64_t start;
  uint64_t end;
  uint64_t total;
} tdl_content_range;

/* Parsed Content-Range bytes are owned by the caller and remain valid until
 * the next call on the same thread. */
int32_t TDL_CALL tdl_parse_content_range(const char* text,
                                         tdl_content_range* output);

/* Positional write contract: returns bytes written, or -1 on failure. */
int64_t TDL_CALL tdl_write_at(intptr_t file_descriptor,
                              const void* data,
                              size_t length,
                              uint64_t offset);

/* Streaming SHA-256 of an already-open file. */
int32_t TDL_CALL tdl_sha256_fd(intptr_t file_descriptor,
                               char output_hex[65]);

uint32_t TDL_CALL tdl_abi_version(void);

#ifdef __cplusplus
}
#endif

#endif
