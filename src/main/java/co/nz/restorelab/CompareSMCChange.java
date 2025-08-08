package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.NoninvertibleTransformException;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.store.EmptyFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@DescribeProcess(title = "compareSMCChange", description = "Computes the gridded change between a date range and the previous 12 months.")
public class CompareSMCChange implements GeoServerProcess {
    Catalog catalog;
    CompareSMCChange(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "The gridded change between the date range and previous 12 months.")
    public SimpleFeatureCollection execute(
            @DescribeParameter(name = "startDate", description = "Starting date in format YYYY-MM-DDTHH:MM:SS") String startTime,
            @DescribeParameter(name = "endDate", description = "Ending date in format YYYY-MM-DDTHH:MM:SS") String endTime
    ) throws ProcessException {
        // get and validate layers
        LayerInfo layerInfoSMC = catalog.getLayerByName("restore-lab:smc_measurements");
        LayerInfo layerInfoMean = catalog.getLayerByName("restore-lab:smc_year_mean");

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

        // Convert and validate start and end times
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
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

        GridCalculator gridCalculator;
        try {
            gridCalculator = new GridCalculator(800);
        } catch (FactoryException | NoninvertibleTransformException e) {
            throw new ProcessException("Error while creating the GridCalculator", e);
        }

        // filter time data for smc layer
        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttr = filterFactory.property("utc_time");
        Filter timeFilterSMC = filterFactory.between(timeAttr, filterFactory.literal(startDate), filterFactory.literal(endDate));
        SimpleFeatureCollection rangeSMC;
        try {
            rangeSMC = featureSourceSMC.getFeatures(timeFilterSMC);
        } catch (IOException e) {
            throw new ProcessException("Error while filtering SMC data", e);
        }
        ReferencedEnvelope bounds = rangeSMC.getBounds();
        // get feature collection for mean layer limiting to the interested area
        Filter bboxFilter = filterFactory.bbox(
                "geometry",
                bounds.getMinX(),
                bounds.getMinY(),
                bounds.getMaxX(),
                bounds.getMaxY(),
                bounds.getCoordinateReferenceSystem().getIdentifiers().iterator().next().toString()
        );
        SimpleFeatureCollection yearMean;
        try {
            yearMean = featureSourceMean.getFeatures(bboxFilter);
        } catch (IOException e) {
            throw new ProcessException("Error in filtering mean layer by smc layer bounds",e);
        }

        return yearMean;
    }

    private List<GridCell> calculateMean(SimpleFeatureSource featureSource) {
        return null;
    }
}
