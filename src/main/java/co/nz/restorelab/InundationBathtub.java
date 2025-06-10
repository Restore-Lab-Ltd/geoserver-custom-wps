package co.nz.restorelab;

import org.geoserver.catalog.*;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.coverage.grid.GridCoordinates;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.parameter.ParameterValueGroup;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
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
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Geometry;

import javax.media.jai.RasterFactory;
import java.awt.*;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

@DescribeProcess(title = "floodingInundationBathtub", description = "Runs Inundation Bathtub model on the inundation data points.")
public class InundationBathtub implements GeoServerProcess {
    Catalog catalog;

    InundationBathtub(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "The output from the bathtub model")
    public GridCoverage2D execute(
            @DescribeParameter(name = "startTime", description = "Starting Date Time for range") String startTime,
            @DescribeParameter(name = "endTime", description = "Ending Date Time for range") String endTime,
            @DescribeParameter(name = "outputCRS", description = "Change the default CRS to output", defaultValue = "EPSG:3857") String crs
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
            Deque<Point> queue = new ArrayDeque<>();

            GridGeometry2D gridGeometry = dem.getGridGeometry();

            try (SimpleFeatureIterator it = featureCollection.features()) {
                while (it.hasNext()) {
                    SimpleFeature f = it.next();
                    // Convert from source CRS to target (dem)
                    Geometry geom3857 = (Geometry) f.getDefaultGeometry();
                    Geometry geom4326 = JTS.transform(geom3857, transform);
                    double lon = geom4326.getCoordinate().x;
                    double lat = geom4326.getCoordinate().y;
                    minLon = Math.min(minLon, lon);
                    maxLon = Math.min(maxLon, lon);
                    minLat = Math.min(minLat, lat);
                    maxLat = Math.min(maxLat, lat);

                    GridCoordinates gc = gridGeometry.worldToGrid(new Position2D(demCRS, lon, lat));
                    int col = gc.getCoordinateValue(0), row = gc.getCoordinateValue(1);
                    queue.add(new Point(col, row));
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

            RenderedImage renderedImage = aoiDem.getRenderedImage();
            Raster demRaster = renderedImage.getData();
            int w = demRaster.getWidth(), h = demRaster.getHeight();

            double[][] elevation = new double[h][w];
            double noData = Double.NaN;
            // try to get a NoData value from the DEM if defined
            ParameterValueGroup params = demCoverage.getGridCoverageReader(null, null)
                    .getFormat()
                    .getReadParameters();

            Object ndv = params.parameter("NoData").getValue();
            if (ndv instanceof Number) noData = ((Number) ndv).doubleValue();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    elevation[y][x] = demRaster.getSampleDouble(x, y, 0);
                }
            }
            // Run the bathtub model
            byte[][] mask = new byte[h][w];
            while (!queue.isEmpty()) {
                Point p = queue.pop();
                int x0 = p.x, y0 = p.y;
                if (x0 < 0 || x0 > w || y0 < 0 || y0 > h) continue;
                if (mask[y0][x0] == 1) continue;
                mask[y0][x0] = 1;

                boolean cont = true;
                while (cont) {
                    double cur = elevation[y0][x0];
                    double maxDh = 0;
                    int bestDx = 0, bestDy = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x0 + dx, ny = y0 + dy;
                            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                            if (mask[ny][nx] == 1) continue;
                            double neighbours = elevation[ny][nx];
                            if (Double.isNaN(neighbours) || neighbours == noData) continue;
                            double dh = cur - neighbours;
                            if (dh > maxDh) {
                                maxDh = dh;
                                bestDx = dx;
                                bestDy = dy;
                            }
                        }
                    }
                    if (maxDh > 0) {
                        queue.add(new Point(x0 + bestDx, y0 + bestDy));
                        x0 += bestDx; y0 += bestDy;
                    } else {
                        cont = false;
                    }
                }
            }

            // Build output byte raster
            WritableRaster outRaster = RasterFactory
                    .createBandedRaster(DataBuffer.TYPE_BYTE, w, h, 1 ,null);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    outRaster.setSample(x, y, 0, mask[y][x]);
                }
            }

            GridCoverageFactory gridCoverageFactory = new GridCoverageFactory();
            return gridCoverageFactory.create("bathtub_flood", outRaster, aoiDem.getEnvelope2D());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return null;
//        return new DefaultFeatureCollection();
    }
}