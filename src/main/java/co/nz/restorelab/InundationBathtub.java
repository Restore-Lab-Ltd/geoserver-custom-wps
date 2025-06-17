package co.nz.restorelab;

import org.geoserver.catalog.*;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.coverage.grid.GridCoordinates;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.processing.Operations;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.geometry.Position2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

@DescribeProcess(title = "floodingInundationBathtub", description = "Runs Inundation Bathtub model on the inundation data points.")
public class InundationBathtub implements GeoServerProcess {
    Catalog catalog;

    InundationBathtub(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "The output from the bathtub model")
    public GridCoverage2D execute(
            @DescribeParameter(name = "startTime", description = "Starting Date Time for range") String startTime,
            @DescribeParameter(name = "endTime", description = "Ending Date Time for range") String endTime
//            @DescribeParameter(name = "outputCRS", description = "Change the default CRS to output", defaultValue = "EPSG:3857") String crs
    ) throws ProcessException {
        try {
            LayerInfo layerInfo = catalog.getLayerByName("restore-lab:flooded_measurements");
            ResourcePool resourcePool = catalog.getResourcePool();

            if (layerInfo == null) {
                throw new ProcessException("Layer not found");
            }

            FeatureTypeInfo featureTypeInfo = (FeatureTypeInfo) layerInfo.getResource();
            SimpleFeatureSource featureSource;
            CoordinateReferenceSystem pointCRS = featureTypeInfo.getCRS();
            try {
                featureSource = (SimpleFeatureSource) featureTypeInfo.getFeatureSource(null, null);
            } catch (IOException e) {
                throw new ProcessException("Error getting feature source", e);
            }

            // Convert the start and end times
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            Date startDate, endDate;
            try {
                startDate = sdf.parse(startTime);
                endDate = sdf.parse(endTime);
            } catch (Exception e) {
                throw new ProcessException("Error parsing date", e);
            }

            if (startDate.after(endDate)) {
                throw new ProcessException("Start is after the end date");
            }
            if (startDate.compareTo(endDate) == 0) {
                throw new ProcessException("Start date is equal to end date for input date range");
            }

            FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
            Expression timeAttr = filterFactory.property("utc_time");
            Filter timeFilter = filterFactory.between(timeAttr,
                    filterFactory.literal(startDate),
                    filterFactory.literal(endDate)
            );

            SimpleFeatureCollection featureCollection;
            try {
                featureCollection = featureSource.getFeatures(timeFilter);
            } catch (IOException e) {
                throw new ProcessException("Error getting features", e);
            }

            // Load in the DEM
            CoverageInfo demCoverage = catalog.getCoverageByName("restore-lab:NZ_DEM_4326_30m");
            GridCoverage2D dem = (GridCoverage2D) resourcePool.getGridCoverage(demCoverage, null, null);
            CoordinateReferenceSystem demCRS = dem.getCoordinateReferenceSystem2D();

            // Create a transform to go from source to target
            MathTransform transform = CRS.findMathTransform(pointCRS, demCRS, true);

            // Compute AOI BBOX and collect grid seeds
            double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            Deque<Point2D.Double> queue = new ArrayDeque<>();

            try (SimpleFeatureIterator it = featureCollection.features()) {
                while (it.hasNext()) {
                    SimpleFeature f = it.next();
                    // Convert from source CRS to target (dem)
                    Geometry geom3857 = (Geometry) f.getDefaultGeometry();
                    Geometry geom4326 = JTS.transform(geom3857, transform);
                    double lon = geom4326.getCoordinate().x;
                    double lat = geom4326.getCoordinate().y;
                    minLon = Math.min(minLon, lon);
                    maxLon = Math.max(maxLon, lon);
                    minLat = Math.min(minLat, lat);
                    maxLat = Math.max(maxLat, lat);

                    queue.add(new Point2D.Double(lon, lat));
                }
            }

            // Crop dem to AOI
            double buf = 0.1;
            ReferencedEnvelope aoiEnv = new ReferencedEnvelope(
                    minLon - buf, maxLon + buf,
                    minLat - buf, maxLat + buf,
                    demCRS
            );
            GridCoverage2D aoiDem = (GridCoverage2D) new Operations(null).crop(dem, aoiEnv);

            GridGeometry2D aoiGG = aoiDem.getGridGeometry();
            GridEnvelope aoiRange = aoiGG.getGridRange();
            int originX = aoiRange.getLow(0);
            int originY = aoiRange.getLow(1);
            RenderedImage renderedImage = aoiDem.getRenderedImage();
            int minX = renderedImage.getMinX();
            int minY = renderedImage.getMinY();
            int w = renderedImage.getWidth();
            int h = renderedImage.getHeight();

            Raster demRaster = renderedImage.getData(new Rectangle(minX, minY, w, h));

            Deque<Point> realQueue = new ArrayDeque<>();
            for (Point2D.Double worldPt : queue) {
                if (!aoiEnv.contains(new Position2D(worldPt))) {
                    System.out.println("Point outside AOI: " + worldPt);
                    continue;
                }
                try {
                    GridCoordinates globalGC = aoiGG.worldToGrid(
                            new Position2D(demCRS, worldPt.x, worldPt.y)
                    );
                    // these are in the original DEM pixel space:
                    int globalCol = globalGC.getCoordinateValue(0);
                    int globalRow = globalGC.getCoordinateValue(1);

                    // shift them to [0…w-1],[0…h-1] in the cropped grid:
                    int localCol = globalCol - originX;
                    int localRow = globalRow - originY;

                    if (localCol >= 0 && localCol < w && localRow >= 0 && localRow < h) {
                        realQueue.add(new Point(localCol, localRow));
                        System.out.println("Mapped to pixel: " +
                                localCol + "," + localRow);
                    } else {
                        System.out.println("Mapped pixel outside raster: " +
                                localCol + "," + localRow);
                    }
                } catch (Exception ex) {
                    System.out.println("worldToGrid error: " + ex.getMessage());
                }
            }

            // Run the bathtub model
            BitSet mask = new BitSet(w * h);
            System.out.println(realQueue.size());
            while (!realQueue.isEmpty()) {
                Point p = realQueue.pop();
                int x0 = p.x, y0 = p.y;

                if (x0 < 0 || x0 >= w || y0 < 0 || y0 >= h) continue;


                int idx = y0 * w + x0;
                if (mask.get(idx)) continue;

                double elevation = demRaster.getSampleDouble(x0 + minX, y0 + minY, 0);
                if (Double.isNaN(elevation)) continue;

                mask.set(idx);

                // Check 8 neighbors
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x0 + dx;
                        int ny = y0 + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;

                        int nIdx = ny * w + nx;
                        if (mask.get(nIdx)) continue;

                        double neighborElevation = demRaster.getSampleDouble(nx + minX, ny + minY, 0);
                        if (Double.isNaN(neighborElevation)) continue;

                        // Flood only if neighbor is lower or equal
                        if (neighborElevation <= elevation) {
                            realQueue.add(new Point(nx, ny));
                        }
                    }
                }
            }

