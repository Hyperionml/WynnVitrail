package dev.wynnvitrail;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.player.LocalPlayer;
import dev.vitrail.mixin.access.MountEquipmentAccessor;
import dev.vitrail.render.PackChain;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WynncraftMountArmorOverlay {
	public static final int OUTER_SIGNAL_COLOR = 0xFCFFFFFF;
	public static final int LEGGINGS_SIGNAL_COLOR = 0xFAFFFFFF;

	private static final Map<ArmorKey, OverlayTextures> CACHE = new HashMap<>();
	private static final Map<Integer, Integer> RENDERED_ARMOR_SIGNAL_CACHE = new HashMap<>();
	private static final int MAX_RENDERED_ARMOR_SIGNAL_CACHE = 128;
	private static volatile ResourceManager lastResources;
	private static int frame;

	public static void beginFrame() { frame++; }
	private static long textureSequence;
	private static ArmorSnapshot lastKnownArmor;
	private static boolean submittingHeadCosmetic;
	private static final float FAKE_PLAYER_Y_THRESHOLD = 1024.0F;
	private static final int FAKE_PLAYER_Y_POSITION_RADIX = 512;
	private static final int FAKE_PLAYER_STEVE_ALEX_RADIX = 2;
	private static final int FAKE_PLAYER_LIMB_FADE_RADIX = 3;
	private static final int FAKE_PLAYER_LIMB_INDEX_RADIX = 6;
	private static final int FAKE_PLAYER_HEAD = 0;
	private static final int FAKE_PLAYER_LEFT_ARM = 2;
	private static final int FAKE_PLAYER_RIGHT_ARM = 3;
	private static final int FAKE_PLAYER_LEFT_LEG = 4;
	private static final int FAKE_PLAYER_RIGHT_LEG = 5;
	private static final float SKIN_OVERLAY_TO_OUTER_ARMOR_EXPAND = 0.75F;
	private static final float SKIN_OVERLAY_TO_OUTER_LEG_ARMOR_EXPAND = 0.65F;
	private static final float SKIN_OVERLAY_TO_BOOTS_ARMOR_EXPAND = 1.25F;
	private static final float SKIN_OVERLAY_TO_LEGGINGS_ARMOR_EXPAND = 0.25F;
	private static final float SKIN_OVERLAY_TO_LEGGINGS_LEG_ARMOR_EXPAND = 0.15F;
	private static final float SLIM_ARM_ARMOR_WIDTH_EXPAND = 0.5F;
	private static final float HEAD_ARMOR_SCALE = 10.0F / 8.5F;
	private static final float BOOTS_ARMOR_SCALE = 11.5F / 8.5F;
	private static final float HEAD_COSMETIC_UP_OFFSET = 0.25F;
	private static final int OUTER_OVERLAY_ALPHA = 252;
	private static final int LEGGINGS_OVERLAY_ALPHA = 250;
	private static final int SHADER_EFFECT_ITEM_ID_BASE = 64000;
	private static final int DEFAULT_OVERLAY_RGB = 0x00FFFFFF;
	private static final int WHITE_COLOR = 0xFFFFFFFF;
	private static final int MAX_WYNNCRAFT_EFFECT_ID = 32;
	private static final int HIDE_ARMOR_EFFECT_ID = 2;
	private static final ResourceKey<EquipmentAsset> HIDDEN_ARMOR_ASSET = EquipmentAssets.createId("hidden");
	private static final String WYNNCRAFT_HAT_MODEL_PREFIX = "item/wynn/skin/hat/";
	private static final String[] WYNNCRAFT_ARMOR_ASSETS = {
		"hidden", "leather", "tan", "chainmail", "copper", "iron", "gold", "diamond",
		"titanium", "netherite", "pale_leather", "pale_chainmail", "pale_copper", "pale_iron",
		"pale_gold", "pale_diamond", "pale_titanium", "pale_netherite", "shaman", "infernal",
		"phantom", "quartz", "wings"
	};

	private WynncraftMountArmorOverlay() {
	}

	public static void onClientTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!WynnOption.MOUNT.enabled() || minecraft.level == null || player == null) {
			lastKnownArmor = null;
			RENDERED_ARMOR_SIGNAL_CACHE.clear();
			iris$clearCache(minecraft);
			return;
		}

		ArmorSnapshot current = iris$readCurrentArmor(player);
		if (!iris$isLikelyMountState(player) || current.hasAnyStack()) {
			iris$rememberArmor(current);
		}
	}

	public static void submitPlayerHeadOverlays(PlayerSkinRenderCache.RenderInfo renderInfo, ItemDisplayContext displayContext,
												PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, SkullModelBase modelBase) {
		if (submittingHeadCosmetic || !iris$shouldSubmitOverlays() || modelBase == null || iris$isExcludedDisplayContext(displayContext)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null || !iris$isLikelyMountState(player)) {
			return;
		}
		if (iris$isForeignPlayerHead(renderInfo, player)) {
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-head-skip")) {
				WynncraftDebugLog.info("mount-armor-overlay-head-skip",
					"[WynnIris MountArmor] skipping non-local player head profile={} local={}",
					renderInfo.gameProfile(), player.getUUID());
			}
			return;
		}

		OverlayTextures textures = iris$getOrCreateTextures(minecraft, player);
		if (textures == null || textures.isEmpty()) {
			return;
		}

		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-submit")) {
			String profile = renderInfo == null || renderInfo.gameProfile() == null ? "none" : renderInfo.gameProfile().toString();
			WynncraftDebugLog.info("mount-armor-overlay-submit",
				"[WynnIris MountArmor] submitting overlays profile={} context={} headCosmetic={} outerHead={} outerBody={} outerBoots={} leggings={}",
				profile, displayContext, textures.headCosmetic == null ? "none" : textures.headCosmetic.itemName,
				iris$layerId(textures.outerHead), iris$layerId(textures.outerBody), iris$layerId(textures.outerBoots),
				iris$layerId(textures.leggings));
		}

		if (textures.headCosmetic != null) {
			int limbIndex = iris$decodeFakePlayerLimbIndex(poseStack.last().pose().m31());
			if (limbIndex == FAKE_PLAYER_HEAD) {
				iris$submitHeadCosmetic(poseStack, submitNodeCollector, packedLight, textures.headCosmetic);
			}
		} else if (textures.outerHead != null) {
			iris$submitExpandedSkullLayer(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerHead, HEAD_ARMOR_SCALE);
			iris$submitExpandedSkullGlint(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerHeadGlint, HEAD_ARMOR_SCALE);
		}
		if (textures.outerBody != null) {
			iris$submitExpandedSkullLayer(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerBody, HEAD_ARMOR_SCALE);
			iris$submitExpandedSkullGlint(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerBodyGlint, HEAD_ARMOR_SCALE);
		}
		if (textures.outerBoots != null) {
			iris$submitExpandedSkullLayer(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerBoots, BOOTS_ARMOR_SCALE);
			iris$submitExpandedSkullGlint(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.outerBootsGlint, BOOTS_ARMOR_SCALE);
		}
		if (textures.leggings != null) {
			iris$submitExpandedSkullLayer(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.leggings, HEAD_ARMOR_SCALE);
			iris$submitExpandedSkullGlint(poseStack, submitNodeCollector, packedLight, modelBase,
				textures.leggingsGlint, HEAD_ARMOR_SCALE);
		}
	}

	public static void submitItemLayerOverlays(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay,
											   List<BakedQuad> quads, ItemDisplayContext displayContext) {
		if (submittingHeadCosmetic || !iris$shouldSubmitOverlays() || quads == null || quads.isEmpty() || iris$isExcludedDisplayContext(displayContext)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (minecraft.level == null || player == null || !iris$isLikelyMountState(player)) {
			return;
		}

		List<BakedQuad> fakeQuads = iris$collectFakePlayerQuads(poseStack, quads);
		if (fakeQuads.isEmpty()) {
			return;
		}

		OverlayTextures textures = iris$getOrCreateTextures(minecraft, player);
		if (textures == null || textures.isEmpty()) {
			return;
		}

		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-item-submit")) {
			WynncraftDebugLog.info("mount-armor-overlay-item-submit",
				"[WynnIris MountArmor] submitting item-layer overlays context={} fakeQuads={}/{} outerBody={} outerBoots={} leggings={}",
				displayContext, fakeQuads.size(), quads.size(), iris$layerId(textures.outerBody),
				iris$layerId(textures.outerBoots), iris$layerId(textures.leggings));
		}

		if (textures.outerBody != null) {
			iris$submitQuadOverlayLayer(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads,
				textures.outerBody, ArmorLayer.OUTER, player.getSkin().model());
			iris$submitQuadGlintOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads,
				textures.outerBodyGlint, ArmorLayer.OUTER, player.getSkin().model());
		}
		if (textures.outerBoots != null) {
			iris$submitQuadOverlayLayer(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads,
				textures.outerBoots, ArmorLayer.BOOTS, player.getSkin().model());
			iris$submitQuadGlintOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads,
				textures.outerBootsGlint, ArmorLayer.BOOTS, player.getSkin().model());
		}
		if (textures.leggings != null) {
			iris$submitQuadOverlayLayer(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads,
				textures.leggings, ArmorLayer.LEGGINGS, player.getSkin().model());
			iris$submitQuadGlintOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, fakeQuads,
				textures.leggingsGlint, ArmorLayer.LEGGINGS, player.getSkin().model());
		}
	}

	private static boolean iris$isExcludedDisplayContext(ItemDisplayContext displayContext) {
		return displayContext == ItemDisplayContext.GUI
			|| displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
			|| displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
	}

	private static boolean iris$isForeignPlayerHead(PlayerSkinRenderCache.RenderInfo renderInfo, LocalPlayer player) {
		if (renderInfo == null || renderInfo.gameProfile() == null || renderInfo.gameProfile().id() == null) {
			return false;
		}
		return !renderInfo.gameProfile().id().equals(player.getUUID());
	}

	private static boolean iris$shouldSubmitOverlays() {
		return WynnOption.MOUNT.enabled() && WynncraftSettings.effects() && PackChain.drawingPack();
	}

	private static boolean iris$isLikelyMountState(LocalPlayer player) {
		return player.getVehicle() != null || player.isSpectator() || player.isInvisible();
	}

	private static List<BakedQuad> iris$collectFakePlayerQuads(PoseStack poseStack, List<BakedQuad> quads) {
		Matrix4f pose = poseStack.last().pose();
		Vector3f transformed = new Vector3f();
		List<BakedQuad> fakeQuads = null;
		for (BakedQuad quad : quads) {
			if (iris$isFakePlayerQuad(pose, quad, transformed)) {
				if (fakeQuads == null) {
					fakeQuads = new ArrayList<>();
				}
				fakeQuads.add(quad);
			}
		}
		return fakeQuads == null ? List.of() : fakeQuads;
	}

	private static boolean iris$isFakePlayerQuad(Matrix4f pose, BakedQuad quad, Vector3f transformed) {
		for (int i = 0; i < BakedQuad.VERTEX_COUNT; i++) {
			pose.transformPosition(quad.position(i), transformed);
			if (transformed.y >= FAKE_PLAYER_Y_THRESHOLD) {
				return true;
			}
		}
		return false;
	}

	private static void iris$submitQuadOverlay(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay,
											   List<BakedQuad> quads, RenderType renderType, ArmorLayer armorLayer, int color, PlayerModelType modelType) {
		submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> {
			LimbBounds[] slimArmBounds = modelType == PlayerModelType.SLIM && armorLayer.widensSlimArms()
				? iris$collectArmBounds(pose, quads) : null;
			int submitted = 0;
			for (BakedQuad quad : quads) {
				int limbIndex = iris$decodeLimbIndex(pose, quad);
				iris$emitExpandedQuad(pose, vertexConsumer, quad, armorLayer, limbIndex, packedLight, packedOverlay, color, slimArmBounds);
				submitted++;
			}

			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-third-geometry")) {
				WynncraftDebugLog.info("mount-armor-overlay-third-geometry",
					"[WynnIris MountArmor] submitted third-layer armor={} quads={}/{}",
					armorLayer, submitted, quads.size());
			}
		});
	}

	private static void iris$submitQuadGlintOverlay(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay,
													List<BakedQuad> quads, DynamicGlintLayer glintLayer, ArmorLayer armorLayer, PlayerModelType modelType) {
		if (glintLayer == null) {
			return;
		}

		iris$updateGlintLayer(glintLayer);
		iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight, packedOverlay, quads, glintLayer.renderType,
			armorLayer, WHITE_COLOR, modelType);
	}

	private static void iris$submitQuadOverlayLayer(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
													int packedOverlay, List<BakedQuad> quads, ArmorTextureLayer layer,
													ArmorLayer armorLayer, PlayerModelType modelType) {
		if (layer == null) {
			return;
		}

		iris$updateArmorTextureLayer(layer);
		iris$withShaderOverlayEffect(layer, () -> iris$submitQuadOverlay(poseStack, submitNodeCollector, packedLight,
			packedOverlay, quads, layer.renderType, armorLayer, layer.color, modelType));
	}

	private static void iris$submitExpandedSkull(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
												 SkullModelBase modelBase, RenderType renderType, int color, float scale) {
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.scale(-scale, -scale, scale);

		SkullModelBase.State state = new SkullModelBase.State();
		state.yRot = 180.0F;
		state.animationPos = 0.0F;
		submitNodeCollector.submitModel(modelBase, state, poseStack, renderType, packedLight,
			OverlayTexture.NO_OVERLAY, color, null, 0);
		poseStack.popPose();
	}

	private static void iris$submitExpandedSkullLayer(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
													  SkullModelBase modelBase, ArmorTextureLayer layer, float scale) {
		if (layer == null) {
			return;
		}

		iris$updateArmorTextureLayer(layer);
		iris$withShaderOverlayEffect(layer,
			() -> iris$submitExpandedSkull(poseStack, submitNodeCollector, packedLight, modelBase, layer.renderType, layer.color, scale));
	}

	private static void iris$submitExpandedSkullGlint(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
													  SkullModelBase modelBase, DynamicGlintLayer glintLayer, float scale) {
		if (glintLayer == null) {
			return;
		}

		iris$updateGlintLayer(glintLayer);
		iris$submitExpandedSkull(poseStack, submitNodeCollector, packedLight, modelBase, glintLayer.renderType, WHITE_COLOR, scale);
	}

	private static void iris$submitHeadCosmetic(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
												HeadCosmeticLayer cosmetic) {
		if (cosmetic == null || cosmetic.renderState.isEmpty()) {
			return;
		}

		poseStack.pushPose();
		poseStack.translate(0.5F, HEAD_COSMETIC_UP_OFFSET, 0.5F);
		poseStack.mulPose(new Matrix4f().rotationY((float) Math.PI));
		poseStack.scale(0.625F, 0.625F, 0.625F);
		boolean previousSubmittingHeadCosmetic = submittingHeadCosmetic;
		submittingHeadCosmetic = true;
		try {
			cosmetic.renderState.submit(poseStack, submitNodeCollector, packedLight, OverlayTexture.NO_OVERLAY, WHITE_COLOR);
		} finally {
			submittingHeadCosmetic = previousSubmittingHeadCosmetic;
			poseStack.popPose();
		}
	}

	private static int iris$decodeLimbIndex(PoseStack.Pose pose, BakedQuad quad) {
		Vector3f transformed = pose.pose().transformPosition(quad.position(0), new Vector3f());
		return iris$decodeFakePlayerLimbIndex(transformed.y);
	}

	private static int iris$decodeFakePlayerLimbIndex(float transformedY) {
		if (transformedY < 2.0F * FAKE_PLAYER_Y_POSITION_RADIX) {
			return -1;
		}

		int metadata = (int) transformedY - 2 * FAKE_PLAYER_Y_POSITION_RADIX;
		return (metadata / FAKE_PLAYER_Y_POSITION_RADIX / FAKE_PLAYER_STEVE_ALEX_RADIX / FAKE_PLAYER_LIMB_FADE_RADIX) % FAKE_PLAYER_LIMB_INDEX_RADIX;
	}

	private static LimbBounds[] iris$collectArmBounds(PoseStack.Pose pose, List<BakedQuad> quads) {
		LimbBounds[] bounds = new LimbBounds[FAKE_PLAYER_LIMB_INDEX_RADIX];
		for (BakedQuad quad : quads) {
			int limbIndex = iris$decodeLimbIndex(pose, quad);
			if (limbIndex != FAKE_PLAYER_LEFT_ARM && limbIndex != FAKE_PLAYER_RIGHT_ARM) {
				continue;
			}
			LimbBounds limbBounds = bounds[limbIndex];
			if (limbBounds == null) {
				limbBounds = new LimbBounds();
				bounds[limbIndex] = limbBounds;
			}
			for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
				limbBounds.include(quad.position(vertex).x());
			}
		}
		return bounds;
	}

	private static void iris$emitExpandedQuad(PoseStack.Pose pose, VertexConsumer vertexConsumer, BakedQuad quad,
											  ArmorLayer armorLayer, int limbIndex, int packedLight, int packedOverlay, int color, LimbBounds[] slimArmBounds) {
		Matrix4f matrix = pose.pose();
		Vector3fc normal = quad.direction().getUnitVec3f();
		Vector3f transformed = new Vector3f();
		int light = LightCoordsUtil.lightCoordsWithEmission(packedLight, quad.materialInfo().lightEmission());
		float expand = armorLayer.expandFor(limbIndex);
		LimbBounds limbBounds = slimArmBounds == null || limbIndex < 0 || limbIndex >= slimArmBounds.length ? null : slimArmBounds[limbIndex];

		for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
			Vector3fc position = quad.position(vertex);
			float x = position.x() + normal.x() * expand;
			if (limbBounds != null && limbBounds.hasWidth()) {
				float side = Math.signum(position.x() - limbBounds.centerX());
				x += side * SLIM_ARM_ARMOR_WIDTH_EXPAND;
			}
			matrix.transformPosition(
				x,
				position.y() + normal.y() * expand,
				position.z() + normal.z() * expand,
				transformed);
			long packedUv = quad.packedUV(vertex);
			vertexConsumer.addVertex(transformed.x(), transformed.y(), transformed.z())
				.setColor(color)
				.setUv(UVPair.unpackU(packedUv), UVPair.unpackV(packedUv))
				.setOverlay(packedOverlay)
				.setLight(light)
				.setNormal(pose, normal.x(), normal.y(), normal.z());
		}
	}

	public static void invalidateResources() { lastResources = null; }

	private static OverlayTextures iris$getOrCreateTextures(Minecraft minecraft, LocalPlayer player) {
		ResourceManager resources = minecraft.getResourceManager();
		if (resources != lastResources) {
			iris$clearCache(minecraft);
			lastResources = resources;
		}

		ArmorSnapshot armor = iris$getArmorForOverlay(player);
		if (armor == null || !armor.hasAnyStack()) {
			return null;
		}

		PlayerModelType modelType = player.getSkin().model();
		int headSignal = iris$getWynncraftEffectSignalRgb(armor.head);
		int chestSignal = iris$getWynncraftEffectSignalRgb(armor.chest);
		int legsSignal = iris$getWynncraftEffectSignalRgb(armor.legs);
		int feetSignal = iris$getWynncraftEffectSignalRgb(armor.feet);
		ArmorKey key = new ArmorKey(modelType,
			ItemStack.hashItemAndComponents(armor.head),
			ItemStack.hashItemAndComponents(armor.chest),
			ItemStack.hashItemAndComponents(armor.legs),
			ItemStack.hashItemAndComponents(armor.feet),
			headSignal, chestSignal, legsSignal, feetSignal);

		OverlayTextures cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		OverlayTextures created = iris$buildTextures(minecraft, player, modelType, armor.head, armor.chest, armor.legs, armor.feet);
		if (CACHE.size() > 16) {
			iris$clearCache(minecraft);
		}
		CACHE.put(key, created);
		return created;
	}

	private static ArmorSnapshot iris$getArmorForOverlay(LocalPlayer player) {
		ArmorSnapshot current = iris$readCurrentArmor(player);
		if (!iris$isLikelyMountState(player) || current.hasAnyStack()) {
			iris$rememberArmor(current);
			return current;
		}

		if (lastKnownArmor != null && lastKnownArmor.matches(player)) {
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-cache-fallback")) {
				WynncraftDebugLog.info("mount-armor-overlay-cache-fallback",
					"[WynnIris MountArmor] live armor slots are empty in mount state; using cached slots=[{},{},{},{}]",
					iris$itemName(lastKnownArmor.head), iris$itemName(lastKnownArmor.chest),
					iris$itemName(lastKnownArmor.legs), iris$itemName(lastKnownArmor.feet));
			}
			return lastKnownArmor;
		}

		return current;
	}

	private static ArmorSnapshot iris$readCurrentArmor(LocalPlayer player) {
		return new ArmorSnapshot(
			player.getUUID(),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.HEAD)),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.CHEST)),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.LEGS)),
			iris$copyStack(player.getItemBySlot(EquipmentSlot.FEET))
		);
	}

	private static ItemStack iris$copyStack(ItemStack stack) {
		return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
	}

	private static void iris$rememberArmor(ArmorSnapshot armor) {
		if (lastKnownArmor != null && lastKnownArmor.sameStacks(armor)) {
			return;
		}

		lastKnownArmor = armor;
		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-armor-cache")) {
			WynncraftDebugLog.info("mount-armor-overlay-armor-cache",
				"[WynnIris MountArmor] cached local armor slots=[{},{},{},{}]",
				iris$itemName(armor.head), iris$itemName(armor.chest), iris$itemName(armor.legs), iris$itemName(armor.feet));
		}
	}

	private static OverlayTextures iris$buildTextures(Minecraft minecraft, LocalPlayer player, PlayerModelType modelType, ItemStack head,
													  ItemStack chest, ItemStack legs, ItemStack feet) {
		NativeImage outerHead = new NativeImage(64, 64, true);
		NativeImage outerBody = new NativeImage(64, 64, true);
		NativeImage outerBoots = new NativeImage(64, 64, true);
		NativeImage leggings = new NativeImage(64, 64, true);
		outerHead.fillRect(0, 0, 64, 64, 0);
		outerBody.fillRect(0, 0, 64, 64, 0);
		outerBoots.fillRect(0, 0, 64, 64, 0);
		leggings.fillRect(0, 0, 64, 64, 0);

		boolean hasOuterHead = false;
		boolean hasOuterBody = false;
		boolean hasOuterBoots = false;
		boolean hasLeggings = false;
		try {
			int headEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(head));
			int chestEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(chest));
			int legsEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(legs));
			int feetEffectId = iris$getWynncraftEffectId(iris$getWynncraftEffectSignalRgb(feet));
			boolean hideHead = iris$shouldHideArmorOverlay(head, headEffectId);
			boolean hideChest = iris$shouldHideArmorOverlay(chest, chestEffectId);
			boolean hideLegs = iris$shouldHideArmorOverlay(legs, legsEffectId);
			boolean hideFeet = iris$shouldHideArmorOverlay(feet, feetEffectId);

			boolean hasHeadCosmeticCandidate = !hideHead && iris$isWynncraftHeadCosmeticCandidate(head);
			if (!hideHead && !hasHeadCosmeticCandidate) {
				hasOuterHead |= iris$compositeArmorPiece(minecraft, outerHead, head, EquipmentClientInfo.LayerType.HUMANOID, WynncraftMountArmorTexture.Piece.OUTER_HEAD, modelType);
			}
			if (!hideChest) {
				hasOuterBody |= iris$compositeArmorPiece(minecraft, outerBody, chest, EquipmentClientInfo.LayerType.HUMANOID, WynncraftMountArmorTexture.Piece.OUTER_CHEST, modelType);
			}
			if (!hideFeet) {
				hasOuterBoots |= iris$compositeArmorPiece(minecraft, outerBoots, feet, EquipmentClientInfo.LayerType.HUMANOID, WynncraftMountArmorTexture.Piece.OUTER_FEET, modelType);
			}
			if (!hideLegs) {
				hasLeggings |= iris$compositeArmorPiece(minecraft, leggings, legs, EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS, WynncraftMountArmorTexture.Piece.LEGGINGS, modelType);
			}
			if (hasOuterHead) {
				WynncraftMountArmorEffects.applyStaticTint(outerHead, headEffectId);
			}
			if (hasOuterBody) {
				WynncraftMountArmorEffects.applyStaticTint(outerBody, chestEffectId);
			}
			if (hasOuterBoots) {
				WynncraftMountArmorEffects.applyStaticTint(outerBoots, feetEffectId);
			}
			if (hasLeggings) {
				WynncraftMountArmorEffects.applyStaticTint(leggings, legsEffectId);
			}

			ArmorTextureLayer outerHeadLayer = hasOuterHead ? iris$registerArmorTexture(minecraft, "outer_head", outerHead, headEffectId, OUTER_OVERLAY_ALPHA) : null;
			ArmorTextureLayer outerBodyLayer = hasOuterBody ? iris$registerArmorTexture(minecraft, "outer_body", outerBody, chestEffectId, OUTER_OVERLAY_ALPHA) : null;
			ArmorTextureLayer outerBootsLayer = hasOuterBoots ? iris$registerArmorTexture(minecraft, "outer_boots", outerBoots, feetEffectId, OUTER_OVERLAY_ALPHA) : null;
			ArmorTextureLayer leggingsLayer = hasLeggings ? iris$registerArmorTexture(minecraft, "leggings", leggings, legsEffectId, LEGGINGS_OVERLAY_ALPHA) : null;
			DynamicGlintLayer outerHeadGlint = hasOuterHead ? iris$registerGlintTexture(minecraft, "outer_head", outerHead, headEffectId) : null;
			DynamicGlintLayer outerBodyGlint = hasOuterBody ? iris$registerGlintTexture(minecraft, "outer_body", outerBody, chestEffectId) : null;
			DynamicGlintLayer outerBootsGlint = hasOuterBoots ? iris$registerGlintTexture(minecraft, "outer_boots", outerBoots, feetEffectId) : null;
			DynamicGlintLayer leggingsGlint = hasLeggings ? iris$registerGlintTexture(minecraft, "leggings", leggings, legsEffectId) : null;
			HeadCosmeticLayer headCosmetic = hasHeadCosmeticCandidate ? iris$createHeadCosmeticLayer(minecraft, player, head) : null;

			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-cache")) {
				WynncraftDebugLog.info("mount-armor-overlay-cache",
					"[WynnIris MountArmor] built overlay textures model={} headCosmetic={} outerHead={} outerBody={} outerBoots={} leggings={} effects=[{},{},{},{}] baked=[{},{},{},{}] dynamicBase=[{},{},{},{}] glint=[{},{},{},{}] colors=[{},{},{},{}] slots=[{},{},{},{}]",
					modelType, headCosmetic == null ? "none" : headCosmetic.itemName,
					iris$layerId(outerHeadLayer), iris$layerId(outerBodyLayer), iris$layerId(outerBootsLayer), iris$layerId(leggingsLayer),
					headEffectId, chestEffectId, feetEffectId, legsEffectId,
					iris$isStaticTintEffect(headEffectId), iris$isStaticTintEffect(chestEffectId),
					iris$isStaticTintEffect(feetEffectId), iris$isStaticTintEffect(legsEffectId),
					outerHeadLayer != null && outerHeadLayer.source != null, outerBodyLayer != null && outerBodyLayer.source != null,
					outerBootsLayer != null && outerBootsLayer.source != null, leggingsLayer != null && leggingsLayer.source != null,
					outerHeadGlint != null, outerBodyGlint != null, outerBootsGlint != null, leggingsGlint != null,
					iris$layerColor(outerHeadLayer), iris$layerColor(outerBodyLayer),
					iris$layerColor(outerBootsLayer), iris$layerColor(leggingsLayer),
					iris$itemName(head), iris$itemName(chest), iris$itemName(legs), iris$itemName(feet));
			}

			return new OverlayTextures(outerHeadLayer, outerBodyLayer, outerBootsLayer, leggingsLayer,
				outerHeadGlint, outerBodyGlint, outerBootsGlint, leggingsGlint, headCosmetic);
		} finally {
			if (!hasOuterHead) {
				outerHead.close();
			}
			if (!hasOuterBody) {
				outerBody.close();
			}
			if (!hasOuterBoots) {
				outerBoots.close();
			}
			if (!hasLeggings) {
				leggings.close();
			}
		}
	}

	private static boolean iris$compositeArmorPiece(Minecraft minecraft, NativeImage target, ItemStack stack, EquipmentClientInfo.LayerType layerType,
													WynncraftMountArmorTexture.Piece copyTarget, PlayerModelType modelType) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}

		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		Optional<ResourceKey<EquipmentAsset>> fallbackAssetId = equippable == null ? Optional.empty() : equippable.assetId();
		Optional<ResourceKey<EquipmentAsset>> customAssetId = iris$getWynncraftCustomArmorAsset(stack);
		Optional<ResourceKey<EquipmentAsset>> assetId = customAssetId.or(() -> fallbackAssetId);
		if (assetId.isEmpty()) {
			iris$logMissingEquipment(stack, "no-equippable-asset");
			return false;
		}

		EquipmentAssetManager assets = ((MountEquipmentAccessor) minecraft.getEntityRenderDispatcher()).vitrail$equipmentAssets();
		EquipmentClientInfo clientInfo = assets.get(assetId.get());
		List<EquipmentClientInfo.Layer> layers = clientInfo.getLayers(layerType);
		if (layers.isEmpty() && fallbackAssetId.isPresent() && !fallbackAssetId.equals(assetId)) {
			assetId = fallbackAssetId;
			clientInfo = assets.get(assetId.get());
			layers = clientInfo.getLayers(layerType);
		}
		if (layers.isEmpty()) {
			iris$logMissingEquipment(stack, "no-layers-" + layerType.getSerializedName());
			return false;
		}

		if (WynncraftDebugLog.shouldLog("mount-armor-overlay-asset-choice-" + layerType.getSerializedName() + "-" + iris$itemName(stack))) {
			WynncraftDebugLog.info("mount-armor-overlay-asset-choice-" + layerType.getSerializedName() + "-" + iris$itemName(stack),
				"[WynnIris MountArmor] stack={} layerType={} itemModel={} customModelData={} customAsset={} fallbackAsset={} chosenAsset={} textureLayers={}",
				iris$itemName(stack), layerType.getSerializedName(), stack.get(DataComponents.ITEM_MODEL),
				iris$customModelDataSummary(stack), customAssetId.map(ResourceKey::identifier).orElse(null),
				fallbackAssetId.map(ResourceKey::identifier).orElse(null), assetId.get().identifier(), layers.size());
		}

		int dyeColor = customAssetId.isPresent() ? 0 : iris$getArmorLayerDyeColor(stack);
		boolean changed = false;
		for (EquipmentClientInfo.Layer layer : layers) {
			int layerColor = iris$getColorForLayer(layer, dyeColor);
			if (layerColor == 0 || layer.usePlayerTexture()) {
				continue;
			}

			Identifier texture = layer.getTextureLocation(layerType);
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-assets")) {
				WynncraftDebugLog.info("mount-armor-overlay-assets",
					"[WynnIris MountArmor] stack={} asset={} layerType={} texture={} color={}",
					iris$itemName(stack), assetId.get().identifier(), layerType.getSerializedName(), texture, String.format("0x%08X", layerColor));
			}

			try (NativeImage source = iris$readTexture(minecraft.getResourceManager(), texture)) {
				if (source == null) {
					iris$logMissingEquipment(stack, "missing-texture-" + texture);
					continue;
				}
				iris$copyArmorTexture(source, target, layerColor, copyTarget, modelType);
				changed = true;
			}
		}
		return changed;
	}

	private static Optional<ResourceKey<EquipmentAsset>> iris$getWynncraftCustomArmorAsset(ItemStack stack) {
		Optional<ResourceKey<EquipmentAsset>> itemModelAsset = iris$getWynncraftArmorAsset(stack.get(DataComponents.ITEM_MODEL));
		if (itemModelAsset.isPresent()) {
			return itemModelAsset;
		}

		CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (customModelData == null) {
			return Optional.empty();
		}

		String stringModelData = customModelData.getString(0);
		Optional<ResourceKey<EquipmentAsset>> stringAsset = iris$getWynncraftArmorAsset(stringModelData);
		if (stringAsset.isPresent()) {
			return stringAsset;
		}

		Float modelData = customModelData.getFloat(0);
		if (modelData == null || modelData < 1.0F) {
			return Optional.empty();
		}

		int assetIndex = (int) Math.floor(modelData);
		if (assetIndex < 1 || assetIndex > WYNNCRAFT_ARMOR_ASSETS.length) {
			return Optional.empty();
		}

		return Optional.of(EquipmentAssets.createId(WYNNCRAFT_ARMOR_ASSETS[assetIndex - 1]));
	}

	private static Optional<ResourceKey<EquipmentAsset>> iris$getWynncraftArmorAsset(Identifier modelId) {
		if (modelId == null || !modelId.getPath().startsWith("item/wynn/armor/")) {
			return Optional.empty();
		}
		return iris$getWynncraftArmorAsset(modelId.getPath());
	}

	private static Optional<ResourceKey<EquipmentAsset>> iris$getWynncraftArmorAsset(String modelPath) {
		if (modelPath == null || modelPath.isBlank()) {
			return Optional.empty();
		}

		String assetName = modelPath;
		int slash = assetName.lastIndexOf('/');
		if (slash >= 0) {
			assetName = assetName.substring(slash + 1);
		}

		for (String suffix : new String[] { "_helmet", "_chestplate", "_leggings", "_boots" }) {
			if (assetName.endsWith(suffix)) {
				assetName = assetName.substring(0, assetName.length() - suffix.length());
				break;
			}
		}

		for (String knownAsset : WYNNCRAFT_ARMOR_ASSETS) {
			if (knownAsset.equals(assetName)) {
				return Optional.of(EquipmentAssets.createId(knownAsset));
			}
		}
		return Optional.empty();
	}

	private static HeadCosmeticLayer iris$createHeadCosmeticLayer(Minecraft minecraft, LocalPlayer player, ItemStack stack) {
		if (!iris$isWynncraftHeadCosmeticCandidate(stack)) {
			return null;
		}

		ItemStackRenderState renderState = new ItemStackRenderState();
		minecraft.getItemModelResolver().updateForLiving(renderState, stack, ItemDisplayContext.HEAD, player);
		if (renderState.isEmpty()) {
			if (WynncraftDebugLog.shouldLog("mount-armor-overlay-empty-head-cosmetic")) {
				WynncraftDebugLog.info("mount-armor-overlay-empty-head-cosmetic",
					"[WynnIris MountArmor] resolved empty head cosmetic stack={} itemModel={} customModelData={}",
					iris$itemName(stack), stack.get(DataComponents.ITEM_MODEL), iris$customModelDataSummary(stack));
			}
			return null;
		}

		return new HeadCosmeticLayer(renderState, iris$itemName(stack));
	}

	private static boolean iris$isWynncraftHeadCosmeticCandidate(ItemStack stack) {
		if (stack == null || stack.isEmpty() || iris$getWynncraftCustomArmorAsset(stack).isPresent()) {
			return false;
		}

		Identifier itemModel = stack.get(DataComponents.ITEM_MODEL);
		if (iris$isWynncraftHatModel(itemModel)) {
			return true;
		}

		Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return iris$isWynncraftHatCarrier(itemModel) || iris$isWynncraftHatCarrier(itemId);
	}

	private static boolean iris$isWynncraftHatModel(Identifier id) {
		return id != null && "minecraft".equals(id.getNamespace()) && id.getPath().startsWith(WYNNCRAFT_HAT_MODEL_PREFIX);
	}

	private static boolean iris$isWynncraftHatCarrier(Identifier id) {
		if (id == null || !"minecraft".equals(id.getNamespace())) {
			return false;
		}
		String path = id.getPath();
		return "diamond_pickaxe".equals(path) || "potion".equals(path);
	}

	private static int iris$overlaySignalColor(int overlayAlpha) {
		return ((overlayAlpha & 0xFF) << 24) | DEFAULT_OVERLAY_RGB;
	}

	public static void cacheRenderedArmorSignal(ItemStack stack, int color) {
		if (!iris$canReadWynncraftArmorSignal(stack)) {
			return;
		}

		int signalRgb = iris$decodeWynncraftEffectSignalRgb(color);
		if (signalRgb == DEFAULT_OVERLAY_RGB) {
			return;
		}

		int stackKey = ItemStack.hashItemAndComponents(stack);
		Integer previous = RENDERED_ARMOR_SIGNAL_CACHE.put(stackKey, signalRgb);
		if (RENDERED_ARMOR_SIGNAL_CACHE.size() > MAX_RENDERED_ARMOR_SIGNAL_CACHE) {
			RENDERED_ARMOR_SIGNAL_CACHE.clear();
			RENDERED_ARMOR_SIGNAL_CACHE.put(stackKey, signalRgb);
		}

		if ((previous == null || previous != signalRgb)
			&& WynncraftDebugLog.shouldLog("mount-armor-overlay-render-signal-" + stackKey)) {
			WynncraftDebugLog.info("mount-armor-overlay-render-signal-" + stackKey,
				"[WynnIris MountArmor] cached render-time signal stack={} itemModel={} customModelData={} raw={} signal={} effect={}",
				iris$itemName(stack), stack.get(DataComponents.ITEM_MODEL), iris$customModelDataSummary(stack),
				String.format("0x%08X", color), String.format("0x%06X", signalRgb), (signalRgb >>> 16) & 0xFF);
		}
	}

	private static int iris$getWynncraftEffectSignalRgb(ItemStack stack) {
		if (!iris$canReadWynncraftArmorSignal(stack)) {
			return DEFAULT_OVERLAY_RGB;
		}

		int stackKey = ItemStack.hashItemAndComponents(stack);
		Integer cachedSignal = RENDERED_ARMOR_SIGNAL_CACHE.get(stackKey);
		if (cachedSignal != null && cachedSignal != DEFAULT_OVERLAY_RGB) {
			iris$logEffectSignal(stack, "render-cache", cachedSignal, cachedSignal);
			return cachedSignal;
		}

		CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (customModelData != null) {
			for (int i = 0; i < customModelData.colors().size(); i++) {
				Integer color = customModelData.getColor(i);
				if (color == null) {
					continue;
				}
				int signalRgb = iris$decodeWynncraftEffectSignalRgb(color);
				if (signalRgb != DEFAULT_OVERLAY_RGB) {
					iris$logEffectSignal(stack, "custom-model-data-color-" + i, color, signalRgb);
					return signalRgb;
				}
			}
		}

		int dyeColor = DyedItemColor.getOrDefault(stack, 0);
		int signalRgb = iris$decodeWynncraftEffectSignalRgb(dyeColor);
		if (signalRgb != DEFAULT_OVERLAY_RGB) {
			iris$logEffectSignal(stack, "dyed-item-color", dyeColor, signalRgb);
			return signalRgb;
		}

		iris$logEffectSignal(stack, "none", dyeColor, DEFAULT_OVERLAY_RGB);
		return DEFAULT_OVERLAY_RGB;
	}

	private static boolean iris$canReadWynncraftArmorSignal(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (iris$getWynncraftCustomArmorAsset(stack).isPresent()) {
			return true;
		}
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.assetId().isPresent();
	}

	private static int iris$getArmorLayerDyeColor(ItemStack stack) {
		int dyeColor = DyedItemColor.getOrDefault(stack, 0);
		return iris$decodeWynncraftEffectSignalRgb(dyeColor) == DEFAULT_OVERLAY_RGB ? dyeColor : WHITE_COLOR;
	}

	private static int iris$decodeWynncraftEffectSignalRgb(int color) {
		int rgb = color & 0x00FFFFFF;
		int r = (rgb >>> 16) & 0xFF;
		int g = (rgb >>> 8) & 0xFF;
		int b = rgb & 0xFF;
		if (r >= 1 && r <= MAX_WYNNCRAFT_EFFECT_ID && g == 255 && b == 0) {
			return rgb;
		}
		return DEFAULT_OVERLAY_RGB;
	}

	private static int iris$getWynncraftEffectId(int signalRgb) {
		if (signalRgb == DEFAULT_OVERLAY_RGB) {
			return 0;
		}
		int effectId = (signalRgb >>> 16) & 0xFF;
		return effectId >= 1 && effectId <= MAX_WYNNCRAFT_EFFECT_ID ? effectId : 0;
	}

	private static boolean iris$isStaticTintEffect(int effectId) {
		return effectId >= 15 && effectId <= 24;
	}

	private static boolean iris$isHideArmorEffect(int effectId) {
		return effectId == HIDE_ARMOR_EFFECT_ID;
	}

	private static boolean iris$shouldHideArmorOverlay(ItemStack stack, int effectId) {
		return iris$isHideArmorEffect(effectId) || iris$usesHiddenEquipmentAsset(stack);
	}

	private static boolean iris$usesHiddenEquipmentAsset(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}

		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.assetId()
			.filter(HIDDEN_ARMOR_ASSET::equals)
			.isPresent();
	}

	private static boolean iris$isDynamicBaseEffect(int effectId) {
		return effectId == 2 || effectId == 3 || effectId == 4 || effectId == 6
			|| effectId == 7 || effectId == 8 || effectId == 10 || effectId == 13;
	}

	private static boolean iris$usesShaderOverlayEffect(int effectId) {
		return effectId == 4 || effectId == 5 || effectId == 12 || effectId == 13;
	}

	private static boolean iris$hasAnimatedGlintEffect(int effectId) {
		return effectId >= 1 && effectId <= 31
			&& !iris$isStaticTintEffect(effectId)
			&& !iris$isDynamicBaseEffect(effectId)
			&& !iris$usesShaderOverlayEffect(effectId);
	}

	private static ArmorTextureLayer iris$registerArmorTexture(Minecraft minecraft, String layer, NativeImage image, int effectId, int overlayAlpha) {
		NativeImage sourceCopy = iris$isDynamicBaseEffect(effectId) && !iris$usesShaderOverlayEffect(effectId) ? iris$copyImage(image) : null;
		Identifier id = Identifier.fromNamespaceAndPath("wynnvitrail", "dynamic/wynn_mount_armor/" + layer + "_" + textureSequence++);
		DynamicTexture texture = new DynamicTexture(() -> "wynn_mount_armor_" + layer, image);
		minecraft.getTextureManager().register(id, texture);
		texture.upload();
		return new ArmorTextureLayer(id, RenderTypes.entityTranslucent(id), image, texture, sourceCopy, effectId, iris$overlaySignalColor(overlayAlpha));
	}

	private static void iris$withShaderOverlayEffect(ArmorTextureLayer layer, Runnable submitter) {
		if (!iris$usesShaderOverlayEffect(layer.effectId)) {
			submitter.run();
			return;
		}

		int previous = dev.vitrail.render.EntityIdentifiers.item();
		dev.vitrail.render.EntityIdentifiers.item(SHADER_EFFECT_ITEM_ID_BASE + layer.effectId);
		try {
			submitter.run();
		} finally {
			dev.vitrail.render.EntityIdentifiers.item(previous);
		}
	}

	private static DynamicGlintLayer iris$registerGlintTexture(Minecraft minecraft, String layer, NativeImage source, int effectId) {
		if (!iris$hasAnimatedGlintEffect(effectId)) {
			return null;
		}

		NativeImage sourceCopy = iris$copyImage(source);
		NativeImage image = new NativeImage(source.getWidth(), source.getHeight(), true);
		image.fillRect(0, 0, image.getWidth(), image.getHeight(), 0);
		Identifier id = Identifier.fromNamespaceAndPath("wynnvitrail", "dynamic/wynn_mount_armor/" + layer + "_glint_" + textureSequence++);
		DynamicTexture texture = new DynamicTexture(() -> "wynn_mount_armor_" + layer + "_glint", image);
		minecraft.getTextureManager().register(id, texture);
		texture.upload();
		return new DynamicGlintLayer(id, RenderTypes.entityTranslucent(id), sourceCopy, image, texture, effectId);
	}

	private static NativeImage iris$copyImage(NativeImage source) {
		NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), true);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				copy.setPixel(x, y, source.getPixel(x, y));
			}
		}
		return copy;
	}

	private static void iris$updateArmorTextureLayer(ArmorTextureLayer layer) {
		if (layer.source == null) {
			return;
		}

		
		if (layer.lastFrame == frame) {
			return;
		}

		layer.lastFrame = frame;
		WynncraftMountArmorEffects.renderBase(layer.source, layer.image, layer.effectId, iris$effectTime());
		layer.texture.upload();
	}

	private static void iris$updateGlintLayer(DynamicGlintLayer layer) {
		
		if (layer.lastFrame == frame) {
			return;
		}

		layer.lastFrame = frame;
		WynncraftMountArmorEffects.renderGlint(layer.source, layer.image, layer.effectId, iris$effectTime());
		layer.texture.upload();
	}

	private static float iris$effectTime() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null) {
			long ticks = minecraft.level.getGameTime() % 24000L;
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			return (ticks + partial) / 80.0F;
		}
		return 0.0F;
	}

	private static void iris$logEffectSignal(ItemStack stack, String source, int rawColor, int signalRgb) {
		int stackKey = ItemStack.hashItemAndComponents(stack);
		String key = "mount-armor-overlay-effect-signal-" + stackKey;
		if (!WynncraftDebugLog.shouldLog(key)) {
			return;
		}

		WynncraftDebugLog.info(key,
			"[WynnIris MountArmor] effect signal source={} stack={} itemModel={} customModelData={} raw={} selected={} effect={}",
			source, iris$itemName(stack), stack.get(DataComponents.ITEM_MODEL), iris$customModelDataSummary(stack),
			String.format("0x%08X", rawColor), String.format("0x%06X", signalRgb),
			signalRgb == DEFAULT_OVERLAY_RGB ? 0 : (signalRgb >>> 16) & 0xFF);
	}

	private static String iris$customModelDataSummary(ItemStack stack) {
		CustomModelData customModelData = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (customModelData == null) {
			return "none";
		}
		return "floats=" + customModelData.floats()
			+ ", strings=" + customModelData.strings()
			+ ", flags=" + customModelData.flags()
			+ ", colors=" + customModelData.colors();
	}

	private static int iris$getColorForLayer(EquipmentClientInfo.Layer layer, int dyeColor) {
		if (layer.dyeable().isPresent()) {
			int undyed = layer.dyeable().get().colorWhenUndyed().orElse(0);
			return dyeColor != 0 ? dyeColor : undyed;
		}
		return -1;
	}

	private static NativeImage iris$readTexture(ResourceManager resourceManager, Identifier texture) {
		Optional<Resource> resource = resourceManager.getResource(texture);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream input = resource.get().open()) {
			return NativeImage.read(input);
		} catch (IOException e) {
			WynncraftDebugLog.info("mount-armor-overlay-texture-error",
				"[WynnIris MountArmor] failed reading equipment texture {}: {}", texture, e.toString());
			return null;
		}
	}

	private static void iris$copyArmorTexture(NativeImage source, NativeImage target, int layerColor,
			WynncraftMountArmorTexture.Piece piece, PlayerModelType modelType) {
		WynncraftMountArmorTexture.lay(source, target, layerColor, piece, modelType == PlayerModelType.SLIM
				? WynncraftMountArmorTexture.Body.SLIM : WynncraftMountArmorTexture.Body.WIDE);
	}

	private static void iris$clearCache(Minecraft minecraft) {
		for (OverlayTextures textures : CACHE.values()) {
			iris$releaseArmorTexture(minecraft, textures.outerHead);
			iris$releaseArmorTexture(minecraft, textures.outerBody);
			iris$releaseArmorTexture(minecraft, textures.outerBoots);
			iris$releaseArmorTexture(minecraft, textures.leggings);
			iris$releaseGlintTexture(minecraft, textures.outerHeadGlint);
			iris$releaseGlintTexture(minecraft, textures.outerBodyGlint);
			iris$releaseGlintTexture(minecraft, textures.outerBootsGlint);
			iris$releaseGlintTexture(minecraft, textures.leggingsGlint);
		}
		CACHE.clear();
	}

	private static void iris$releaseArmorTexture(Minecraft minecraft, ArmorTextureLayer layer) {
		if (layer == null) {
			return;
		}

		minecraft.getTextureManager().release(layer.id);
		if (layer.source != null) {
			layer.source.close();
		}
	}

	private static void iris$releaseGlintTexture(Minecraft minecraft, DynamicGlintLayer layer) {
		if (layer == null) {
			return;
		}

		minecraft.getTextureManager().release(layer.id);
		layer.source.close();
	}

	private static Identifier iris$layerId(ArmorTextureLayer layer) {
		return layer == null ? null : layer.id;
	}

	private static String iris$layerColor(ArmorTextureLayer layer) {
		return layer == null ? "none" : String.format("0x%08X", layer.color);
	}

	private static void iris$logMissingEquipment(ItemStack stack, String reason) {
		WynncraftDebugLog.info("mount-armor-overlay-missing-" + reason,
			"[WynnIris MountArmor] skipping stack={} reason={}", iris$itemName(stack), reason);
	}

	private static String iris$itemName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "empty";
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id == null ? stack.getItem().toString() : id.toString();
	}

	private enum ArmorLayer {
		OUTER(SKIN_OVERLAY_TO_OUTER_ARMOR_EXPAND, SKIN_OVERLAY_TO_OUTER_LEG_ARMOR_EXPAND),
		BOOTS(SKIN_OVERLAY_TO_BOOTS_ARMOR_EXPAND, SKIN_OVERLAY_TO_BOOTS_ARMOR_EXPAND),
		LEGGINGS(SKIN_OVERLAY_TO_LEGGINGS_ARMOR_EXPAND, SKIN_OVERLAY_TO_LEGGINGS_LEG_ARMOR_EXPAND);

		private final float bodyExpand;
		private final float legExpand;

		ArmorLayer(float bodyExpand, float legExpand) {
			this.bodyExpand = bodyExpand;
			this.legExpand = legExpand;
		}

		private float expandFor(int limbIndex) {
			return limbIndex == FAKE_PLAYER_LEFT_LEG || limbIndex == FAKE_PLAYER_RIGHT_LEG ? legExpand : bodyExpand;
		}

		private boolean widensSlimArms() {
			return this == OUTER;
		}
	}

	private static final class LimbBounds {
		private float minX = Float.POSITIVE_INFINITY;
		private float maxX = Float.NEGATIVE_INFINITY;

		private void include(float x) {
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
		}

		private boolean hasWidth() {
			return maxX > minX;
		}

		private float centerX() {
			return (minX + maxX) * 0.5F;
		}
	}

	private static final class ArmorTextureLayer {
		private final Identifier id;
		private final RenderType renderType;
		private final NativeImage image;
		private final DynamicTexture texture;
		private final NativeImage source;
		private final int effectId;
		private final int color;
		private int lastFrame = -1;

		private ArmorTextureLayer(Identifier id, RenderType renderType, NativeImage image, DynamicTexture texture,
								  NativeImage source, int effectId, int color) {
			this.id = id;
			this.renderType = renderType;
			this.image = image;
			this.texture = texture;
			this.source = source;
			this.effectId = effectId;
			this.color = color;
		}
	}

	private static final class DynamicGlintLayer {
		private final Identifier id;
		private final RenderType renderType;
		private final NativeImage source;
		private final NativeImage image;
		private final DynamicTexture texture;
		private final int effectId;
		private int lastFrame = -1;

		private DynamicGlintLayer(Identifier id, RenderType renderType, NativeImage source, NativeImage image, DynamicTexture texture, int effectId) {
			this.id = id;
			this.renderType = renderType;
			this.source = source;
			this.image = image;
			this.texture = texture;
			this.effectId = effectId;
		}
	}

	private static final class HeadCosmeticLayer {
		private final ItemStackRenderState renderState;
		private final String itemName;

		private HeadCosmeticLayer(ItemStackRenderState renderState, String itemName) {
			this.renderState = renderState;
			this.itemName = itemName;
		}
	}

	private record ArmorKey(PlayerModelType modelType, int head, int chest, int legs, int feet,
							int headSignal, int chestSignal, int legsSignal, int feetSignal) {
	}

	private record ArmorSnapshot(UUID playerId, ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {
		boolean matches(LocalPlayer player) {
			return playerId.equals(player.getUUID());
		}

		boolean hasAnyStack() {
			return !head.isEmpty() || !chest.isEmpty() || !legs.isEmpty() || !feet.isEmpty();
		}

		boolean sameStacks(ArmorSnapshot other) {
			return playerId.equals(other.playerId)
				&& ItemStack.matches(head, other.head)
				&& ItemStack.matches(chest, other.chest)
				&& ItemStack.matches(legs, other.legs)
				&& ItemStack.matches(feet, other.feet);
		}
	}

	private record OverlayTextures(ArmorTextureLayer outerHead, ArmorTextureLayer outerBody, ArmorTextureLayer outerBoots,
								   ArmorTextureLayer leggings, DynamicGlintLayer outerHeadGlint, DynamicGlintLayer outerBodyGlint,
								   DynamicGlintLayer outerBootsGlint, DynamicGlintLayer leggingsGlint,
								   HeadCosmeticLayer headCosmetic) {
		boolean isEmpty() {
			return outerHead == null && outerBody == null && outerBoots == null && leggings == null && headCosmetic == null;
		}
	}
}
