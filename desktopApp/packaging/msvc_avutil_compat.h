#if defined(_MSC_VER) && defined(_M_ARM64) && !defined(__GNUC__)
#include <stdint.h>
// JavaCPP emits wrappers for FFmpeg's GCC-only packed helper unions.
#pragma pack(push, 1)
union unaligned_16 { uint16_t l; };
union unaligned_32 { uint32_t l; };
union unaligned_64 { uint64_t l; };
#pragma pack(pop)
#endif
