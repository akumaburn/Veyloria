package com.eonmux.chatllm.map.generation;

import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.TerrainData;

import java.util.*;

/**
 * Assigns cultural zones across the map using Voronoi-like partitioning.
 * Cultural centers are placed with biome affinity weighting, and each grid cell
 * is assigned to the nearest culture (with affinity bonus for matching biomes).
 * Generates unique empire names for each region.
 */
public class CulturalRegionMapper {

    private static final float AFFINITY_BONUS = 0.6f;
    private static final String[] EMPIRE_SUFFIXES = {
        "Empire", "Kingdom", "Dominion", "Realm", "Federation", "Territories"
    };

    private final List<CulturalRegion> regions;
    private final TerrainData terrain;

    public CulturalRegionMapper(TerrainData terrain, long seed) {
        this.terrain = terrain;
        this.regions = placeRegions(terrain, seed);
    }

    /** Returns the dominant culture at the given grid coordinates. */
    public Culture getCulture(int gridX, int gridY) {
        int w = terrain.getWidth();
        int h = terrain.getHeight();
        if (gridX < 0 || gridX >= w || gridY < 0 || gridY >= h) {
            return Culture.HEARTHFOLK;
        }

        Culture best = Culture.HEARTHFOLK;
        float bestScore = Float.MAX_VALUE;

        Biome biome = Biome.fromId(terrain.getBiomeGrid()[gridY * w + gridX]);

        for (CulturalRegion region : regions) {
            float dx = gridX - region.centerX();
            float dy = gridY - region.centerY();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            // Apply affinity bonus: cultures in their preferred biome have stronger pull
            if (region.culture().getBiomeAffinity().contains(biome)) {
                dist *= AFFINITY_BONUS;
            }

            if (dist < bestScore) {
                bestScore = dist;
                best = region.culture();
            }
        }
        return best;
    }

    public List<CulturalRegion> getRegions() {
        return regions;
    }

    private List<CulturalRegion> placeRegions(TerrainData terrain, long seed) {
        Random rng = new Random(seed + 77777);
        int width = terrain.getWidth();
        int height = terrain.getHeight();
        int regionCount = 3 + rng.nextInt(4); // 3-6 regions
        Culture[] cultures = Culture.values();
        float minDim = Math.min(width, height);

        List<CulturalRegion> result = new ArrayList<>();
        List<int[]> usedCenters = new ArrayList<>();

        // Separate RNG for empire name generation to avoid perturbing existing sequence
        Random nameRng = new Random(seed + 88888);
        Set<String> usedNames = new HashSet<>();

        for (int i = 0; i < regionCount; i++) {
            Culture culture = selectCultureForPosition(terrain, rng, cultures, width, height);

            int cx = 0, cy = 0;
            boolean placed = false;
            for (int attempt = 0; attempt < 50; attempt++) {
                cx = rng.nextInt(width);
                cy = rng.nextInt(height);

                boolean tooClose = false;
                for (int[] prev : usedCenters) {
                    float dx = cx - prev[0];
                    float dy = cy - prev[1];
                    if (Math.sqrt(dx * dx + dy * dy) < minDim * 0.2f) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;

                Biome biome = Biome.fromId(terrain.getBiomeGrid()[cy * width + cx]);
                if (culture.getBiomeAffinity().contains(biome) || attempt > 30) {
                    placed = true;
                    break;
                }
            }

            if (placed) {
                float radius = minDim * (0.25f + rng.nextFloat() * 0.25f);
                String empireName = generateEmpireName(culture, nameRng, usedNames);
                usedNames.add(empireName);
                result.add(new CulturalRegion(culture, cx, cy, radius, empireName));
                usedCenters.add(new int[]{cx, cy});
            }
        }

        if (result.isEmpty()) {
            String fallbackName = generateEmpireName(Culture.HEARTHFOLK, nameRng, usedNames);
            result.add(new CulturalRegion(Culture.HEARTHFOLK, width / 2, height / 2, minDim * 0.5f, fallbackName));
        }

        return List.copyOf(result);
    }

    /**
     * Generate a unique empire name using culture prefix/suffix roots + empire type suffix.
     * Format: "{PrefixRoot}{SuffixRoot} {EmpireSuffix}" e.g. "Stormhold Empire"
     */
    private String generateEmpireName(Culture culture, Random rng, Set<String> usedNames) {
        String[] prefixes = culture.getPrefixRoots();
        String[] suffixes = culture.getSuffixRoots();

        for (int attempt = 0; attempt < 50; attempt++) {
            String prefix = prefixes[rng.nextInt(prefixes.length)];
            String suffix = suffixes[rng.nextInt(suffixes.length)];
            String empireType = EMPIRE_SUFFIXES[rng.nextInt(EMPIRE_SUFFIXES.length)];
            String name = prefix + suffix + " " + empireType;
            if (!usedNames.contains(name)) {
                return name;
            }
        }
        // Fallback: append a numeric suffix to guarantee uniqueness
        for (int i = 2; i < 100; i++) {
            String name = prefixes[0] + suffixes[0] + " " + EMPIRE_SUFFIXES[0] + " " + i;
            if (!usedNames.contains(name)) {
                return name;
            }
        }
        return prefixes[0] + suffixes[0] + " " + EMPIRE_SUFFIXES[0] + " " + rng.nextInt();
    }

    private Culture selectCultureForPosition(TerrainData terrain, Random rng,
                                              Culture[] cultures, int width, int height) {
        int sx = rng.nextInt(width);
        int sy = rng.nextInt(height);
        Biome sampleBiome = Biome.fromId(terrain.getBiomeGrid()[sy * width + sx]);

        List<Culture> matching = new ArrayList<>();
        for (Culture c : cultures) {
            if (c.getBiomeAffinity().contains(sampleBiome)) {
                matching.add(c);
            }
        }

        if (matching.isEmpty()) {
            return cultures[rng.nextInt(cultures.length)];
        }
        return matching.get(rng.nextInt(matching.size()));
    }
}
