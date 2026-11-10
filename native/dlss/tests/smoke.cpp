// Offscreen integration test: a real Vulkan device, real NGX execution, GPU readback.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan.h>
#include "wynn_dlss.h"
#include <cmath>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

static void check(int32_t result, const char *operation) {
    if (result != 0) throw std::runtime_error(std::string(operation) + " failed: " + std::to_string(result));
}

#define INSTANCE_FUNCTIONS(X) \
    X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties) \
    X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceMemoryProperties) \
    X(vkGetPhysicalDeviceFeatures) X(vkCreateDevice) X(vkGetDeviceProcAddr)
#define DEVICE_FUNCTIONS(X) \
    X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkCreateCommandPool) X(vkAllocateCommandBuffers) \
    X(vkBeginCommandBuffer) X(vkEndCommandBuffer) X(vkQueueSubmit) X(vkQueueWaitIdle) \
    X(vkCreateImage) X(vkGetImageMemoryRequirements) X(vkAllocateMemory) X(vkBindImageMemory) \
    X(vkCreateImageView) X(vkCmdPipelineBarrier) X(vkCmdClearColorImage) \
    X(vkCreateBuffer) X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) \
    X(vkCmdCopyImageToBuffer) X(vkMapMemory) X(vkUnmapMemory) \
    X(vkDestroyImageView) X(vkDestroyImage) X(vkFreeMemory) X(vkDestroyBuffer) X(vkDestroyCommandPool)
struct Api {
#define DECLARE(name) PFN_##name name = nullptr;
    INSTANCE_FUNCTIONS(DECLARE)
    DEVICE_FUNCTIONS(DECLARE)
#undef DECLARE
};

static std::vector<std::string> extensions(uint64_t instance, uint64_t physical, const wchar_t *runtime) {
    char names[16384]{};
    check(wv_dlss_extensions(instance, physical, runtime, names, sizeof(names)), "extensions");
    std::vector<std::string> result;
    char *start = names;
    for (char *cursor = names; *cursor; ++cursor) {
        if (*cursor == '\n') {
            result.emplace_back(start, cursor);
            start = cursor + 1;
        }
    }
    return result;
}

static uint32_t memory_type(const VkPhysicalDeviceMemoryProperties &properties,
        uint32_t bits, VkMemoryPropertyFlags flags) {
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (properties.memoryTypes[i].propertyFlags & flags) == flags) return i;
    throw std::runtime_error("No compatible Vulkan memory type");
}

struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    WvImage descriptor{};
};

static Image make_image(Api &vk, VkDevice device, const VkPhysicalDeviceMemoryProperties &memory,
        VkFormat format, uint32_t width, uint32_t height, bool output) {
    Image result;
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {width, height, 1};
    info.mipLevels = 1; info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
        | (output ? VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
    check(vk.vkCreateImage(device, &info, nullptr, &result.image), "create image");
    VkMemoryRequirements requirements{};
    vk.vkGetImageMemoryRequirements(device, result.image, &requirements);
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = memory_type(memory, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vk.vkAllocateMemory(device, &allocation, nullptr, &result.memory), "allocate image memory");
    check(vk.vkBindImageMemory(device, result.image, result.memory, 0), "bind image memory");
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = result.image; view.viewType = VK_IMAGE_VIEW_TYPE_2D; view.format = format;
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    check(vk.vkCreateImageView(device, &view, nullptr, &result.view), "create image view");
    result.descriptor = {reinterpret_cast<uint64_t>(result.image), reinterpret_cast<uint64_t>(result.view),
        static_cast<uint32_t>(format), width, height, VK_IMAGE_ASPECT_COLOR_BIT};
    return result;
}

static void barrier(Api &vk, VkCommandBuffer commands, VkImage image, VkImageLayout from,
        VkImageLayout to, VkAccessFlags src, VkAccessFlags dst) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = src; b.dstAccessMask = dst;
    b.oldLayout = from; b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vk.vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}

static float half(uint16_t value) {
    int exponent = (value >> 10) & 31;
    int mantissa = value & 1023;
    if (exponent == 31) throw std::runtime_error("Non-finite DLSS output");
    float magnitude = exponent ? std::ldexp(1.0f + mantissa / 1024.0f, exponent - 15)
        : std::ldexp(static_cast<float>(mantissa), -24);
    return (value & 0x8000) ? -magnitude : magnitude;
}

