package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.production.PlotRoles.Role;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Draws what each of the player's own hexagons is on top of the finished map picture: production hexagons carry a
 * pickaxe inside a dashed line, house hexagons carry a house.
 * The plot's own colour underneath still says "this is yours"; the mark says what it is for. Pure drawing.
 */
final class ClaimMapRoleArt {
    /** The dashed line inside a production hexagon. */
    static final int PRODUCTION = 0xFFD27A;
    private static final Color INK = new Color(0x10, 0x1E, 0x22), LIGHT = new Color(0xF8, 0xF4, 0xE6), HALO = new Color(0x10, 0x1E, 0x22, 0xB8);

    private ClaimMapRoleArt() {}

    static void paint(BufferedImage image, ClaimMapGeometry g, Map<HexCoord, Role> roles) {
        if (roles == null || roles.isEmpty()) return;
        Graphics2D gfx = image.createGraphics();
        try {
            gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gfx.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            for (var entry : roles.entrySet()) {
                HexCoord cell = entry.getKey();
                var centre = g.centre(cell);
                if (!g.onPicture(centre.x(), centre.y())) continue;
                if (entry.getValue() == Role.PRODUCTION) {
                    // The plot's own colour stays (colour says whose land it is); a dashed amber line sets production apart.
                    gfx.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {7f, 6f}, 0f));
                    gfx.setColor(new Color((PRODUCTION >> 16) & 0xFF, (PRODUCTION >> 8) & 0xFF, PRODUCTION & 0xFF));
                    gfx.draw(ClaimMapRenderer.hexPath(g, cell, 7));
                }
                double x = centre.x(), y = centre.y();
                gfx.setColor(HALO);
                gfx.fill(new Ellipse2D.Double(x - 19, y - 19, 38, 38));
                switch (entry.getValue()) {
                    case PRODUCTION -> pickaxe(gfx, x, y);
                    case HOUSE -> house(gfx, x, y, 1.0, true);
                }
            }
        } finally {
            gfx.dispose();
        }
    }

    /** A pickaxe: a curved head over a slanted handle. */
    private static void pickaxe(Graphics2D gfx, double x, double y) {
        gfx.setStroke(new BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        gfx.setColor(new Color(0xC9, 0x8A, 0x4A));
        gfx.draw(new java.awt.geom.Line2D.Double(x - 9, y + 11, x + 6, y - 5));
        Path2D head = new Path2D.Double();
        head.moveTo(x - 6, y - 11);
        head.quadTo(x + 9, y - 13, x + 12, y + 3);
        gfx.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        gfx.setColor(LIGHT);
        gfx.draw(head);
    }

    /** A house: a roof over a body. Filled for the house hexagon, an outline for an extension. */
    private static void house(Graphics2D gfx, double x, double y, double scale, boolean filled) {
        double w = 13 * scale, h = 11 * scale, roof = 12 * scale;
        Path2D shape = new Path2D.Double();
        shape.moveTo(x - w - 3 * scale, y - 1 * scale);
        shape.lineTo(x, y - roof);
        shape.lineTo(x + w + 3 * scale, y - 1 * scale);
        shape.lineTo(x + w, y - 1 * scale);
        shape.lineTo(x + w, y + h);
        shape.lineTo(x - w, y + h);
        shape.lineTo(x - w, y - 1 * scale);
        shape.closePath();
        gfx.setColor(LIGHT);
        if (filled) {
            gfx.fill(shape);
            gfx.setColor(INK);
            gfx.fill(new java.awt.geom.Rectangle2D.Double(x - 3.2, y + h - 9, 6.4, 9));
        } else {
            gfx.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gfx.draw(shape);
        }
    }
}
