package dev.wynnvitrail;

/**
 * Wynncraft's sky, which is drawn as an object rather than painted behind the world.
 * <p>
 * <strong>A third signal, and the same door as the other two.</strong> The glint and the
 * translucency level ride in the mesh's vertex colour and the unlit and self-lit markers in a
 * texture's alpha; a skybox is marked in a texture too, but with three bytes at once. The green is
 * two hundred and fifty-one, the alpha two hundred and fifty-four - which is the self-lit marker's
 * alpha, which is why {@link WynncraftShading#IS_EMISSIVE_NAME} excludes a green of two hundred and
 * fifty-one rather than trusting the alpha alone - and the blue is the identity of the sky, one to
 * seven.
 * <p>
 * <strong>What a pack does with one without being told is draw it.</strong> Wynncraft has no sky
 * object the game knows about: it puts a very large box around the player, textures its faces with
 * the animated art the drawing is made of, and marks them with this signal so that the vanilla
 * resource pack can replace the fragment with the sky the art meant. A shader pack reads the same
 * box as geometry and shades it, and gets a lit, fogged, shadowed cube instead of a sky - and the
 * cube is the wrong shape for it, so the sky is not merely darkened but wrong in a way that moves
 * when the player moves.
 * <p>
 * <strong>The replacement is the resource pack's own procedure, transcribed.</strong> Seven skies
 * are written out here, each a function of the direction the fragment lies in and of the time of
 * day, and the drawing dispatches on the blue the signal carried. The noise the seven are built on
 * - the lattice the fbm interpolates, the flashes' envelopes - is {@link WynncraftGlint}'s, which
 * is why the two libraries are written together and in that order: this one calls
 * {@code wynnRandom} and {@code wynnSmoothNoise}, and a stage that gained this one without the
 * other would not compile.
 * <p>
 * <strong>The two identities the drawing is told are nought here, and the half that fills them is
 * not something this engine is missing.</strong> WynnIris paints a sky twice over: the replacement
 * below runs in the entity pass, and a fullscreen pass
 * ({@code pathways/WynncraftSkyboxRenderer}) paints a sky of its own over every pixel whose depth
 * is at the clear value - in world space, across the whole screen, over terrain too. The identity
 * that pass has decided to paint is published as {@code iris_wynncraftPrimarySkyboxId} and the one
 * it has just faded from as {@code iris_wynncraftRecentSkyboxId} (both from
 * {@code IrisRenderingPipeline}), and the replacement discards the dome of either so that the two
 * paintings do not land on top of each other. What fills those two is a CPU half - a mixin that
 * reads the pixels of item textures for the marking, a per-frame collector that prefers a dome at
 * the height a beacon sits at ({@code ImmediateState}), and a three-frame debounce, a smoothed
 * fade and ten seconds of grace in the pipeline - and none of it is ported.
 * <p>
 * <strong>A sky here is the entity stage's own fragment and nothing else, so there is no second
 * painting to suppress.</strong> That is the shape the resource pack has, and the shape WynnIris's
 * own comment points at ("exactly how the vanilla Wynncraft resource pack works",
 * {@code EntityPatcher.java:120-123}). A discard fired from a nought would not remove a duplicate;
 * it would take the sky away and leave the clear colour behind it. So the two stand at nought -
 * which is not a placeholder but WynnIris's own value for "no primary detected", matching no
 * identity a signal can carry - and the comparison is a branch that is never taken rather than a
 * feature that is switched off.
 * <p>
 * <strong>The time is the glint's day clock and not a second one.</strong> WynnIris reads
 * {@code fract(iris_globalInfo.GameTime) * 12000.0}, and its GameTime is the fraction of the day
 * this engine publishes in ticks as {@code worldTime}; the expression is kept in that shape,
 * divided by the day and taken back up to twelve thousand, so that the two engines' skies are at
 * the same hour rather than merely both moving. {@link WynncraftPatch#DAY_CLOCK} carries the whole
 * of that argument.
 * <p>
 * The three helpers that only these skies use - the crystalline noise, the bolt and its two
 * envelopes - are here rather than beside the glint's, and the split is by caller: every effect of
 * the glint library is built on the lattice, and none of the skies' own functions is reached from
 * anything else.
 */
final class WynncraftSkybox {

