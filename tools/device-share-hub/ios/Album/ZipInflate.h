#ifndef ALBUM_ZIP_INFLATE_H
#define ALBUM_ZIP_INFLATE_H

#include <stdint.h>

int album_inflate_raw_file(const char *archive_path,
                           uint64_t data_offset,
                           uint64_t compressed_size,
                           const char *output_path,
                           uint64_t expected_size);

#endif
