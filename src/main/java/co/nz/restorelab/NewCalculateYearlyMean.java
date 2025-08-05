package co.nz.restorelab;

import org.geoserver.catalog.*;
import org.geoserver.catalog.impl.DimensionInfoImpl;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.data.Transaction;
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
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Polygon;
import org.springframework.security.core.parameters.P;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@DescribeProcess(
        title = "NewCalculateYearlyMean",
        description = "Calculates "
)
public class NewCalculateYearlyMean implements GeoServerProcess {
    Catalog catalog;
    NewCalculateYearlyMean(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "Returns the layer name that was created/modified")
    public String execute(
        @DescribeParameter(name = "Starting year") int year,
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

        // loop over previous 12 months
        Calendar calendar = Calendar.getInstance();

        calendar.add(Calendar.MONTH, -1); // move to previous month

        // get data store for writing to
        WorkspaceInfo ws = catalog.getWorkspaceByName("restore-lab");
        DataStoreInfo storeInfo = catalog.getDefaultDataStore(ws);

        SimpleFeatureSource meanLayer = getMeanLayer(storeInfo);

        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttrSMC = filterFactory.property("utc_time");
        Expression timeAttrCheck = filterFactory.property("time");

        GridCalculator gridCalculator;
        try {
            gridCalculator = new GridCalculator(800);
        } catch (FactoryException | NoninvertibleTransformException e) {
            throw new ProcessException("Error creating the GridCalculator", e);
        }

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

            // check to see if we already have data for this range computed.
            Filter timeFilterCheck = filterFactory.between(timeAttrCheck,  filterFactory.literal(startDate), filterFactory.literal(endDate));
            SimpleFeatureCollection smcRange;
            try {
                SimpleFeatureCollection range = meanLayer.getFeatures(timeFilterCheck);
                if (!range.isEmpty()) {
                    continue;
                }
                // Get features for month from layer
                Filter timeFilter = filterFactory.between(timeAttrSMC, filterFactory.literal(startDate), filterFactory.literal(endDate));
                smcRange = featureSource.getFeatures(timeFilter);
            } catch (IOException e) {
                throw new ProcessException("Error getting features in the mean layer check");
            }

            // Aggregate layers
            List<GridCell> grid = gridCalculator.aggregate(smcRange, progressListener);
            // Make simple feature collection for writing

            DefaultFeatureCollection collection = new DefaultFeatureCollection();
            SimpleFeatureBuilder builder;
            try {
                builder = new SimpleFeatureBuilder(getLayerFeatureType());
            } catch (FactoryException e) {
                throw new ProcessException("Error in creating the feature builder", e);
            }

            try {
                DataStore dataStore = (DataStore) storeInfo.getDataStore(null);
                Transaction transaction = new DefaultTransaction("create");

                try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer = dataStore.getFeatureWriterAppend("smc_year_mean", transaction)) {
                    int fid = 0;
                    for (GridCell cell : grid) {
                        builder.add(cell.getPolygon());
                        builder.add(cell.average());
                        builder.add(startDate);
                        SimpleFeature feature = builder.buildFeature("fid-"+fid++);
                        SimpleFeature newFeature = writer.next();

                        newFeature.setAttributes(feature.getAttributes());
                        writer.write();
                    }
                }
                transaction.commit();
            } catch (IOException e) {
                throw new ProcessException("Error in getting dataStore to write to", e);
            }
        }
        return "Hey";
    }

    private SimpleFeatureSource getMeanLayer(DataStoreInfo dataStoreInfo) throws ProcessException {
        LayerInfo layerInfo = catalog.getLayerByName("restore-lab:smc_year_mean");

        if (layerInfo == null) {
            return createMeanLayer(dataStoreInfo);
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

    private SimpleFeatureSource createMeanLayer(DataStoreInfo dataStoreInfo) throws ProcessException {
        // Create the featureType in the database
        try {
            DataStore dataStore = (DataStore) dataStoreInfo.getDataStore(null);
            SimpleFeatureType schema = getLayerFeatureType();
            dataStore.createSchema(schema);
        } catch (IOException | FactoryException e) {
            throw new ProcessException("Error getting dataStore", e);
        }

        // Register with geoserver catalog
        FeatureTypeInfo featureTypeInfo = catalog.getFactory().createFeatureType();
        featureTypeInfo.setName("smc_year_mean");
        featureTypeInfo.setNativeName("smc_year_mean");
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
            newLayer.setName("smc_year_mean");

            catalog.add(newLayer);

            return (SimpleFeatureSource) featureTypeInfo.getFeatureSource(null, null);
        } catch (FactoryException | TransformException | IOException e) {
            throw new ProcessException("Error in creating new layer",e);
        }
    }

    private SimpleFeatureType getLayerFeatureType() throws FactoryException {
        SimpleFeatureTypeBuilder featureTypeBuilder = new SimpleFeatureTypeBuilder();
        featureTypeBuilder.setName("smc_year_mean");
        featureTypeBuilder.setCRS(CRS.decode("EPSG:3857"));
        featureTypeBuilder.add("geometry", Polygon.class);
        featureTypeBuilder.add("smc_mat", Double.class);
        featureTypeBuilder.add("time", Date.class);
        return featureTypeBuilder.buildFeatureType();
    }
}
