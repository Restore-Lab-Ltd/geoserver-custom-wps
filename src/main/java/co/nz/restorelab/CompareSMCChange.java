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
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@DescribeProcess(title = "compareSMCChange", description = "Computes the gridded change between a date range and the previous 12 months.")
public class CompareSMCChange implements GeoServerProcess {
    Catalog catalog;
    private final String[] meanLookup = {"monthly", "weekly", "daily"};
    private final int[] gridLookup = {800, 1200, 1800};
    CompareSMCChange(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "The gridded change between the date range and previous 12 months.")
    public SimpleFeatureCollection execute(
            @DescribeParameter(name = "startDate", description = "Starting date in format YYYY-MM-DDTHH:MM:SS") String startTime,
            @DescribeParameter(name = "endDate", description = "Ending date in format YYYY-MM-DDTHH:MM:SS") String endTime,
            @DescribeParameter(name = "Mean To Compare To", description = "Choose between 0 for monthly mean, 1 for weekly mean, and 2 for daily mean", defaultValue = "1") int meanInt,
            ProgressListener progressListener
    ) throws ProcessException {
        if (meanInt > 2 || meanInt < 0) {
            throw new ProcessException("Mean lookup needs to be between 0 and 3");
        }
        // Convert and validate start and end times
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date startDate, endDate;
        try {
            startDate = simpleDateFormat.parse(startTime);
            endDate = simpleDateFormat.parse(endTime);
        } catch (ParseException e) {
            throw new ProcessException("Dates are in an invalid format");
        }

        if (startDate.after(endDate)) {
            throw new ProcessException("Start date is after the end date");
        }
        if (startDate.compareTo(endDate) == 0) {
            throw new ProcessException("Start date is equal to the end date");
        }
        // get and validate layers
        LayerInfo layerInfoSMC = catalog.getLayerByName("restore-lab:smc_measurements");
        String mean = meanLookup[meanInt];
        LayerInfo layerInfoMean = catalog.getLayerByName("restore-lab:smc_" + mean + "_mean");

        if (layerInfoSMC == null) {
            throw new ProcessException("Cannot find the SMC measurements layer");
        }
        if (layerInfoMean == null) {
            throw new ProcessException("Cannot find the SMC mean layer");
        }

        FeatureTypeInfo typeInfoSMC = (FeatureTypeInfo) layerInfoSMC.getResource();
        FeatureTypeInfo typeInfoMean = (FeatureTypeInfo) layerInfoMean.getResource();
        SimpleFeatureSource featureSourceSMC;
        SimpleFeatureSource featureSourceMean;
        try {
            featureSourceSMC = (SimpleFeatureSource) typeInfoSMC.getFeatureSource(null, null);
            featureSourceMean = (SimpleFeatureSource) typeInfoMean.getFeatureSource(null, null);
        } catch (IOException e) {
            throw new ProcessException("Error while getting the feature source for the layers", e);
        }

        GridCalculator gridCalculator;
        try {
            gridCalculator = new GridCalculator(gridLookup[meanInt]);
        } catch (FactoryException | NoninvertibleTransformException e) {
            throw new ProcessException("Error while creating the GridCalculator", e);
        }

        // filter time data for smc layer
        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttr = filterFactory.property("utc_time");
        Filter timeFilterSMC = filterFactory.between(
                timeAttr,
                filterFactory.literal(startDate),
                filterFactory.literal(endDate));
        SimpleFeatureCollection rangeSMC;
        try {
            rangeSMC = featureSourceSMC.getFeatures(timeFilterSMC);
            if (rangeSMC.isEmpty()) {
                throw new ProcessException("No data found for the specified time range");
            }
        } catch (IOException e) {
            throw new ProcessException("Error while filtering SMC data", e);
        }
        ReferencedEnvelope bounds = rangeSMC.getBounds();
        // get feature collection for meanInt layer limiting to the interested area
        Set<MeanGridCell> meanGridCellSet = calculateMean(featureSourceMean, endDate, bounds);

        Map<String,Double> meanLookup = createMeanLookup(meanGridCellSet);
        SimpleFeatureType resultType;
        try {
            resultType = GridCalculator.getResultFeatureType("EPSG:3857", "gridcell");
        } catch (FactoryException e) {
            throw new ProcessException("Error in getting result type information");
        }

        return buildResultCollection(
                gridCalculator.aggregate(rangeSMC, progressListener),
                meanLookup,
                resultType
                );
    }

    private Map<String, Double> createMeanLookup(Set<MeanGridCell> meanGridCells) {
        Map<String, Double> lookup = new HashMap<>();
        for (MeanGridCell cell : meanGridCells) {
            lookup.put(cell.getPolygon().toString(), cell.getAverage());
        }
        return lookup;
    }

    private SimpleFeatureCollection buildResultCollection(
            List<GridCell> grid,
            Map<String, Double> meanLookup,
            SimpleFeatureType resultType
    ) {
        List<SimpleFeature> results = new ArrayList<>(grid.size());
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(resultType);

        int total =grid.size();
        for (int i = 0; i < total; i++) {
            GridCell cell = grid.get(i);
            double currentValue = cell.average();

            Double meanValue = meanLookup.getOrDefault(cell.getPolygon().toString(), 0.0);
            double change = currentValue - meanValue;
            builder.add(cell.getPolygon());
            builder.add(change);
            results.add(builder.buildFeature("fid-"+i));
        }

        return new ListFeatureCollection(resultType, results);
    }

    private Set<MeanGridCell> calculateMean(SimpleFeatureSource featureSource, Date endDate, ReferencedEnvelope bounds) {
        Map<String, MeanGridCell> yearMeanMap = new HashMap<>();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(endDate);
        Date filterEnd = calendar.getTime();
        calendar.add(Calendar.YEAR, -1);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        Date filterStart = calendar.getTime();

        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttr = filterFactory.property("time");

        // first filter by bounding box of time range data
        Filter bboxFilter = filterFactory.bbox(
                "geometry",
                bounds.getMinX(),
                bounds.getMinY(),
                bounds.getMaxX(),
                bounds.getMaxY(),
                bounds.getCoordinateReferenceSystem().getIdentifiers().iterator().next().toString());

        Filter timeFilter = filterFactory.between(timeAttr, filterFactory.literal(filterStart), filterFactory.literal(filterEnd));

        Filter combinedFilter = filterFactory.and(bboxFilter, timeFilter);
        try {
            SimpleFeatureCollection meanRange = featureSource.getFeatures(combinedFilter);
            if (meanRange.isEmpty()) return new HashSet<>();

            int batchSize = 10000;
            int processed = 0;
            try (SimpleFeatureIterator iterator = meanRange.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature f = iterator.next();
                    Geometry poly = (Geometry) f.getAttribute("geometry");
                    Double mean = (Double) f.getAttribute("smc_mat");

                    String polyKey = poly.toString();
                    MeanGridCell cell = yearMeanMap.get(polyKey);
                    if (cell == null) {
                        cell = new MeanGridCell(poly, mean);
                        yearMeanMap.put(polyKey, cell);
                    } else {
                        cell.addValue(mean);
                    }
                    processed++;
                    if (processed % batchSize == 0) {
                        System.gc();
                    }
                }

            }
        } catch (IOException e) {
            throw new ProcessException("Error in requesting features from combined filter");
        }
        return new HashSet<>(yearMeanMap.values());
    }
}
