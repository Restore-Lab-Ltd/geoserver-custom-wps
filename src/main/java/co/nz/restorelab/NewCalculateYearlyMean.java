package co.nz.restorelab;

import org.geoserver.catalog.*;
import org.geoserver.catalog.impl.DimensionInfoImpl;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.referencing.CRS;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;

@DescribeProcess(
        title = "NewCalculateYearlyMean",
        description = "Calculates "
)
public class NewCalculateYearlyMean {
    Catalog catalog;
    NewCalculateYearlyMean(Catalog catalog) {
        this.catalog = catalog;
    }

    @DescribeResult(description = "Returns the layer name that was created/modified")
    public String execute(

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

        }

        return "Hey";
    }

    private SimpleFeatureSource getMeanLayer(DataStoreInfo dataStoreInfo) {
        LayerInfo layerInfo = catalog.getLayerByName("restore-lab:smc_year_mean");

        if (layerInfo == null) {
            return createMeanLayer(dataStoreInfo);
        }
        return null;
    }

    private SimpleFeatureSource createMeanLayer(DataStoreInfo dataStoreInfo) {
        FeatureTypeInfo featureTypeInfo = catalog.getFactory().createFeatureType();
        featureTypeInfo.setName("smc_year_mean");
        featureTypeInfo.setNativeName("smc_year_mean");
        featureTypeInfo.setStore(dataStoreInfo);
        featureTypeInfo.setEnabled(true);
        MetadataMap metadataMap = featureTypeInfo.getMetadata();
        metadataMap.put("srsHandling", "FORCE_DECLARED");
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

        metadataMap.put("time", timeInfo);
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
        } catch (NoSuchAuthorityCodeException e) {
            throw new RuntimeException(e);
        } catch (FactoryException e) {
            throw new RuntimeException(e);
        } catch (TransformException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