            System.out.println("Flooded cells: " + mask.cardinality());

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int val = mask.get(i) ? 255 : 0;
                    img.getRaster().setSample(x, y, 0, val);
                }
            }
            ImageIO.write(img, "png", new File("/tmp/flood-debug.png"));

            SampleModel sm = new MultiPixelPackedSampleModel(
                    DataBuffer.TYPE_BYTE,
                    w,
                    h,
                    1
            );

            int bytesPerRow = (int) Math.ceil(w / 8.0);
            int packedBytes = bytesPerRow * h;
            byte[] packedData = new byte[packedBytes];


            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (mask.get(i)) {
                        int byteIndex = y * bytesPerRow + (x / 8);
                        int bitIndex = 7 - (x % 8); // MSB first
                        packedData[byteIndex] |= (byte) (1 << bitIndex);
                    }
                }
            }


            DataBufferByte dataBuffer = new DataBufferByte(packedData, packedData.length);
//
            WritableRaster outRaster = WritableRaster.createWritableRaster(sm, dataBuffer, new Point(0,0));
            GridCoverageFactory gridCoverageFactory = new GridCoverageFactory();
            return gridCoverageFactory.create("bathtub_flood", outRaster, aoiDem.getEnvelope2D());
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println(Arrays.toString(e.getStackTrace()));
        }
        return null;
    }
}