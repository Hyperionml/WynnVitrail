package dev.vitrail.render;

// Procedural sky and ambient palette from WynnIris, WynncraftSkyboxRenderer (LGPL-3.0).
final class WynnSkyShader {
	private WynnSkyShader() {}

	static final String SOURCE = """
		// === Constants ===
		#define PI 3.14159265359
		#define TAU (PI * 2.0)

		// === Noise utilities (ported from Wynncraft util.glsl) ===

		float wRandom(float seed) {
		    return fract(57128.836 * sin(dot(vec2(seed), vec2(12.77251, 72.37871))));
		}

		float wNoise(vec2 p) {
		    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
		}

		float wSmoothNoise(vec2 p) {
		    vec2 i = floor(p); vec2 f = fract(p);
		    float a = wNoise(i); float b = wNoise(i + vec2(1.0, 0.0));
		    float c = wNoise(i + vec2(0.0, 1.0)); float d = wNoise(i + vec2(1.0, 1.0));
		    vec2 u = f * f * (3.0 - 2.0 * f);
		    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
		}

		float wFbm2(vec2 p) {
		    float v = 0.0, a = 0.5, freq = 1.0;
		    for (int i = 0; i < 5; i++) {
		        v += a * wSmoothNoise(p * freq);
		        freq *= 2.0; a *= 0.5;
		    }
		    return v;
		}

		float wFbm3(vec3 p) {
		    return wFbm2(p.xy) + wFbm2(p.yz) + wFbm2(p.zx);
		}

		vec3 wHsvToRgb(vec3 c) {
		    vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
		    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
		    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
		}

		vec3 wRotateAxis(vec3 v, vec3 ax, float angle) {
		    return mix(dot(v, ax) * ax, v, cos(angle)) + cross(ax, v) * sin(angle);
		}

		float wCrystalNoise(vec3 position, float time) {
		    int iterations = 8;
		    float start = 2.20, expand = 1.20;
		    float edgeThickness = 0.25;
		    vec3 axis1 = vec3(0.8506, 0.5257, 0.0000);
		    vec3 axis2 = vec3(0.0000, 0.5257, 0.8506);
		    float angle1 = PI / 7.0, angle2 = PI / 27.0;
		    float expand1 = 1.00, expand2 = 1.25;
		    float centralise = 0.5, dampen = 1.8;

		    float noise = 0.0;
		    float scale = start;
		    vec3 travel1 = position;
		    vec3 travel2 = abs(fract(position) - 0.5) * 0.15;

		    for (int iteration = 0; iteration < iterations; iteration++) {
		        travel1 = wRotateAxis(travel1, axis1, angle1);
		        travel1 *= expand1;
		        vec3 point = cos(travel1 * scale + travel2 + time);
		        noise += sin(TAU * dot(point, vec3(0.3))) * 0.5 + 0.5;
		        scale *= expand;
		        travel2 += cos(smoothstep(0.0, edgeThickness, point));
		        travel2 = wRotateAxis(travel2, axis2, angle2);
		        travel2 *= expand2;
		    }

		    noise = noise / float(iterations);
		    noise = noise * 2.0 - 1.0;
		    noise = pow(abs(noise), centralise) * sign(noise);
		    noise = noise * 0.5 + 0.5;
		    noise = pow(noise, dampen);
		    return noise;
		}

		// === Lightning system (ported from Wynncraft skybox.glsl) ===

		float wLightningBolt(vec2 uv2, vec2 start, vec2 end, float seed, float width) {
		    vec2 dir = end - start;
		    float len = length(dir);
		    vec2 norm = dir / len;
		    vec2 perp = vec2(-norm.y, norm.x);
		    vec2 toPoint = uv2 - start;
		    float t = clamp(dot(toPoint, norm) / len, 0.0, 1.0);
		    float across = dot(toPoint, perp);

		    float disp = 0.0;
		    disp += 0.06 * sin(t * 8.0 + seed * 3.7) * smoothstep(0.0, 0.3, t) * smoothstep(1.0, 0.7, t);
		    disp += 0.03 * sin(t * 17.0 + seed * 7.1);
		    disp += 0.015 * sin(t * 31.0 + seed * 11.3);
		    disp += 0.008 * sin(t * 61.0 + seed * 19.7);

		    float dist = abs(across - disp);
		    float core = smoothstep(width * 0.5, 0.0, dist);
		    float glow1 = smoothstep(width * 3.0, 0.0, dist) * 0.6;
		    float glow2 = smoothstep(width * 8.0, 0.0, dist) * 0.2;
		    float bolt = (core + glow1 + glow2) * step(0.0, t) * step(t, 1.0);

		    float branch = 0.0;
		    // Branch 1
		    float bt1 = 0.4 + 0.2 * fract(seed * 1.618);
		    vec2 branchPt1 = start + dir * bt1 + perp * disp;
		    vec2 branchEnd1 = branchPt1 + vec2(0.08, -0.12) + vec2(fract(seed * 2.71) * 0.1 - 0.05, 0.0);
		    {
		        vec2 bd = branchEnd1 - branchPt1; float bl = length(bd);
		        vec2 bn = bd / bl; vec2 bp = vec2(-bn.y, bn.x);
		        vec2 tp = uv2 - branchPt1;
		        float bt = clamp(dot(tp, bn) / bl, 0.0, 1.0);
		        float ba = dot(tp, bp);
		        float bd2 = 0.04 * sin(bt * 12.0 + seed * 5.3);
		        float bd3 = abs(ba - bd2);
		        branch += smoothstep(width * 0.3, 0.0, bd3) * step(0.0, bt) * step(bt, 1.0);
		        branch += smoothstep(width * 2.0, 0.0, bd3) * 0.4 * step(0.0, bt) * step(bt, 1.0);
		    }
		    // Branch 2
		    float bt2 = 0.65 + 0.15 * fract(seed * 2.414);
		    vec2 branchPt2 = start + dir * bt2 + perp * disp;
		    vec2 branchEnd2 = branchPt2 + vec2(-0.10, -0.09) + vec2(fract(seed * 1.41) * 0.08 - 0.04, 0.0);
		    {
		        vec2 bd = branchEnd2 - branchPt2; float bl = length(bd);
		        vec2 bn = bd / bl; vec2 bp = vec2(-bn.y, bn.x);
		        vec2 tp = uv2 - branchPt2;
		        float bt = clamp(dot(tp, bn) / bl, 0.0, 1.0);
		        float ba = dot(tp, bp);
		        float bd2 = 0.03 * sin(bt * 15.0 + seed * 8.1);
		        float bd3 = abs(ba - bd2);
		        branch += smoothstep(width * 0.25, 0.0, bd3) * step(0.0, bt) * step(bt, 1.0);
		        branch += smoothstep(width * 2.0, 0.0, bd3) * 0.35 * step(0.0, bt) * step(bt, 1.0);
		    }
		    return clamp(bolt + branch * 0.7, 0.0, 1.0);
		}

		float wLightningFlash(float time, float seed) {
		    float period = 35.0 + 25.0 * wRandom(seed);
		    float phase = fract((time + seed * 37.3) / period);
		    float numFlashes = floor(wRandom(seed + floor((time + seed * 37.3) / period) * 7.91) * 3.0) + 1.0;
		    float spacing = 0.012 + 0.018 * wRandom(seed + 44.1);
		    float duration = 0.025 + 0.015 * wRandom(seed + 88.3);
		    float result = 0.0;
		    for (int f = 0; f < 3; f++) {
		        if (float(f) >= numFlashes) break;
		        float offset = float(f) * spacing;
		        float brightness = pow(0.55, float(f));
		        result += brightness * smoothstep(0.0, 0.003, phase - offset)
		            * smoothstep(duration + offset, duration + offset - 0.008, phase);
		    }
		    return clamp(result, 0.0, 1.0);
		}

		float wDistantCloudFlash(float time, float seed) {
		    float period = 40.0 + 80.0 * wRandom(seed + 100.0);
		    float phase = fract((time + seed * 53.7) / period);
		    float duration = 0.08 + 0.06 * wRandom(seed + 200.0);
		    float envelope = smoothstep(0.0, duration * 0.4, phase) * smoothstep(duration, duration * 0.6, phase);
		    return envelope * 0.18;
		}

		// === Skybox effects (ported from Wynncraft skybox.glsl) ===

		// Case 0 (rainbow - experimental)
		vec4 skyboxRainbow(float time, vec3 dir) {
		    float speed = 0.02;
		    vec3 col = wHsvToRgb(vec3(dir.x - time * speed, 1.0, 1.0));
		    return vec4(col, 1.0);
		}

		// Case 1: Memory Mist
		vec4 skyboxMemoryMist(float time, vec3 dir) {
		    vec3 mistColor = vec3(0.89, 0.91, 0.95);
		    float shiftS = 0.025 * time;
		    float mystifyA = wFbm3(dir + vec3(0.0, sin(shiftS), 0.0));
		    vec2 reMiss = vec2(0.8 * wFbm3(vec3(dir.xy + vec2(mystifyA, 0.1), 0.0)),
		                       wFbm3(vec3(dir.yz - mystifyA, 0.0)));
		    float mystifyB = wFbm3(0.25 * (dir + vec3(0.0, atan(reMiss.x, reMiss.y) / PI, 0.0)));
		    vec3 col = mix(vec3(-0.15), mistColor + vec3(0.25), mystifyB);
		    return vec4(col, 1.0);
		}

		// Case 2: Memory Fog
		vec4 skyboxMemoryFog(float time, vec3 dir) {
		    vec3 fogColor = vec3(0.55, 0.5, 0.6);
		    float shiftS = -0.05 * time;
		    vec3 pos = dir + 0.25 * vec3(sin(shiftS), shiftS, cos(shiftS));
		    float noises = wFbm3(pos + vec3(0.2, 0.3, 0.2)) * 0.3;
		    float redir = dir.y + noises;
		    float q = (1.0 - redir * redir * 1.4) * 0.9;
		    float fogAlpha = 1.0 - smoothstep(0.0, 0.6, redir);
		    return vec4(mix(vec3(-0.2), fogColor + vec3(0.2), q), 0.85 * fogAlpha);
		}

		// Shared base: Red Cloudy (used by stormy, war, red lightning)
		vec3 skyboxRedCloudyColor(float time, vec3 dir) {
		    float speed = 0.01;
		    float intensity = 2.5, scale = 1.2;
		    vec3 c1 = vec3(0.600, 0.000, 0.000), c2 = vec3(1.000, 0.200, 0.000);
		    vec3 c3 = vec3(0.000, 0.200, 0.000), c4 = vec3(1.000, 0.600, 0.600);
		    vec3 c5 = vec3(0.300, 0.300, 0.300), c6 = vec3(1.200, 1.200, 1.200);

		    float shift = time * speed;
		    vec3 p1 = dir * intensity + vec3(0.0, shift, shift);
		    float n1 = wFbm3(scale * p1);
		    vec2 p2 = vec2(wFbm3(vec3(p1.xy + n1, 0.0)), wFbm3(vec3(p1.yz - n1, 0.0)));
		    float n2 = wFbm3(scale * (p1 + vec3(p2, 0.0)));

		    vec3 color = mix(c1, c2, n2);
		    color += mix(c3, c4, p2.x);
		    color -= mix(c5, c6, p2.y);
		    return clamp(color, 0.0, 1.0);
		}

		// Case 3: Stormy
		vec4 skyboxStormy(float time, vec3 dir) {
		    vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
		    vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
		    float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;

		    vec3 base = skyboxRedCloudyColor(time, dir);
		    vec3 colorUpperSky = vec3(dot(base, valuationUpper));
		    float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, dir.y);
		    vec3 colorSky = mix(vec3(0.0), colorUpperSky, influenceUpper);
		    float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(dir.y - heightHorizon)));
		    return vec4(mix(colorHorizon, colorSky, influenceSky), 1.0);
		}

		// Case 4: War Surface
		vec4 skyboxWarSurface(float time, vec3 dir) {
		    vec3 colorHorizon = vec3(0.0);
		    float heightHorizon = 0.5;
		    vec3 base = skyboxRedCloudyColor(time, dir);
		    float influenceSky = smoothstep(0.0, heightHorizon, dir.y);
		    return vec4(mix(colorHorizon, base, influenceSky), 1.0);
		}

		// Case 5: War Heights
		vec4 skyboxWarHeights(float time, vec3 dir) {
		    vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
		    vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
		    float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;

		    vec3 base = skyboxRedCloudyColor(time, dir);
		    vec3 colorLowerSky = base;
		    vec3 colorUpperSky = vec3(dot(colorLowerSky, valuationUpper));
		    float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, dir.y);
		    vec3 colorSky = mix(colorLowerSky, colorUpperSky, influenceUpper);
		    float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(dir.y - heightHorizon)));
		    return vec4(mix(colorHorizon, colorSky, influenceSky), 1.0);
		}

		// Case 6: Light
		vec4 skyboxLight(float time, vec3 dir) {
		    vec3 c1 = vec3(0.850, 0.850, 1.000);
		    vec3 c2 = vec3(0.750, 0.400, 0.000);
		    float noise = wCrystalNoise(dir * 3.0, time * 0.01);
		    vec3 color = mix(c1, c2, noise);
		    return vec4(mix(vec3(1.0, 0.9, 0.8), color, smoothstep(0.1, 0.5, dir.y)), 1.0);
		}

		// Case 7: Red Lightning
		vec4 skyboxRedLightning(float time, vec3 dir) {
		    vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
		    vec3 colorUpper = vec3(1.0);
		    vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
		    float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;

		    vec3 base = skyboxRedCloudyColor(time, dir);
		    vec3 colorUpperSky = vec3(dot(base, valuationUpper) * colorUpper);
		    float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, dir.y);
		    vec3 colorSky = mix(vec3(0.0), colorUpperSky, influenceUpper);
		    float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(dir.y - heightHorizon)));
		    vec3 result = mix(colorHorizon, colorSky, influenceSky);

		    // Distant cloud flashes
		    float cloudGlow = 0.0;
		    for (int i = 0; i < 3; i++) {
		        float seed = float(i) * 17.13 + 3.7;
		        float glow = wDistantCloudFlash(time, seed);
		        float cycle = floor((time + seed * 53.7) / (20.0 + 40.0 * wRandom(seed + 100.0)));
		        float az = fract(seed * 0.137 + cycle * 0.318) * 6.28318;
		        vec3 flashDir = vec3(cos(az), 0.0, sin(az));
		        float azimuthalFocus = dot(normalize(dir.xz), flashDir.xz);
		        float azimuthalMask = smoothstep(0.55, 0.90, azimuthalFocus);
		        float flashEl = 0.25 + fract(seed * 0.331 + cycle * 0.271) * 0.30;
		        float elevationMask = smoothstep(0.18, 0.0, abs(dir.y - flashEl));
		        cloudGlow += glow * azimuthalMask * elevationMask;
		    }
		    result += vec3(1.0, 0.10, 0.05) * clamp(cloudGlow, 0.0, 0.35);

		    // Lightning bolts
		    float totalLightning = 0.0, totalScreenFlash = 0.0;
		    for (int i = 0; i < 4; i++) {
		        float seed = float(i) * 31.41592 + 7.3;
		        float strikeIntensity = wLightningFlash(time, seed);
		        if (strikeIntensity > 0.0) {
		            float cycle = floor((time + seed * 37.3) / (35.0 + 25.0 * wRandom(seed)));
		            float az = fract(seed * 0.137 + cycle * 0.419) * 6.28318;
		            float el = 0.3 + fract(seed * 0.271 + cycle * 0.347) * 0.35;
		            vec3 boltCenter = normalize(vec3(cos(az) * sqrt(1.0 - el*el), el, sin(az) * sqrt(1.0 - el*el)));
		            vec3 up = vec3(0.0, 1.0, 0.0);
		            vec3 tangentX = normalize(cross(up, boltCenter));
		            vec3 tangentY = normalize(cross(boltCenter, tangentX));
		            vec3 d = normalize(dir);
		            vec2 localUV = vec2(dot(d, tangentX), dot(d, tangentY));
		            vec2 origin = vec2(fract(seed * 0.413 + cycle * 0.531) * 0.3 - 0.15, 0.25);
		            vec2 target = origin + vec2(fract(seed * 0.619 + cycle * 0.217) * 0.16 - 0.08, -0.5);
		            float proximity = smoothstep(0.5, 0.85, dot(d, boltCenter));
		            float bolt = wLightningBolt(localUV, origin, target, seed + cycle, 0.003);
		            totalLightning += bolt * strikeIntensity * proximity;
		            totalScreenFlash += strikeIntensity * 0.25 * proximity;
		        }
		    }
		    totalLightning = clamp(totalLightning, 0.0, 1.0);
		    totalScreenFlash = clamp(totalScreenFlash, 0.0, 1.0);
		    vec3 boltColor = mix(vec3(1.0, 0.05, 0.02), vec3(1.0, 0.85, 0.80), totalLightning);
		    vec3 flashColor = vec3(0.9, 0.08, 0.04) * totalScreenFlash;
		    result = clamp(result + flashColor + boltColor * totalLightning, 0.0, 1.0);

		    return vec4(result, 1.0);
		}

		// === Per-variant ambient parameters for scene tinting ===

		void getSkyboxAmbient(int id, out vec3 ambientColor, out float darkening, out float baseTint) {
		    switch (id) {
		        case 1: // Memory Mist - cool, misty wash
		            ambientColor = vec3(0.75, 0.78, 0.85); darkening = 0.65; baseTint = 0.30; break;
		        case 2: // Memory Fog - dark purple-gray fog
		            ambientColor = vec3(0.5, 0.45, 0.55); darkening = 0.4; baseTint = 0.40; break;
		        case 3: // Stormy - dark overcast
		            ambientColor = vec3(0.3, 0.3, 0.33); darkening = 0.35; baseTint = 0.40; break;
		        case 4: // War Surface - dark red mood
		            ambientColor = vec3(0.6, 0.15, 0.1); darkening = 0.4; baseTint = 0.35; break;
		        case 5: // War Heights - dark red mood
		            ambientColor = vec3(0.5, 0.15, 0.1); darkening = 0.4; baseTint = 0.35; break;
		        case 6: // Light - warm, minimal darkening
		            ambientColor = vec3(1.0, 0.95, 0.85); darkening = 1.0; baseTint = 0.10; break;
		        case 7: // Red Lightning - very dark stormy red
		            ambientColor = vec3(0.4, 0.12, 0.08); darkening = 0.3; baseTint = 0.45; break;
		        default:
		            ambientColor = vec3(1.0); darkening = 1.0; baseTint = 0.0; break;
		    }
		}

		vec4 computeSkybox(int id, float skyTime, vec3 worldDir) {
		    switch (id) {
		        case 0: return skyboxRainbow(skyTime, worldDir); // Debug/experimental
		        case 1: return skyboxMemoryMist(skyTime, worldDir);
		        case 2: return skyboxMemoryFog(skyTime, worldDir);
		        case 3: return skyboxStormy(skyTime, worldDir);
		        case 4: return skyboxWarSurface(skyTime, worldDir);
		        case 5: return skyboxWarHeights(skyTime, worldDir);
		        case 6: return skyboxLight(skyTime, worldDir);
		        case 7: return skyboxRedLightning(skyTime, worldDir);
		        default: return vec4(0.0); // Unknown ID - no effect
		    }
		}

		""";
}
