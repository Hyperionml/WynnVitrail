package dev.vitrail.render.upscale;

import dev.vitrail.pack.source.ShaderPackSource;

import java.nio.file.Files;
import java.nio.file.Path;

/** Development-only entry point. Neither command changes game configuration. */
public final class DlssDevelopmentTool {
	private DlssDevelopmentTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 2 && args[0].equals("audit")) {
			try (ShaderPackSource source = ShaderPackSource.open(Path.of(args[1]))) {
				var inspection = SolasDlssProfile.inspect(source);
				if (!inspection.recognized()) {
					throw new IllegalArgumentException("Not the supported Solas 3.9 source: "
							+ inspection.differences().stream().limit(8).toList());
				}
				System.out.println("Recognized Solas 3.9; HDR colortex0 before composite13.");
				System.out.println("composite15 must retain colortex2.r lens-flare state when replacing TAA.");
				System.out.println("Pack recognition only. In-game DLSS integration is not enabled.");
			}
		} else if (args.length == 3 && args[0].equals("probe")) {
			Path runtime = Path.of(args[2]).toAbsolutePath();
			if (!Files.isRegularFile(runtime.resolve("nvngx_dlss.dll"))) {
				throw new IllegalArgumentException("Missing nvngx_dlss.dll in " + runtime);
			}
			try (DlssBridge bridge = new DlssBridge(Path.of(args[1]))) {
				System.out.println("DLSS bridge ABI 1 loaded; instance extensions: "
						+ bridge.extensions(0, 0, runtime));
				System.out.println("Use dlss_smoke.exe to verify a real device and GPU output.");
			}
		} else {
			throw new IllegalArgumentException("Usage: audit <Solas.zip> | probe <bridge.dll> <runtime-directory>");
		}
	}
}
