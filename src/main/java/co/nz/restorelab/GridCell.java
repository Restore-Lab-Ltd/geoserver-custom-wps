package co.nz.restorelab;

import org.locationtech.jts.geom.Polygon;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class GridCell {
    private final int row;
    private final int col;
    private final Polygon polygon;
    private final List<Double> values = new ArrayList<>();

    public GridCell(int col, int row, Polygon polygon, double smc_value) {
        this.col = col;
        this.row = row;
        this.polygon = polygon;
        this.values.add(smc_value);
    }

    public double average() {
        return values.stream().reduce(Double::sum).orElse(0.0) / values.size();
    }

    public void addAllValues(List<Double> oldValues) {
        values.addAll(oldValues);
    }

    public List<Double> getValues() {
        return values;
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

    @Override
    public String toString() {
        return "GridCell[col=" + col + ", row="+row+", polygon="+polygon+", value="+values;
    }
}
