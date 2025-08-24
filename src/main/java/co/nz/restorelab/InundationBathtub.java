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
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.coverage.grid.*;
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

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.*;
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
            ReferencedEnvelope bounds = featureCollection.getBounds().transform(demCoverage.getCRS(), true);

            GridCoverage2D fullDem = (GridCoverage2D) resourcePool.getGridCoverage(demCoverage, null, null);
            CoordinateReferenceSystem demCRS = fullDem.getCoordinateReferenceSystem2D();

            //Crop DEM
            GridCoverage2D aoiDem = (GridCoverage2D) new Operations(null).crop(fullDem, bounds);
            MathTransform transform = CRS.findMathTransform(pointCRS, demCRS, true);

            // Crop dem to AOI
            RenderedImage renderedImage = aoiDem.getRenderedImage();

            Deque<Point> seedPoints = transformPoints(
                    featureCollection,
                    aoiDem.getGridGeometry(),
                    transform, bounds
                    );

            // Run the bathtub model
            BitSet mask = runBathtubModel(renderedImage, seedPoints, renderedImage.getWidth(), renderedImage.getHeight(), renderedImage.getMinX(), renderedImage.getMinY());
            return createFloodCoverage(mask, renderedImage.getWidth(), renderedImage.getHeight(), aoiDem);
        } catch (TransformException | IOException | FactoryException e) {
            throw new ProcessException(e);
        }
    }

    private BitSet runBathtubModel(RenderedImage image, Deque<Point> seedPoints, int width, int height, int minX, int minY) {
        BitSet mask = new BitSet(width * height);

        final int[] dx = {-1,0,1,-1,1,-1,0,1};
        final int[] dy = {-1,-1,-1,0,0,1,1,1};
        ArrayDeque<int[]> queue = new ArrayDeque<>(seedPoints.size()*8);

        for (Point p : seedPoints) {
            queue.add(new int[]{p.x,p.y});
        }
        Rectangle tileRect = new Rectangle(0,0,256,256);
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int x = p[0], y = p[1];
            if (x < 0 || x >= width || y < 0 || y>= height) continue;

            int idx = y * width + x;
            if (mask.get(idx)) continue;

            tileRect.x = x + minX;
            tileRect.y = y + minY;
            tileRect.width = 1;
            tileRect.height = 1;

            Raster pointRaster = image.getData(tileRect);
            double elevation = pointRaster.getSampleDouble(tileRect.x, tileRect.y, 0);
            if (Double.isNaN(elevation)) continue;

            mask.set(idx);

            for (int i = 0; i < 8; i++) {
                int nx = x + dx[i];
                int ny = y + dy[i];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;

                int nIdx = ny * width + nx;
                if (mask.get(nIdx)) continue;

                tileRect.x = nx + minX;
                tileRect.y = ny + minY;

                Raster neighbourRaster = image.getData(tileRect);
                double neighbourElevation = neighbourRaster.getSampleDouble(tileRect.x, tileRect.y, 0);

                if (Double.isNaN(neighbourElevation)) continue;
                if (neighbourElevation < elevation) {
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return mask;
    }

    private GridCoverage2D createFloodCoverage(
            BitSet mask,
            int width,
            int height,
            GridCoverage2D aoiDem
    ) {
        int bytesPerRow = (int) Math.ceil(width/8.0);
        byte[] packedData = new byte[bytesPerRow * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (mask.get(i)) {
                    int byteIndex = y * bytesPerRow + (x/8);
                    int bitIndex = 7 - (x%8);
                    packedData[byteIndex] |= (byte) (1 << bitIndex);
                }
            }
        }

        SampleModel sm = new MultiPixelPackedSampleModel(DataBuffer.TYPE_BYTE, width, height, 1);
        DataBufferByte dataBuffer = new DataBufferByte(packedData, packedData.length);
        WritableRaster outRaster = WritableRaster.createWritableRaster(sm, dataBuffer, new Point(0,0));

        GridCoverageFactory factory = new GridCoverageFactory();
        return factory.create("bathtub_flood", outRaster, aoiDem.getEnvelope2D());
    }

    private Deque<Point> transformPoints(SimpleFeatureCollection featureCollection, GridGeometry2D gridGeometry, MathTransform transform, ReferencedEnvelope aoiEnv) {
        CoordinateReferenceSystem demCRS = gridGeometry.getCoordinateReferenceSystem();
        GridEnvelope range = gridGeometry.getGridRange();
        int originX = range.getLow(0);
        int originY = range.getLow(1);

        Deque<Point> result = new ArrayDeque<>();

        try (SimpleFeatureIterator it = featureCollection.features()) {
            while (it.hasNext()) {
                SimpleFeature f = it.next();
                Geometry geom = (Geometry) f.getDefaultGeometry();

                Geometry transformed = JTS.transform(geom, transform);

                Point2D.Double worldPt = new Point2D.Double(
                        transformed.getCoordinate().x,
                        transformed.getCoordinate().y
                );

                if (!aoiEnv.contains(worldPt.x, worldPt.y)) continue;

                GridCoordinates gc = gridGeometry.worldToGrid(
                        new Position2D(demCRS, worldPt.x, worldPt.y)
                );
                int localCol = gc.getCoordinateValue(0) - originX;
                int localRow = gc.getCoordinateValue(1) - originY;

                result.add(new Point(localCol, localRow));
            }
        } catch (TransformException e) {
            throw new ProcessException("Error in transforming point geometry");
        }
        return result;
    }
}