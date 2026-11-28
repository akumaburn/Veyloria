package com.eonmux.chatllm.services;

import com.eonmux.chatllm.comfyui.ComfyUIClient;
import com.eonmux.chatllm.config.AppConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns a completed 8-panel character sheet (the D2 asset pipeline) into the
 * character's reference-image set. The sheet is generated at 1024x1024 by the
 * H3 character-portrait template; this service cuts the eight panels out,
 * stores them under the uploads directory, and replaces the character's active
 * image references with the eight crops. Later avatar/portrait generations
 * then condition on these crops via the ref2VA graph.
 *
 * <p>The panel table is expressed in the native 1024x1024 coordinate space;
 * sheets of other sizes are scaled proportionally.
 */
@ApplicationScoped
@Slf4j
public class SheetRefService {

    /** One sheet panel: its view name, native 1024x1024 crop box, and
     *  reference weight (identity-critical views rank highest because the
     *  ref2VA path keeps only the top references). */
    private static final class Panel {
        final String view;
        final int x;
        final int y;
        final int w;
        final int h;
        final double weight;

        Panel(String view, int x, int y, int w, int h, double weight) {
            this.view = view;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.weight = weight;
        }
    }

    /** A cropped panel paired with the view it represents. */
    public static final class Crop {
        public final String view;
        public final double weight;
        public final BufferedImage image;

        Crop(String view, double weight, BufferedImage image) {
            this.view = view;
            this.weight = weight;
            this.image = image;
        }
    }

    // Sheet panel table (1024x1024): grid columns at x=0/257/509/763, rows at
    // y=0/512. Weights rank identity-critical views first so the ref2VA upload
    // path (top-N by weight) keeps face and three-quarter views.
    private static final Panel[] PANELS = {
        new Panel("front", 0, 0, 257, 512, 1.0),
        new Panel("three_quarter", 257, 0, 252, 512, 0.95),
        new Panel("head_shoulders", 0, 512, 257, 512, 0.95),
        new Panel("side_profile", 509, 0, 254, 512, 0.85),
        new Panel("chest_waist", 257, 512, 252, 512, 0.8),
        new Panel("back", 763, 0, 261, 512, 0.7),
        new Panel("arm_hand", 509, 512, 254, 512, 0.6),
        new Panel("leg_foot", 763, 512, 261, 512, 0.6),
    };

    @Inject
    Pool pool;
    @Inject
    AppConfig config;
    @Inject
    ComfyUIClient comfyUIClient;

    // ImageIO decode/encode is blocking; keep it off the event loop on a
    // dedicated daemon thread.
    private final ExecutorService cropPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sheet-crop");
        t.setDaemon(true);
        return t;
    });

    /**
     * Download the sheet image, crop its panels, store the crops, and replace
     * the character's active image references with them. Resolves to the
     * stored file names.
     */
    public Uni<List<String>> deriveReferences(String characterId, String imageUrl) {
        return comfyUIClient.downloadImage(imageUrl)
            .map(buffer -> buffer.getBytes())
            .chain(data -> Uni.createFrom().emitter(emitter -> cropPool.submit(() -> {
                try {
                    emitter.complete(cropAndStore(characterId, data));
                } catch (Exception e) {
                    emitter.fail(e);
                }
            })));
    }

    /**
     * Cut the eight panels out of the sheet. Pure image math (no IO, no DB)
     * so the panel table and scaling are unit-testable on synthetic sheets.
     * Coordinates scale proportionally when the sheet is not exactly 1024x1024.
     */
    static List<Crop> cropPanels(BufferedImage sheet) {
        if (sheet == null) {
            throw new IllegalArgumentException("Sheet image is null");
        }
        double sx = sheet.getWidth() / 1024.0;
        double sy = sheet.getHeight() / 1024.0;
        List<Crop> crops = new ArrayList<>(PANELS.length);
        for (Panel p : PANELS) {
            int x = (int) Math.round(p.x * sx);
            int y = (int) Math.round(p.y * sy);
            int w = Math.max(1, (int) Math.round(p.w * sx));
            int h = Math.max(1, (int) Math.round(p.h * sy));
            if (x + w > sheet.getWidth()) w = sheet.getWidth() - x;
            if (y + h > sheet.getHeight()) h = sheet.getHeight() - y;
            crops.add(new Crop(p.view, p.weight, sheet.getSubimage(x, y, w, h)));
        }
        return crops;
    }

    private List<String> cropAndStore(String characterId, byte[] sheetBytes) throws Exception {
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(sheetBytes));
        if (sheet == null) {
            throw new IllegalArgumentException("Sheet bytes are not a decodable image");
        }
        List<Crop> crops = cropPanels(sheet);

        String prefix = "ref_" + characterId.substring(0, Math.min(8, characterId.length())) + "_";
        File dir = new File(config.getUploadsDirectory());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create uploads directory " + dir);
        }

        List<String> names = new ArrayList<>(crops.size());
        for (Crop crop : crops) {
            String name = prefix + crop.view + ".png";
            File out = new File(dir, name);
            if (!ImageIO.write(crop.image, "png", out)) {
                throw new IllegalStateException("ImageIO refused to write crop " + name);
            }
            names.add(name);
        }

        storeReferenceRows(characterId, crops);
        return names;
    }

    /**
     * Replace the character's active image references with the new crops.
     * Old active rows are deactivated (history stays), new rows carry the view
     * name in promptFragment so downstream prompt assembly can address a
     * specific panel.
     *
     * <p>The deactivation and the multi-row insert run in one transaction so a
     * single derivation is atomic: if two derivations ever race (e.g. a
     * duplicated completion event in dev mode), the last commit wins and
     * exactly one set of rows is active, instead of interleaved
     * update/insert chains leaving several sets active at once.
     * Fire-and-forget with logging: a failed store must not break the
     * image-completion update that triggered it.
     */
    private void storeReferenceRows(String characterId, List<Crop> crops) {
        StringBuilder values = new StringBuilder();
        Tuple params = Tuple.tuple();
        for (Crop crop : crops) {
            if (values.length() > 0) values.append(',');
            values.append("(?,?,?,?,?,?,TRUE)");
            String prefix = "ref_" + characterId.substring(0, Math.min(8, characterId.length())) + "_";
            params.addUUID(UUID.randomUUID());
            params.addString(characterId);
            params.addString("image");
            params.addString(prefix + crop.view + ".png");
            params.addString(crop.view);
            params.addDouble(crop.weight);
        }
        String insert = "INSERT INTO character_visual_ref "
            + "(refId, characterId, referenceType, referencePath, promptFragment, weight, isActive) VALUES "
            + values;
        pool.withTransaction(client ->
            client.preparedQuery(
                "UPDATE character_visual_ref SET isActive = FALSE WHERE characterId = ? AND referenceType = 'image'")
                .execute(Tuple.of(characterId))
                .chain(updated -> client.preparedQuery(insert).execute(params))
                .replaceWithVoid()
        ).subscribe().with(
            ok -> log.info("Stored {} sheet reference crops for character {}", crops.size(), characterId),
            err -> log.error("Failed to store sheet reference rows for character {}: {}",
                characterId, err.getMessage()));
    }
}
