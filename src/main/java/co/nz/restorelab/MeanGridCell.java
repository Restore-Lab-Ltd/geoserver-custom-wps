package co.nz.restorelab;

import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;

public class MeanGridCell {
    private final Geometry polygon;
    private final List<Double> values = new ArrayList<>();

    public MeanGridCell(Geometry polygon, double smcValue) {
        this.polygon = polygon;
        this.values.add(smcValue);
    }

    public double getAverage() {
        return values.stream().reduce(Double::sum).orElse(0.0) / values.size();
    }

    public void addValue(double value) {
        values.add(value);
    }

    public Geometry getPolygon() {
        return polygon;
    }
}
