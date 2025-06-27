package co.nz.restorelab;

import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.NoninvertibleTransformException;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.*;

import java.util.*;

public class GridCalculator {
    private final double cellSize;
    private final GeometryFactory geometryFactory;
    private final MathTransform transform;
    private final MathTransform inverseTransform;

    public GridCalculator(double cellSize) throws FactoryException, NoninvertibleTransformException {
        this.cellSize = cellSize;
        this.geometryFactory = JTSFactoryFinder.getGeometryFactory();
        CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:3857");
        // Use NZTM (EPSG:2193) for the grid calculations
        CoordinateReferenceSystem gridCRS = CRS.decode("EPSG:2193");
        this.transform = CRS.findMathTransform(sourceCRS, gridCRS, true);
        this.inverseTransform = transform.inverse();
    }

    public List<GridCell> aggregate(SimpleFeatureCollection features) {
        double minX = 800000;   // western extent of NZ in NZTM
        double minY = 4700000;  // southern extent of NZ in NZTM

        List<GridCell> counts = new ArrayList<>();

        try (SimpleFeatureIterator featureIterator = features.features()) {
            while (featureIterator.hasNext()) {
                SimpleFeature feature = featureIterator.next();
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom == null) continue;

                // Transform from 3857 to NZTM
                Geometry transformedGeom = JTS.transform(geom, transform);
                Envelope geomEnv = transformedGeom.getEnvelopeInternal();

                int colStart = (int) Math.floor((geomEnv.getMinX() - minX) / cellSize);
                int colEnd = (int) Math.floor((geomEnv.getMaxX() - minX) / cellSize);
                int rowStart = (int) Math.floor((geomEnv.getMinY() - minY) / cellSize);
                int rowEnd = (int) Math.floor((geomEnv.getMaxY() - minY) / cellSize);

                for (int col = colStart; col <= colEnd; col++) {
                    for (int row = rowStart; row <= rowEnd; row++) {
                        double cellMinX = minX + col * cellSize;
                        double cellMinY = minY + row * cellSize;
                        Polygon cellPolygon = createCell(cellMinX, cellMinY, cellSize);

                        if (geom.intersects(cellPolygon)) {
                            GridCell cell = new GridCell(col, row, cellPolygon, (Double) feature.getAttribute("smc_mat"));
                            if (counts.contains(cell)) {
                                cell.addAllValues(counts.get(counts.indexOf(cell)).getValues());
                                counts.remove(cell);
                            }
                            counts.add(cell);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error processing features", e);
        }
        return counts;
    }

    public SimpleFeatureType getResultFeatureType(String outputCrs) throws FactoryException {
        SimpleFeatureTypeBuilder featureTypeBuilder = new SimpleFeatureTypeBuilder();
        featureTypeBuilder.setName("gridcell");
        featureTypeBuilder.setCRS(CRS.decode(outputCrs));
        featureTypeBuilder.add("geometry", Polygon.class);
        featureTypeBuilder.add("value", Double.class);
        return featureTypeBuilder.buildFeatureType();
    }

    private Polygon createCell(double minX, double minY, double size) {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(minX, minY),
                new Coordinate(minX + size, minY),
                new Coordinate(minX + size, minY + size),
                new Coordinate(minX, minY + size),
                new Coordinate(minX, minY),
        };
        LinearRing ring = geometryFactory.createLinearRing(coords);
        Polygon poly = geometryFactory.createPolygon(ring);

        try {
            // Transform the cell back to the requested CRS
            return (Polygon) JTS.transform(poly, inverseTransform);
        } catch (Exception e) {
            throw new RuntimeException("Error transforming grid cell", e);
        }
    }
}
