package dev.wynnvitrail.ambience;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AmbienceWarmupScreen extends Screen {
	private final Screen parent;

	AmbienceWarmupScreen(Screen parent) {
		super(Component.literal("Warming shader presets"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
				.bounds(width / 2 - 64, height / 2 + 18, 128, 20).build());
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (!WynnAmbience.warming()) minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		WynnAmbience.cancelWarmup();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
		super.extractRenderState(graphics, x, y, tick);
		graphics.centeredText(font, title, width / 2, height / 2 - 20, -1);
		graphics.centeredText(font, WynnAmbience.warmProgress(), width / 2, height / 2 - 4, -1);
	}
}
