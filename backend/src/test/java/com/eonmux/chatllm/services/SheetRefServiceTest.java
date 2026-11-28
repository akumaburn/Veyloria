package com.eonmux.chatllm.services;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the sheet panel table: each crop must come from the exact panel
 * box (wrong coordinates would sample a neighboring panel's color) and must
 * scale/clamp for non-1024 sheets.
 */
class SheetRefServiceTest {

    /** Expected (view, box, color) for the 1024x1024 sheet, in crop order. */
    private static final Object[][] EXPECTED = {
        {"front",          0,   0,  257, 512, 0xFF0000},
        {"three_quarter",  257, 0,  252, 512, 0x00FF00},
        {"head_shoulders", 0,   512, 257, 512, 0xFF00FF},
        {"side_profile",   509, 0,  254, 512, 0x0000FF},
        {"chest_waist",    257, 512, 252, 512, 0x00FFFF},
        {"back",           763, 0,  261, 512, 0xFFFF00},
        {"arm_hand",       509, 512, 254, 512, 0x800000},
        {"leg_foot",       763, 512, 261, 512, 0x008000},
    };

    private static BufferedImage sheet(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        double sx = width / 1024.0;
        double sy = height / 1024.0;
        for (Object[] e : EXPECTED) {
            int x = (int) Math.round(((int) e[1]) * sx);
            int y = (int) Math.round(((int) e[2]) * sy);
            int w = (int) Math.round(((int) e[3]) * sx);
            int h = (int) Math.round(((int) e[4]) * sy);
            g.setColor(new Color(((int) e[5]) & 0xFFFFFF));
            g.fillRect(x, y, w, h);
        }
        g.dispose();
        return img;
    }

    @Test
    void cropsMatchPanelBoxesOnNativeSheet() {
        List<SheetRefService.Crop> crops = SheetRefService.cropPanels(sheet(1024, 1024));
        assertEquals(EXPECTED.length, crops.size());
        for (int i = 0; i < EXPECTED.length; i++) {
            Object[] e = EXPECTED[i];
            SheetRefService.Crop crop = crops.get(i);
            assertEquals(e[0], crop.view, "crop order");
            assertEquals((int) e[3], crop.image.getWidth());
            assertEquals((int) e[4], crop.image.getHeight());
            int cx = crop.image.getWidth() / 2;
            int cy = crop.image.getHeight() / 2;
            int actual = crop.image.getRGB(cx, cy) & 0xFFFFFF;
            assertEquals((int) e[5], actual,
                "center color of crop " + e[0] + " must come from its own panel box");
        }
    }

    @Test
    void cropsScaleForHalfSizeSheet() {
        List<SheetRefService.Crop> crops = SheetRefService.cropPanels(sheet(512, 512));
        assertEquals(8, crops.size());
        // Half-size sheet: every crop box is halved, within 1px where the
        // origin and size rounding interact at the sheet edge.
        for (int i = 0; i < EXPECTED.length; i++) {
            Object[] e = EXPECTED[i];
            int expW = (int) Math.round(((int) e[3]) / 2.0);
            int expH = (int) Math.round(((int) e[4]) / 2.0);
            assertTrue(Math.abs(crops.get(i).image.getWidth() - expW) <= 1,
                e[0] + " width " + crops.get(i).image.getWidth() + " ~ " + expW);
            assertTrue(Math.abs(crops.get(i).image.getHeight() - expH) <= 1,
                e[0] + " height " + crops.get(i).image.getHeight() + " ~ " + expH);
        }
    }

    @Test
    void cropsClampAtSheetEdge() {
        // Sheet shorter than the panel table: bottom-row crops must clamp to
        // the edge instead of throwing.
        BufferedImage img = sheet(1024, 1000);
        List<SheetRefService.Crop> crops = SheetRefService.cropPanels(img);
        assertEquals(8, crops.size());
        for (SheetRefService.Crop crop : crops) {
            assertNotNull(crop.image);
            assertTrue(crop.image.getHeight() <= 1000);
        }
    }

    @Test
    void nullSheetRejected() {
        assertThrows(IllegalArgumentException.class, () -> SheetRefService.cropPanels(null));
    }
}
