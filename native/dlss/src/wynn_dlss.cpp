#include "wynn_dlss.h"
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <mutex>
#include <new>
#include <string>

static_assert(sizeof(WvImage) == 32 && offsetof(WvImage, aspect) == 28,
    "Java FFM image descriptors must match the versioned C ABI");

struct WvDlss {
    VkDevice device = VK_NULL_HANDLE;
    PFN_vkDeviceWaitIdle wait_idle = nullptr;
    NVSDK_NGX_Parameter *capabilities = nullptr;
    NVSDK_NGX_Parameter *parameters = nullptr;
    NVSDK_NGX_Handle *feature = nullptr;
    std::wstring directory;
    const wchar_t *search_path = nullptr;
    NVSDK_NGX_FeatureCommonInfo common{};
    uint32_t width = 0, height = 0, output_width = 0, output_height = 0;
};

namespace {
std::mutex ngx_mutex;
WvDlss *active = nullptr;
// A project UUID owned by this integration, not another game's NVIDIA app ID.
constexpr const char *project_id = "b0d70a4d-cc47-4db9-9d81-025bc4616819";
constexpr int32_t invalid = -1;
constexpr int32_t busy = -2;
constexpr int32_t unsupported = -3;
constexpr int32_t no_memory = -4;

int32_t result(NVSDK_NGX_Result value) {
    return NVSDK_NGX_SUCCEED(value) ? 0 : static_cast<int32_t>(value);
}

bool quality_valid(int32_t quality) {
    return quality >= NVSDK_NGX_PerfQuality_Value_MaxPerf
        && quality <= NVSDK_NGX_PerfQuality_Value_DLAA;
}

NVSDK_NGX_FeatureDiscoveryInfo discovery(const wchar_t *directory,
        const NVSDK_NGX_FeatureCommonInfo *common) {
    NVSDK_NGX_FeatureDiscoveryInfo info{};
    info.SDKVersion = NVSDK_NGX_Version_API;
    info.FeatureID = NVSDK_NGX_Feature_SuperSampling;
    info.Identifier.IdentifierType = NVSDK_NGX_Application_Identifier_Type_Project_Id;
    info.Identifier.v.ProjectDesc = {project_id, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "WynnVitrail-1"};
    info.ApplicationDataPath = directory;
    info.FeatureInfo = common;
    return info;
}

NVSDK_NGX_Resource_VK resource(const WvImage &image, bool writable) {
    VkImageSubresourceRange range{image.aspect, 0, 1, 0, 1};
    return NVSDK_NGX_Create_ImageView_Resource_VK(
        reinterpret_cast<VkImageView>(image.view), reinterpret_cast<VkImage>(image.image),
        range, static_cast<VkFormat>(image.format), image.width, image.height, writable);
}
}

uint32_t wv_dlss_abi(void) { return 1; }

int32_t wv_dlss_extensions(uint64_t instance, uint64_t physical,
        const wchar_t *directory, char *output, uint32_t capacity) {
    std::lock_guard<std::mutex> lock(ngx_mutex);
    if (!directory || !*directory || !output || capacity == 0 || (physical && !instance))
        return invalid;
    output[0] = '\0';
    NVSDK_NGX_FeatureCommonInfo common{};
    common.PathListInfo = {&directory, 1};
    auto info = discovery(directory, &common);
    uint32_t count = 0;
    VkExtensionProperties *properties = nullptr;
    auto status = physical
        ? NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(
            reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physical),
            &info, &count, &properties)
        : NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info, &count, &properties);
    if (NVSDK_NGX_FAILED(status)) return result(status);
    size_t needed = 1;
    for (uint32_t i = 0; i < count; ++i) needed += std::strlen(properties[i].extensionName) + 1;
    if (needed > capacity) return invalid;
    char *cursor = output;
    for (uint32_t i = 0; i < count; ++i) {
        size_t length = std::strlen(properties[i].extensionName);
        std::memcpy(cursor, properties[i].extensionName, length);
        cursor += length;
        *cursor++ = '\n';
    }
    *cursor = '\0';
    return 0;
}

