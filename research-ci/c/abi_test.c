#include "tdl_abi.h"

#include <stdio.h>
#include <string.h>

int main(void) {
  tdl_content_range range;
  if (tdl_parse_content_range("bytes 0-65535/1048576", &range) != TDL_OK ||
      range.start != 0 || range.end != 65535 || range.total != 1048576) {
    return 1;
  }
  if (tdl_parse_content_range("bytes 4-3/10", &range) == TDL_OK) return 1;
  if (tdl_abi_version() != TDL_ABI_VERSION) return 1;
  puts("C ABI conformance PASS");
  return 0;
}
