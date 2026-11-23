/**
 * Ray-marched terrain shader — visual style from David Hoskins' "Mountains" Shadertoy.
 * https://www.shadertoy.com/view/4slGD4
 *
 * Renders terrain, water, sky, clouds, and fog in a single full-screen fragment pass.
 * Terrain height is driven entirely by a backend-generated heightmap (Hoskins noise
 * computed in Java). The shader reads the heightmap linearly — no procedural overlay.
 *
 * The heightmap texture holds heights in world units with the map-border fade
 * already applied. RayMarchedTerrain bakes both when it builds the texture, so
 * the sample path here is one clamp and one fetch. Knobs that control the
 * march's per-sample cost are grouped in the "Per-fragment constants" block.
 *
 * Writes gl_FragDepth so Three.js objects (markers, roads) depth-test naturally.
 */

export const rayMarchVertexShader = /* glsl */ `
  varying vec2 vUv;

  void main() {
    vUv = uv;
    gl_Position = vec4(position.xy, 0.0, 1.0);
  }
`;

export const rayMarchFragmentShader = /* glsl */ `
  precision highp float;

  // Enable gl_FragDepth on WebGL1 (natively available in WebGL2/GLSL 300 es)
  #ifdef GL_EXT_frag_depth
  #extension GL_EXT_frag_depth : enable
  #endif

  // ---- Uniforms ----
  uniform float uTime;
  uniform vec3 uCameraPosition;
  uniform mat4 uInvProjectionMatrix;
  uniform mat4 uInvViewMatrix;
  uniform mat4 uProjectionMatrix;
  uniform mat4 uViewMatrix;
  uniform float uLogDepthBufFC;

  // Terrain data
  uniform sampler2D uHeightmap;
  uniform sampler2D uRoadMap;
  uniform float uWorldSize;
  uniform float uMaxElevation;
  uniform float uWaterLevel;        // normalised [0,1] raw water level

  // Lighting
  uniform vec3 uSunDirection;
  uniform vec3 uSunColor;

  // Fog
  uniform vec3 uFogColor;
  uniform float uFogDensity;

  // Theme: 0=temperate, 1=desert, 2=arctic, 3=tropical
  uniform int uTheme;

  // Cultural regions
  uniform bool uShowEmpires;
  uniform int uCultureCount;
  uniform vec3 uCultureCenters[16];
  uniform vec3 uCultureColors[16];

  // Fraction of the remaining distance to the surface to advance per sample.
  const float MARCH_STEP = 0.45;
  // Extra advance per unit of distance travelled, which coarsens the far field.
  const float MARCH_DISTANCE_STEP = 0.0065;

  // Ray-march tuning
  uniform float uMaxRayDistance;
  uniform int uRayMarchSteps;
  uniform int uBinarySteps;
  uniform float uCloudHeight;

  // Highest reachable terrain surface (heightmap peak plus the hit threshold).
  // The CPU derives it from the heightmap so rays that cannot reach the ground
  // skip the march entirely.
  uniform float uHeightCeiling;

  varying vec2 vUv;

  // =========================================================
  //  Hoskins globals — ported directly
  // =========================================================
  float treeCol = 0.0;
  float specular = 0.0;
  float ambient = 0.0;
  vec2 add = vec2(1.0, 0.0);

  // =========================================================
  //  Per-fragment constants
  //  These come from uniforms only, so main() derives them once and the
  //  helpers below read them instead of repeating the same divisions on
  //  every sample of every ray-march step.
  // =========================================================
  float gWaterY;      // water surface height in world units
  float gHeightScale; // 280 / (maxElevation - waterY)
  float gTreeScale;   // 140 / (maxElevation - waterY)
  float gHalfWorld;
  float gInvWorld;    // 1 / uWorldSize, so the sample path multiplies

  // =========================================================
  //  Hash + Noise — from Hoskins
  // =========================================================

  float Hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.x + p3.y) * p3.z);
  }

  vec2 Hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.xx + p3.yz) * p3.zy);
  }

  float Noise(vec2 x) {
    vec2 p = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float res = mix(
      mix(Hash12(p),          Hash12(p + add.xy), f.x),
      mix(Hash12(p + add.yx), Hash12(p + add.xx), f.x),
      f.y
    );
    return res;
  }

  vec2 Noise2(vec2 x) {
    vec2 p = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    vec2 res = mix(
      mix(Hash22(p),          Hash22(p + add.xy), f.x),
      mix(Hash22(p + add.yx), Hash22(p + add.xx), f.x),
      f.y
    );
    return res;
  }

  // FBM for clouds (Hoskins)
  float FractalNoise(vec2 xy) {
    float w = 0.7;
    float f = 0.0;
    for (int i = 0; i < 4; i++) {
      f += Noise(xy) * w;
      w *= 0.5;
      xy *= 2.7;
    }
    return f;
  }

  // =========================================================
  //  Heightmap sampling
  // =========================================================

  // The heightmap arrives already scaled to world units with the border fade
  // applied, so this costs one clamp and one texture fetch. The ray march calls
  // it dozens of times per pixel, and the fade used to cost two smoothsteps and
  // an extra multiply on every one of those calls.
  float SampleHeightmap(vec2 worldXZ) {
    if (abs(worldXZ.x) > gHalfWorld || abs(worldXZ.y) > gHalfWorld) {
      return 0.0;
    }
    vec2 uv = (worldXZ + gHalfWorld) * gInvWorld;
    uv = clamp(uv, 0.0005, 0.9995);
    return texture2D(uHeightmap, uv).r;
  }

  // =========================================================
  //  Terrain height functions — heightmap-direct (linear)
  //  Backend generates Hoskins terrain into the heightmap,
  //  so no procedural noise overlay needed here.
  // =========================================================

  // Terrain height from heightmap (linear — no pow curve)
  float Terrain(vec2 p) {
    return SampleHeightmap(p);
  }

  // Distance from the sample to the ground, which is all the march needs.
  //
  // Hoskins' version also adds the canopy, a 13-cycle-per-unit noise that raises
  // the ground by at most 1% of the elevation range. The march deliberately does
  // not: the bump is smaller than a pixel almost everywhere, so marching it only
  // adds three noise fields per sample and forces short steps over ground that is
  // otherwise smooth. Terrain2 keeps it where it is visible, in the shaded
  // normal, so forests still look bumpy.
  float Map(vec3 p) {
    return p.y - Terrain(p.xz);
  }

  // High-detail terrain for normal computation: heightmap plus canopy.
  // This is the only place the canopy bump survives, so the shaded surface keeps
  // its bumpy forests while the marched surface stays smooth.
  float Terrain2(vec2 p) {
    float h = SampleHeightmap(p);
    float hForTree = (h - gWaterY) * gTreeScale;
    float ff = Noise(p * 0.3) + Noise(p * 3.3) * 0.5;
    float localTreeLine = smoothstep(ff * 2.0, ff * 2.0 + 10.0, hForTree)
                        * smoothstep(ff * 3.0 + 70.0, ff * 3.0 + 45.0, hForTree);
    treeCol = Noise(p * 13.0) * localTreeLine;
    return h + treeCol * uMaxElevation * 0.01;
  }

  // =========================================================
  //  Terrain normal — Hoskins' cross-product method
  //  with distance-adaptive epsilon for anti-aliasing
  // =========================================================
  vec3 TerrainNormal(vec2 p, float dist) {
    float eps = 0.02 + 0.003 * dist;
    vec3 nor = vec3(0.0,        Terrain2(p), 0.0);
    vec3 v2  = nor - vec3(eps,  Terrain2(p + vec2(eps, 0.0)), 0.0);
    vec3 v3  = nor - vec3(0.0,  Terrain2(p + vec2(0.0, -eps)), -eps);
    return normalize(cross(v2, v3));
  }

  // =========================================================
  //  Sky — enhanced atmosphere with Rayleigh-like gradient
  // =========================================================
  vec3 GetSky(vec3 rd) {
    float sunAmount = max(dot(rd, uSunDirection), 0.0);
    float altitude = max(rd.y, 0.0);

    // Zenith-to-horizon gradient: deep blue at top, warm haze at horizon
    vec3 zenith  = vec3(0.06, 0.16, 0.55);
    vec3 horizon = vec3(0.25, 0.33, 0.55);
    // Rayleigh-like scatter: warm tint towards sun direction at horizon
    vec3 sunHaze = vec3(0.40, 0.30, 0.20) * pow(sunAmount, 2.0) * 0.25;
    horizon += sunHaze;

    float blend = pow(1.0 - altitude, 4.0);
    vec3 sky = mix(zenith, horizon, blend);

    // Wide glare corona around sun — softer than before
    sky += uSunColor * pow(sunAmount, 5.0) * 0.22;
    // Tighter inner corona
    sky += uSunColor * pow(sunAmount, 32.0) * 0.15;
    // Sun disk
    sky += uSunColor * min(pow(sunAmount, 1150.0), 0.3) * 0.65;

    return sky;
  }

  // =========================================================
  //  Clouds — wind-animated with soft edges
  // =========================================================
  vec3 GetClouds(vec3 sky, vec3 rd, vec3 ro) {
    if (rd.y < 0.01) return sky;
    float v = (uCloudHeight - ro.y) / rd.y;
    vec2 cloudP = rd.xz * v + ro.xz;
    // Wind drift — slow eastward movement
    cloudP += vec2(uTime * 1.2, uTime * 0.4);
    cloudP *= 0.010;
    float f = (FractalNoise(cloudP) - 0.55) * 5.0;
    // Softer blend — cloud edges fade more naturally
    float cloudMask = smoothstep(-0.1, 0.6, f * rd.y - 0.1);
    // Lit side vs shadow side
    vec3 cloudBright = vec3(0.60, 0.60, 0.58);
    vec3 cloudDark   = vec3(0.42, 0.42, 0.44);
    vec3 cloudCol = mix(cloudDark, cloudBright, clamp(f * 0.3, 0.0, 1.0));
    sky = mix(sky, cloudCol, cloudMask);
    return sky;
  }

  // =========================================================
  //  Fog — distance + height-based (valley mist)
  // =========================================================
  vec3 ApplyFog(vec3 rgb, float disSqrd, vec3 dir) {
    // Scale fog inversely with camera altitude so zoomed-out views stay clear.
    float sceneScale = uWorldSize / 200.0;
    float camAlt = max(uCameraPosition.y / sceneScale, 1.0);
    float fogScale = clamp(40.0 / camAlt, 0.0, 1.0);

    // Distance fog — very subtle, only kicks in at extreme range
    float distFog = exp(-disSqrd / (sceneScale * sceneScale) * uFogDensity * fogScale);

    // Light height fog — slight mist in valleys below water level
    float waterY = gWaterY;
    float fogCeiling = waterY + 10.0 * sceneScale;
    float hitY = uCameraPosition.y + dir.y * sqrt(disSqrd);
    float heightFactor = smoothstep(fogCeiling, waterY - 5.0 * sceneScale, hitY);
    float heightFog = 1.0 - heightFactor * 0.15 * fogScale;

    float fogAmount = distFog * heightFog;

    // Subtle aerial perspective tint
    vec3 fogCol = mix(uFogColor, vec3(0.55, 0.62, 0.72), heightFactor * 0.15);

    return mix(fogCol, rgb, fogAmount);
  }

  // =========================================================
  //  Ambient occlusion — curvature-based from heightmap samples
  //  Compares local height to nearby average to darken valleys
  // =========================================================
  float TerrainAO(vec3 pos, vec3 normal) {
    // Sample terrain at small offsets along the normal direction
    // If nearby terrain is higher, this point is in a concavity (darkened)
    float ao = 0.0;
    float scale = 1.5;  // world-space sample radius
    for (int i = 0; i < 3; i++) {
      float h = float(i + 1) * scale;
      vec3 samplePos = pos + normal * h;
      float terrainH = Terrain(samplePos.xz);
      // How much the actual terrain overshoots our sample ray
      float diff = max(samplePos.y - terrainH, 0.0);
      ao += diff / h;
    }
    ao = ao / 3.0;
    // Map to 0-1: 0 = fully occluded, 1 = fully open
    return clamp(ao * 2.0, 0.15, 1.0);
  }

  // =========================================================
  //  Cloud shadow — project terrain pos to cloud plane and sample FBM
  // =========================================================
  float CloudShadow(vec3 pos) {
    // Project from terrain surface to cloud plane along sun direction
    float dY = uCloudHeight - pos.y;
    float sunUp = max(uSunDirection.y, 0.05);
    vec2 cloudP = pos.xz + uSunDirection.xz * (dY / sunUp);
    cloudP *= 0.010;  // same scale as GetClouds
    float f = (FractalNoise(cloudP) - 0.55) * 5.0;
    return clamp(1.0 - clamp(f, 0.0, 1.0) * 0.45, 0.55, 1.0);
  }

  // =========================================================
  //  Lighting — hemisphere ambient + sun + rim + cloud shadows + AO
  // =========================================================
  void DoLighting(inout vec3 mat, vec3 pos, vec3 normal, vec3 eyeDir, float disSqrd) {
    // Cloud shadow
    float shadow = CloudShadow(pos);

    // Ambient occlusion — darken valleys, brighten ridges
    float ao = TerrainAO(pos, normal);

    // Direct sun — half-Lambert for softer shadow terminator
    float NdotL = dot(uSunDirection, normal);
    float halfLambert = NdotL * 0.5 + 0.5;
    halfLambert = halfLambert * halfLambert;
    float directLight = mix(max(NdotL, 0.0), halfLambert, 0.35);
    directLight *= shadow;

    // Hemisphere ambient — sky blue from above, warm ground bounce from below
    // ambient scalar (0.1 base, boosted by snow/roads) scales the hemisphere proportionally
    // AO primarily affects ambient light (indirect illumination)
    vec3 skyAmbient   = vec3(0.14, 0.17, 0.24);
    vec3 groundBounce = vec3(0.10, 0.08, 0.05);
    float upFactor = normal.y * 0.5 + 0.5;
    vec3 hemiAmbient = mix(groundBounce, skyAmbient, upFactor) * (1.0 + ambient * 2.5) * ao;

    vec3 lit = mat * (uSunColor * directLight + hemiAmbient);

    // Rim/backlight — highlights terrain silhouettes against sky
    vec3 viewDir = normalize(eyeDir);
    float rim = 1.0 - max(dot(normal, -viewDir), 0.0);
    rim = pow(rim, 3.0) * 0.12;
    // Rim color tinted by sky
    lit += vec3(0.18, 0.22, 0.30) * rim * shadow;

    // Specular
    if (NdotL > 0.0) {
      vec3 R = reflect(uSunDirection, normal);
      float specAmount = pow(max(dot(R, normalize(eyeDir)), 0.0), 3.0) * specular * shadow;
      lit = mix(lit, uSunColor, specAmount);
    }

    mat = lit;
  }

  // =========================================================
  //  Cultural region overlay (Voronoi with soft border blending)
  // =========================================================
  vec3 ApplyCulturalOverlay(vec3 color, vec2 worldXZ) {
    if (!uShowEmpires || uCultureCount <= 0) return color;

    float minDist = 1e10;
    float secondDist = 1e10;
    int nearestIdx = 0;
    int secondIdx = 0;
    for (int i = 0; i < 16; i++) {
      if (i >= uCultureCount) break;
      vec2 delta = worldXZ - uCultureCenters[i].xy;
      float d = dot(delta, delta);
      if (d < minDist) {
        secondDist = minDist;
        secondIdx = nearestIdx;
        minDist = d;
        nearestIdx = i;
      } else if (d < secondDist) {
        secondDist = d;
        secondIdx = i;
      }
    }

    float d1 = sqrt(minDist);
    float d2 = sqrt(secondDist);

    // Blend zone: where the two nearest regions are close in distance
    // ratio → 0 means right on the border, ratio → 1 means deep inside nearest region
    float ratio = clamp((d2 - d1) / max(d1 + d2, 0.01), 0.0, 1.0);

    // Smooth transition — full overlay deep inside, fading near borders
    float strength = smoothstep(0.0, 0.12, ratio) * 0.35;

    // Blend between nearest and second-nearest colors at the border
    float borderBlend = 1.0 - smoothstep(0.0, 0.06, ratio);
    vec3 regionCol = mix(uCultureColors[nearestIdx], uCultureColors[secondIdx], borderBlend * 0.4);

    return mix(color, regionCol, strength);
  }

  // =========================================================
  //  Terrain colour — from Hoskins (noise-driven materials)
  // =========================================================
  vec3 TerrainColour(vec3 pos, vec3 normal, float dis) {
    vec3 mat;
    specular = 0.0;
    ambient = 0.1;
    vec3 dir = normalize(pos - uCameraPosition);

    // Map terrain height to Hoskins' matPos range:
    // water level → matPos.y = 0, peaks → matPos.y = 280
    // x/z doubled to match Hoskins' original texture scale
    float waterWorldY = gWaterY;
    float hScale = gHeightScale;
    vec3 matPos = vec3(pos.x * 2.0, (pos.y - waterWorldY) * hScale, pos.z * 2.0);

    float disSqrd = dis * dis;

    // Base rock/earth colour from noise
    float f = clamp(Noise(matPos.xz * 0.05), 0.0, 1.0);
    f += Noise(matPos.xz * 0.1 + normal.yz * 1.08) * 0.85;
    f *= 0.55;
    vec3 m = mix(
      vec3(0.63 * f + 0.2, 0.7 * f + 0.1, 0.7 * f + 0.1),
      vec3(f * 0.43 + 0.1, f * 0.3 + 0.2, f * 0.35 + 0.1),
      f * 0.65
    );
    mat = m * vec3(f * m.x + 0.36, f * m.y + 0.30, f * m.z + 0.28);

    // Theme-specific rock tint
    if (uTheme == 1)      mat *= vec3(1.30, 1.10, 0.80);  // Desert: warm sandstone
    else if (uTheme == 2) mat *= vec3(0.85, 0.90, 1.05);  // Arctic: cool blue-gray

    // Cliff/rock faces — stratified layers with erosion (steepest slopes, normal.y < 0.5)
    if (normal.y < 0.5) {
      float c = (0.5 - normal.y) * 4.0;
      c = clamp(c * c, 0.1, 1.0);

      // Base cliff noise
      f = Noise(vec2(matPos.x * 0.09, matPos.z * 0.095 + matPos.y * 0.15));
      f += Noise(vec2(matPos.x * 2.233, matPos.z * 2.23)) * 0.5;

      // Horizontal sedimentary layering — bands of lighter/darker rock
      float layerFreq = matPos.y * 0.35;
      float layers = sin(layerFreq) * 0.5 + 0.5;
      layers *= sin(layerFreq * 2.7 + Noise(matPos.xz * 0.2) * 3.0) * 0.5 + 0.5;
      layers = layers * 0.3 + 0.7;  // subtle 0.7-1.0 variation

      // Vertical erosion channels — streaks down cliff face
      float erosion = Noise(vec2(matPos.x * 0.3 + matPos.z * 0.3, matPos.y * 0.08));
      erosion = smoothstep(0.4, 0.6, erosion) * 0.15;

      vec3 cliffLight = vec3(0.48, 0.44, 0.40);
      vec3 cliffDark  = vec3(0.30, 0.28, 0.26);
      vec3 cliffColor = mix(cliffDark, cliffLight, f * layers);
      cliffColor -= erosion;  // darken erosion channels

      mat = mix(mat, cliffColor, c);
      specular += 0.1;
    }

    // Scree/gravel on moderate slopes (normal.y 0.50-0.65) — rocky debris
    if (normal.y >= 0.50 && normal.y < 0.68) {
      float screeBlend = smoothstep(0.50, 0.56, normal.y) * smoothstep(0.68, 0.60, normal.y);
      float sn = Noise(matPos.xz * 0.4) * 0.5 + Noise(matPos.xz * 2.0) * 0.3 + Noise(matPos.xz * 8.0) * 0.2;
      vec3 screeCol = mix(vec3(0.35, 0.32, 0.28), vec3(0.42, 0.38, 0.33), sn);
      // Theme tint
      if (uTheme == 1)      screeCol *= vec3(1.20, 1.05, 0.85);  // Desert: sandy gravel
      else if (uTheme == 2) screeCol *= vec3(0.90, 0.92, 1.00);  // Arctic: frosty
      mat = mix(mat, screeCol, screeBlend * 0.7);
    }

    // Grass on flatter ground — with wind sway, moisture variation, wildflower patches
    if (matPos.y < 78.0 && normal.y > 0.65) {
      // Theme-specific grass palette
      vec3 grassBase, grassScale;
      if (uTheme == 1) {        // Desert: golden sand
        grassBase = vec3(0.28, 0.20, 0.06);
        grassScale = vec3(0.30, 0.18, 0.08);
      } else if (uTheme == 2) { // Arctic: muted gray-green tundra
        grassBase = vec3(0.16, 0.18, 0.14);
        grassScale = vec3(0.15, 0.18, 0.12);
      } else if (uTheme == 3) { // Tropical: bright lush green
        grassBase = vec3(0.08, 0.32, 0.02);
        grassScale = vec3(0.20, 0.35, 0.08);
      } else {                   // Temperate
        grassBase = vec3(0.15, 0.25, 0.0);
        grassScale = vec3(0.50, 0.60, 0.0);
      }
      m = vec3(
        Noise(matPos.xz * 0.023) * grassScale.x + grassBase.x,
        Noise(matPos.xz * 0.03) * grassScale.y + grassBase.y,
        Noise(matPos.xz * 0.02) * grassScale.z + grassBase.z
      );

      // Wind sway — time-varying brightness simulating blades catching light
      float windSway = Noise(matPos.xz * 0.15 + vec2(uTime * 0.8, uTime * 0.3));
      windSway = windSway * 0.15 + 0.92;  // subtle 0.92-1.07 variation
      m *= windSway;

      // Moisture gradient — darker lush green in low areas, drier pale on ridges
      float moistureFade = smoothstep(0.0, 60.0, matPos.y);
      m = mix(m * 1.1, m * 0.85, moistureFade);

      // Wildflower patches (temperate & tropical only) — warm color dots
      if (uTheme == 0 || uTheme == 3) {
        float flowerNoise = Noise(matPos.xz * 3.5);
        float flowers = smoothstep(0.72, 0.82, flowerNoise) * 0.08;
        vec3 flowerCol = (uTheme == 3)
          ? vec3(0.35, 0.12, 0.20)   // tropical: pink/magenta
          : vec3(0.30, 0.25, 0.05);  // temperate: golden yellow
        m += flowerCol * flowers;
      }

      m *= (normal.y - 0.65) * 0.75;
      mat = mix(mat, m, clamp((normal.y - 0.65) * 1.3 * (78.0 - matPos.y) * 0.06, 0.0, 1.0));
    }

    // Save terrain normal before trees perturb it (roads need the smooth original)
    vec3 terrainNormal = normal;

    // Trees — theme-specific forest canopy with top-lighting and subsurface scatter
    if (treeCol > 0.0) {
      // Base canopy color by theme
      vec3 treeBase, treeHighlight;
      if (uTheme == 1) {
        treeBase = vec3(0.06, 0.07, 0.02);       // Desert: olive/sparse
        treeHighlight = vec3(0.12, 0.11, 0.04);
      } else if (uTheme == 2) {
        treeBase = vec3(0.01, 0.04, 0.04);       // Arctic: dark spruce
        treeHighlight = vec3(0.04, 0.08, 0.07);
      } else if (uTheme == 3) {
        treeBase = vec3(0.01, 0.12, 0.03);       // Tropical: bright canopy
        treeHighlight = vec3(0.05, 0.22, 0.06);
      } else {
        treeBase = vec3(0.03, 0.08, 0.02);       // Temperate
        treeHighlight = vec3(0.08, 0.16, 0.04);
      }

      // Canopy noise — varied color within forest
      float canopyNoise = Noise(matPos.xz * 5.0) * 0.5 + Noise(matPos.xz * 1.2) * 0.5;
      mat = mix(treeBase, treeHighlight, canopyNoise);

      // Top-lighting — canopy tops lit by sun, undersides dark
      float sunTop = max(dot(normal, uSunDirection), 0.0);
      mat += treeHighlight * sunTop * 0.3;

      // Subsurface scattering approximation — light passing through thin leaves
      // Strongest when sun is behind the canopy relative to view
      float sss = pow(clamp(dot(dir, uSunDirection), 0.0, 1.0), 3.0) * 0.15;
      vec3 sssCol = (uTheme == 3) ? vec3(0.08, 0.25, 0.05) : vec3(0.06, 0.15, 0.02);
      mat += sssCol * sss;

      // Perturb normal for bumpy canopy texture
      normal = normalize(normal + vec3(
        Noise(matPos.xz * 33.0) * 1.0 - 0.5,
        0.0,
        Noise(matPos.xz * 33.0 + vec2(100.0, 0.0)) * 1.0 - 0.5
      ));
      specular = 0.02;  // slight leaf sheen
    }

    // Snow-topped mountains — threshold varies by theme
    float snowThreshold = 80.0;
    if (uTheme == 1)      snowThreshold = 180.0;  // Desert: almost no snow
    else if (uTheme == 2) snowThreshold = 30.0;   // Arctic: snow at low elevation
    else if (uTheme == 3) snowThreshold = 220.0;  // Tropical: effectively none
    if (matPos.y > snowThreshold && normal.y > 0.35) {
      // Base snow coverage — fades in above threshold, stronger on flatter ground
      float elevFactor = clamp((matPos.y - snowThreshold - Noise(matPos.xz * 0.1) * 20.0) * 0.04, 0.0, 1.0);
      float slopeFactor = smoothstep(0.35, 0.65, normal.y);
      float snow = elevFactor * slopeFactor;

      // Wind-blown streaks — directional noise oriented NE
      vec2 windDir = vec2(0.7, 0.7);
      float windStreak = Noise(vec2(dot(matPos.xz, windDir) * 0.15, dot(matPos.xz, vec2(-windDir.y, windDir.x)) * 0.6));
      windStreak = windStreak * 0.3 + 0.7;  // subtle variation, not patchy
      snow *= windStreak;

      // Exposed rock patches on steeper ground — snow doesn't stick as well
      float rockExposure = smoothstep(0.65, 0.45, normal.y) * Noise(matPos.xz * 0.5) * 0.5;
      snow = max(snow - rockExposure, 0.0);

      // Snow color — not pure white, slight blue tint in shadows, warm in sun
      vec3 snowBase = vec3(0.72, 0.73, 0.80);
      vec3 snowSunlit = vec3(0.82, 0.80, 0.75);
      float sunFacing = max(dot(normal, uSunDirection), 0.0);
      vec3 snowCol = mix(snowBase, snowSunlit, sunFacing * 0.5);

      // Ice crystal sparkle — view-angle-dependent glints
      float sparkleNoise = Noise(matPos.xz * 40.0 + matPos.xz * 17.3);
      float sparkle = pow(sparkleNoise, 8.0) * snow * sunFacing * 0.4;

      mat = mix(mat, snowCol, snow);
      mat += vec3(sparkle);
      specular += snow * 0.8;
      ambient += snow * 0.25;
    }

    // Beach zone — narrow sandy coastline hugging the waterline
    if (matPos.y < 8.0 && normal.y > 0.4) {
      float flatness = smoothstep(0.4, 0.75, normal.y);

      // Underwater sandy seabed — visible through shallow water refraction
      float seabedBlend = smoothstep(-6.0, -2.0, matPos.y) * smoothstep(1.0, -1.0, matPos.y) * flatness;
      // Above-water beach — tight band near waterline only
      float beachBlend = smoothstep(8.0, 2.0, matPos.y) * smoothstep(-1.0, 1.0, matPos.y) * flatness;

      float totalBeach = max(seabedBlend, beachBlend);

      if (totalBeach > 0.01) {
        // Sand noise — multi-scale grain pattern
        float sandNoise = Noise(matPos.xz * 0.3) * 0.12 + Noise(matPos.xz * 2.0) * 0.06;

        // Dune ripple patterns — wind-driven
        vec2 duneDir = vec2(0.8, 0.6);
        float duneRipple = sin(dot(matPos.xz, duneDir) * 2.5 + Noise(matPos.xz * 0.3) * 4.0) * 0.5 + 0.5;
        duneRipple *= 0.06;

        // Dry sand — warm golden
        vec3 drySand = vec3(0.60, 0.50, 0.34) + sandNoise + duneRipple;

        // Wet sand near waterline — darker, compact
        vec3 wetSand = vec3(0.35, 0.30, 0.20);

        // Underwater seabed — darker sandy, tinted by water absorption
        vec3 seabedSand = vec3(0.30, 0.26, 0.18);

        // Blend wet↔dry based on elevation above waterline
        float wetness = smoothstep(3.0, 0.0, matPos.y);
        vec3 sandCol = mix(drySand, wetSand, wetness);

        // Blend to seabed for underwater portions
        float underwaterFade = smoothstep(0.0, -4.0, matPos.y);
        sandCol = mix(sandCol, seabedSand, underwaterFade);

        // Scattered debris/shells on dry sand
        float debris = pow(Noise(matPos.xz * 12.0), 6.0) * 0.15 * beachBlend;
        sandCol += debris;

        mat = mix(mat, sandCol, totalBeach);
        specular = mix(specular, wetness * 0.12, totalBeach);
      }
    }

    // Gradual underwater darkening by depth — starts deeper to preserve sandy seabed
    if (matPos.y < -4.0) {
      float depthFade = clamp((-matPos.y - 4.0) * 0.05, 0.0, 0.80);
      mat *= 1.0 - depthFade;
    }

    // Road surface overlay — sampled from rasterized road map texture.
    // Road intensity encodes type: ~1.0 = main road, ~0.5 = trail.
    {
      vec2 roadUV = (pos.xz + gHalfWorld) / uWorldSize;
      roadUV = clamp(roadUV, 0.0, 1.0);
      float roadVal = texture2D(uRoadMap, roadUV).r;

      if (roadVal > 0.04) {
        bool isMain = roadVal > 0.6;

        // Restore smooth terrain normal for road surfaces (trees perturb it)
        normal = mix(normal, terrainNormal, smoothstep(0.04, 0.22, roadVal));

        // Road surface colour by terrain elevation zone
        vec3 roadCol;
        float rn = Noise(matPos.xz * 0.15) * 0.06 + Noise(matPos.xz * 1.5) * 0.03;

        if (matPos.y > 80.0) {
          // Snow zone: packed snow with gravel
          roadCol = isMain ? vec3(0.52, 0.52, 0.57) : vec3(0.58, 0.58, 0.63);
        } else if (matPos.y > 60.0) {
          // Rocky hills: worn stone path
          roadCol = isMain ? vec3(0.40, 0.37, 0.33) : vec3(0.43, 0.40, 0.36);
        } else if (matPos.y < 1.45) {
          // Beach: sandy track
          roadCol = vec3(0.50, 0.42, 0.30);
        } else {
          // Grass / forest: dirt road
          roadCol = isMain ? vec3(0.32, 0.22, 0.12) : vec3(0.36, 0.27, 0.16);
        }

        roadCol += rn;

        // Subtle edge darkening (worn shoulders)
        float edgeDarken = smoothstep(0.35, 0.12, roadVal) * 0.12;
        roadCol *= 1.0 - edgeDarken;

        // Anti-aliased blend at road edges
        float roadAlpha = smoothstep(0.04, 0.22, roadVal) * 0.88;
        mat = mix(mat, roadCol, roadAlpha);

        // Roads are matte — reduce specular, slightly raise ambient
        specular *= 1.0 - roadAlpha * 0.7;
        ambient += roadAlpha * 0.05;
      }
    }

    DoLighting(mat, pos, normal, dir, disSqrd);

    // Cultural region overlay
    mat = ApplyCulturalOverlay(mat, pos.xz);

    mat = ApplyFog(mat, disSqrd, dir);
    return mat;
  }

  // =========================================================
  //  Fresnel — Schlick approximation
  // =========================================================
  float FresnelSchlick(float cosTheta, float f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
  }

  // =========================================================
  //  Water rendering — Fresnel, depth color, multi-scale waves
  // =========================================================
  vec3 RenderWater(vec3 ro, vec3 rd, vec3 terrainPos, vec3 terrainMat, float dis) {
    float waterY = gWaterY;
    float hScale = gHeightScale;
    vec3 matPos = vec3(terrainPos.x * 2.0, (terrainPos.y - waterY) * hScale, terrainPos.z * 2.0);

    // Project terrain position to water surface in WORLD space
    float safeDirY = abs(rd.y) > 0.000001 ? rd.y : (rd.y >= 0.0 ? 0.000001 : -0.000001);
    float waterDist = (waterY - ro.y) / safeDirY;
    vec3 worldWatPos = ro + rd * waterDist;
    vec3 watPos = vec3(worldWatPos.x * 2.0, 0.0, worldWatPos.z * 2.0);

    float time = uTime * 0.03;

    // Multi-scale wave normals — large swells + medium chop + fine ripples
    float tx = cos(watPos.x * 0.052) * 4.5;
    float tz = sin(watPos.z * 0.072) * 4.5;
    // Large swell
    vec2 co = Noise2(vec2(watPos.x * 4.7 + 1.3 + tz, watPos.z * 4.69 + time * 35.0 - tx));
    // Medium chop
    co += Noise2(vec2(watPos.z * 8.6 + time * 13.0 - tx, watPos.x * 8.712 + tz)) * 0.4;
    // Fine ripples (attenuated by distance for LOD)
    float rippleLOD = clamp(1.0 - dis * 0.003, 0.0, 1.0);
    co += Noise2(vec2(watPos.x * 18.3 + time * 22.0, watPos.z * 17.8 - time * 18.0)) * 0.15 * rippleLOD;

    vec3 waterNormal = normalize(vec3(co.x, 20.0, co.y));
    vec3 reflDir = normalize(reflect(rd, waterNormal));

    // Fresnel — reflective at grazing angles, capped to keep water dark
    float NdotV = abs(dot(waterNormal, -rd));
    float fresnel = FresnelSchlick(NdotV, 0.02) * 0.55;

    // Depth in Hoskins space
    float depth = max(watPos.y - matPos.y, 0.0);

    // Depth-based water color: shallow turquoise → mid teal → deep dark blue
    vec3 shallowCol = vec3(0.04, 0.16, 0.30);
    vec3 midCol     = vec3(0.02, 0.08, 0.24);
    vec3 deepCol    = vec3(0.01, 0.03, 0.16);
    float shallowBlend = clamp(depth * 0.15, 0.0, 1.0);
    float deepBlend    = clamp((depth - 4.0) * 0.08, 0.0, 1.0);
    vec3 waterBodyCol = mix(shallowCol, midCol, shallowBlend);
    waterBodyCol = mix(waterBodyCol, deepCol, deepBlend);

    // Refracted view: sandy seabed visible through shallow water, fading with depth
    float refractVisibility = clamp(1.0 - depth * 0.15, 0.0, 0.55);
    vec3 refractedCol = mix(waterBodyCol, terrainMat * 0.55, refractVisibility);

    // Reflected sky + clouds
    vec3 skyReflection = GetClouds(GetSky(reflDir), reflDir, ro);

    // Combine reflection and refraction via Fresnel
    vec3 waterCol = mix(refractedCol, skyReflection, fresnel);

    // Sun specular — tight highlight, reduced intensity
    vec3 halfVec = normalize(uSunDirection - rd);
    float specAngle = max(dot(waterNormal, halfVec), 0.0);
    float sunSpec = pow(specAngle, 256.0) * 0.6;
    sunSpec += pow(specAngle, 32.0) * 0.08;
    waterCol += uSunColor * sunSpec * (0.2 + fresnel * 0.4);

    // Thin animated surf line — narrow wave break at the water's edge
    if (depth < 2.0) {
      // Tight band concentrated at the very shoreline
      float surfLine = smoothstep(2.0, 0.3, depth) * smoothstep(0.0, 0.15, depth);
      // Animated wave break — rolls in and out
      float wavePhase = sin(uTime * 1.5 + dot(watPos.xz, vec2(0.3, 0.7)) * 0.4) * 0.5 + 0.5;
      float surfNoise = Noise(watPos.xz * 1.5 + vec2(uTime * 4.0, uTime * 2.5));
      float surf = surfLine * smoothstep(0.2, 0.5, surfNoise) * (0.6 + wavePhase * 0.4);
      // Light aqua surf — not white
      waterCol = mix(waterCol, vec3(0.55, 0.62, 0.72), surf * 0.35);
    }

    // Cloud shadow on water surface
    float shadow = CloudShadow(worldWatPos);
    waterCol *= 0.7 + shadow * 0.3;

    // Cultural overlay
    waterCol = ApplyCulturalOverlay(waterCol, terrainPos.xz);

    float disSqrd = dis * dis;
    waterCol = ApplyFog(waterCol, disSqrd, rd);
    return waterCol;
  }

  // =========================================================
  //  ACES-approximation filmic tone mapping (Narkowicz 2015)
  // =========================================================
  vec3 ACESFilm(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
  }

  // =========================================================
  //  Post-processing — ACES tone mapping + vignette
  // =========================================================
  vec3 PostEffects(vec3 color) {
    // Exposure adjustment — slightly brighter than before to feed ACES curve
    color *= 1.8;
    // ACES filmic curve — better contrast, richer colors, natural highlight rolloff
    color = ACESFilm(color);
    // Subtle vignette — darkens corners for cinematic feel
    vec2 uv = vUv * 2.0 - 1.0;
    float vignette = 1.0 - dot(uv, uv) * 0.12;
    color *= vignette;
    return color;
  }

  // Clip the ray to each horizontal slab before marching. Parallel rays
  // outside a slab miss it; no epsilon divisor can invent an intersection.
  bool ClipMapAxis(float origin, float direction, float halfExtent,
                   inout float entry, inout float exit) {
    if (abs(direction) < 0.0000001) return abs(origin) <= halfExtent;
    float a = (-halfExtent - origin) / direction;
    float b = ( halfExtent - origin) / direction;
    entry = max(entry, min(a, b));
    exit = min(exit, max(a, b));
    return entry <= exit;
  }

  // =========================================================
  //  Ray march scene
  // =========================================================
  void main() {
    // Per-fragment constants derived from uniforms. Every helper below reads
    // these instead of repeating the same divisions on every march sample.
    gWaterY = uWaterLevel * uMaxElevation;
    float heightRange = max(uMaxElevation - gWaterY, 1.0);
    gHeightScale = 280.0 / heightRange;
    gTreeScale = 140.0 / heightRange;
    gHalfWorld = uWorldSize * 0.5;
    gInvWorld = 1.0 / uWorldSize;

    // Reconstruct ray direction from screen UV via inverse projection
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 clipPos = vec4(ndc, -1.0, 1.0);
    vec4 viewPos = uInvProjectionMatrix * clipPos;
    viewPos.xyz /= viewPos.w;
    vec3 viewDir = normalize(viewPos.xyz);
    vec3 rd = normalize((uInvViewMatrix * vec4(viewDir, 0.0)).xyz);

    vec3 ro = uCameraPosition;

    float halfWorld = gHalfWorld;
    float waterY = gWaterY;

    // --- Sky fast path: a ray above every surface that never descends cannot
    // hit terrain or water, so skip the march and the depth buffer entirely.
    if (ro.y >= uHeightCeiling && rd.y >= 0.0) {
      vec3 skyColor = GetSky(rd);
      skyColor = GetClouds(skyColor, rd, ro);
      skyColor = PostEffects(skyColor);
      gl_FragDepth = 1.0;
      gl_FragColor = vec4(skyColor, 1.0);
      return;
    }

    // Only the finite map contains terrain. Previously the padded box and
    // the zero height outside the texture created phantom ground at corners,
    // especially when a march step jumped over the entire bounds interval.
    float tStart = 0.0;
    float tExit = uMaxRayDistance;
    bool intersectsMap = ClipMapAxis(ro.x, rd.x, halfWorld, tStart, tExit);
    intersectsMap = ClipMapAxis(ro.z, rd.z, halfWorld, tStart, tExit) && intersectsMap;
    if (!intersectsMap) {
      vec3 skyColor = PostEffects(GetClouds(GetSky(rd), rd, ro));
      gl_FragDepth = 1.0;
      gl_FragColor = vec4(skyColor, 1.0);
      return;
    }

    // --- Ray march (Hoskins Scene function) ---
    float t = tStart;
    float oldT = tStart;
    bool hit = false;
    vec2 distances;

    for (int i = 0; i < 150; i++) {
      if (i >= uRayMarchSteps) break;
      if (t > tExit) break;

      vec3 p = ro + rd * t;

      float h = Map(p);

      // Hit detection (Hoskins: h < 0.5)
      if (h < 0.5) {
        hit = true;
        distances = vec2(oldT, t);
        break;
      }

      // Adaptive step (from Hoskins)
      // The step multiplier is the sample-density knob. Hoskins tuned 0.3 for a
      // canopy-bumped surface; the smooth surface above takes 0.45 without
      // skipping ridges, which removes about a third of the samples.
      float delta = max(0.01, MARCH_STEP * h) + (t * MARCH_DISTANCE_STEP);
      oldT = t;
      t += delta;
    }

    // Binary subdivision for precision (Hoskins)
    float finalT = t;
    if (hit) {
      float lo = distances.x;
      float hi = distances.y;
      for (int i = 0; i < 8; i++) {
        if (i >= uBinarySteps) break;
        float mid = (lo + hi) * 0.5;
        float d = Map(ro + mid * rd);
        if (d < 0.5) {
          hi = mid;
        } else {
          lo = mid;
        }
      }
      finalT = (lo + hi) * 0.5;
    }

    // --- Independent intersection with the finite water surface ---
    bool waterHit = false;
    float waterT = 0.0;
    {
      float wY = gWaterY;
      if (ro.y > wY && rd.y < -0.001) {
        float wt = (wY - ro.y) / rd.y;
        if (wt > 0.0 && wt < uMaxRayDistance) {
          vec3 wPos = ro + rd * wt;
          if (abs(wPos.x) <= halfWorld && abs(wPos.z) <= halfWorld) {
            waterHit = true;
            waterT = wt;
          }
        }
      }
    }

    // --- Shading ---
    vec3 color;
    float depthT = finalT;
    bool wroteDepth = false;

    // Water and ground have separate intersections. Choose the nearest valid
    // surface instead of projecting an out-of-bounds seabed hit into water.
    bool visibleGround = hit && (!waterHit || finalT <= waterT);
    // A submerged seabed cannot appear beyond the finite water surface.
    if (visibleGround && (ro + finalT * rd).y < waterY && !waterHit) {
      visibleGround = false;
    }
    if (visibleGround) {
      vec3 pos = ro + finalT * rd;
      vec3 normal = TerrainNormal(pos.xz, finalT);
      color = TerrainColour(pos, normal, finalT);
      wroteDepth = true;
    } else if (waterHit) {
      vec3 wPos = ro + rd * waterT;
      vec3 seabed = hit ? ro + rd * finalT
        : vec3(wPos.x, SampleHeightmap(wPos.xz), wPos.z);
      vec3 normal = TerrainNormal(seabed.xz, waterT);
      vec3 seabedColor = TerrainColour(seabed, normal, waterT);
      color = RenderWater(ro, rd, seabed, seabedColor, waterT);
      depthT = waterT;
      wroteDepth = true;
    } else {
      color = GetSky(rd);
      color = GetClouds(color, rd, ro);
    }

    // Tone mapping
    color = PostEffects(color);

    // Write gl_FragDepth (logarithmic to match Three.js logarithmicDepthBuffer)
    if (wroteDepth) {
      vec3 hitWorld = ro + rd * depthT;
      vec4 clipP = uProjectionMatrix * uViewMatrix * vec4(hitWorld, 1.0);
      gl_FragDepth = log2(max(1e-6, 1.0 + clipP.w)) * uLogDepthBufFC * 0.5;
    } else {
      gl_FragDepth = 1.0;
    }

    gl_FragColor = vec4(color, 1.0);
  }
`;
