package co.nz.restorelab;

import org.geoserver.catalog.*;
import org.geoserver.catalog.impl.DimensionInfoImpl;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.*;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.NoninvertibleTransformException;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.api.util.ProgressListener;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@DescribeProcess(
        title = "NewCalculateYearlyMean",
        description = "Calculates "
)
public class CalculateYearlyMean implements GeoServerProcess {
    Catalog catalog;
    private final String[] typeLookup = {"monthly", "weekly", "daily"};
    CalculateYearlyMean(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "Returns the layer name that was created/modified")
    public String execute(
        @DescribeParameter(name = "Starting year", description = "0 = monthly, 1 = weekly, 2 = daily") int typeInt,
        ProgressListener progressListener
    ) throws ProcessException {
        LayerInfo layerInfo = catalog.getLayerByName("restore-lab:smc_measurements");

        if (layerInfo == null) {
            throw new ProcessException("smc_measurements layer not found");
        }

        // get feature source for smc_measurements
        FeatureTypeInfo featureTypeInfo = (FeatureTypeInfo) layerInfo.getResource();
        SimpleFeatureSource featureSource;
        try {
            featureSource = (SimpleFeatureSource) featureTypeInfo.getFeatureSource(null, null);
        } catch (IOException e) {
            throw new ProcessException(e);
        }

        String type = typeLookup[typeInt];

        // get data store for writing to
        WorkspaceInfo ws = catalog.getWorkspaceByName("restore-lab");
        DataStoreInfo storeInfo = catalog.getDefaultDataStore(ws);

        SimpleFeatureSource meanLayer = getMeanLayer(storeInfo, type);

        Calendar calendar = Calendar.getInstance();

        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttrSMC = filterFactory.property("utc_time");
        Expression timeAttrCheck = filterFactory.property("time");

        GridCalculator gridCalculator;
        try {
            gridCalculator = new GridCalculator(0);
        } catch (FactoryException | NoninvertibleTransformException e) {
            throw new ProcessException("Error creating the GridCalculator", e);
        }

        if (typeInt == 0) {
            calendar.add(Calendar.MONTH, -1); // move to previous month
            gridCalculator.setGridSize(800);
            for (int i = 0; i < 12; i++) {

                // Get first day of month
                calendar.set(Calendar.DAY_OF_MONTH, 1);
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                Date startDate = calendar.getTime();

                // Get last day of month
                int lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
                calendar.set(Calendar.DAY_OF_MONTH, lastDay);
                calendar.set(Calendar.HOUR_OF_DAY, 23);
                calendar.set(Calendar.MINUTE, 59);
                calendar.set(Calendar.SECOND, 59);
                Date endDate = calendar.getTime();

                Filter timeFilterSMC = filterFactory.between(
                        timeAttrSMC,
                        filterFactory.literal(startDate),
                        filterFactory.literal(endDate)
                );
                Filter timeFilterCheck = filterFactory.between(
                        timeAttrCheck,
                        filterFactory.literal(startDate),
                        filterFactory.literal(endDate)
                );

                processData(
                        gridCalculator,
                        timeFilterCheck,
                        timeFilterSMC,
                        meanLayer,
                        featureSource,
                        progressListener,
                        type,
                        storeInfo,
                        startDate
                );
                progressListener.progress((float) i /12);

                calendar.add(Calendar.MONTH, -1);
            }
        } else if (typeInt == 1) {
            // process weekly
            gridCalculator.setGridSize(1200);
            calendar.add(Calendar.WEEK_OF_YEAR, -1);
            for (int i =0; i < 52; i++) {
                //Get first week
                calendar.set(Calendar.DAY_OF_WEEK, calendar.getActualMinimum(Calendar.DAY_OF_WEEK));
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                Date startDate = calendar.getTime();

                //Get ending day of week
                calendar.set(Calendar.DAY_OF_WEEK, calendar.getActualMaximum(Calendar.DAY_OF_WEEK));
                calendar.set(Calendar.HOUR_OF_DAY, 23);
                calendar.set(Calendar.MINUTE, 59);
                calendar.set(Calendar.SECOND, 59);
                Date endDate = calendar.getTime();

                Filter timeFilterSMC = filterFactory.between(
                        timeAttrSMC,
                        filterFactory.literal(startDate),
                        filterFactory.literal(endDate)
                );
                Filter timeFilterCheck = filterFactory.between(
                        timeAttrCheck,
                        filterFactory.literal(startDate),
                        filterFactory.literal(endDate)
                );

                processData(
                        gridCalculator,
                        timeFilterCheck,
                        timeFilterSMC,
                        meanLayer,
                        featureSource,
                        progressListener,
                        type,
                        storeInfo,
                        startDate
                );
                progressListener.progress((float) i /52);
                calendar.add(Calendar.WEEK_OF_YEAR, -1);
            }
        } else if (typeInt == 2) {
            // process daily
            gridCalculator.setGridSize(1800);
            calendar.add(Calendar.DAY_OF_YEAR, -1);
            for (int i = 0; i < 365; i++) {
                // get day
                calendar.set(Calendar.HOUR_OF_DAY, 0);
                calendar.set(Calendar.MINUTE, 0);
                calendar.set(Calendar.SECOND, 0);
                Date startDate = calendar.getTime();

                //get end of day
                calendar.set(Calendar.HOUR_OF_DAY, 23);
                calendar.set(Calendar.MINUTE, 59);
                calendar.set(Calendar.SECOND, 59);
                Date endDate = calendar.getTime();

                Filter timeFilterSMC = filterFactory.between(
                        timeAttrSMC,
                        filterFactory.literal(startDate),
                        filterFactory.literal(endDate)
                );
                Filter timeFilterCheck = filterFactory.between(
                        timeAttrCheck,
                        filterFactory.literal(startDate),
                        filterFactory.literal(endDate)
                );

                processData(
                        gridCalculator,
                        timeFilterCheck,
                        timeFilterSMC,
                        meanLayer,
                        featureSource,
                        progressListener,
                        type,
                        storeInfo,
                        startDate
                );
                progressListener.progress((float) i /365);
                calendar.add(Calendar.DAY_OF_YEAR, -1);
            }
        } else {
            throw new ProcessException("Invalid type selection, choose from 0,1,2");
        }
        return "Successfully created mean layer for " + type;
    }