int32_t wv_dlss_open(uint64_t instance, uint64_t physical, uint64_t device,
        uint64_t get_instance_proc, uint64_t get_device_proc,
        const wchar_t *directory, WvDlss **output) {
    std::lock_guard<std::mutex> lock(ngx_mutex);
    if (!output) return invalid;
    *output = nullptr;
    if (active) return busy;
    if (!instance || !physical || !device || !get_instance_proc || !get_device_proc
            || !directory || !*directory) return invalid;
    WvDlss *context = new (std::nothrow) WvDlss;
    if (!context) return no_memory;
    try { context->directory = directory; }
    catch (const std::bad_alloc &) { delete context; return no_memory; }
    context->search_path = context->directory.c_str();
    context->common.PathListInfo = {&context->search_path, 1};
    context->device = reinterpret_cast<VkDevice>(device);
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(get_instance_proc);
    auto gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(get_device_proc);
    context->wait_idle = reinterpret_cast<PFN_vkDeviceWaitIdle>(gdpa(context->device, "vkDeviceWaitIdle"));
    if (!context->wait_idle) { delete context; return invalid; }
    auto status = NVSDK_NGX_VULKAN_Init_with_ProjectID(project_id,
        NVSDK_NGX_ENGINE_TYPE_CUSTOM, "WynnVitrail-1", context->directory.c_str(),
        reinterpret_cast<VkInstance>(instance), reinterpret_cast<VkPhysicalDevice>(physical),
        context->device, gipa, gdpa, &context->common);
    if (NVSDK_NGX_FAILED(status)) { delete context; return result(status); }
    status = NVSDK_NGX_VULKAN_GetCapabilityParameters(&context->capabilities);
    int available = 0;
    if (NVSDK_NGX_SUCCEED(status))
        status = NVSDK_NGX_Parameter_GetI(context->capabilities,
            NVSDK_NGX_Parameter_SuperSampling_Available, &available);
    if (NVSDK_NGX_SUCCEED(status) && available)
        status = NVSDK_NGX_VULKAN_AllocateParameters(&context->parameters);
    if (NVSDK_NGX_FAILED(status) || !available) {
        if (context->capabilities) NVSDK_NGX_VULKAN_DestroyParameters(context->capabilities);
        NVSDK_NGX_VULKAN_Shutdown1(context->device);
        delete context;
        return available ? result(status) : (NVSDK_NGX_FAILED(status) ? result(status) : unsupported);
    }
    active = context;
    *output = context;
    return 0;
}

int32_t wv_dlss_optimal(WvDlss *context, uint32_t width, uint32_t height,
        int32_t quality, uint32_t *output) {
    std::lock_guard<std::mutex> lock(ngx_mutex);
    if (!context || context != active || !output || !width || !height || !quality_valid(quality))
        return invalid;
    float unused_sharpness = 0;
    return result(NGX_DLSS_GET_OPTIMAL_SETTINGS(context->capabilities, width, height,
        static_cast<NVSDK_NGX_PerfQuality_Value>(quality), output, output + 1,
        output + 4, output + 5, output + 2, output + 3, &unused_sharpness));
}

int32_t wv_dlss_create(WvDlss *context, uint64_t commands, uint32_t width, uint32_t height,
        uint32_t output_width, uint32_t output_height, int32_t quality) {
    std::lock_guard<std::mutex> lock(ngx_mutex);
    if (!context || context != active || context->feature || !commands || !width || !height
            || width > output_width || height > output_height || !quality_valid(quality)) return invalid;
    NVSDK_NGX_DLSS_Create_Params params{};
    params.Feature.InWidth = width;
    params.Feature.InHeight = height;
    params.Feature.InTargetWidth = output_width;
    params.Feature.InTargetHeight = output_height;
    params.Feature.InPerfQualityValue = static_cast<NVSDK_NGX_PerfQuality_Value>(quality);
    params.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_IsHDR
        | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    auto status = NGX_VULKAN_CREATE_DLSS_EXT1(context->device,
        reinterpret_cast<VkCommandBuffer>(commands), 1, 1,
        &context->feature, context->parameters, &params);
    if (NVSDK_NGX_SUCCEED(status)) {
        context->width = width; context->height = height;
        context->output_width = output_width; context->output_height = output_height;
    }
    return result(status);
}

