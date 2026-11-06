package dev.wynnvitrail.ambience;

import dev.vitrail.Vitrail;
import dev.vitrail.render.PackChoice;
import dev.wynnvitrail.WynnOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AmbienceScreen extends Screen {
	private final Screen parent;
	private final AmbiencePackManager manager = AmbiencePackManager.getInstance();
	private EditBox name;
	private String profile = "";
	private String message = "";
	private boolean installing;
	private boolean editing;
	private boolean confirmDelete;
    private boolean finding;
    private String dependencyId = "";
    private List<AmbienceDependencyCandidate> candidates = List.of();
    private int candidateIndex;

	public AmbienceScreen(Screen parent) {
		super(Component.literal("Ambience packs"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 146;
		int y = 28;
		var selected = manager.getPack(WynnOption.selectedPack());
		String id = selected.map(p -> p.pack().id).orElse("");
		var profiles = selected.map(p -> List.copyOf(p.profilesById().keySet())).orElse(List.of());
		if (!profiles.contains(profile)) profile = profiles.isEmpty() ? "" : profiles.getFirst();
		name = addRenderableWidget(new EditBox(font, x, y, 292, 20, Component.literal("Pack or preset name")));
		name.setHint(Component.literal("New pack or preset name"));
		if (editing && selected.isPresent()) {
            var pack = selected.get().pack();
            action(x,y+24,"Rename preset", () -> profile = manager.renameProfile(id,profile,input()).id).active = !profile.isEmpty();
            action(x+148,y+24,"Duplicate preset", () -> profile = manager.duplicateProfile(id,profile,input()).id).active = !profile.isEmpty();
            action(x,y+48,confirmDelete ? "Confirm delete preset" : "Delete preset", () -> {
                if (confirmDelete) manager.deleteProfile(id,profile);
                confirmDelete = !confirmDelete;
            }).active = !profile.isEmpty();
            action(x+148,y+48,"Use current shaders", () -> {
                var session = PackChoice.session().orElseThrow(() -> new IOException("Select a shader pack first"));
                manager.changeProfileShader(id,profile,session.packFileName(), null);
                manager.saveProfileOptions(id,profile,currentOptions(session));
            }).active = !profile.isEmpty();
            action(x,y+72,"Set pack name", () -> manager.updatePackMetadata(id,new AmbiencePackMetadata(input(),pack.version,pack.authors)));
            action(x+148,y+72,"Set pack version", () -> manager.updatePackMetadata(id,new AmbiencePackMetadata(pack.name,input(),pack.authors)));
            action(x,y+96,"Set authors (comma list)", () -> manager.updatePackMetadata(id,new AmbiencePackMetadata(pack.name,pack.version,
                    java.util.Arrays.stream(input().split(",")).map(String::strip).filter(a -> !a.isEmpty()).toList())));
            var local = pack.dependencies.stream().filter(d -> d != null && "local".equals(d.type)).toList();
            if (local.stream().noneMatch(d -> d.id.equals(dependencyId))) {
                dependencyId = local.isEmpty() ? "" : local.getFirst().id;
                candidates = List.of();
            }
            action(x,y+120,"Dependency: " + dependencyId, () -> {
                var ids = local.stream().map(d -> d.id).toList();
                dependencyId = ids.get((ids.indexOf(dependencyId) + 1) % ids.size());
                candidates = List.of();
            }).active = !local.isEmpty() && !finding;
            action(x+148,y+120,"Find on Modrinth", () -> findDependency(id,local.stream()
                    .filter(d -> d.id.equals(dependencyId)).findFirst().orElseThrow())).active = !local.isEmpty() && !finding;
            action(x,y+144,candidates.isEmpty() ? "Match" : candidates.get(candidateIndex).displayName(), () ->
                    candidateIndex = (candidateIndex + 1) % candidates.size()).active = !candidates.isEmpty();
            action(x+148,y+144,"Link selected match", () -> {
                manager.replaceDependency(id,dependencyId,new AmbienceDependencyResolver().dependencyFromCandidate(candidates.get(candidateIndex)));
                candidates = List.of();
            }).active = !candidates.isEmpty();
            action(x+148,y+96,"Back", () -> { editing = false; confirmDelete = false; });
            action(x,y+168,"Edit preset options", () -> {
                var preset = manager.resolveProfile(id, profile)
                        .orElseThrow(() -> new IOException("Install this preset's shader pack first"));
                minecraft.gui.setScreen(new AmbienceOptionsScreen(this, preset));
            }).active = !profile.isEmpty();
            action(x+148,y+168,"Delete pack", () -> minecraft.gui.setScreen(
                    new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
                        if (confirmed) {
                            try {
                                manager.deletePack(id);
                                editing = false;
                            } catch (IOException error) {
                                message = error.getMessage();
                            }
                        }
                        minecraft.gui.setScreen(this);
                    }, Component.literal("Delete ambience pack?"), Component.literal(pack.name))));
            return;
        }
        action(x,y+24, "Pack: " + id, () -> {
			var ids = manager.getPacks().stream().map(p -> p.pack().id).toList();
			if (!ids.isEmpty()) WynnOption.selectPack(ids.get((ids.indexOf(id) + 1) % ids.size()));
		});
		action(x+148,y+24,"Create pack", () -> WynnOption.selectPack(manager.createEmptyPack(input()).pack().id));
		action(x,y+48,"Preset: " + profile, () -> {
			if (!profiles.isEmpty()) profile = profiles.get((profiles.indexOf(profile) + 1) % profiles.size());
		});
		action(x+148,y+48,"Save current shaders", () -> {
			var session = PackChoice.session().orElseThrow(() -> new IOException("Select a shader pack first"));
			String requested = input();
			var created = manager.addProfile(id, requested, session.packFileName());
			manager.saveProfileOptions(id, created.id, currentOptions(session));
			profile = created.id;
		});
		action(x,y+72,"Default preset", () -> manager.setDefaultProfile(id,profile)).active = !profile.isEmpty();
		action(x+148,y+72,"Edit regions", () -> minecraft.gui.setScreen(new AmbienceRegionEditorScreen(this,id))).active = !id.isEmpty();
		action(x,y+96,"Install missing shaders", () -> {
			var pack = selected.orElseThrow().pack();
			installing = true;
			message = "Installing dependencies...";
			CompletableFuture.runAsync(() -> {
				try { new AmbienceDependencyResolver().installMissing(pack); }
				catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CompletionException(e); }
			}).whenComplete((ignored,error) -> minecraft.execute(() -> {
				manager.reload();
				installing = false;
				message = error == null ? "Dependencies installed" : error.getMessage();
				if (minecraft.gui.screen() == this) rebuildWidgets();
			}));
		}).active = selected.isPresent() && !installing;
		action(x+148,y+96,"Export pack", () -> {
			Path exports = manager.getDirectory().resolveSibling("exports");
			Files.createDirectories(exports);
			Path zip = exports.resolve(id + ".wynnambience.zip");
			if (Files.exists(zip)) throw new IOException("Export already exists: " + zip);
			manager.exportPack(id, zip);
			message = zip.toString();
		}).active = !id.isEmpty();
		action(x,y+120,"Reload packs", manager::reload);
		action(x,y+144, WynnAmbience.warming() ? "Cancel warmup" : "Warm shader presets", () -> {
			if (WynnAmbience.warming()) WynnAmbience.cancelWarmup();
			else WynnAmbience.warm(false);
		}).active = minecraft.level != null && !profiles.isEmpty();
		action(x+148,y+144,"Manage pack / preset", () -> editing = true).active = selected.isPresent();
		action(x+148,y+120,"Done", this::onClose);
		action(x,y+168,"Open packs folder", () -> {
			Files.createDirectories(manager.getDirectory());
			dev.vitrail.screen.Desktop.openPath(manager.getDirectory());
		});
	}

	private static java.util.Map<String, String> currentOptions(dev.vitrail.settings.PackSession session) {
		var active = WynnAmbience.active();
		if (active != null && active.resolvedShaderPack().equals(session.packFileName())) {
			return new LinkedHashMap<>(active.profile().options);
		}
		var options = new LinkedHashMap<String, String>();
		session.saved().values().forEach((name, value) -> {
			if (session.declared().contains(name)) options.put(name, value);
		});
		return options;
	}

	private void findDependency(String packId, AmbienceDependency dependency) {
        var resolver = new AmbienceDependencyResolver();
        String local = dependency.localNames.isEmpty() ? dependency.id : dependency.localNames.getFirst();
        Path directory = Vitrail.platform().gameDirectory().resolve("shaderpacks").toAbsolutePath().normalize();
        Path file = directory.resolve(local).normalize();
        String query = name.getValue().isBlank() ? local : name.getValue().strip();
        candidates = List.of();
        finding = true;
        message = "Searching Modrinth...";
        CompletableFuture.supplyAsync(() -> {
            try {
                if (directory.equals(file.getParent()) && Files.isRegularFile(file)) {
                    var exact = resolver.findExactDependency(file, local);
                    if (exact.isPresent()) return List.of(exact.get());
                }
                return resolver.searchDependencies(query, local);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((matches,error) -> minecraft.execute(() -> {
            finding = false;
            if (packId.equals(WynnOption.selectedPack()) && dependency.id.equals(dependencyId)) {
                candidates = matches == null ? List.of() : matches;
                candidateIndex = 0;
                message = error != null ? error.getMessage() : candidates.size() + " matches";
            }
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }

    private String input() throws IOException {
		if (name.getValue().isBlank()) throw new IOException("Enter a name first");
		return name.getValue().strip();
	}

	private Button action(int x, int y, String label, Action action) {
		return addRenderableWidget(Button.builder(Component.literal(label), b -> {
			try {
				message = "";
				action.run();
				if (minecraft.gui.screen() == this) rebuildWidgets();
			} catch (Exception e) {
				message = String.valueOf(e.getMessage());
				Vitrail.logger().warn("Ambience action failed: {}", label, e);
			}
		}).bounds(x,y,144,20).build());
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		try {
			for (Path file : files) WynnOption.selectPack(manager.importPack(file).pack().id);
			rebuildWidgets();
		} catch (IOException e) { message = e.getMessage(); }
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics,int mx,int my,float tick) {
		super.extractRenderState(graphics,mx,my,tick);
		graphics.centeredText(font,title,width/2,10,-1);
		graphics.centeredText(font, message.isEmpty() ? "Drop an ambience pack here to import it" : message,width/2,222,-1);
	}

	@Override public void onClose() {
		if (editing) {
			editing = false;
			confirmDelete = false;
			rebuildWidgets();
		} else {
			Minecraft.getInstance().gui.setScreen(parent);
		}
	}
	@FunctionalInterface private interface Action { void run() throws IOException; }
}
