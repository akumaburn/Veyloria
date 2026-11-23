package com.eonmux.chatllm.map.generation;

/**
 * OpenSimplex2 noise implementation for procedural terrain generation.
 * Based on the OpenSimplex2S variant (smoother).
 */
public class SimplexNoise {

    private static final long PRIME_X = 0x5205402B9270C86FL;
    private static final long PRIME_Y = 0x598CD327003817B5L;
    private static final long HASH_MULTIPLIER = 0x53A3F72DEEC546F5L;
    private static final int N_GRADS_2D = 128;

    private static final double ROOT2OVER2 = 0.7071067811865476;
    private static final double SKEW_2D = 0.366025403784439;
    private static final double UNSKEW_2D = -0.21132486540518713;

    private final long seed;
    private final double seedOffsetX;
    private final double seedOffsetY;
    private static final Grad2[] GRADIENTS_2D;

    static {
        GRADIENTS_2D = new Grad2[N_GRADS_2D];
        Grad2[] grad2 = {
            new Grad2(0.130526192220052, 0.99144486137381),
            new Grad2(0.38268343236509, 0.923879532511287),
            new Grad2(0.608761429008721, 0.793353340291235),
            new Grad2(0.793353340291235, 0.608761429008721),
            new Grad2(0.923879532511287, 0.38268343236509),
            new Grad2(0.99144486137381, 0.130526192220052),
            new Grad2(0.99144486137381, -0.130526192220052),
            new Grad2(0.923879532511287, -0.38268343236509),
            new Grad2(0.793353340291235, -0.608761429008721),
            new Grad2(0.608761429008721, -0.793353340291235),
            new Grad2(0.38268343236509, -0.923879532511287),
            new Grad2(0.130526192220052, -0.99144486137381),
            new Grad2(-0.130526192220052, -0.99144486137381),
            new Grad2(-0.38268343236509, -0.923879532511287),
            new Grad2(-0.608761429008721, -0.793353340291235),
            new Grad2(-0.793353340291235, -0.608761429008721),
            new Grad2(-0.923879532511287, -0.38268343236509),
            new Grad2(-0.99144486137381, -0.130526192220052),
            new Grad2(-0.99144486137381, 0.130526192220052),
            new Grad2(-0.923879532511287, 0.38268343236509),
            new Grad2(-0.793353340291235, 0.608761429008721),
            new Grad2(-0.608761429008721, 0.793353340291235),
            new Grad2(-0.38268343236509, 0.923879532511287),
            new Grad2(-0.130526192220052, 0.99144486137381),
        };
        for (int i = 0; i < N_GRADS_2D; i++) {
            GRADIENTS_2D[i] = grad2[i % grad2.length];
        }
    }

    public SimplexNoise(long seed) {
        this.seed = seed;
        // Derive large, seed-unique offsets for Hoskins noise coordinate space
        this.seedOffsetX = ((seed * 1103515245L + 12345L) & 0x7fffffffL) / (double) 0x7fffffffL * 1000.0;
        this.seedOffsetY = ((seed * 6364136223846793005L + 1442695040888963407L) & 0x7fffffffL) / (double) 0x7fffffffL * 1000.0;
    }

    /**
     * 2D noise evaluation. Returns value in range [-1, 1].
     */
    public double noise2D(double x, double y) {
        double s = SKEW_2D * (x + y);
        double xs = x + s;
        double ys = y + s;
        return noise2DUnskewedBase(xs, ys);
    }

    /**
     * Fractal Brownian Motion (fBm) using 2D noise.
     * Layers octaves with decreasing amplitude and increasing frequency.
     */
    public double fbm2D(double x, double y, int octaves, double lacunarity, double persistence) {
        double total = 0;
        double amplitude = 1;
        double frequency = 1;
        double maxAmplitude = 0;

        for (int i = 0; i < octaves; i++) {
            total += noise2D(x * frequency, y * frequency) * amplitude;
            maxAmplitude += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }

        return maxAmplitude == 0 ? 0 : total / maxAmplitude;
    }

    private double noise2DUnskewedBase(double xs, double ys) {
        int xsb = fastFloor(xs);
        int ysb = fastFloor(ys);
        float xi = (float)(xs - xsb);
        float yi = (float)(ys - ysb);

        long xsbp = xsb * PRIME_X;
        long ysbp = ysb * PRIME_Y;

        float t = (xi + yi) * (float) UNSKEW_2D;
        float dx0 = xi + t;
        float dy0 = yi + t;

        double value = 0;
        double a0 = (2.0 / 3.0) - dx0 * dx0 - dy0 * dy0;
        if (a0 > 0) {
            value = (a0 * a0) * (a0 * a0) * grad(xsbp, ysbp, dx0, dy0);
        }

        double a1 = (float)(2 * (1 + 2 * UNSKEW_2D) * (1 / UNSKEW_2D + 2)) * t +
                     ((float)(-2 * (1 + 2 * UNSKEW_2D) * (1 + 2 * UNSKEW_2D)) + a0);
        if (a1 > 0) {
            float dx1 = dx0 - (float)(1 + 2 * UNSKEW_2D);
            float dy1 = dy0 - (float)(1 + 2 * UNSKEW_2D);
            value += (a1 * a1) * (a1 * a1) * grad(xsbp + PRIME_X, ysbp + PRIME_Y, dx1, dy1);
        }

        if (dy0 > dx0) {
            float dx2 = dx0 - (float) UNSKEW_2D;
            float dy2 = dy0 - (float)(UNSKEW_2D + 1);
            double a2 = (2.0 / 3.0) - dx2 * dx2 - dy2 * dy2;
            if (a2 > 0) {
                value += (a2 * a2) * (a2 * a2) * grad(xsbp, ysbp + PRIME_Y, dx2, dy2);
            }
        } else {
            float dx2 = dx0 - (float)(UNSKEW_2D + 1);
            float dy2 = dy0 - (float) UNSKEW_2D;
            double a2 = (2.0 / 3.0) - dx2 * dx2 - dy2 * dy2;
            if (a2 > 0) {
                value += (a2 * a2) * (a2 * a2) * grad(xsbp + PRIME_X, ysbp, dx2, dy2);
            }
        }

        return value;
    }