	/**
	 * The decode, which answers the identity of the sky a texel belongs to or nought for a texel
	 * that belongs to no sky.
	 * <p>
	 * A tolerance on two of the three bytes and a range on the third, which is what the resource
	 * pack's own test does: the marking is bytes of a texture read back through a sampler, so a
	 * pack that filters rather than snapping to a texel lands between two values rather than on
	 * one, and a blue outside one to seven is a texel that merely happens to look like a marking.
	 */
	static final String SIGNAL_NAME = "wynnSkyboxSignal";

	/**
	 * The dispatch, which answers the colour a sky of a given identity is at a given hour from a
	 * given direction.
	 * <p>
	 * Its second and third arguments are the caller's: this half knows what a sky looks like and
	 * the caller knows where the fragment is and what time the world is at.
	 */
	static final String APPLY_NAME = "wynnSkyApply";

	private static final String SIGNAL =
			"""
			int wynnSkyboxSignal(sampler2D tex, vec2 uv) {
			    vec4 sc = texture(tex, uv);
			    int sg = int(round(sc.g * 255.0));
			    int sa = int(round(sc.a * 255.0));
			    if (sg == 251 && sa == 254) {
			        int sid = int(round(sc.b * 255.0));
			        if (sid >= 1 && sid <= 7) return sid;
			    }
			    return 0;
			}
			""";

	private static final String SKY_FBM_2D =
			"""
			float wynnSkyFbm(vec2 p) {
			    float v = 0.0, a = 0.5, freq = 1.0;
			    for (int i = 0; i < 5; i++) {
			        v += a * wynnSmoothNoise(p * freq);
			        freq *= 2.0; a *= 0.5;
			    }
			    return v;
			}
			""";

	private static final String SKY_FBM_3D =
			"""
			float wynnSkyFbm(vec3 p) {
			    return wynnSkyFbm(p.xy) + wynnSkyFbm(p.yz) + wynnSkyFbm(p.zx);
			}
			""";

	private static final String SKY_ROTATE_AXIS =
			"""
			vec3 wynnSkyRotateAxis(vec3 v, vec3 axis, float angle) {
			    return mix(dot(v, axis) * axis, v, cos(angle)) + cross(axis, v) * sin(angle);
			}
			""";

	private static final String SKY_CRYSTAL_NOISE =
			"""
			float wynnSkyCrystalNoise(vec3 position, float time) {
			    const float WYNN_SKY_PI = 3.14159265359;
			    const float WYNN_SKY_TAU = WYNN_SKY_PI * 2.0;
			    int iterations = 8;
			    float start = 2.20, expand = 1.20, edgeThickness = 0.25;
			    vec3 axis1 = vec3(0.8506, 0.5257, 0.0);
			    vec3 axis2 = vec3(0.0, 0.5257, 0.8506);
			    float angle1 = WYNN_SKY_PI / 7.0, angle2 = WYNN_SKY_PI / 27.0;
			    float expand1 = 1.0, expand2 = 1.25;
			    float centralise = 0.5, dampen = 1.8;
			    float n = 0.0, scale = start;
			    vec3 travel1 = position;
			    vec3 travel2 = abs(fract(position) - 0.5) * 0.15;
			    for (int i = 0; i < iterations; i++) {
			        travel1 = wynnSkyRotateAxis(travel1, axis1, angle1);
			        travel1 *= expand1;
			        vec3 pt = cos(travel1 * scale + travel2 + time);
			        n += sin(WYNN_SKY_TAU * dot(pt, vec3(0.3))) * 0.5 + 0.5;
			        scale *= expand;
			        travel2 += cos(smoothstep(0.0, edgeThickness, pt));
			        travel2 = wynnSkyRotateAxis(travel2, axis2, angle2);
			        travel2 *= expand2;
			    }
			    n = n / float(iterations);
			    n = n * 2.0 - 1.0;
			    n = pow(abs(n), centralise) * sign(n);
			    n = n * 0.5 + 0.5;
			    n = pow(n, dampen);
			    return n;
			}
			""";

