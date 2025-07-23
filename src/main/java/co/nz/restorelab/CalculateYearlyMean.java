package co.nz.restorelab;

import com.sun.media.jai.opimage.FFT;
import org.geoserver.catalog.*;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.*;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.api.filter.expression.Expression;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.NoninvertibleTransformException;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

@DescribeProcess(title = "CalculateYearlyMean", description = "Calculates the yearly mean for a given year")
public class CalculateYearlyMean implements GeoServerProcess {
    Catalog catalog;
    CalculateYearlyMean(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(name = "result", description = "Returns the layer name that was created")
    public String execute(
            @DescribeParameter(name = "year", description = "Year to calculate") int year
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

        List<GridCell> grid = gridCalculator.aggregate(range);
        WorkspaceInfo ws = catalog.getWorkspaceByName("restore-lab");
        DataStoreInfo storeInfo = catalog.getDefaultDataStore(ws);
        DataAccess<?, ?> dataAccess;
        try {
            dataAccess = storeInfo.getDataStore(null);
        } catch (IOException e) {
            throw new ProcessException("Error getting data store", e);
        }

        SimpleFeatureType featureType;
        try {
            featureType = gridCalculator.getResultFeatureType("EPSG:3857");
        } catch (FactoryException e) {
            throw new ProcessException("");
        }

        DataStore dataStore = (DataStore) dataAccess;

        String typeName = "yearly_mean_smc_" + year;

        // Make the simple feature collection
        DefaultFeatureCollection collection = new DefaultFeatureCollection();
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
        int fid = 0;

        for (GridCell cell : grid) {
            builder.add(cell.getPolygon());
            builder.add(cell.average());
            SimpleFeature feature = builder.buildFeature("fid-"+fid++);
            collection.add(feature);
        }

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
        ftiNew.setNamespace(catalog.getNamespaceByPrefix("restore-lab"));

        catalog.add(ftiNew);

        LayerInfo newLayer = catalog.getFactory().createLayer();
        newLayer.setResource(ftiNew);
        newLayer.setEnabled(true);
        newLayer.setName(typeName);

        catalog.add(newLayer);

        return "Layer created: restore-lab:" + typeName;
    }
}