    private void processData(
            GridCalculator gridCalculator,
            Filter timeFilterCheck,
            Filter timeFilter,
            SimpleFeatureSource meanLayer,
            SimpleFeatureSource featureSource,
            ProgressListener progressListener,
            String type,
            DataStoreInfo storeInfo,
            Date startDate
            ) {
        // check to see if we already have data for this range computed.
        SimpleFeatureCollection smcRange;
        try {
            Query query = new Query();
            query.setFilter(timeFilterCheck);
            int count = meanLayer.getCount(query);
            if (count > 0) {
                return;
            }
            // Get features for month from layer
            smcRange = featureSource.getFeatures(timeFilter);
        } catch (IOException e) {
            throw new ProcessException("Error getting features in the mean layer check");
        }

        // Aggregate layers
        List<GridCell> grid = gridCalculator.aggregate(smcRange, progressListener);
        // Make simple feature collection for writing

        SimpleFeatureBuilder builder;
        try {
            builder = new SimpleFeatureBuilder(getLayerFeatureType(type));
        } catch (FactoryException e) {
            throw new ProcessException("Error in creating the feature builder", e);
        }
        Transaction transaction = null;
        try {
            DataStore dataStore = (DataStore) storeInfo.getDataStore(null);
            transaction = new DefaultTransaction("create");

            try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer = dataStore.getFeatureWriterAppend("smc_"+type+"_mean", transaction)) {
                int fid = 0;
                for (GridCell cell : grid) {
                    builder.add(cell.getPolygon());
                    builder.add(cell.average());
                    builder.add(startDate);
                    SimpleFeature feature = builder.buildFeature("fid-" + fid++);
                    SimpleFeature newFeature = writer.next();

                    newFeature.setAttributes(feature.getAttributes());
                    writer.write();
                }
            }
            transaction.commit();
        } catch (IOException e) {
            throw new ProcessException("Error in getting dataStore to write to", e);
        } finally {
            try {
                if (transaction != null) transaction.close();
            } catch (IOException ignored) {}
        }
    }

    private SimpleFeatureSource getMeanLayer(DataStoreInfo dataStoreInfo, String type) throws ProcessException {
        LayerInfo layerInfo = catalog.getLayerByName("restore-lab:smc_" + type + "_mean");

        if (layerInfo == null) {
            return createMeanLayer(dataStoreInfo, type);
        }

        FeatureTypeInfo featureTypeInfo = (FeatureTypeInfo) layerInfo.getResource();
        SimpleFeatureSource featureSource;
        try {
            featureSource = (SimpleFeatureSource) featureTypeInfo.getFeatureSource(null, null);
        } catch (IOException e) {
            throw new ProcessException("Error getting the feature source", e);
        }
        return featureSource;
    }

    private SimpleFeatureSource createMeanLayer(DataStoreInfo dataStoreInfo, String type) throws ProcessException {
        // Create the featureType in the database
        try {
            DataStore dataStore = (DataStore) dataStoreInfo.getDataStore(null);
            SimpleFeatureType schema = getLayerFeatureType(type);
            dataStore.createSchema(schema);
        } catch (IOException | FactoryException e) {
            throw new ProcessException("Error getting dataStore", e);
        }

        // Register with geoserver catalog
        FeatureTypeInfo featureTypeInfo = catalog.getFactory().createFeatureType();
        featureTypeInfo.setName("smc_"+type+"_mean");
        featureTypeInfo.setNativeName("smc_"+type+"_mean");
        featureTypeInfo.setStore(dataStoreInfo);
        featureTypeInfo.setEnabled(true);
        featureTypeInfo.setProjectionPolicy(ProjectionPolicy.FORCE_DECLARED);
        featureTypeInfo.setNamespace(catalog.getNamespaceByPrefix("restore-lab"));
        featureTypeInfo.setSRS("EPSG:3857");

        // enable time dimension
        DimensionInfoImpl timeInfo = new DimensionInfoImpl();
        timeInfo.setEnabled(true);
        timeInfo.setAttribute("time");
        timeInfo.setPresentation(DimensionPresentation.CONTINUOUS_INTERVAL);
        timeInfo.setUnits("ISO8601");

        DimensionDefaultValueSetting defaultValue = new DimensionDefaultValueSetting();
        defaultValue.setStrategyType(DimensionDefaultValueSetting.Strategy.MINIMUM);
        timeInfo.setDefaultValue(defaultValue);

        featureTypeInfo.getMetadata().put("time", timeInfo);
        try {
            CoordinateReferenceSystem crs = CRS.decode("EPSG:3857");
            featureTypeInfo.setNativeCRS(crs);

            ReferencedEnvelope nativeBounds = new ReferencedEnvelope(
                    18500000, 19500000, -5500000, -4000000, crs
            );

            featureTypeInfo.setNativeBoundingBox(nativeBounds);

            CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326");
            ReferencedEnvelope latLonBounds = nativeBounds.transform(wgs84, true);
            featureTypeInfo.setLatLonBoundingBox(latLonBounds);

            catalog.add(featureTypeInfo);

            LayerInfo newLayer = catalog.getFactory().createLayer();
            newLayer.setResource(featureTypeInfo);
            newLayer.setEnabled(true);
            newLayer.setName("smc_"+type+"_mean");

            catalog.add(newLayer);

            return (SimpleFeatureSource) featureTypeInfo.getFeatureSource(null, null);
        } catch (FactoryException | TransformException | IOException e) {
            throw new ProcessException("Error in creating new layer",e);
        }
    }

    private SimpleFeatureType getLayerFeatureType(String type) throws FactoryException {
        SimpleFeatureTypeBuilder featureTypeBuilder = new SimpleFeatureTypeBuilder();
        featureTypeBuilder.setName("smc_"+type+"_mean");
        featureTypeBuilder.setCRS(CRS.decode("EPSG:3857"));
        featureTypeBuilder.add("geometry", Polygon.class);
        featureTypeBuilder.add("smc_mat", Double.class);
        featureTypeBuilder.add("time", Date.class);
        return featureTypeBuilder.buildFeatureType();
    }
}
