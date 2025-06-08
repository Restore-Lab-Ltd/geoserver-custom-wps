package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourcePool;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;

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


        } catch (Exception ignored) {

        }
        return null;
//        return new DefaultFeatureCollection();
    }
}