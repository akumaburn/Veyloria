/**
 * Custom GLSL shaders for terrain rendering.
 * Uses continuous height + slope-based coloring inspired by David Hoskins's "Mountains" Shadertoy.
 * Cultural region Voronoi overlay is blended via uniform arrays.
 *
 * IMPORTANT: Normal and height calculations MUST match rayMarchShaders.ts exactly.
 * We sample the heightmap texture to compute per-pixel normals matching the ray-marcher.
 */

export const terrainVertexShader = /* glsl */ `
  varying vec3 vWorldPosition;
  varying vec2 vUv;

  void main() {
    vec4 worldPos = modelMatrix * vec4(position, 1.0);
    vWorldPosition = worldPos.xyz;
    vUv = uv;

    gl_Position = projectionMatrix * viewMatrix * worldPos;
  }
`;

export const terrainFragmentShader = /* glsl */ `
  uniform float uWaterLevel;
  uniform float uMaxElevation;
  uniform float uWorldSize;
  uniform sampler2D uHeightmap;
  uniform sampler2D uRoadMap;
  uniform vec3 uSunDirection;
  uniform vec3 uSunColor;
  uniform vec3 uAmbientColor;

  // Theme: 0=temperate, 1=desert, 2=arctic, 3=tropical
  uniform int uTheme;

  uniform bool uShowEmpires;
  uniform int uCultureCount;
  uniform vec3 uCultureCenters[16];
  uniform vec3 uCultureColors[16];

  uniform vec3 uFogColor;
  uniform float uFogDensity;
  uniform vec3 uCameraPosition;

  varying vec3 vWorldPosition;
  varying vec2 vUv;

  // ----------------------------------------------------
  // Noise
  // ----------------------------------------------------

  float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.x + p3.y) * p3.z);
  }

  float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
  }

  float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
      v += a * noise2D(p);
      p *= 2.0;
      a *= 0.5;
    }
    return v;
  }

  // ----------------------------------------------------
  // Heightmap sampling - MUST match rayMarchShaders.ts SampleHeightmap
  // ----------------------------------------------------

  float sampleHeightmap(vec2 worldXZ) {
    float halfWorld = uWorldSize * 0.5;
    if (abs(worldXZ.x) > halfWorld || abs(worldXZ.y) > halfWorld) {
      return 0.0;
    }
    vec2 uv = (worldXZ + halfWorld) / uWorldSize;
    uv = clamp(uv, 0.0005, 0.9995);
    float h = texture2D(uHeightmap, uv).r;
    // Smooth edge falloff to prevent cliff artifacts at map boundaries
    float fadeWidth = halfWorld * 0.05;
    float edgeFade = smoothstep(halfWorld, halfWorld - fadeWidth, abs(worldXZ.x))
                   * smoothstep(halfWorld, halfWorld - fadeWidth, abs(worldXZ.y));
    return h * edgeFade;
  }

  // Terrain height in world units - matches rayMarchShaders.ts Terrain()
  float terrainHeight(vec2 worldXZ) {
    return sampleHeightmap(worldXZ) * uMaxElevation;
  }

  // Terrain2 includes tree contribution - matches rayMarchShaders.ts Terrain2()
  // Tree contribution via noise, matching Map() function
  float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.x + p3.y) * p3.z);
  }

  float noise(vec2 x) {
    vec2 p = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float res = mix(
      mix(hash12(p), hash12(p + vec2(1.0, 0.0)), f.x),
      mix(hash12(p + vec2(0.0, 1.0)), hash12(p + vec2(1.0, 1.0)), f.x),
      f.y
    );
    return res;
  }

  float trees(vec2 p) {
    return noise(p * 13.0);
  }

  float terrain2(vec2 p) {
    float h = sampleHeightmap(p) * uMaxElevation;
    float waterWorldY = uWaterLevel * uMaxElevation;
    float hForTree = (h - waterWorldY) * 140.0 / max(uMaxElevation - waterWorldY, 1.0);
    float ff = noise(p * 0.3) + noise(p * 3.3) * 0.5;
    float treeLine = smoothstep(ff * 2.0, ff * 2.0 + 10.0, hForTree)
                   * smoothstep(ff * 3.0 + 70.0, ff * 3.0 + 45.0, hForTree);
    float treeCol = trees(p) * treeLine;
    return h + treeCol * uMaxElevation * 0.01;
  }

  // Terrain normal via finite differences - matches rayMarchShaders.ts TerrainNormal()
  vec3 terrainNormal(vec2 p, float dist) {
    float eps = 0.02 + 0.003 * dist;
    vec3 nor = vec3(0.0, terrain2(p), 0.0);
    vec3 v2 = nor - vec3(eps, terrain2(p + vec2(eps, 0.0)), 0.0);
    vec3 v3 = nor - vec3(0.0, terrain2(p + vec2(0.0, -eps)), -eps);
    return normalize(cross(v2, v3));
  }

  // ----------------------------------------------------
  void main() {

    vec2 worldXZ = vWorldPosition.xz;
    float dist = length(vWorldPosition - uCameraPosition);

    // Compute terrain height from heightmap (matching ray-marcher)
    float height = terrain2(worldXZ);
    float h = clamp(height / uMaxElevation, 0.0, 1.0);

    // Compute normal from heightmap (matching ray-marcher)
    vec3 normal = terrainNormal(worldXZ, dist);
    float slope = clamp(normal.y, 0.0, 1.0);

    float waterH = uWaterLevel;

    // Precompute reusable noise
    float macroNoise = fbm(worldXZ * 0.04);
    float detailNoise = fbm(worldXZ * 0.12);
    float perturb = macroNoise * 0.05;

    // ----------------------------------------------------
    // Biome thresholds — MUST match rayMarchShaders.ts matPos.y scale
    // matPos.y = (h - waterH) / (1 - waterH) * 280
    // Inverse: h = waterH + matPos.y / 280 * (1 - waterH)
    // ----------------------------------------------------

    // Beach: matPos.y < 8 → h < waterH + 8/280*(1-waterH)
    float beachEnd = waterH + (8.0 / 280.0) * (1.0 - waterH) + perturb * 0.1;
    // Grass ends: matPos.y < 78
    float grassEnd = waterH + (78.0 / 280.0) * (1.0 - waterH) + perturb;
    // Forest ends: matPos.y ~ 120 (between grass and alpine)
    float forestEnd = waterH + (120.0 / 280.0) * (1.0 - waterH) + perturb;
    // Alpine starts: matPos.y ~ 160
    float alpineStart = waterH + (160.0 / 280.0) * (1.0 - waterH) + perturb;
    // Snow starts: matPos.y > 80 (but with noise variation)
    float snowStart = waterH + (80.0 / 280.0) * (1.0 - waterH) + perturb;

    // ----------------------------------------------------
    // Color palette - theme-aware
    // ----------------------------------------------------

    vec3 deepWater    = vec3(0.03, 0.06, 0.14);
    vec3 shallowWater = vec3(0.10, 0.20, 0.32);
    vec3 sand         = vec3(0.76, 0.70, 0.50);
    vec3 grass        = vec3(0.22, 0.38, 0.12);
    vec3 forest       = vec3(0.08, 0.26, 0.08);
    vec3 alpine       = vec3(0.36, 0.40, 0.30);
    vec3 rock         = vec3(0.40, 0.38, 0.35);
    vec3 snow         = vec3(0.93, 0.95, 0.97);

    // Theme-specific color adjustments
    if (uTheme == 1) {
      // Desert: warm sandstone, golden tones
      rock  *= vec3(1.30, 1.10, 0.80);
      grass = vec3(0.28, 0.20, 0.06);  // golden sand instead of grass
      forest = vec3(0.20, 0.16, 0.08); // sparse vegetation
      sand  = vec3(0.82, 0.72, 0.50);  // warmer sand
    } else if (uTheme == 2) {
      // Arctic: cool blue-gray, tundra
      rock  *= vec3(0.85, 0.90, 1.05);
      grass = vec3(0.16, 0.18, 0.14);  // gray-green tundra
      forest = vec3(0.08, 0.12, 0.10); // dark spruce
    } else if (uTheme == 3) {
      // Tropical: bright lush greens
      grass = vec3(0.08, 0.40, 0.05);  // bright green
      forest = vec3(0.02, 0.28, 0.05); // lush canopy
      sand  = vec3(0.80, 0.74, 0.55);  // slightly lighter sand
    }

    vec3 color;

    // ----------------------------------------------------
    // Water
    // ----------------------------------------------------

    if (h < waterH) {
      float t = clamp(h / max(waterH, 0.001), 0.0, 1.0);
      color = mix(deepWater, shallowWater, t);

      // subtle specular highlight
      vec3 viewDir = normalize(uCameraPosition - vWorldPosition);
      vec3 halfDir = normalize(uSunDirection + viewDir);
      float spec = pow(max(dot(normal, halfDir), 0.0), 64.0);
      color += spec * 0.15;

    } else {

      // ------------------------------------------------
      // Terrain - slope-aware material selection
      // MUST match rayMarchShaders.ts TerrainColour logic
      // ------------------------------------------------

      // Compute matPos.y scale (same as ray-march shader)
      float waterWorldY = waterH * uMaxElevation;
      float hScale = 280.0 / max(uMaxElevation - waterWorldY, 1.0);
      float matPosY = (height - waterWorldY) * hScale;

      // Start with base rock color
      color = rock;

      // Cliff faces (normal.y < 0.5) - fully override with cliff color
      if (slope < 0.5) {
        float cliffBlend = (0.5 - slope) * 4.0;
        cliffBlend = clamp(cliffBlend * cliffBlend, 0.1, 1.0);
        vec3 cliffColor = mix(vec3(0.30, 0.28, 0.26), vec3(0.48, 0.44, 0.40), detailNoise);
        color = mix(color, cliffColor, cliffBlend);
      }

      // Scree/gravel on moderate slopes (0.50-0.68)
      if (slope >= 0.50 && slope < 0.68) {
        float screeBlend = smoothstep(0.50, 0.56, slope) * smoothstep(0.68, 0.60, slope) * 0.7;
        vec3 screeCol = mix(vec3(0.35, 0.32, 0.28), vec3(0.42, 0.38, 0.33), detailNoise);
        color = mix(color, screeCol, screeBlend);
      }

      // Grass on flatter ground (matPosY < 78, slope > 0.65)
      if (matPosY < 78.0 && slope > 0.65) {
        float grassBlend = clamp((slope - 0.65) * 1.3 * (78.0 - matPosY) * 0.06, 0.0, 1.0);
        vec3 grassCol = mix(grass, forest, detailNoise * 0.4);
        grassCol *= mix(0.9, 1.1, detailNoise);
        color = mix(color, grassCol, grassBlend);
      }

      // Snow on high terrain (threshold varies by theme)
      // Only on flatter surfaces - steep slopes expose rock
      float snowThreshold = 80.0;
      if (uTheme == 1)      snowThreshold = 180.0;  // Desert: almost no snow
      else if (uTheme == 2) snowThreshold = 30.0;   // Arctic: snow at low elevation
      else if (uTheme == 3) snowThreshold = 220.0;  // Tropical: effectively none

      if (matPosY > snowThreshold && slope > 0.35) {
        float elevFactor = clamp((matPosY - snowThreshold - detailNoise * 20.0) * 0.04, 0.0, 1.0);
        float slopeFactor = smoothstep(0.35, 0.65, slope);
        float snowBlend = elevFactor * slopeFactor;

        // Rock exposure on steeper ground
        float rockExposure = smoothstep(0.65, 0.45, slope) * detailNoise * 0.5;
        snowBlend = max(snowBlend - rockExposure, 0.0);

        color = mix(color, snow, snowBlend);
      }

      // Beach zone (matPosY < 8, slope > 0.4) - only on flat terrain near water
      if (matPosY < 8.0 && slope > 0.4) {
        float flatness = smoothstep(0.4, 0.75, slope);
        float beachBlend = smoothstep(8.0, 2.0, matPosY) * smoothstep(-1.0, 1.0, matPosY) * flatness;
        color = mix(color, sand, beachBlend);
      }
    }

    // ----------------------------------------------------
    // Road surface overlay - matching rayMarchShaders.ts
    // ----------------------------------------------------

    {
      float halfW = uWorldSize * 0.5;
      vec2 roadUV = (worldXZ + halfW) / uWorldSize;
      roadUV = clamp(roadUV, 0.0, 1.0);
      float roadVal = texture2D(uRoadMap, roadUV).r;

      if (roadVal > 0.04) {
        bool isMain = roadVal > 0.6;

        float waterWorldY = waterH * uMaxElevation;
        float hScale = 280.0 / max(uMaxElevation - waterWorldY, 1.0);
        float matPosY = (height - waterWorldY) * hScale;
        float rn = noise2D(worldXZ * 0.15) * 0.06 + noise2D(worldXZ * 1.5) * 0.03;

        vec3 roadCol;
        if (matPosY > 80.0) {
          roadCol = isMain ? vec3(0.52, 0.52, 0.57) : vec3(0.58, 0.58, 0.63);
        } else if (matPosY > 60.0) {
          roadCol = isMain ? vec3(0.40, 0.37, 0.33) : vec3(0.43, 0.40, 0.36);
        } else if (matPosY < 1.45) {
          roadCol = vec3(0.50, 0.42, 0.30);
        } else {
          roadCol = isMain ? vec3(0.32, 0.22, 0.12) : vec3(0.36, 0.27, 0.16);
        }

        roadCol += rn;

        float edgeDarken = smoothstep(0.35, 0.12, roadVal) * 0.12;
        roadCol *= 1.0 - edgeDarken;

        float roadAlpha = smoothstep(0.04, 0.22, roadVal) * 0.88;
        color = mix(color, roadCol, roadAlpha);
      }
    }

    // ----------------------------------------------------
    // Lighting
    // ----------------------------------------------------

    float NdotL = max(dot(normal, normalize(uSunDirection)), 0.0);
    float diffuseTerm = pow(NdotL, 1.2); // softer falloff

    vec3 lighting = uAmbientColor + uSunColor * diffuseTerm;
    color *= lighting;

    // ----------------------------------------------------
    // Cultural Overlay (Voronoi with soft border blending)
    // Matching rayMarchShaders.ts ApplyCulturalOverlay
    // ----------------------------------------------------

    if (uShowEmpires && uCultureCount > 0) {
      float minDist = 1e10;
      float secondDist = 1e10;
      int nearestIdx = 0;
      int secondIdx = 0;

      for (int i = 0; i < 16; i++) {
        if (i >= uCultureCount) break;
        vec2 d = worldXZ - uCultureCenters[i].xy;
        float distSq = dot(d, d);
        if (distSq < minDist) {
          secondDist = minDist;
          secondIdx = nearestIdx;
          minDist = distSq;
          nearestIdx = i;
        } else if (distSq < secondDist) {
          secondDist = distSq;
          secondIdx = i;
        }
      }

      float d1 = sqrt(minDist);
      float d2 = sqrt(secondDist);

      // ratio -> 0 means on the border, ratio -> 1 means deep inside nearest
      float ratio = clamp((d2 - d1) / max(d1 + d2, 0.01), 0.0, 1.0);

      float strength = smoothstep(0.0, 0.12, ratio) * 0.35;

      float borderBlend = 1.0 - smoothstep(0.0, 0.06, ratio);
      vec3 regionCol = mix(uCultureColors[nearestIdx], uCultureColors[secondIdx], borderBlend * 0.4);

      color = mix(color, regionCol, strength);
    }

    // ----------------------------------------------------
    // Fog
    // ----------------------------------------------------

    float fog = 1.0 - exp(-dist * uFogDensity * 0.02);
    fog = clamp(fog, 0.0, 1.0);

    color = mix(color, uFogColor, fog);

    gl_FragColor = vec4(color, 1.0);
  }
`;