int wmain(int argc, wchar_t **argv) {
    if (argc != 2) { std::cerr << "Usage: dlss_smoke <absolute directory containing nvngx_dlss.dll>\n"; return 2; }
    try {
        HMODULE loader = LoadLibraryExW(L"vulkan-1.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
        if (!loader) throw std::runtime_error("Vulkan loader unavailable");
        auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(loader, "vkGetInstanceProcAddr"));
        if (!gipa) throw std::runtime_error("Vulkan entry point unavailable");
        auto create_instance = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
        auto required = extensions(0, 0, argv[1]);
        std::vector<const char *> names;
        for (auto &name : required) names.push_back(name.c_str());
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "WynnVitrail DLSS smoke"; app.apiVersion = VK_API_VERSION_1_3;
        VkInstanceCreateInfo instance_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        instance_info.pApplicationInfo = &app;
        instance_info.enabledExtensionCount = static_cast<uint32_t>(names.size());
        instance_info.ppEnabledExtensionNames = names.data();
        VkInstance instance;
        check(create_instance(&instance_info, nullptr, &instance), "create instance");
        Api vk;
#define LOAD_INSTANCE(name) vk.name = reinterpret_cast<PFN_##name>(gipa(instance, #name));
        INSTANCE_FUNCTIONS(LOAD_INSTANCE)
#undef LOAD_INSTANCE
        uint32_t count = 0;
        check(vk.vkEnumeratePhysicalDevices(instance, &count, nullptr), "enumerate GPUs");
        std::vector<VkPhysicalDevice> devices(count);
        check(vk.vkEnumeratePhysicalDevices(instance, &count, devices.data()), "enumerate GPUs");
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        for (auto candidate : devices) {
            VkPhysicalDeviceProperties props{};
            vk.vkGetPhysicalDeviceProperties(candidate, &props);
            if (props.vendorID == 0x10de) {
                physical = candidate;
                std::cout << "GPU: " << props.deviceName << '\n';
                break;
            }
        }
        if (!physical) throw std::runtime_error("No NVIDIA Vulkan device");
        required = extensions(reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(physical), argv[1]);
        names.clear();
        for (auto &name : required) names.push_back(name.c_str());
        vk.vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
        std::vector<VkQueueFamilyProperties> queues(count);
        vk.vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, queues.data());
        uint32_t family = count;
        for (uint32_t i = 0; i < count; ++i)
            if ((queues[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT))
                    == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { family = i; break; }
        if (family == count) throw std::runtime_error("No graphics/compute queue");
        float priority = 1;
        VkDeviceQueueCreateInfo queue_info{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queue_info.queueFamilyIndex = family; queue_info.queueCount = 1; queue_info.pQueuePriorities = &priority;
        VkPhysicalDeviceFeatures features{};
        vk.vkGetPhysicalDeviceFeatures(physical, &features);
        VkDeviceCreateInfo device_info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        device_info.queueCreateInfoCount = 1; device_info.pQueueCreateInfos = &queue_info;
        device_info.enabledExtensionCount = static_cast<uint32_t>(names.size());
        device_info.ppEnabledExtensionNames = names.data(); device_info.pEnabledFeatures = &features;
        VkDevice device;
        check(vk.vkCreateDevice(physical, &device_info, nullptr, &device), "create device");
#define LOAD_DEVICE(name) vk.name = reinterpret_cast<PFN_##name>(vk.vkGetDeviceProcAddr(device, #name));
        DEVICE_FUNCTIONS(LOAD_DEVICE)
#undef LOAD_DEVICE
        WvDlss *dlss = nullptr;
        check(wv_dlss_open(reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(physical),
            reinterpret_cast<uint64_t>(device), reinterpret_cast<uint64_t>(gipa),
            reinterpret_cast<uint64_t>(vk.vkGetDeviceProcAddr), argv[1], &dlss), "open NGX");
        uint32_t sizes[6]{};
        constexpr uint32_t out_w = 960, out_h = 540;
        check(wv_dlss_optimal(dlss, out_w, out_h, 2, sizes), "optimal size");
        std::cout << "Quality: " << sizes[0] << 'x' << sizes[1] << " -> 960x540\n";
        VkPhysicalDeviceMemoryProperties memory{};
        vk.vkGetPhysicalDeviceMemoryProperties(physical, &memory);
        std::vector<Image> images;
        images.push_back(make_image(vk, device, memory, VK_FORMAT_R16G16B16A16_SFLOAT, sizes[0], sizes[1], false));
        images.push_back(make_image(vk, device, memory, VK_FORMAT_R32_SFLOAT, sizes[0], sizes[1], false));
        images.push_back(make_image(vk, device, memory, VK_FORMAT_R16G16_SFLOAT, sizes[0], sizes[1], false));
        images.push_back(make_image(vk, device, memory, VK_FORMAT_R16G16B16A16_SFLOAT, out_w, out_h, true));
        VkBufferCreateInfo buffer_info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        buffer_info.size = out_w * out_h * 8; buffer_info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        VkBuffer readback;
        check(vk.vkCreateBuffer(device, &buffer_info, nullptr, &readback), "create readback");
        VkMemoryRequirements requirements{};
        vk.vkGetBufferMemoryRequirements(device, readback, &requirements);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = memory_type(memory, requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        VkDeviceMemory readback_memory;
        check(vk.vkAllocateMemory(device, &allocation, nullptr, &readback_memory), "allocate readback");
        check(vk.vkBindBufferMemory(device, readback, readback_memory, 0), "bind readback");
        VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pool_info.queueFamilyIndex = family;
        VkCommandPool pool;
        check(vk.vkCreateCommandPool(device, &pool_info, nullptr, &pool), "command pool");
        VkCommandBufferAllocateInfo commands_info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        commands_info.commandPool = pool; commands_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        commands_info.commandBufferCount = 1;
        VkCommandBuffer commands;
        check(vk.vkAllocateCommandBuffers(device, &commands_info, &commands), "command buffer");
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        check(vk.vkBeginCommandBuffer(commands, &begin), "begin");
        check(wv_dlss_create(dlss, reinterpret_cast<uint64_t>(commands), sizes[0], sizes[1], out_w, out_h, 2), "create DLSS");
        VkClearColorValue clear[3] = {{{0.25f, 0.5f, 0.75f, 1.0f}}, {{0.5f, 0, 0, 0}}, {{0, 0, 0, 0}}};
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        for (int i = 0; i < 3; ++i) {
            barrier(vk, commands, images[i].image, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
            vk.vkCmdClearColorImage(commands, images[i].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear + i, 1, &range);
            barrier(vk, commands, images[i].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        barrier(vk, commands, images[3].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT);
        WvImage descriptors[4];
        for (int i = 0; i < 4; ++i) descriptors[i] = images[i].descriptor;
        check(wv_dlss_evaluate(dlss, reinterpret_cast<uint64_t>(commands), descriptors, 0, 0, 16.67f, 1), "evaluate DLSS");
        barrier(vk, commands, images[3].image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        VkBufferImageCopy copy{};
        copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; copy.imageExtent = {out_w, out_h, 1};
        vk.vkCmdCopyImageToBuffer(commands, images[3].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, 1, &copy);
        VkMemoryBarrier host{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vk.vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
            0, 1, &host, 0, nullptr, 0, nullptr);
        check(vk.vkEndCommandBuffer(commands), "end");
        VkQueue queue;
        vk.vkGetDeviceQueue(device, family, 0, &queue);
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1; submit.pCommandBuffers = &commands;
        check(vk.vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE), "submit");
        check(vk.vkQueueWaitIdle(queue), "wait");
        void *mapped;
        check(vk.vkMapMemory(device, readback_memory, 0, VK_WHOLE_SIZE, 0, &mapped), "map output");
        auto pixels = static_cast<const uint16_t *>(mapped);
        size_t center = (static_cast<size_t>(out_h / 2) * out_w + out_w / 2) * 4;
        for (int channel = 0; channel < 3; ++channel) {
            float value = half(pixels[center + channel]);
            std::cout << "Output channel " << channel << ": " << value << '\n';
            if (std::abs(value - clear[0].float32[channel]) > 0.08f)
                throw std::runtime_error("DLSS output differs from constant HDR input");
        }
        vk.vkUnmapMemory(device, readback_memory);
        check(wv_dlss_close(dlss), "close DLSS");
        vk.vkDestroyCommandPool(device, pool, nullptr);
        vk.vkDestroyBuffer(device, readback, nullptr);
        vk.vkFreeMemory(device, readback_memory, nullptr);
        for (auto &image : images) {
            vk.vkDestroyImageView(device, image.view, nullptr);
            vk.vkDestroyImage(device, image.image, nullptr);
            vk.vkFreeMemory(device, image.memory, nullptr);
        }
        vk.vkDestroyDevice(device, nullptr);
        vk.vkDestroyInstance(instance, nullptr);
        FreeLibrary(loader);
        std::cout << "PASS: real DLSS SR dispatch and HDR readback\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