int32_t wv_dlss_evaluate(WvDlss *context, uint64_t commands,
        const WvImage *images, float jitter_x, float jitter_y, float delta_ms, int32_t reset) {
    return wv_dlss_evaluate_masked(context, commands, images, nullptr, jitter_x, jitter_y, delta_ms, reset);
}

int32_t wv_dlss_evaluate_masked(WvDlss *context, uint64_t commands,
        const WvImage *images, const WvImage *mask, float jitter_x, float jitter_y, float delta_ms, int32_t reset) {
    std::lock_guard<std::mutex> lock(ngx_mutex);
    if (!context || context != active || !context->feature || !commands || !images
            || !std::isfinite(jitter_x) || !std::isfinite(jitter_y)
            || !std::isfinite(delta_ms) || delta_ms <= 0) return invalid;
    for (int i = 0; i < 4; ++i) {
        bool output = i == 3;
        if (!images[i].image || !images[i].view || images[i].format == VK_FORMAT_UNDEFINED
                || images[i].width != (output ? context->output_width : context->width)
                || images[i].height != (output ? context->output_height : context->height)
                || images[i].aspect != VK_IMAGE_ASPECT_COLOR_BIT) return invalid;
        if (!output && images[i].image == images[3].image) return invalid;
    }
    if (images[0].format != VK_FORMAT_R16G16B16A16_SFLOAT
            || images[3].format != VK_FORMAT_R16G16B16A16_SFLOAT
            || images[1].format != VK_FORMAT_R32_SFLOAT
            || (images[2].format != VK_FORMAT_R16G16_SFLOAT
                && images[2].format != VK_FORMAT_R32G32_SFLOAT)) return invalid;
    auto color = resource(images[0], false);
    auto depth = resource(images[1], false);
    auto motion = resource(images[2], false);
    auto output = resource(images[3], true);
    NVSDK_NGX_Resource_VK bias{};
    if (mask) {
        if (!mask->image || !mask->view || mask->format != VK_FORMAT_R8_UNORM
                || mask->width != context->width || mask->height != context->height
                || mask->aspect != VK_IMAGE_ASPECT_COLOR_BIT) return invalid;
        for (int i = 0; i < 4; ++i) if (mask->image == images[i].image) return invalid;
        bias = resource(*mask, false);
    }
    NVSDK_NGX_VK_DLSS_Eval_Params params{};
    params.Feature.pInColor = &color;
    params.Feature.pInOutput = &output;
    params.pInDepth = &depth;
    params.pInMotionVectors = &motion;
    params.pInBiasCurrentColorMask = mask ? &bias : nullptr;
    params.InJitterOffsetX = jitter_x;
    params.InJitterOffsetY = jitter_y;
    params.InRenderSubrectDimensions = {context->width, context->height};
    params.InReset = reset != 0;
    params.InMVScaleX = 1.0f;
    params.InMVScaleY = 1.0f;
    params.InPreExposure = 1.0f;
    params.InExposureScale = 1.0f;
    params.InFrameTimeDeltaInMsec = delta_ms;
    return result(NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(commands),
        context->feature, context->parameters, &params));
}

int32_t wv_dlss_close(WvDlss *context) {
    std::lock_guard<std::mutex> lock(ngx_mutex);
    if (!context || context != active) return invalid;
    VkResult waited = context->wait_idle(context->device);
    // Device loss terminates pending work too; other failures leave ownership with caller.
    if (waited != VK_SUCCESS && waited != VK_ERROR_DEVICE_LOST) return -5;
    int32_t first_error = 0;
    auto remember = [&](NVSDK_NGX_Result status) {
        if (!first_error) first_error = result(status);
    };
    if (context->feature) remember(NVSDK_NGX_VULKAN_ReleaseFeature(context->feature));
    remember(NVSDK_NGX_VULKAN_DestroyParameters(context->parameters));
    remember(NVSDK_NGX_VULKAN_DestroyParameters(context->capabilities));
    remember(NVSDK_NGX_VULKAN_Shutdown1(context->device));
    active = nullptr;
    delete context;
    return first_error;
}
