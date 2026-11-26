Codec2 speech codec, vendored from https://github.com/drowe67/codec2 tag 1.2.0 (LGPL-2.1, see COPYING).

Only the files the speech codec needs are here (no modems or FreeDV). The `codebook*.c` files were generated
with Codec2's own `generate_codebook` tool from that release, so the Android build needs no host tool.
`codec2/version.h` is the one Codec2's CMake configure step produces.

The library is built as its own shared object (libcodec2.so) and loaded at run time, so it can be replaced
with a different build of Codec2 as the LGPL requires.