	private static final String SKY_LIGHTNING_BOLT =
			"""
			float wynnSkyLightningBolt(vec2 uv, vec2 start, vec2 end, float seed, float width) {
			    vec2 dir = end - start;
			    float len = length(dir);
			    vec2 norm = dir / len;
			    vec2 perp = vec2(-norm.y, norm.x);
			    vec2 toPoint = uv - start;
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
			    float bt1 = 0.4 + 0.2 * fract(seed * 1.618);
			    vec2 branchPt1 = start + dir * bt1 + perp * disp;
			    vec2 branchEnd1 = branchPt1 + vec2(0.08, -0.12) + vec2(fract(seed * 2.71) * 0.1 - 0.05, 0.0);
			    { vec2 bd = branchEnd1 - branchPt1; float bl = length(bd); vec2 bn = bd / bl; vec2 bp = vec2(-bn.y, bn.x);
			      vec2 tp = uv - branchPt1; float bt = clamp(dot(tp, bn) / bl, 0.0, 1.0); float ba = dot(tp, bp);
			      float bd2 = 0.04 * sin(bt * 12.0 + seed * 5.3); float bd3 = abs(ba - bd2);
			      branch += smoothstep(width * 0.3, 0.0, bd3) * step(0.0, bt) * step(bt, 1.0);
			      branch += smoothstep(width * 2.0, 0.0, bd3) * 0.4 * step(0.0, bt) * step(bt, 1.0); }
			    float bt2 = 0.65 + 0.15 * fract(seed * 2.414);
			    vec2 branchPt2 = start + dir * bt2 + perp * disp;
			    vec2 branchEnd2 = branchPt2 + vec2(-0.10, -0.09) + vec2(fract(seed * 1.41) * 0.08 - 0.04, 0.0);
			    { vec2 bd = branchEnd2 - branchPt2; float bl = length(bd); vec2 bn = bd / bl; vec2 bp = vec2(-bn.y, bn.x);
			      vec2 tp = uv - branchPt2; float bt = clamp(dot(tp, bn) / bl, 0.0, 1.0); float ba = dot(tp, bp);
			      float bd2 = 0.03 * sin(bt * 15.0 + seed * 8.1); float bd3 = abs(ba - bd2);
			      branch += smoothstep(width * 0.25, 0.0, bd3) * step(0.0, bt) * step(bt, 1.0);
			      branch += smoothstep(width * 2.0, 0.0, bd3) * 0.35 * step(0.0, bt) * step(bt, 1.0); }
			    return clamp(bolt + branch * 0.7, 0.0, 1.0);
			}
			""";

	private static final String SKY_LIGHTNING_FLASH =
			"""
			float wynnSkyLightningFlash(float time, float seed) {
			    float period = 35.0 + 25.0 * wynnRandom(seed);
			    float phase = fract((time + seed * 37.3) / period);
			    float numFlashes = floor(wynnRandom(seed + floor((time + seed * 37.3) / period) * 7.91) * 3.0) + 1.0;
			    float spacing = 0.012 + 0.018 * wynnRandom(seed + 44.1);
			    float duration = 0.025 + 0.015 * wynnRandom(seed + 88.3);
			    float result = 0.0;
			    for (int f = 0; f < 3; f++) {
			        if (float(f) >= numFlashes) break;
			        float offset = float(f) * spacing;
			        float brightness = pow(0.55, float(f));
			        result += brightness * smoothstep(0.0, 0.003, phase - offset) * smoothstep(duration + offset, duration + offset - 0.008, phase);
			    }
			    return clamp(result, 0.0, 1.0);
			}
			""";

	private static final String SKY_DISTANT_CLOUD_FLASH =
			"""
			float wynnSkyDistantCloudFlash(float time, float seed) {
			    float period = 40.0 + 80.0 * wynnRandom(seed + 100.0);
			    float phase = fract((time + seed * 53.7) / period);
			    float duration = 0.08 + 0.06 * wynnRandom(seed + 200.0);
			    float envelope = smoothstep(0.0, duration * 0.4, phase) * smoothstep(duration, duration * 0.6, phase);
			    return envelope * 0.18;
			}
			""";

