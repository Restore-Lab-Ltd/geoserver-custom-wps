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
import org.geotools.api.referencing.operation.NoninvertibleTransformException;
import org.geotools.api.util.ProgressListener;
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
import java.util.Calendar;
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
            @DescribeParameter(name = "startTime1", description = "Starting Date Time for time period 1") String startTime,
            @DescribeParameter(name = "endTime1", description = "Ending Date Time for time period 1") String endTime,
            @DescribeParameter(name = "year", description = "Year to compare the time range to.") int year,
            @DescribeParameter(name = "outputCRS", description = "Change the default CRS to output", defaultValue = "EPSG:3857") String crs,
            ProgressListener listener
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
        Date startDate, endDate;
        try {
            startDate = sdf.parse(startTime);
            endDate = sdf.parse(endTime);
        } catch (Exception e) {
            throw new ProcessException("Error parsing date", e);
        }

        if (startDate.after(endDate)) {
            throw new ProcessException("Start date is after end date for date range 1");
        }

        if (startDate.compareTo(endDate) == 0) {
            throw new ProcessException("Start date is equal to end date for date range 1");
        }

        Calendar calStart = Calendar.getInstance();
        Calendar calEnd = Calendar.getInstance();

        calStart.setTime(startDate);
        calStart.set(Calendar.YEAR, year); // set to requested year to compare
        int startDay = calStart.get(Calendar.DAY_OF_YEAR);
        calEnd.setTime(endDate);
        calEnd.set(Calendar.YEAR, year); // set to requested year to compare
        int endDay = calEnd.get(Calendar.DAY_OF_YEAR);
        int gridSize;
        if (endDay-startDay > 7) {
            gridSize = 800;
            // Use mean for the month
            calStart.set(Calendar.DAY_OF_MONTH, calStart.getActualMinimum(Calendar.DAY_OF_MONTH));
            calEnd.set(Calendar.DAY_OF_MONTH, calEnd.getActualMaximum(Calendar.DAY_OF_MONTH));
        } else {
            gridSize = 2000;
            // Use week means
            calStart.set(Calendar.DAY_OF_WEEK, calStart.getFirstDayOfWeek());
            calEnd.set(Calendar.DAY_OF_WEEK, 8);
        }
        Date compareStart = calStart.getTime();
        Date compareEnd = calEnd.getTime();

        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttr = filterFactory.property("utc_time");
        Filter timeFilter1 = filterFactory.between(timeAttr, filterFactory.literal(startDate), filterFactory.literal(endDate));
        Filter timeFilter2 = filterFactory.between(timeAttr, filterFactory.literal(compareStart), filterFactory.literal(compareEnd));

        SimpleFeatureCollection range1, range2;
        try {
            range1 = featureSource.getFeatures(timeFilter1);
            range2 = featureSource.getFeatures(timeFilter2);
        } catch (IOException e) {
            throw new ProcessException("Error getting features", e);
        }
        GridCalculator gridCalculator;
        try {
            gridCalculator = new GridCalculator(gridSize);
        } catch (FactoryException e) {
            throw new ProcessException("Error decoding source or target CRS", e);
        } catch (NoninvertibleTransformException e) {
            throw new ProcessException("Error creating inverse crs transformer", e);
        }

        List<GridCell> grid1 = gridCalculator.aggregate(range1, listener);
        List<GridCell> grid2 = gridCalculator.aggregate(range2, listener);

        List<SimpleFeature> results = new ArrayList<>();
        SimpleFeatureType resultType;
        try {
            resultType = gridCalculator.getResultFeatureType(crs, "gridcells");
        } catch (FactoryException e) {
            throw new ProcessException("Error decoding CRS value");
        }

        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(resultType);
        int fid = 0;

        for (GridCell cell: grid1) {
            double val1 = cell.average();
            double val2 = 0;
            if (grid2.contains(cell)) {
                val2 = grid2.get(grid2.indexOf(cell)).average();
            }
            double change = val1 - val2;
            builder.add(cell.getPolygon());
            builder.add(change);
            results.add(builder.buildFeature(String.valueOf(fid++)));
        }

        return new ListFeatureCollection(resultType, results);
    }
}
