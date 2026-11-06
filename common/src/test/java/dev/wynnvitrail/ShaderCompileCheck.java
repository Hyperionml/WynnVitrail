package dev.wynnvitrail;

import dev.vitrail.pack.model.ProgramStage;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.util.shaderc.Shaderc.*;

public final class ShaderCompileCheck {
    private ShaderCompileCheck() {}

	public static void compile(ProgramStage stage, String text) {
		long compiler = shaderc_compiler_initialize();
		long options = shaderc_compile_options_initialize();
		try {
			shaderc_compile_options_add_macro_definition(options, "gl_VertexID", "gl_VertexIndex");
			shaderc_compile_options_add_macro_definition(options, "gl_InstanceID", "gl_InstanceIndex");
			shaderc_compile_options_set_auto_bind_uniforms(options, true);
			shaderc_compile_options_set_auto_map_locations(options, true);
			long result = shaderc_compile_into_spv(compiler, text,
					stage == ProgramStage.VERTEX ? shaderc_glsl_vertex_shader : shaderc_glsl_fragment_shader,
					"wynn-regression", "main", options);
			try {
				assertTrue(shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success,
						() -> shaderc_result_get_error_message(result));
			} finally { shaderc_result_release(result); }
		} finally {
			shaderc_compile_options_release(options);
			shaderc_compiler_release(compiler);
		}
	}

}
