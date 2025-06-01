package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@DescribeProcess(title = "temporalGridChange", description = "Computes the gridded change between two date ranges for the soil moisture dataset.")
public class TemporalGridChange implements GeoServerProcess {
    Catalog catalog;

    TemporalGridChange(Catalog catalog) {
        this.catalog = catalog;
    }
    @DescribeResult(description = "The gridded change between the two date ranges.")
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

        GridCalculator gridCalculator = new GridCalculator(5000);
        Map<GridCell, Double> grid1 = gridCalculator.aggregate(range1);
        Map<GridCell, Double> grid2 = gridCalculator.aggregate(range2);

        List<SimpleFeature> results = new ArrayList<>();
        SimpleFeatureType resultType = gridCalculator.getResultFeatureType();
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(resultType);
        int fid = 0;

        for (GridCell cell: grid1.keySet()) {
            double val1 = grid1.getOrDefault(cell, 0.0);
            double val2 = grid2.getOrDefault(cell, 0.0);
            double change = val1-val2;
            builder.add(cell.getPolygon());
            builder.add(change);
            results.add(builder.buildFeature(String.valueOf(fid++)));
        }

        return new ListFeatureCollection(resultType, results);
    }
}
