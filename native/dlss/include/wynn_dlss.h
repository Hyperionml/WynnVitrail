#pragma once
#include <stdint.h>
#include <wchar.h>

#ifdef _WIN32
#ifdef WV_DLSS_BUILD
#define WV_API __declspec(dllexport)
#else
#define WV_API __declspec(dllimport)
#endif
#else
#define WV_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

/* Private, versioned C ABI. Handles belong to the caller's Vulkan device. All
 * calls are serialized internally. Zero means success; NGX errors are preserved.
 * No frame generation or ray reconstruction is exposed by this interface. */
typedef struct WvDlss WvDlss;
typedef struct WvImage {
    uint64_t image;
    uint64_t view;
    uint32_t format;
    uint32_t width;
    uint32_t height;
    uint32_t aspect;
} WvImage;

WV_API uint32_t wv_dlss_abi(void);
/* Optional R8_UNORM bias-current-color mask, same extent as input. */
WV_API int32_t wv_dlss_evaluate_masked(WvDlss *context, uint64_t commands,
    const WvImage *images, const WvImage *mask, float jitter_x, float jitter_y, float delta_ms, int32_t reset);
/* Result is a newline-separated, NUL-terminated list. Call before creating the
 * corresponding Vulkan object. A failed query must not enable DLSS. */
WV_API int32_t wv_dlss_extensions(uint64_t instance, uint64_t physical,
    const wchar_t *directory, char *output, uint32_t capacity);
WV_API int32_t wv_dlss_open(uint64_t instance, uint64_t physical, uint64_t device,
    uint64_t get_instance_proc, uint64_t get_device_proc,
    const wchar_t *directory, WvDlss **output);
/* quality follows NVSDK_NGX_PerfQuality_Value. output: optimalW/H, minW/H, maxW/H. */
WV_API int32_t wv_dlss_optimal(WvDlss *context, uint32_t width, uint32_t height,
    int32_t quality, uint32_t *output);
/* Must be recorded outside a render pass. Recreate only after retiring the old
 * context. Inputs use linear HDR, forward depth, unjittered low-res motion vectors
 * in render-pixel units, top-left origin, current-to-previous direction. */
WV_API int32_t wv_dlss_create(WvDlss *context, uint64_t commands,
    uint32_t width, uint32_t height, uint32_t output_width, uint32_t output_height,
    int32_t quality);
/* images: color, depth, motion, output. Input images must be in SHADER_READ_ONLY
 * layout; output in GENERAL. Caller supplies barriers before/after evaluation and
 * retains every image until the submission has completed. */
WV_API int32_t wv_dlss_evaluate(WvDlss *context, uint64_t commands,
    const WvImage *images, float jitter_x, float jitter_y, float delta_ms,
    int32_t reset);
/* Caller must submit every command buffer using this context before close.
 * Waits for device idle; intended for teardown, never for the per-frame path. */
WV_API int32_t wv_dlss_close(WvDlss *context);

#ifdef __cplusplus
}
#endif
