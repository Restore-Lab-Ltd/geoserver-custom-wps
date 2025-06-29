package co.nz.restorelab.utils;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.io.IOException;
import java.util.Date;

import org.geotools.api.filter.Filter;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

public class MockSoilMoisture {
    public Catalog mockCatalog;
    LayerInfo mockLayer;
    FeatureTypeInfo mockFeatureType;
    SimpleFeatureSource mockFeatureSource;

    SimpleFeatureTypeBuilder tb;
    GeometryFactory gf;
    public MockSoilMoisture(int featureCount) throws IOException {
        tb = new SimpleFeatureTypeBuilder();
        tb.setName("testing");
        tb.add("geometry", Point.class);
        tb.add("utc_time", Date.class);
        tb.add("smc_mat", Double.class);
        SimpleFeatureType featureType = tb.buildFeatureType();

        gf = new GeometryFactory();
        DefaultFeatureCollection dateRange1 = new DefaultFeatureCollection();
        DefaultFeatureCollection dateRange2 = new DefaultFeatureCollection();

        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);

        for (int i = 0; i < featureCount; i++) {
            Point p = gf.createPoint(new Coordinate(
                    19500000 + (i * 10000), // Spread points ~10km apart
                    -5000000 + (i * 10000)  // Spread points ~10km apart
            ));
            // Date range 1 data
            double smcMat = 50d;
            featureBuilder.set("geometry", p);
            featureBuilder.set("utc_time", null);
            featureBuilder.set("smc_mat", smcMat);
            dateRange1.add(featureBuilder.buildFeature("fid"+i));
            // Data range 2
            smcMat = 60.0;

            featureBuilder.set("geometry", p);
            featureBuilder.set("utc_time", null);
            featureBuilder.set("smc_mat", smcMat);
            dateRange2.add(featureBuilder.buildFeature("fid" + i));
        }
        mockCatalog = mock(Catalog.class);
        mockLayer = mock(LayerInfo.class);
        mockFeatureType = mock(FeatureTypeInfo.class);
        mockFeatureSource = mock(SimpleFeatureSource.class);

        when(mockCatalog.getLayerByName("restore-lab:smc_measurements")).thenReturn(mockLayer);
        when(mockLayer.getResource()).thenReturn(mockFeatureType);
        when((SimpleFeatureSource) mockFeatureType.getFeatureSource(null, null)).thenReturn(mockFeatureSource);
        when(mockFeatureSource.getFeatures((Filter) any())).thenReturn(dateRange1, dateRange2);
    }
}
