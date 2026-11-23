package com.eonmux.chatllm.map;

import com.eonmux.chatllm.map.generation.MapGenerator;
import com.eonmux.chatllm.map.models.Biome;
import com.eonmux.chatllm.map.models.GenerationConfig;
import com.eonmux.chatllm.map.models.GenerationResult;
import com.eonmux.chatllm.map.models.TerrainData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(MapGenerationDeterminismTest.MinimalProfile.class)
public class MapGenerationDeterminismTest {

    public static class MinimalProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "chatllm.llm.enabled", "false",
                "chatllm.llm.mock-mode", "true"
            );
        }
    }

    @Test
    void testDeterministicGeneration() {
        MapGenerator generator = new MapGenerator();
        GenerationConfig config = GenerationConfig.builder()
            .seed("test-seed".hashCode())
            .mapScale("region")
            .gridResolution(128)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("temperate")
            .build();

        GenerationResult result1 = generator.generate(config);
        GenerationResult result2 = generator.generate(config);

        // Same number of locations (locationIds are UUIDs, but positions are deterministic)
        assertEquals(result1.getLocations().size(), result2.getLocations().size());

        // Same terrain dimensions
        TerrainData t1 = result1.getTerrain();
        TerrainData t2 = result2.getTerrain();
        assertEquals(t1.getWidth(), t2.getWidth());
        assertEquals(t1.getHeight(), t2.getHeight());
        assertArrayEquals(t1.getBiomeGrid(), t2.getBiomeGrid());

        // Same road network size
        assertEquals(result1.getRoads().size(), result2.getRoads().size());

        // Same cultural regions
        assertEquals(result1.getCulturalRegions().size(), result2.getCulturalRegions().size());

        // Starting locations should have same positions (not IDs, which are UUIDs)
        if (result1.getStartingLocationId() != null && result2.getStartingLocationId() != null) {
            var loc1 = result1.getLocations().stream()
                .filter(l -> l.getLocationId().toString().equals(result1.getStartingLocationId()))
                .findFirst().orElseThrow();
            var loc2 = result2.getLocations().stream()
                .filter(l -> l.getLocationId().toString().equals(result2.getStartingLocationId()))
                .findFirst().orElseThrow();
            assertEquals(loc1.getX(), loc2.getX());
            assertEquals(loc1.getY(), loc2.getY());
            assertEquals(loc1.getName(), loc2.getName());
        }

        // Location positions are deterministic (skip UUIDs which are random)
        for (int i = 0; i < Math.min(result1.getLocations().size(), result2.getLocations().size()); i++) {
            var l1 = result1.getLocations().get(i);
            var l2 = result2.getLocations().get(i);
            assertEquals(l1.getX(), l2.getX());
            assertEquals(l1.getY(), l2.getY());
            assertEquals(l1.getName(), l2.getName());
            assertEquals(l1.getLocationType(), l2.getLocationType());
        }
    }

    @Test
    void testDifferentSeedsProduceDifferentMaps() {
        MapGenerator generator = new MapGenerator();

        GenerationConfig config1 = GenerationConfig.builder()
            .seed("hello".hashCode())
            .mapScale("region")
            .gridResolution(128)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("temperate")
            .build();

        GenerationConfig config2 = GenerationConfig.builder()
            .seed("world".hashCode())
            .mapScale("region")
            .gridResolution(128)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("temperate")
            .build();

        GenerationResult r1 = generator.generate(config1);
        GenerationResult r2 = generator.generate(config2);

        boolean different = r1.getLocations().size() != r2.getLocations().size()
            || r1.getRoads().size() != r2.getRoads().size()
            || !Arrays.equals(r1.getTerrain().getBiomeGrid(), r2.getTerrain().getBiomeGrid());

        assertTrue(different, "Maps generated from different seeds should differ in at least one property");
    }

    @Test
    void testMapScaleAffectsResolution() {
        MapGenerator generator = new MapGenerator();

        GenerationConfig cityConfig = GenerationConfig.builder()
            .seed(42)
            .mapScale("city")
            .gridResolution(384)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("temperate")
            .build();

        GenerationConfig continentConfig = GenerationConfig.builder()
            .seed(42)
            .mapScale("continent")
            .gridResolution(768)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("temperate")
            .build();

        GenerationResult city = generator.generate(cityConfig);
        GenerationResult continent = generator.generate(continentConfig);

        assertEquals(384, city.getTerrain().getWidth());
        assertEquals(384, city.getTerrain().getHeight());
        assertEquals(768, continent.getTerrain().getWidth());
        assertEquals(768, continent.getTerrain().getHeight());
    }

    @Test
    void testThemeAffectsBiomes() {
        MapGenerator generator = new MapGenerator();

        GenerationConfig desertConfig = GenerationConfig.builder()
            .seed(42)
            .mapScale("region")
            .gridResolution(128)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("desert")
            .build();

        GenerationConfig arcticConfig = GenerationConfig.builder()
            .seed(42)
            .mapScale("region")
            .gridResolution(128)
            .waterLevel(0.22f)
            .locationCount(60)
            .theme("arctic")
            .build();

        GenerationResult desert = generator.generate(desertConfig);
        GenerationResult arctic = generator.generate(arcticConfig);

        int[] desertBiomes = desert.getTerrain().getBiomeGrid();
        int[] arcticBiomes = arctic.getTerrain().getBiomeGrid();

        assertFalse(Arrays.equals(desertBiomes, arcticBiomes),
            "Desert and arctic biome grids should differ");
    }

    @Test
    void testLocationCountConstraint() {
        MapGenerator generator = new MapGenerator();

        GenerationConfig config = GenerationConfig.builder()
            .seed(42)
            .mapScale("region")
            .gridResolution(128)
            .waterLevel(0.22f)
            .locationCount(100)
            .theme("temperate")
            .build();

        GenerationResult result = generator.generate(config);
        int count = result.getLocations().size();

        // Location count is approximate due to terrain constraints (water avoidance,
        // minimum spacing, etc.). The generator treats locationCount as a target,
        // and may produce fewer if the terrain limits placement.
        assertTrue(count >= 50, "Expected at least 50 locations, got " + count);
        assertTrue(count <= 150, "Expected at most 150 locations, got " + count);
    }

    @Test
    void testAllBiomesPresentInLargeMap() {
        MapGenerator generator = new MapGenerator();

        GenerationConfig config = GenerationConfig.builder()
            .seed(42)
            .mapScale("continent")
            .gridResolution(256)
            .waterLevel(0.22f)
            .locationCount(100)
            .theme("temperate")
            .build();

        GenerationResult result = generator.generate(config);
        int[] biomeGrid = result.getTerrain().getBiomeGrid();

        Set<Integer> present = new HashSet<>();
        for (int b : biomeGrid) {
            present.add(b);
        }

        // Check that at least 6 different biome types appear on a continent-scale map
        // (biome distribution depends on noise values; not every biome is guaranteed
        // on a given seed, but a large map should have decent variety)
        assertTrue(present.size() >= 6, "Expected at least 6 biome types, got " + present.size());
        assertTrue(present.contains(Biome.DEEP_WATER.getId()), "Deep water should exist");
    }
}
