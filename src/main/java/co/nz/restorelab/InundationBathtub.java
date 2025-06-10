package co.nz.restorelab;

import org.geoserver.catalog.*;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.coverage.Coverage;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.geometry.Bounds;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.coverage.processing.Operations;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Point;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

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
            Filter timeFilter = filterFactory.between(timeAttr, filterFactory.literal(startDate), filterFactory.literal(endDate));

            SimpleFeatureCollection featureCollection;
            try {
                featureCollection = featureSource.getFeatures(timeFilter);
            } catch (IOException e) {
                throw new ProcessException("Error getting features", e);
            }

            // Compute AOI BBOX
            double minLon = Double.POSITIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;

            try (SimpleFeatureIterator it = featureCollection.features()) {
                while (it.hasNext()) {
                    SimpleFeature f = it.next();
                    Point geom = (Point) f.getDefaultGeometry();
                    double lon = geom.getX();
                    double lat = geom.getY();
                    minLon = Math.min(minLon, lon);
                    maxLon = Math.min(maxLon, lon);
                    minLat = Math.min(minLat, lat);
                    maxLat = Math.min(maxLat, lat);
                }
            }
            double buf = 0.1;
            minLon -= buf; maxLon += buf;
            minLat -= buf; maxLat += buf;

            // Load in the DEM
            CoverageInfo demCoverage = catalog.getCoverageByName("restore-lab:NZ_DEM_4326_30m");
            GridCoverage2D dem = (GridCoverage2D) resourcePool.getGridCoverage(demCoverage, null, null);

            // Crop DEM to AOI BBOX
            ReferencedEnvelope aioEnv = new ReferencedEnvelope(minLon, maxLon, minLat, maxLat, CRS.decode("EPSG:3857"));
            GridCoverage2D aoiDem = (GridCoverage2D) new Operations(null).crop(dem, aioEnv);
            return aoiDem;

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return null;
//        return new DefaultFeatureCollection();
    }
}