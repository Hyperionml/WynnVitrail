package dev.wynnvitrail;

import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import java.util.Map;

/** Missing world-text program. Movement and colour equations follow WynnIris ShaderSynthesizer. */
public final class WynnGlyphFallback {
    private WynnGlyphFallback() {}

    public static Map<ProgramStage, ExpandedUnit> program() {
        return Map.of(ProgramStage.VERTEX, WynnVfx.unit("wynn_text.vsh", """
            #version 330 compatibility
            uniform float wynnDayClock;
            varying vec4 layerColor;
            varying vec2 uv;
            varying vec2 lightUV;
            flat out int irisW_moveBlink;
            vec3 irisW_hsvToRgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }
            float irisW_hash(float n) { return fract(sin(n) * 43758.5453); }
            void main() {
                gl_Position = ftransform();
                uv = gl_MultiTexCoord0.xy;
                lightUV = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
                layerColor = gl_Color;
                ivec3 irisW_rawCol = ivec3(floor(gl_Color.rgb * 255.0 + 0.5));
                irisW_moveBlink = 0;
                int irisW_effectB = -1;
                int irisW_moveB = -1;
                int irisW_moveR = 0;
                bool irisW_isShadow = (max(gl_Color.r, max(gl_Color.g, gl_Color.b)) / 4.0 < 0.23);

                // Detect effect (G=240) or shadow of effect (G=60)
                if (irisW_rawCol.g == 240 || irisW_rawCol.g == 60) {
                    irisW_effectB = (irisW_rawCol.g == 240) ? irisW_rawCol.b : irisW_rawCol.b * 4;
                }
                // Detect movement (G=235) or shadow of movement (G=58 or 59)
                if (irisW_rawCol.g == 235 || irisW_rawCol.g == 58 || irisW_rawCol.g == 59) {
                    irisW_moveB = (irisW_rawCol.g == 235) ? irisW_rawCol.b : irisW_rawCol.b * 4;
                    irisW_moveR = (irisW_rawCol.g == 235) ? irisW_rawCol.r : irisW_rawCol.r * 4;
                }

                // Apply movements (position modifications)
                if (irisW_moveB >= 0) {
                    float irisW_mt = (wynnDayClock / 24000.0) * 12000.0;
                    vec2 irisW_pivot = vec2[4](vec2(0,0), vec2(0,1), vec2(1,1), vec2(1,0))[gl_VertexID % 4] - 0.5;
                    float irisW_isTop = (gl_VertexID % 4 == 0 || gl_VertexID % 4 == 3) ? 1.0 : 0.0;
                    vec3 irisW_mpos = gl_Vertex.xyz;
                    if (irisW_moveB == 0) irisW_mpos.y += sin(irisW_mt) * 3.0; // slide Y
                    else if (irisW_moveB == 4) irisW_mpos.x += sin(irisW_mt) * 3.0; // slide X
                    else if (irisW_moveB == 8) irisW_mpos.y += sin(irisW_mt + irisW_mpos.y * 0.05) * 2.0; // warp Y->Y
                    else if (irisW_moveB == 12) irisW_mpos.x += sin(irisW_mt + irisW_mpos.y * 0.05) * 2.0; // warp Y->X
                    else if (irisW_moveB == 16) irisW_mpos.y += sin(irisW_mt + irisW_mpos.x * 0.05) * 2.0; // warp X->Y
                    else if (irisW_moveB == 20) irisW_mpos.x += sin(irisW_mt + irisW_mpos.x * 0.05) * 2.0; // warp X->X
                    else if (irisW_moveB == 24) { // vibrate
                        float irisW_vStr = float(irisW_moveR) / 255.0;
                        irisW_mpos.xy += vec2(cos(irisW_mt), sin(irisW_mt)) * irisW_hash(irisW_mt) * irisW_vStr;
                    }
                    else if (irisW_moveB == 28) { // scale pulse
                        irisW_mpos.xy += irisW_pivot * (1.0 + sin(irisW_mt) * 2.0);
                    }
                    else if (irisW_moveB == 32) { // orbit
                        irisW_mpos.x += cos(irisW_mt) * 3.0;
                        irisW_mpos.y += sin(irisW_mt) * 3.0;
                    }
                    else if (irisW_moveB == 36) { // blink
                        irisW_moveBlink = (sin(irisW_mt * 0.5 * 3.14159) < 0.0) ? 1 : 0;
                    }
                    else if (irisW_moveB == 40) { // shake
                        float irisW_sStr = float(irisW_moveR) / 255.0;
                        irisW_mpos.xy += vec2(irisW_hash(irisW_mt), irisW_hash(irisW_mt + 1.0)) * irisW_sStr;
                    }
                    else if (irisW_moveB == 44) { // stretch (binary approx)
                        if (irisW_isTop > 0.5) irisW_mpos.y += cos(irisW_mt) * 5.0;
                    }
                    else if (irisW_moveB == 48) { // bump
                        irisW_mpos.x += sin(irisW_mt + irisW_mpos.x) * 1.0;
                    }
                    else if (irisW_moveB == 52) { // italic (binary approx)
                        if (irisW_isTop > 0.5) irisW_mpos.x += -64.0 / 256.0;
                    }
                    else if (irisW_moveB == 56) { // scale static
                        irisW_mpos.xy += irisW_pivot * 12.0;
                    }
                    else if (irisW_moveB >= 60 && irisW_moveB <= 72) { // offset (4 directions)
                        float irisW_dir = float(irisW_moveB - 60) / 12.0 * 0.25;
                        float irisW_amt = float(irisW_moveR);
                        irisW_mpos.xy += vec2(cos(irisW_dir * 6.28318), sin(irisW_dir * 6.28318)) * irisW_amt;
                    }
                    gl_Position = gl_ModelViewProjectionMatrix * vec4(irisW_mpos, 1.0);
                    // Neutralize color to white
                    layerColor = vec4(1.0) * vec4(1.0);
                    if (irisW_isShadow) layerColor *= 0.25;
                }

                // Apply effects (color modifications)
                if (irisW_effectB >= 0) {
                    float irisW_gt = (wynnDayClock / 24000.0) * 300.0;
                    vec3 irisW_eCol = vec3(1.0);
                    float irisW_eAlpha = gl_Color.a;
                    vec3 irisW_ePos = gl_Vertex.xyz;
                    if (irisW_effectB == 0) { // rainbow
                        irisW_eCol = irisW_hsvToRgb(vec3(0.005 * (irisW_ePos.x + irisW_ePos.y) - irisW_gt, 0.7, 1.0));
                    }
                    else if (irisW_effectB == 4) { // gradient orange-blue
                        float irisW_gm = (sin(0.08 * (irisW_ePos.x + irisW_ePos.y) - irisW_gt * 500.0 * 3.14159) + 1.0) * 0.5;
                        irisW_eCol = mix(vec3(0.961, 0.384, 0.090), vec3(0.043, 0.282, 0.420), irisW_gm);
                    }
                    else if (irisW_effectB == 8) { // fade + green
                        irisW_eCol = vec3(0.353, 0.941, 0.510);
                        irisW_eAlpha = mix(irisW_eAlpha, 0.0, sin(irisW_gt * 1200.0 * 3.14159) * 0.5 + 0.5);
                    }
                    else if (irisW_effectB == 12) { // blink + red
                        irisW_eCol = vec3(0.784, 0.196, 0.196);
                        if (sin(irisW_gt * 6400.0 * 0.2 * 3.14159) < 0.0) irisW_eAlpha = 0.0;
                    }
                    else if (irisW_effectB == 16) { // dark red gradient
                        float irisW_gm2 = (sin(0.08 * (irisW_ePos.x + irisW_ePos.y) - irisW_gt * 1000.0 * 3.14159) + 1.0) * 0.5;
                        irisW_eCol = mix(vec3(0.337, 0.020, 0.020), vec3(0.541, 0.012, 0.012), irisW_gm2);
                    }
                    else if (irisW_effectB == 20) { // shine sweep
                        vec3 irisW_shBase = vec3(0.627, 0.784, 0.294);
                        vec3 irisW_shHi = vec3(1.0, 1.0, 0.824);
                        float irisW_shM = smoothstep(0.0, 1.0, sin(irisW_ePos.x * 0.1 + irisW_gt * 500.0 * 6.28318) + 0.5);
                        irisW_eCol = mix(irisW_shBase, irisW_shHi, irisW_shM);
                    }
                    else if (irisW_effectB == 24) { // shake + faded + fade (hybrid)
                        irisW_eCol = vec3(0.5);
                        irisW_eAlpha = mix(irisW_eAlpha, 0.0, sin(irisW_gt * 1200.0 * 3.14159) * 0.5 + 0.5);
                        // Shake position
                        float irisW_smt = (wynnDayClock / 24000.0) * 12000.0;
                        vec3 irisW_shPos = gl_Vertex.xyz;
                        irisW_shPos.xy += vec2(irisW_hash(irisW_smt), irisW_hash(irisW_smt + 1.0)) * 0.5;
                        gl_Position = gl_ModelViewProjectionMatrix * vec4(irisW_shPos, 1.0);
                    }
                    else if (irisW_effectB == 28) { // italic + cyan (hybrid)
                        irisW_eCol = vec3(0.333, 1.0, 1.0);
                        float irisW_itop = (gl_VertexID % 4 == 0 || gl_VertexID % 4 == 3) ? 1.0 : 0.0;
                        if (irisW_itop > 0.5) {
                            vec3 irisW_ip = gl_Vertex.xyz; irisW_ip.x += -64.0 / 256.0;
                            gl_Position = gl_ModelViewProjectionMatrix * vec4(irisW_ip, 1.0);
                        }
                    }
                    else if (irisW_effectB == 32) { // italic + grey (hybrid)
                        irisW_eCol = vec3(0.5);
                        float irisW_itop2 = (gl_VertexID % 4 == 0 || gl_VertexID % 4 == 3) ? 1.0 : 0.0;
                        if (irisW_itop2 > 0.5) {
                            vec3 irisW_ip2 = gl_Vertex.xyz; irisW_ip2.x += -64.0 / 256.0;
                            gl_Position = gl_ModelViewProjectionMatrix * vec4(irisW_ip2, 1.0);
                        }
                    }
                    else if (irisW_effectB == 36) { // warp + grey (hybrid)
                        irisW_eCol = vec3(0.5);
                        float irisW_wmt = (wynnDayClock / 24000.0) * 12000.0;
                        vec3 irisW_wp = gl_Vertex.xyz;
                        irisW_wp.x += sin(irisW_wmt + irisW_wp.x * 0.05) * 2.0;
                        gl_Position = gl_ModelViewProjectionMatrix * vec4(irisW_wp, 1.0);
                    }
                    layerColor = vec4(irisW_eCol, irisW_eAlpha) * vec4(1.0);
                    if (irisW_isShadow) layerColor *= 0.25;
                }
            }
            """), ProgramStage.FRAGMENT, WynnVfx.unit("wynn_text.fsh", """
            #version 330 compatibility
            uniform sampler2D gtexture;
            uniform sampler2D lightmap;
            varying vec4 layerColor;
            varying vec2 uv;
            varying vec2 lightUV;
            flat in int irisW_moveBlink;
            void main() {
                if (irisW_moveBlink != 0) discard;
                gl_FragColor = texture2D(gtexture, uv) * layerColor * vec4(texture2D(lightmap, lightUV).rgb, 1.0);
            }
            """));
    }
}