    private double grad(long xsvp, long ysvp, float dx, float dy) {
        long hash = (seed ^ xsvp ^ ysvp) * HASH_MULTIPLIER;
        hash ^= hash >> 32;
        int gi = (int) hash & (N_GRADS_2D - 1);
        Grad2 g = GRADIENTS_2D[gi];
        return g.dx * dx + g.dy * dy;
    }

    // =========================================================
    //  Hoskins noise — port of David Hoskins' "Mountains" shader
    //  https://www.shadertoy.com/view/4slGD4
    //  Uses hash-based value noise with smoothstep interpolation,
    //  domain-rotation matrix, sign-flipping weights, and power peaks.
    // =========================================================

    private static double fract(double x) {
        return x - Math.floor(x);
    }

    /**
     * Hoskins' hash function: vec2 → [0, 1] scalar.
     * Faithful port of the GLSL Hash12 function.
     */
    private static double hoskinsHash12(double x, double y) {
        // vec3 p3 = fract(vec3(p.xyx) * 0.1031)
        double p3x = fract(x * 0.1031);
        double p3y = fract(y * 0.1031);
        double p3z = fract(x * 0.1031); // p.xyx → z = x

        // p3 += dot(p3, p3.yzx + 19.19)
        double d = p3x * (p3y + 19.19) + p3y * (p3z + 19.19) + p3z * (p3x + 19.19);
        p3x += d;
        p3y += d;
        p3z += d;

        // return fract((p3.x + p3.y) * p3.z)
        return fract((p3x + p3y) * p3z);
    }

    /**
     * Hoskins' bilinear-interpolated value noise: vec2 → [0, 1].
     * Faithful port of the GLSL Noise function.
     */
    private static double hoskinsNoise(double x, double y) {
        double px = Math.floor(x);
        double py = Math.floor(y);
        double fx = x - px;
        double fy = y - py;

        // smoothstep: f * f * (3 - 2*f)
        fx = fx * fx * (3.0 - 2.0 * fx);
        fy = fy * fy * (3.0 - 2.0 * fy);

        // Bilinear interpolation of hash at 4 corners
        double v00 = hoskinsHash12(px, py);
        double v10 = hoskinsHash12(px + 1.0, py);
        double v01 = hoskinsHash12(px, py + 1.0);
        double v11 = hoskinsHash12(px + 1.0, py + 1.0);

        double mix0 = v00 + (v10 - v00) * fx;
        double mix1 = v01 + (v11 - v01) * fx;
        return mix0 + (mix1 - mix0) * fy;
    }

    /**
     * Hoskins terrain height at a world-space position.
     * Faithful port of the GLSL HoskinsNoise function with sign-flipping
     * octaves, domain-rotation matrix, and power-peak term.
     *
     * Seed is incorporated via coordinate offset so each seed produces
     * unique terrain while preserving the Hoskins mountain character.
     *
     * @param worldX world-space X coordinate (typically [-100, 100])
     * @param worldY world-space Y coordinate (typically [-100, 100])
     * @param iterations number of noise octaves (6 = region, 4 = city, 8 = continent)
     * @return raw terrain height (unnormalized; caller handles normalization)
     */
    public double hoskinsTerrain(double worldX, double worldY, int iterations) {
        // Domain-rotation matrix — same as GLSL rotate2D
        final double m00 = 1.3623, m01 = 1.7531;
        final double m10 = -1.7131, m11 = 1.4623;

        // Seed offset for per-seed uniqueness
        double ox = worldX + seedOffsetX;
        double oy = worldY + seedOffsetY;

        // Match shader's 0.25 frequency scale
        double px = ox * 0.25;
        double py = oy * 0.25;

        // Spatially-varying base weight
        double baseNoise = hoskinsNoise(px * 0.25, py * 0.25);
        double w = (baseNoise * 0.75 + 0.15);
        w = 66.0 * w * w;

        double f = 0.0;
        for (int i = 0; i < iterations; i++) {
            f += w * hoskinsNoise(px, py);
            w = -w * 0.4; // sign-flip: creates ridges AND valleys
            // Domain rotation
            double nx = m00 * px + m01 * py;
            double ny = m10 * px + m11 * py;
            px = nx;
            py = ny;
        }

        // Power-peak term for dramatic isolated peaks
        double ff = hoskinsNoise(px * 0.002, py * 0.002);
        f += Math.pow(Math.abs(ff), 5.0) * 275.0 - 5.0;

        return f;
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private record Grad2(double dx, double dy) {}
}
