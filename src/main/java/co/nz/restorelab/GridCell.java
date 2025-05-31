package co.nz.restorelab;

import org.locationtech.jts.geom.Polygon;

import java.util.Objects;

public class GridCell {
    private final int row;
    private final int col;
    private final Polygon polygon;

    public GridCell(int col, int row, Polygon polygon) {
        this.col = col;
        this.row = row;
        this.polygon = polygon;
    }

    public Polygon getPolygon() {
        return polygon;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        GridCell gridCell = (GridCell) o;
        return row == gridCell.row && col == gridCell.col;
    }

    @Override
    public int hashCode() {
        return Objects.hash(row, col);
    }
}
