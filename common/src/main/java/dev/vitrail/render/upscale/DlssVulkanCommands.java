package dev.vitrail.render.upscale;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

/** Layout boundary between Minecraft's GENERAL images and NGX's sampled inputs. */
public final class DlssVulkanCommands {
	private DlssVulkanCommands() {
	}

	/** The caller ends its render pass first and keeps all four images alive through submission. */
	public static void evaluate(VkCommandBuffer commands, DlssBridge.Session session,
			DlssBridge.Image color, DlssBridge.Image depth, DlssBridge.Image motion,
			DlssBridge.Image output, float jitterX, float jitterY, float deltaMillis, boolean reset) {
		evaluate(commands, session, color, depth, motion, output, null, jitterX, jitterY, deltaMillis, reset);
	}

	public static void evaluate(VkCommandBuffer commands, DlssBridge.Session session,
			DlssBridge.Image color, DlssBridge.Image depth, DlssBridge.Image motion,
			DlssBridge.Image output, DlssBridge.Image mask, float jitterX, float jitterY, float deltaMillis, boolean reset) {
		DlssBridge.validateFrame(commands.address(), color, depth, motion, output,
				jitterX, jitterY, deltaMillis);
		DlssBridge.validateMask(mask, color, depth, motion, output);
		DlssBridge.Image[] images = mask == null ? new DlssBridge.Image[]{color, depth, motion, output}
				: new DlssBridge.Image[]{color, depth, motion, output, mask};
		try (MemoryStack stack = MemoryStack.stackPush()) {
			barrier(commands, stack, images, true);
			try {
				session.evaluate(commands.address(), color, depth, motion, output, mask,
						jitterX, jitterY, deltaMillis, reset);
			} finally {
				// Even a rejected NGX evaluation must not leave the game's images in another layout.
				barrier(commands, stack, images, false);
			}
		}
	}

	private static void barrier(VkCommandBuffer commands, MemoryStack stack,
			DlssBridge.Image[] images, boolean before) {
		VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(images.length, stack);
		for (int i = 0; i < images.length; i++) {
			boolean input = i != 3;
			int ngxLayout = input ? VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
					: VK12.VK_IMAGE_LAYOUT_GENERAL;
			int ngxAccess = input ? VK12.VK_ACCESS_SHADER_READ_BIT : VK12.VK_ACCESS_SHADER_WRITE_BIT;
			int gameAccess = VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT;
			barriers.get(i).sType$Default().image(images[i].image())
					.oldLayout(before ? VK12.VK_IMAGE_LAYOUT_GENERAL : ngxLayout)
					.newLayout(before ? ngxLayout : VK12.VK_IMAGE_LAYOUT_GENERAL)
					.srcAccessMask(before ? gameAccess : ngxAccess)
					.dstAccessMask(before ? ngxAccess : gameAccess)
					.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
					.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
					.subresourceRange(range -> range.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
							.baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
		}
		VK12.vkCmdPipelineBarrier(commands,
				before ? VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
				before ? VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				0, null, null, barriers);
	}
}
