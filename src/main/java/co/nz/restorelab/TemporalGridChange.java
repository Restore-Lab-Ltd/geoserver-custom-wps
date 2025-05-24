package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.filter.expression.PropertyName;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

@DescribeProcess(title = "temporalGridChange", description = "Computes the gridded change between two date ranges for the soil moisture dataset.")
public class TemporalGridChange implements GeoServerProcess {
    Catalog catalog;

    TemporalGridChange(Catalog catalog) {
        this.catalog = catalog;
    }
    @DescribeResult(name = "result", description = "The gridded change between the two date ranges.")
    public SimpleFeatureCollection execute(
            @DescribeParameter(name = "startTime1", description = "Starting Date Time for time period 1") String startTime1,
            @DescribeParameter(name = "endTime1", description = "Ending Date Time for time period 1") String endTime1,
            @DescribeParameter(name = "startTime2", description = "Starting Date Time for time period 2") String startTime2,
            @DescribeParameter(name = "endTime2", description = "Ending Date Time for time period 2") String endTime2
    ) throws ProcessException {
        LayerInfo layerInfo = catalog.getLayerByName("restore-lab:smc_measurements");

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
        Date startDate1, endDate1, startDate2, endDate2;
        try {
            startDate1 = sdf.parse(startTime1);
            endDate1 = sdf.parse(endTime1);
            startDate2 = sdf.parse(startTime2);
            endDate2 = sdf.parse(endTime2);
        } catch (Exception e) {
            throw new ProcessException("Error parsing date", e);
        }

        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttr = filterFactory.property("utc_time");
        Filter timeFilter1 = filterFactory.between(timeAttr, filterFactory.literal(startDate1), filterFactory.literal(endDate1));
        Filter timeFilter2 = filterFactory.between(timeAttr, filterFactory.literal(startDate2), filterFactory.literal(endDate2));

        SimpleFeatureCollection range1, range2;
        try {
            range1 = featureSource.getFeatures(timeFilter1);
            range2 = featureSource.getFeatures(timeFilter2);
        } catch (IOException e) {
            throw new ProcessException("Error getting features", e);
        }
        try (SimpleFeatureIterator iterator = range1.features()) {

            while (iterator.hasNext()) {
                SimpleFeature feature = iterator.next();
                System.out.println("Feature: " + feature.getID());
            }
        }

        return range1;
    }
}
