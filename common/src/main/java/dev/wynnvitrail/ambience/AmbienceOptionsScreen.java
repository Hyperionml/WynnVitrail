package dev.wynnvitrail.ambience;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.load.PackLoader;
import dev.vitrail.pack.menu.MenuValues;
import dev.vitrail.settings.PackSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Edits the selected region preset independently of the active shader pack. */
final class AmbienceOptionsScreen extends Screen {
	private final Screen parent;
	private final AmbiencePackManager.ResolvedProfile preset;
	private final PackSession session;
	private final List<String> names;
	private MenuValues values;
	private int page;
	private String error = "";

	AmbienceOptionsScreen(Screen parent, AmbiencePackManager.ResolvedProfile preset) throws IOException {
		super(Component.literal(preset.profileId()));
		this.parent = parent;
		this.preset = preset;
		var game = Vitrail.platform().gameDirectory();
		var pack = PackLoader.candidates(game).stream()
				.filter(path -> path.getFileName().toString().equals(preset.resolvedShaderPack()))
				.findFirst().orElseThrow(() -> new IOException("Shader pack missing: " + preset.resolvedShaderPack()));
		session = PackSession.read(game, pack, net.minecraft.client.Minecraft.getInstance().options.languageCode);
		names = session.menu().optionNames().stream().sorted().toList();
		values = MenuValues.of(session.menu(), preset.profile().options, session.forcedText());
	}

	@Override
	protected void init() {
		int rows = Math.max(1, (height - 88) / 22);
		int pages = Math.max(1, Math.ceilDiv(names.size(), rows * 2));
		page = Math.min(page, pages - 1);
		int start = page * rows * 2;
		int left = width / 2 - 148;
		for (int i = 0; i < rows * 2 && start + i < names.size(); i++) {
			String name = names.get(start + i);
			var option = session.menu().option(name).orElseThrow();
			String value = values.pending(name);
			var labels = session.menu().lang();
			button(left + i % 2 * 150, 25 + i / 2 * 22, 146, labels.option(name) + " = " + labels.value(name, value), () -> {
				values.queue(name, option.at(option.indexOf(value) + 1));
				rebuildWidgets();
			}).active = !values.forced(name) && option.size() > 1;
		}
		button(left, height - 53, 72, "<", () -> { page--; rebuildWidgets(); }).active = page > 0;
		button(left + 75, height - 53, 72, ">", () -> { page++; rebuildWidgets(); }).active = page + 1 < pages;
		button(left + 150, height - 53, 146, "Reset to defaults", () -> {
			values = MenuValues.of(session.menu(), Map.of(), session.forcedText());
			rebuildWidgets();
		});
		button(left, height - 30, 146, "Cancel", this::onClose);
		button(left + 150, height - 30, 146, "Save preset", () -> {
			try {
				AmbiencePackManager.getInstance().saveProfileOptions(preset.loadedPack().pack().id,
						preset.profileId(), values.toSave());
				onClose();
			} catch (IOException failure) {
				error = failure.getMessage();
			}
		});
	}

	private Button button(int x, int y, int w, String label, Runnable action) {
		return addRenderableWidget(Button.builder(Component.literal(label), button -> action.run())
				.bounds(x, y, w, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
		super.extractRenderState(graphics, x, y, tick);
		graphics.centeredText(font, error.isEmpty() ? title.getString() : error, width / 2, 8, -1);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