	private static final String APPLY =
			"""
			vec4 wynnSkyApply(int id, float time, vec3 direction) {
			    const float WYNN_SKY_PI = 3.14159265359;
			    vec3 color = vec3(0.0);
			    float alpha = 1.0;

			    // Sub-function: red cloudy sky base (used by cases 3-5, 7)
			    // Inlined as a block to avoid needing a separate function declaration.
			    // After this block, color holds the red cloudy result.
			    // Cases that use it will call this macro-like pattern.

			    switch (id) {
			        case 1: {
			            // Memory Mist (RP skyboxMemoryMist)
			            vec3 mistColor = vec3(0.89, 0.91, 0.95);
			            float shiftS = 0.025 * time;
			            float mystifyA = wynnSkyFbm(direction + vec3(0.0, sin(shiftS), 0.0));
			            vec2 reMiss = vec2(0.8 * wynnSkyFbm(direction.xy + vec2(mystifyA, 0.1)),
			                               wynnSkyFbm(direction.yz - mystifyA));
			            float mystifyB = wynnSkyFbm(0.25 * (direction + vec3(0.0, atan(reMiss.x, reMiss.y) / WYNN_SKY_PI, 0.0)));
			            color = mix(vec3(-0.15), mistColor + vec3(0.25), mystifyB);
			            break;
			        }
			        case 2: {
			            // Memory Fog (RP skyboxMemoryFog)  -  uses alpha for transparency
			            vec3 fogColor = vec3(0.55, 0.5, 0.6);
			            float shiftS = -0.05 * time;
			            vec3 pos = direction + 0.25 * vec3(sin(shiftS), shiftS, cos(shiftS));
			            float noises = wynnSkyFbm(pos + vec3(0.2, 0.3, 0.2)) * 0.3;
			            float redir = direction.y + noises;
			            float q = (1.0 - redir * redir * 1.4) * 0.9;
			            float fogAlpha = 1.0 - smoothstep(0.0, 0.6, redir);
			            color = mix(vec3(-0.2), fogColor + vec3(0.2), q);
			            alpha = 0.85 * fogAlpha;
			            break;
			        }
			        case 3: {
			            // Stormy (RP skyboxStormy)  -  red cloudy base with dark horizon
			            vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
			            vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
			            float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;
			            // Inline redCloudy
			            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
			            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
			            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
			            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
			            float rcShift = time * rcSpeed;
			            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
			            float rcNoise1 = wynnSkyFbm(rcScale * rcPos1);
			            vec2 rcPos2 = vec2(wynnSkyFbm(rcPos1.xy + rcNoise1), wynnSkyFbm(rcPos1.yz - rcNoise1));
			            float rcNoise2 = wynnSkyFbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
			            vec3 rcColor = mix(rc1, rc2, rcNoise2);
			            rcColor += mix(rc3, rc4, rcPos2.x);
			            rcColor -= mix(rc5, rc6, rcPos2.y);
			            rcColor = clamp(rcColor, 0.0, 1.0);
			            // Apply stormy horizon
			            vec3 colorUpperSky = vec3(dot(rcColor, valuationUpper));
			            float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, direction.y);
			            vec3 colorSky = mix(vec3(0.0), colorUpperSky, influenceUpper);
			            float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(direction.y - heightHorizon)));
			            color = mix(colorHorizon, colorSky, influenceSky);
			            break;
			        }
			        case 4: {
			            // War Surface (RP skyboxWarSurface)  -  red cloudy fading to black at horizon
			            float heightHorizon = 0.5;
			            // Inline redCloudy
			            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
			            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
			            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
			            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
			            float rcShift = time * rcSpeed;
			            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
			            float rcNoise1 = wynnSkyFbm(rcScale * rcPos1);
			            vec2 rcPos2 = vec2(wynnSkyFbm(rcPos1.xy + rcNoise1), wynnSkyFbm(rcPos1.yz - rcNoise1));
			            float rcNoise2 = wynnSkyFbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
			            vec3 rcColor = mix(rc1, rc2, rcNoise2);
			            rcColor += mix(rc3, rc4, rcPos2.x);
			            rcColor -= mix(rc5, rc6, rcPos2.y);
			            rcColor = clamp(rcColor, 0.0, 1.0);
			            // Apply war surface horizon
			            float influenceSky = smoothstep(0.0, heightHorizon, direction.y);
			            color = mix(vec3(0.0), rcColor, influenceSky);
			            break;
			        }
			        case 5: {
			            // War Heights (RP skyboxWarHeights)  -  variant of stormy with brightness shift
			            vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
			            vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
			            float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;
			            // Inline redCloudy
			            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
			            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
			            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
			            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
			            float rcShift = time * rcSpeed;
			            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
			            float rcNoise1 = wynnSkyFbm(rcScale * rcPos1);
			            vec2 rcPos2 = vec2(wynnSkyFbm(rcPos1.xy + rcNoise1), wynnSkyFbm(rcPos1.yz - rcNoise1));
			            float rcNoise2 = wynnSkyFbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
			            vec3 rcColor = mix(rc1, rc2, rcNoise2);
			            rcColor += mix(rc3, rc4, rcPos2.x);
			            rcColor -= mix(rc5, rc6, rcPos2.y);
			            rcColor = clamp(rcColor, 0.0, 1.0);
			            // Apply war heights horizon (differs from stormy: uses colorLowerSky)
			            vec3 colorLowerSky = rcColor;
			            vec3 colorUpperSky = vec3(dot(colorLowerSky, valuationUpper));
			            float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, direction.y);
			            vec3 colorSky = mix(colorLowerSky, colorUpperSky, influenceUpper);
			            float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(direction.y - heightHorizon)));
			            color = mix(colorHorizon, colorSky, influenceSky);
			            break;
			        }
			        case 6: {
			            // Light (RP skyboxLight)  -  crystal noise patterns
			            vec3 color1 = vec3(0.85, 0.85, 1.0);
			            vec3 color2 = vec3(0.75, 0.4, 0.0);
			            float cn = wynnSkyCrystalNoise(direction * 3.0, time * 0.01);
			            vec3 baseColor = mix(color1, color2, cn);
			            color = mix(vec3(1.0, 0.9, 0.8), baseColor, smoothstep(0.1, 0.5, direction.y));
			            break;
			        }
			        case 7: {
			            // Red Lightning (RP skyboxRedLightning)  -  red cloudy + lightning bolts + screen flash
			            vec3 valuationUpper = vec3(0.106, 0.358, 0.036);
			            vec3 colorUpper = vec3(1.0, 1.0, 1.0);
			            vec3 colorHorizon = vec3(0.05, 0.05, 0.05);
			            float heightHorizon = 0.15, widthHorizon = 0.30, mixHorizon = 0.10;
			            // Inline redCloudy
			            float rcSpeed = 0.01, rcIntensity = 2.5, rcScale = 1.2;
			            vec3 rc1 = vec3(0.6, 0.0, 0.0), rc2 = vec3(1.0, 0.2, 0.0);
			            vec3 rc3 = vec3(0.0, 0.2, 0.0), rc4 = vec3(1.0, 0.6, 0.6);
			            vec3 rc5 = vec3(0.3, 0.3, 0.3), rc6 = vec3(1.2, 1.2, 1.2);
			            float rcShift = time * rcSpeed;
			            vec3 rcPos1 = direction * rcIntensity + vec3(0.0, rcShift, rcShift);
			            float rcNoise1 = wynnSkyFbm(rcScale * rcPos1);
			            vec2 rcPos2 = vec2(wynnSkyFbm(rcPos1.xy + rcNoise1), wynnSkyFbm(rcPos1.yz - rcNoise1));
			            float rcNoise2 = wynnSkyFbm(rcScale * (rcPos1 + vec3(rcPos2, 0.0)));
			            vec3 rcColor = mix(rc1, rc2, rcNoise2);
			            rcColor += mix(rc3, rc4, rcPos2.x);
			            rcColor -= mix(rc5, rc6, rcPos2.y);
			            rcColor = clamp(rcColor, 0.0, 1.0);
			            // Stormy sky base
			            vec3 colorUpperSky = vec3(dot(rcColor, valuationUpper) * colorUpper);
			            float influenceUpper = smoothstep(heightHorizon - widthHorizon, heightHorizon, direction.y);
			            vec3 colorSky = mix(vec3(0.0), colorUpperSky, influenceUpper);
			            float influenceSky = mix(mixHorizon, 1.0, smoothstep(0.0, widthHorizon, abs(direction.y - heightHorizon)));
			            color = mix(colorHorizon, colorSky, influenceSky);
			            // Distant cloud flashes
			            float cloudGlow = 0.0;
			            for (int ci = 0; ci < 3; ci++) {
			                float cseed = float(ci) * 17.13 + 3.7;
			                float glow = wynnSkyDistantCloudFlash(time, cseed);
			                float cycle = floor((time + cseed * 53.7) / (20.0 + 40.0 * wynnRandom(cseed + 100.0)));
			                float az = fract(cseed * 0.137 + cycle * 0.318) * 6.28318;
			                vec3 flashDir = vec3(cos(az), 0.0, sin(az));
			                float azimuthalFocus = dot(normalize(direction.xz), flashDir.xz);
			                float azimuthalMask = smoothstep(0.55, 0.90, azimuthalFocus);
			                float flashEl = 0.25 + fract(cseed * 0.331 + cycle * 0.271) * 0.30;
			                float elevationMask = smoothstep(0.18, 0.0, abs(direction.y - flashEl));
			                cloudGlow += glow * azimuthalMask * elevationMask;
			            }
			            color += vec3(1.0, 0.10, 0.05) * clamp(cloudGlow, 0.0, 0.35);
			            // Lightning bolts
			            float totalLightning = 0.0, totalScreenFlash = 0.0;
			            for (int li = 0; li < 4; li++) {
			                float lseed = float(li) * 31.41592 + 7.3;
			                float strikeIntensity = wynnSkyLightningFlash(time, lseed);
			                if (strikeIntensity > 0.0) {
			                    float lcycle = floor((time + lseed * 37.3) / (35.0 + 25.0 * wynnRandom(lseed)));
			                    float laz = fract(lseed * 0.137 + lcycle * 0.419) * 6.28318;
			                    float lel = 0.3 + fract(lseed * 0.271 + lcycle * 0.347) * 0.35;
			                    vec3 boltCenter = normalize(vec3(cos(laz) * sqrt(1.0 - lel * lel), lel, sin(laz) * sqrt(1.0 - lel * lel)));
			                    vec3 up = vec3(0.0, 1.0, 0.0);
			                    vec3 tangentX = normalize(cross(up, boltCenter));
			                    vec3 tangentY = normalize(cross(boltCenter, tangentX));
			                    vec3 d = normalize(direction);
			                    vec2 localUV = vec2(dot(d, tangentX), dot(d, tangentY));
			                    vec2 origin = vec2(fract(lseed * 0.413 + lcycle * 0.531) * 0.3 - 0.15, 0.25);
			                    vec2 target = origin + vec2(fract(lseed * 0.619 + lcycle * 0.217) * 0.16 - 0.08, -0.5);
			                    float proximity = smoothstep(0.5, 0.85, dot(d, boltCenter));
			                    float bolt = wynnSkyLightningBolt(localUV, origin, target, lseed + lcycle, 0.003);
			                    totalLightning += bolt * strikeIntensity * proximity;
			                    totalScreenFlash += strikeIntensity * 0.25 * proximity;
			                }
			            }
			            totalLightning = clamp(totalLightning, 0.0, 1.0);
			            totalScreenFlash = clamp(totalScreenFlash, 0.0, 1.0);
			            vec3 boltColor = mix(vec3(1.0, 0.05, 0.02), vec3(1.0, 0.85, 0.80), totalLightning);
			            vec3 flashColor = vec3(0.9, 0.08, 0.04) * totalScreenFlash;
			            color = clamp(color + flashColor + boltColor * totalLightning, 0.0, 1.0);
			            break;
			        }
			    }
			    return vec4(color, alpha);
			}
			""";

	/**
	 * Every helper above, in the order one may call another.
	 * <p>
	 * One list rather than a call per name at the one site that writes them, for the reason
	 * {@link WynncraftSignals#HELPERS} gives: a helper added below is added here or not at all, and
	 * a callee the header never wrote is a stage that does not compile. The order is not free -
	 * {@code APPLY} dispatches to all seven of the others and {@code SKY_FBM_3D} calls
	 * {@code SKY_FBM_2D} - so the array is the dependency order and not a list of names.
	 */
	static final String[] HELPERS = {SIGNAL, SKY_FBM_2D, SKY_FBM_3D, SKY_ROTATE_AXIS,
			SKY_CRYSTAL_NOISE, SKY_LIGHTNING_BOLT, SKY_LIGHTNING_FLASH, SKY_DISTANT_CLOUD_FLASH,
			APPLY};

	private WynncraftSkybox() {
	}
}
