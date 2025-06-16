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
import org.geotools.api.referencing.FactoryException;
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
            @DescribeParameter(name = "endTime2", description = "Ending Date Time for time period 2") String endTime2,
            @DescribeParameter(name = "outputCRS", description = "Change the default CRS to output", defaultValue = "EPSG:3857") String crs
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

        if (startDate1.after(endDate1)) {
            throw new ProcessException("Start date is after end date for date range 1");
        }
        if (startDate2.after(endDate2)) {
            throw new ProcessException("Start date is after end date for date range 2");
        }

        if (startDate1.compareTo(endDate1) == 0) {
            throw new ProcessException("Start date is equal to end date for date range 1");
        }
        if (startDate2.compareTo(endDate2) == 0) {
            throw new ProcessException("Start date is equal to end date for date range 2");
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
        List<GridCell> grid1 = gridCalculator.aggregate(range1);
        List<GridCell> grid2 = gridCalculator.aggregate(range2);

        List<SimpleFeature> results = new ArrayList<>();
        SimpleFeatureType resultType;
        try {
            resultType = gridCalculator.getResultFeatureType(crs);
        } catch (FactoryException e) {
            throw new ProcessException("Error decoding CRS value");
        }

        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(resultType);
        int fid = 0;

        for (GridCell cell: grid1) {
            double val1 = cell.average();
            double val2;
            if (!grid2.contains(cell)) {
                val2 = 0;
            } else {
                val2 = grid2.get(grid2.indexOf(cell)).average();
            }
            double change = val2-val1;
            builder.add(cell.getPolygon());
            builder.add(change);
            results.add(builder.buildFeature(String.valueOf(fid++)));
        }

        return new ListFeatureCollection(resultType, results);
    }
}
