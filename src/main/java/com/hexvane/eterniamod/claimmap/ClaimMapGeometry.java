package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLattice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where everything sits on the claim map: a fixed window of {@link #COLUMNS} by {@link #ROWS} cells, always snapped to
 * the world's lattice, drawn north up at {@link #SCALE} pixels per block. Pure and immutable.
 *
 * <p>Because the window always starts on an even lattice row and a whole cell column, every cell lands on the same
 * pixel spot whatever part of the world is shown. So the clickable cell buttons are laid out once when the page opens,
 * and panning only swaps the picture and which cell each button means.
 *
 * <p>Each button is a "brick": the cell's full width by one row pitch, centred on the cell. Bricks tile the window with
 * no gaps (odd rows are shifted half a cell, so they start and end with a half brick) and each covers most of its own
 * hexagon; only the small tip triangles fall in a neighbour's brick. {@link #cellAtPixel} is the exact answer.
 */
public record ClaimMapGeometry(HexLattice lattice, int column0, int row0) {
    /** Picture pixels per block. A 32 block cell is 80 by 90 pixels. */
    public static final double SCALE = 2.5;
    /** Whole cells across the window (even rows). */
    public static final int COLUMNS = 11;
    /** Rows of cells down the window. */
    public static final int ROWS = 11;
    /** How far the arrows move the window: columns east or west, rows north or south (even, to keep the layout). */
    public static final int PAN_COLUMNS = 3;
    public static final int PAN_ROWS = 4;

    public enum SlotKind { FULL, WEST_HALF, EAST_HALF }

    /** One clickable brick: its index in the page's button list, the cell it means, and its pixel box. */
    public record Slot(int index, HexCoord cell, int left, int top, int width, int height, SlotKind kind) {}

    /** A pixel point on the picture (may be outside it). */
    public record Point(double x, double y) {}

    public ClaimMapGeometry {
        Objects.requireNonNull(lattice);
        if ((row0 & 1) != 0) throw new IllegalArgumentException("The map window must start on an even row");
    }

    /** The window whose middle cell is the one holding block column (x, z). */
    public static ClaimMapGeometry centredOn(HexLattice lattice, double x, double z) {
        HexCoord cell = lattice.cellAt((int) Math.floor(x), (int) Math.floor(z));
        int row = cell.r() - ROWS / 2;
        if ((row & 1) != 0) row--;
        int column = offsetColumn(cell) - COLUMNS / 2;
        return new ClaimMapGeometry(lattice, column, row);
    }

    /** Offset column of an axial cell (odd rows are shifted half a cell east). */
    public static int offsetColumn(HexCoord cell) { return cell.q() + Math.floorDiv(cell.r(), 2); }

    /** Axial cell at an offset column and row. */
    public static HexCoord cellAtOffset(int column, int row) { return new HexCoord(column - Math.floorDiv(row, 2), row); }

    public ClaimMapGeometry panned(int columns, int rows) {
        if ((rows & 1) != 0) throw new IllegalArgumentException("Pan by an even number of rows");
        return new ClaimMapGeometry(lattice, column0 + columns, row0 + rows);
    }

    public static int width() { return (int) Math.round(COLUMNS * DEFAULT_CELL_WIDTH * SCALE); }

    public static int height() { return (int) Math.round(ROWS * DEFAULT_ROW_PITCH * SCALE); }

    private static final int DEFAULT_CELL_WIDTH = HexLattice.DEFAULT.cellWidth();
    private static final int DEFAULT_ROW_PITCH = HexLattice.DEFAULT.rowPitch();

    /** Picture size in pixels for this lattice. */
    public int pixelWidth() { return (int) Math.round(COLUMNS * lattice.cellWidth() * SCALE); }

    public int pixelHeight() { return (int) Math.round(ROWS * lattice.rowPitch() * SCALE); }

    /** World block X at the picture's left edge. */
    public double worldX0() { return lattice.originX() + (double) column0 * lattice.cellWidth(); }

    /** World block Z at the picture's top edge: the top of the first row's bricks. */
    public double worldZ0() { return lattice.originZ() + (double) row0 * lattice.rowPitch() + (lattice.cellDepth() - lattice.rowPitch()) / 2.0; }

    public double worldWidth() { return (double) COLUMNS * lattice.cellWidth(); }

    public double worldHeight() { return (double) ROWS * lattice.rowPitch(); }

    /** Picture pixel of a world point given in block edge coordinates. */
    public Point pixel(double worldX, double worldZ) { return new Point((worldX - worldX0()) * SCALE, (worldZ - worldZ0()) * SCALE); }

    /** Picture pixel of a cell's centre. */
    public Point centre(HexCoord cell) { return pixel(lattice.centerX(cell), lattice.centerZ(cell)); }

    /** The cell drawn at a picture pixel (exact hexagon, not the brick). */
    public HexCoord cellAtPixel(double px, double py) {
        return lattice.cellAt((int) Math.floor(worldX0() + px / SCALE), (int) Math.floor(worldZ0() + py / SCALE));
    }

    /** True when the picture pixel is on the map. */
    public boolean onPicture(double px, double py) { return px >= 0 && py >= 0 && px < pixelWidth() && py < pixelHeight(); }

    /** Every clickable brick, row by row from the top, west to east. The list is the same for every window. */
    public List<Slot> slots() {
        List<Slot> out = new ArrayList<>(COLUMNS * ROWS + ROWS);
        double cw = lattice.cellWidth() * SCALE, pitch = lattice.rowPitch() * SCALE;
        int pictureWidth = pixelWidth();
        for (int i = 0; i < ROWS; i++) {
            int row = row0 + i;
            boolean odd = (i & 1) == 1;
            int top = (int) Math.round(i * pitch), bottom = (int) Math.round((i + 1) * pitch);
            int first = odd ? -1 : 0;
            for (int j = first; j < COLUMNS; j++) {
                double left = j * cw + (odd ? cw / 2 : 0);
                int l = (int) Math.round(Math.max(0, left)), r = (int) Math.round(Math.min(pictureWidth, left + cw));
                SlotKind kind = left < 0 ? SlotKind.WEST_HALF : left + cw > pictureWidth + 0.5 ? SlotKind.EAST_HALF : SlotKind.FULL;
                out.add(new Slot(out.size(), cellAtOffset(column0 + j, row), l, top, r - l, bottom - top, kind));
            }
        }
        return List.copyOf(out);
    }

    /** Number of bricks (the same for every window). */
    public static int slotCount() { return COLUMNS * ROWS + ROWS / 2; }

    /** The cell a brick means in this window, or null for an index that is not on the map. */
    public HexCoord cellOfSlot(int index) {
        if (index < 0 || index >= slotCount()) return null;
        int remaining = index;
        for (int i = 0; i < ROWS; i++) {
            boolean odd = (i & 1) == 1;
            int count = odd ? COLUMNS + 1 : COLUMNS;
            if (remaining < count) return cellAtOffset(column0 + remaining - (odd ? 1 : 0), row0 + i);
            remaining -= count;
        }
        return null;
    }

    /** Every cell any part of which shows on the picture (one ring beyond the bricks, for the tips). */
    public List<HexCoord> visibleCells() {
        List<HexCoord> out = new ArrayList<>();
        for (int row = row0 - 1; row <= row0 + ROWS; row++)
            for (int column = column0 - 1; column <= column0 + COLUMNS; column++) out.add(cellAtOffset(column, row));
        return out;
    }

    /** True when the cell's brick is on the map (so the player can click it here). */
    public boolean shows(HexCoord cell) {
        int row = cell.r() - row0;
        if (row < 0 || row >= ROWS) return false;
        int column = offsetColumn(cell) - column0;
        return (row & 1) == 1 ? column >= -1 && column < COLUMNS : column >= 0 && column < COLUMNS;
    }
}
