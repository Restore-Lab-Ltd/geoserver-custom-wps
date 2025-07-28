package co.nz.restorelab;

import org.geoserver.catalog.*;
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
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@DescribeProcess(
        title = "CalculateYearlyMean",
        description = "Calculates the yearly mean for a given year"
)
public class CalculateYearlyMean implements GeoServerProcess {
    Catalog catalog;
    CalculateYearlyMean(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(name = "result", description = "Returns the layer name that was created")
    public String execute(
            @DescribeParameter(name = "year", description = "Year to calculate") int year,
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
        Calendar calendar = Calendar.getInstance();
        // Get the start date
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.DAY_OF_MONTH, 0);
        calendar.set(Calendar.HOUR, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        Date startDate = calendar.getTime();

        // Get the end date
        calendar.set(Calendar.MONTH, 11);
        calendar.set(Calendar.DAY_OF_MONTH, 31);
        calendar.set(Calendar.HOUR, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        Date endDate = calendar.getTime();

        FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
        Expression timeAttr = filterFactory.property("utc_time");
        Filter timeFilter = filterFactory.between(timeAttr, filterFactory.literal(startDate), filterFactory.literal(endDate));

        SimpleFeatureCollection range;
        try {
            range = featureSource.getFeatures(timeFilter);
        } catch (IOException e) {
            throw new ProcessException("Error getting features", e);
        }

        GridCalculator gridCalculator;
        try {
            gridCalculator = new GridCalculator(800);
        } catch (NoninvertibleTransformException e) {
            throw new ProcessException("Error decoding source or target CRS", e);
        } catch (FactoryException e) {
            throw new ProcessException("Error creating inverse crs transformer", e);
        }

        List<GridCell> grid = gridCalculator.aggregate(range, listener);
        WorkspaceInfo ws = catalog.getWorkspaceByName("restore-lab");
        DataStoreInfo storeInfo = catalog.getDefaultDataStore(ws);
        DataAccess<?, ?> dataAccess;
        try {
            dataAccess = storeInfo.getDataStore(null);
        } catch (IOException e) {
            throw new ProcessException("Error getting data store", e);
        }

        SimpleFeatureType featureType;

        DataStore dataStore = (DataStore) dataAccess;

        String typeName = "yearly_mean_smc_" + year;
        try {
            featureType = gridCalculator.getResultFeatureType("EPSG:3857", typeName);
        } catch (FactoryException e) {
            throw new ProcessException("");
        }

        // Make the simple feature collection
        DefaultFeatureCollection collection = new DefaultFeatureCollection();
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
        int fid = 0;

        for (GridCell cell : grid) {
            builder.add(cell.getPolygon());
            builder.add(cell.average());
            SimpleFeature feature = builder.buildFeature("fid-"+fid++);
            collection.add(feature);
            float progress = 0.33f + 0.33f * (float) fid / grid.size();
            listener.progress(progress);
        }

        fid = 0;
        try {
            dataStore.createSchema(featureType);
            Transaction transaction = new DefaultTransaction("create");

            try (FeatureWriter<SimpleFeatureType, SimpleFeature> writer = dataStore.getFeatureWriterAppend(typeName, transaction)) {
                try (SimpleFeatureIterator features = collection.features()) {
                   while (features.hasNext()) {
                       SimpleFeature feature = features.next();
                       SimpleFeature newFeature = writer.next();
                       newFeature.setAttributes(feature.getAttributes());
                       writer.write();

                       float progress = 0.66f + 0.33f * (float) fid / grid.size();
                       listener.progress(progress);
                   }
                }
            }
            transaction.commit();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        FeatureTypeInfo ftiNew = catalog.getFactory().createFeatureType();
        ftiNew.setName(typeName);
        ftiNew.setNativeName(typeName);
        ftiNew.setStore(storeInfo);
        ftiNew.setEnabled(true);
        ftiNew.getMetadata().put("srsHandling", "FORCE_DECLARED");
        ftiNew.setNamespace(catalog.getNamespaceByPrefix("restore-lab"));
        ftiNew.setSRS("EPSG:3857");
        ftiNew.setNativeBoundingBox(collection.getBounds());
        try {
            CoordinateReferenceSystem crs = CRS.decode("EPSG:3857");
            ftiNew.setNativeCRS(crs);
            ReferencedEnvelope latLonBounds = collection.getBounds().transform(crs, true);
            ftiNew.setLatLonBoundingBox(latLonBounds);
        } catch (FactoryException | TransformException e) {
            throw new ProcessException("Error decoding EPSG to set native");
        }

        catalog.add(ftiNew);

        LayerInfo newLayer = catalog.getFactory().createLayer();
        newLayer.setResource(ftiNew);
        newLayer.setEnabled(true);
        newLayer.setName(typeName);
        newLayer.setDefaultStyle(catalog.getStyleByName("Soil Moisture"));

        catalog.add(newLayer);

        listener.complete();

        return "Layer created: restore-lab:" + typeName;
    }
}
