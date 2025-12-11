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
    // Override with -Dbathtub.max.cells=<long>; 0 disables the cap
    private static final long MAX_OUTPUT_CELLS = Long.getLong("bathtub.max.cells", 0L);
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
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
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
            if (featureCollection == null) {
                throw new ProcessException("No features returned for given time range");
            }
            ReferencedEnvelope featureBounds = featureCollection.getBounds();
            if (featureBounds == null || featureBounds.isEmpty() || featureBounds.isNull()) {
                throw new ProcessException("No measurements found in the requested time range");
            }

            // Load in the DEM
            CoverageInfo demCoverage = catalog.getCoverageByName("restore-lab:NZ_DEM_4326_30m");
            ReferencedEnvelope demEnvelope = new ReferencedEnvelope(demCoverage.getNativeBoundingBox());
            ReferencedEnvelope bounds = featureBounds.transform(demCoverage.getCRS(), true);
            bounds = bounds.intersection(demEnvelope);
            if (bounds.isEmpty()) {
                throw new ProcessException("Measurement bounds do not intersect the DEM");
            }

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
            if (p.x < 0 || p.x >= width || p.y < 0 || p.y >= height) {
                continue;
            }
            int idx = p.y * width + p.x;
            if (mask.get(idx)) continue;
            mask.set(idx); // mark when queued to avoid duplicate enqueues
            queue.add(new int[]{p.x,p.y});
        }
        Rectangle tileRect = new Rectangle(0,0,256,256);
        while (!queue.isEmpty()) {
            System.out.println("queue size: " + queue.size());
            int[] p = queue.poll();
            int x = p[0], y = p[1];
            if (x < 0 || x >= width || y < 0 || y >= height) continue;

            int idx = y * width + x;
            // Already marked when enqueued; if seen again just skip
            if (!mask.get(idx)) {
                mask.set(idx);
            }

            tileRect.x = x + minX;
            tileRect.y = y + minY;
            tileRect.width = 1;
            tileRect.height = 1;

            Raster pointRaster = image.getData(tileRect);
            double elevation = pointRaster.getSampleDouble(tileRect.x, tileRect.y, 0);
            if (Double.isNaN(elevation)) continue;
            // Stop propagation into sea-level/NoData areas so the fill doesn't run across ocean tiles
            if (elevation <= 0) continue;

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
                if (neighbourElevation <= 0) continue;
                if (neighbourElevation <= elevation) {
                    mask.set(nIdx); // mark as soon as queued to prevent multiple queue entries
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
    ) throws ProcessException {
        long cells = (long) width * (long) height;
        if (cells <= 0) {
            throw new ProcessException("Invalid output raster dimensions");
        }
        if (MAX_OUTPUT_CELLS > 0 && cells > MAX_OUTPUT_CELLS) {
            throw new ProcessException("Requested output too large (" + cells + " cells). Reduce area or increase guardrail (bathtub.max.cells).");
        }

        GridCoverageFactory factory = new GridCoverageFactory();
        RenderedImage maskImage = new MaskRenderedImage(mask, width, height, 256, 256);
        return factory.create("bathtub_flood", maskImage, aoiDem.getEnvelope2D());
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

    /**
     * Lightweight RenderedImage that reads pixel bits from the in-memory BitSet and tiles on demand,
     * avoiding allocation of a full byte array for very large outputs.
     */
    private static final class MaskRenderedImage implements RenderedImage {
        private final BitSet mask;
        private final int width;
        private final int height;
        private final int tileWidth;
        private final int tileHeight;
        private final SampleModel sampleModel;
        private final ColorModel colorModel;

        MaskRenderedImage(BitSet mask, int width, int height, int tileWidth, int tileHeight) {
            this.mask = mask;
            this.width = width;
            this.height = height;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.sampleModel = new MultiPixelPackedSampleModel(DataBuffer.TYPE_BYTE, tileWidth, tileHeight, 1);
            byte[] ramp = new byte[]{0, (byte) 255};
            this.colorModel = new IndexColorModel(1, 2, ramp, ramp, ramp);
        }

        @Override
        public Vector<RenderedImage> getSources() {
            return null;
        }

        @Override
        public Object getProperty(String name) {
            return java.awt.Image.UndefinedProperty;
        }

        @Override
        public String[] getPropertyNames() {
            return new String[0];
        }

        @Override
        public ColorModel getColorModel() {
            return colorModel;
        }

        @Override
        public SampleModel getSampleModel() {
            return sampleModel;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getMinX() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }

        @Override
        public int getNumXTiles() {
            return (int) Math.ceil((double) width / tileWidth);
        }

        @Override
        public int getNumYTiles() {
            return (int) Math.ceil((double) height / tileHeight);
        }

        @Override
        public int getMinTileX() {
            return 0;
        }

        @Override
        public int getMinTileY() {
            return 0;
        }

        @Override
        public int getTileWidth() {
            return tileWidth;
        }

        @Override
        public int getTileHeight() {
            return tileHeight;
        }

        @Override
        public int getTileGridXOffset() {
            return 0;
        }

        @Override
        public int getTileGridYOffset() {
            return 0;
        }

        @Override
        public Raster getTile(int tileX, int tileY) {
            if (tileX < 0 || tileY < 0 || tileX >= getNumXTiles() || tileY >= getNumYTiles()) {
                throw new IllegalArgumentException("Requested tile outside image");
            }
            int x = tileX * tileWidth;
            int y = tileY * tileHeight;
            int tw = Math.min(tileWidth, width - x);
            int th = Math.min(tileHeight, height - y);

            SampleModel sm = sampleModel.createCompatibleSampleModel(tw, th);
            int bytesPerRow = (int) Math.ceil(tw / 8.0);
            byte[] data = new byte[bytesPerRow * th];

            for (int row = 0; row < th; row++) {
                int globalY = y + row;
                int rowOffset = row * bytesPerRow;
                int baseIndex = globalY * width;
                for (int col = 0; col < tw; col++) {
                    int globalX = x + col;
                    int bitIndex = baseIndex + globalX;
                    if (mask.get(bitIndex)) {
                        int byteIndex = rowOffset + (col >> 3);
                        int bit = 7 - (col & 7);
                        data[byteIndex] |= (byte) (1 << bit);
                    }
                }
            }

            DataBufferByte db = new DataBufferByte(data, data.length);
            return WritableRaster.createWritableRaster(sm, db, new Point(x, y));
        }

        @Override
        public Raster getData() {
            return getData(new Rectangle(0, 0, width, height));
        }

        @Override
        public Raster getData(Rectangle rect) {
            Rectangle clip = new Rectangle(0, 0, width, height).intersection(rect);
            if (clip.isEmpty()) {
                throw new IllegalArgumentException("Requested region outside image");
            }
            int w = clip.width;
            int h = clip.height;
            SampleModel sm = sampleModel.createCompatibleSampleModel(w, h);
            int bytesPerRow = (int) Math.ceil(w / 8.0);
            byte[] data = new byte[bytesPerRow * h];

            for (int row = 0; row < h; row++) {
                int globalY = clip.y + row;
                int rowOffset = row * bytesPerRow;
                int baseIndex = globalY * width;
                for (int col = 0; col < w; col++) {
                    int globalX = clip.x + col;
                    int bitIndex = baseIndex + globalX;
                    if (mask.get(bitIndex)) {
                        int byteIndex = rowOffset + (col >> 3);
                        int bit = 7 - (col & 7);
                        data[byteIndex] |= (byte) (1 << bit);
                    }
                }
            }
            DataBufferByte db = new DataBufferByte(data, data.length);
            return WritableRaster.createWritableRaster(sm, db, new Point(clip.x, clip.y));
        }

        @Override
        public WritableRaster copyData(WritableRaster raster) {
            if (raster == null) {
                raster = (WritableRaster) getData();
                return raster;
            }
            Rectangle target = raster.getBounds();
            Raster src = getData(target);
            raster.setRect(src);
            return raster;
        }
    }
}
