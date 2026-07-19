#include "ZipInflate.h"

#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <zlib.h>

int album_inflate_raw_file(const char *archive_path,
                           uint64_t data_offset,
                           uint64_t compressed_size,
                           const char *output_path,
                           uint64_t expected_size) {
    FILE *input = fopen(archive_path, "rb");
    FILE *output = fopen(output_path, "wb");
    if (!input || !output || fseeko(input, (off_t)data_offset, SEEK_SET) != 0) {
        if (input) fclose(input);
        if (output) fclose(output);
        return -1;
    }

    z_stream stream;
    memset(&stream, 0, sizeof(stream));
    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) {
        fclose(input);
        fclose(output);
        return -2;
    }

    unsigned char in_buffer[64 * 1024];
    unsigned char out_buffer[64 * 1024];
    uint64_t remaining = compressed_size;
    int result = Z_OK;
    while (result != Z_STREAM_END) {
        if (stream.avail_in == 0 && remaining > 0) {
            size_t wanted = remaining < sizeof(in_buffer) ? (size_t)remaining : sizeof(in_buffer);
            size_t count = fread(in_buffer, 1, wanted, input);
            if (count == 0) { result = Z_DATA_ERROR; break; }
            remaining -= count;
            stream.next_in = in_buffer;
            stream.avail_in = (uInt)count;
        }
        stream.next_out = out_buffer;
        stream.avail_out = sizeof(out_buffer);
        result = inflate(&stream, Z_NO_FLUSH);
        if (result != Z_OK && result != Z_STREAM_END) break;
        size_t produced = sizeof(out_buffer) - stream.avail_out;
        if (produced > 0 && fwrite(out_buffer, 1, produced, output) != produced) {
            result = Z_ERRNO;
            break;
        }
        if (remaining == 0 && stream.avail_in == 0 && result != Z_STREAM_END) {
            result = Z_DATA_ERROR;
            break;
        }
    }

    uint64_t written = stream.total_out;
    inflateEnd(&stream);
    fclose(input);
    fclose(output);
    if (result != Z_STREAM_END || written != expected_size) {
        remove(output_path);
        return -3;
    }
    return 0;
}
