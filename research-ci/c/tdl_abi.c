#include "tdl_abi.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <io.h>
#define tdl_lseek _lseeki64
#define tdl_read _read
#define tdl_write _write
#else
#include <unistd.h>
#define tdl_lseek lseek
#define tdl_read read
#define tdl_write write
#endif

uint32_t TDL_CALL tdl_abi_version(void) { return TDL_ABI_VERSION; }

int32_t TDL_CALL tdl_parse_content_range(const char* text,
                                         tdl_content_range* output) {
  if (text == NULL || output == NULL || strncmp(text, "bytes ", 6) != 0) {
    return TDL_ERR_ARGUMENT;
  }
  const char* dash = strchr(text + 6, '-');
  const char* slash = dash == NULL ? NULL : strchr(dash + 1, '/');
  if (dash == NULL || slash == NULL) return TDL_ERR_RANGE;
  char* end = NULL;
  output->start = strtoull(text + 6, &end, 10);
  if (end != dash) return TDL_ERR_RANGE;
  output->end = strtoull(dash + 1, &end, 10);
  if (end != slash) return TDL_ERR_RANGE;
  output->total = strtoull(slash + 1, &end, 10);
  if (end == slash + 1 || *end != '\0' || output->end < output->start ||
      output->total <= output->end) {
    return TDL_ERR_RANGE;
  }
  return TDL_OK;
}

int64_t TDL_CALL tdl_write_at(intptr_t file_descriptor,
                              const void* data,
                              size_t length,
                              uint64_t offset) {
  if (file_descriptor < 0 || data == NULL || length == 0) return -1;
  /* Keep this ABI probe dependency-free. It serializes file-position writes;
   * native backends should use pwrite/WriteFile in an actual implementation. */
  if (tdl_lseek(file_descriptor, (long long)offset, SEEK_SET) < 0) return -1;
  const char* bytes = (const char*)data;
  size_t done = 0;
  while (done < length) {
    const int count = tdl_write(file_descriptor, bytes + done, (unsigned)(length - done));
    if (count <= 0) return -1;
    done += (size_t)count;
  }
  return (int64_t)done;
}

typedef struct {
  uint32_t state[8];
  uint64_t total;
  unsigned char buffer[64];
  size_t buffered;
} sha256_ctx;

static uint32_t rotr(uint32_t value, uint32_t count) {
  return (value >> count) | (value << (32 - count));
}

static void sha256_block(sha256_ctx* ctx, const unsigned char* block) {
  static const uint32_t k[64] = {
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b,
      0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
      0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7,
      0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
      0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152,
      0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
      0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
      0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
      0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
      0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
      0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
      0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
  uint32_t w[64];
  for (int i = 0; i < 16; ++i) {
    w[i] = ((uint32_t)block[i * 4] << 24) |
           ((uint32_t)block[i * 4 + 1] << 16) |
           ((uint32_t)block[i * 4 + 2] << 8) |
           (uint32_t)block[i * 4 + 3];
  }
  for (int i = 16; i < 64; ++i) {
    const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^
                        (w[i - 15] >> 3);
    const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^
                        (w[i - 2] >> 10);
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }
  uint32_t a = ctx->state[0], b = ctx->state[1], c = ctx->state[2];
  uint32_t d = ctx->state[3], e = ctx->state[4], f = ctx->state[5];
  uint32_t g = ctx->state[6], h = ctx->state[7];
  for (int i = 0; i < 64; ++i) {
    const uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
    const uint32_t ch = (e & f) ^ (~e & g);
    const uint32_t t1 = h + s1 + ch + k[i] + w[i];
    const uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
    const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
    const uint32_t t2 = s0 + maj;
    h = g;
    g = f;
    f = e;
    e = d + t1;
    d = c;
    c = b;
    b = a;
    a = t1 + t2;
  }
  ctx->state[0] += a;
  ctx->state[1] += b;
  ctx->state[2] += c;
  ctx->state[3] += d;
  ctx->state[4] += e;
  ctx->state[5] += f;
  ctx->state[6] += g;
  ctx->state[7] += h;
}

static void sha256_update(sha256_ctx* ctx, const unsigned char* data,
                          size_t length) {
  ctx->total += length;
  while (length) {
    const size_t take = length < 64 - ctx->buffered ? length : 64 - ctx->buffered;
    memcpy(ctx->buffer + ctx->buffered, data, take);
    ctx->buffered += take;
    data += take;
    length -= take;
    if (ctx->buffered == 64) {
      sha256_block(ctx, ctx->buffer);
      ctx->buffered = 0;
    }
  }
}

static void sha256_final(sha256_ctx* ctx, unsigned char digest[32]) {
  const uint64_t bits = ctx->total * 8;
  ctx->buffer[ctx->buffered++] = 0x80;
  if (ctx->buffered > 56) {
    while (ctx->buffered < 64) ctx->buffer[ctx->buffered++] = 0;
    sha256_block(ctx, ctx->buffer);
    ctx->buffered = 0;
  }
  while (ctx->buffered < 56) ctx->buffer[ctx->buffered++] = 0;
  for (int shift = 56; shift >= 0; shift -= 8) {
    ctx->buffer[ctx->buffered++] = (unsigned char)(bits >> shift);
  }
  sha256_block(ctx, ctx->buffer);
  for (int i = 0; i < 8; ++i) {
    digest[i * 4] = (unsigned char)(ctx->state[i] >> 24);
    digest[i * 4 + 1] = (unsigned char)(ctx->state[i] >> 16);
    digest[i * 4 + 2] = (unsigned char)(ctx->state[i] >> 8);
    digest[i * 4 + 3] = (unsigned char)ctx->state[i];
  }
}

int32_t TDL_CALL tdl_sha256_fd(intptr_t file_descriptor, char output_hex[65]) {
  if (file_descriptor < 0 || output_hex == NULL) return TDL_ERR_ARGUMENT;
  if (tdl_lseek(file_descriptor, 0, SEEK_SET) < 0) return TDL_ERR_IO;
  sha256_ctx ctx = {{0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
                     0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U},
                    0, {0}, 0};
  unsigned char buffer[1024 * 1024];
  for (;;) {
    const int count = tdl_read(file_descriptor, buffer, (unsigned)sizeof(buffer));
    if (count < 0) return TDL_ERR_IO;
    if (count == 0) break;
    sha256_update(&ctx, buffer, (size_t)count);
  }
  unsigned char digest[32];
  sha256_final(&ctx, digest);
  static const char hex[] = "0123456789abcdef";
  for (int i = 0; i < 32; ++i) {
    output_hex[i * 2] = hex[digest[i] >> 4];
    output_hex[i * 2 + 1] = hex[digest[i] & 15];
  }
  output_hex[64] = '\0';
  return TDL_OK;
}
