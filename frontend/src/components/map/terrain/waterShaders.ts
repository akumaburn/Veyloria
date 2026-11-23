/**
 * Custom GLSL shaders for animated water rendering.
 * Features: vertex wave displacement, dual-layer wave normals, Fresnel reflections,
 * sun specular highlights, depth-based transparency, and fog.
 */

export const waterVertexShader = /* glsl */ `
  uniform float uTime;

  varying vec3 vWorldPosition;
  varying vec2 vUv;

  // Multi-scale sine waves for vertex displacement
  float wave(vec2 p, float t) {
    float w = 0.0;
    w += sin(p.x * 0.15 + t * 0.8) * 0.12;
    w += sin(p.y * 0.12 + t * 0.6) * 0.10;
    w += sin((p.x + p.y) * 0.08 + t * 1.1) * 0.08;
    w += sin((p.x - p.y) * 0.20 + t * 0.9) * 0.05;
    return w;
  }

  void main() {
    vUv = uv;

    vec3 pos = position;
    // Vertex wave displacement on Y axis
    pos.y += wave(pos.xz, uTime);

    vec4 worldPos = modelMatrix * vec4(pos, 1.0);
    vWorldPosition = worldPos.xyz;

    gl_Position = projectionMatrix * viewMatrix * worldPos;
  }
`;

export const waterFragmentShader = /* glsl */ `
  uniform float uTime;
  uniform vec3 uSunDirection;
  uniform vec3 uSunColor;
  uniform vec3 uCameraPosition;

  // Fog
  uniform vec3 uFogColor;
  uniform float uFogDensity;

  varying vec3 vWorldPosition;
  varying vec2 vUv;

  // Hash noise for wave patterns
  float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
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

  // Compute wave normal via finite differences
  vec3 waveNormal(vec2 p, float t) {
    float eps = 0.5;

    // Dual-layer noise at different scales and speeds
    float h  = noise2D(p * 0.08 + t * vec2(0.3, 0.2))
             + noise2D(p * 0.15 + t * vec2(-0.2, 0.35)) * 0.5;
    float hx = noise2D((p + vec2(eps, 0.0)) * 0.08 + t * vec2(0.3, 0.2))
             + noise2D((p + vec2(eps, 0.0)) * 0.15 + t * vec2(-0.2, 0.35)) * 0.5;
    float hy = noise2D((p + vec2(0.0, eps)) * 0.08 + t * vec2(0.3, 0.2))
             + noise2D((p + vec2(0.0, eps)) * 0.15 + t * vec2(-0.2, 0.35)) * 0.5;

    float dhdx = (hx - h) / eps;
    float dhdy = (hy - h) / eps;

    return normalize(vec3(-dhdx * 2.0, 1.0, -dhdy * 2.0));
  }

  void main() {
    vec3 normal = waveNormal(vWorldPosition.xz, uTime);
    vec3 viewDir = normalize(uCameraPosition - vWorldPosition);

    // Fresnel — reflective at grazing angles, capped to keep water darker
    float fresnel = pow(1.0 - max(dot(viewDir, normal), 0.0), 4.0);
    fresnel = clamp(fresnel, 0.05, 0.50);

    // Deep water color and shallow color
    vec3 deepColor = vec3(0.01, 0.04, 0.18);
    vec3 shallowColor = vec3(0.04, 0.14, 0.34);

    // Depth approximation — areas near water level are shallower
    // We don't have terrain depth info, so use a world-space heuristic
    float depthFactor = 0.6; // base depth for open water

    // Water body color
    vec3 waterColor = mix(shallowColor, deepColor, depthFactor);

    // Sky reflection — simple vertical gradient
    vec3 reflectDir = reflect(-viewDir, normal);
    float skyT = clamp(reflectDir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 skyZenith = vec3(0.10, 0.20, 0.58);
    vec3 skyHorizon = vec3(0.30, 0.40, 0.60);
    vec3 skyColor = mix(skyHorizon, skyZenith, skyT);

    // Specular — sun reflection, reduced to avoid blowout
    vec3 halfVec = normalize(viewDir + uSunDirection);
    float spec = pow(max(dot(normal, halfVec), 0.0), 128.0);
    vec3 specular = uSunColor * spec * 0.5;

    // Combine: water body + Fresnel reflection + specular
    vec3 color = mix(waterColor, skyColor, fresnel) + specular;

    // Transparency: deep water more opaque
    float alpha = mix(0.50, 0.85, depthFactor);

    // Fog — scaled by camera altitude so zoomed-out views stay clear
    float dist = length(vWorldPosition - uCameraPosition);
    float camAlt = max(uCameraPosition.y, 1.0);
    float fogScale = clamp(40.0 / camAlt, 0.0, 1.0);
    float fogFactor = 1.0 - exp(-dist * dist * uFogDensity * uFogDensity * fogScale);
    fogFactor = clamp(fogFactor, 0.0, 1.0);
    color = mix(color, uFogColor, fogFactor);
    // Fog also fades alpha toward opaque fog color
    alpha = mix(alpha, 1.0, fogFactor);

    gl_FragColor = vec4(color, alpha);
  }
`;
