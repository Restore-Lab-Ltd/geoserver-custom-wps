package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;


import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

public class TemporalChangeGridProcessIntegrationTest {

    Catalog mockCatalog;
    LayerInfo mockLayer;
    FeatureTypeInfo mockFeatureTypeInfo;
    SimpleFeatureSource mockFeatureSource;
    TemporalGridChange process;

    SimpleFeatureTypeBuilder tb;
    GeometryFactory gf;

    @BeforeEach
    public void init() throws Exception {
        tb = new SimpleFeatureTypeBuilder();
        tb.setName("testing");
        tb.add("geometry", Point.class);
        tb.add("utc_time", Date.class);
        SimpleFeatureType featureType = tb.buildFeatureType();

        gf = new GeometryFactory();
        DefaultFeatureCollection allFeatures = new DefaultFeatureCollection();

        Calendar cal = Calendar.getInstance();

        List<Date> timestamps = new ArrayList<>();

        cal.set(2025, Calendar.JANUARY, 1); timestamps.add(cal.getTime());
        cal.set(2025, Calendar.JANUARY, 5); timestamps.add(cal.getTime());
        cal.set(2025, Calendar.FEBRUARY, 1); timestamps.add(cal.getTime());
        cal.set(2025, Calendar.FEBRUARY, 5); timestamps.add(cal.getTime());

        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
        for (int i = 0; i < timestamps.size(); i++) {
            Point p = gf.createPoint(new Coordinate(i * 0.001, 0));
            featureBuilder.set("geometry", p);
            featureBuilder.set("utc_time", timestamps.get(i));
            allFeatures.add(featureBuilder.buildFeature("fid" + i));
        }

        mockCatalog = mock(Catalog.class);
        mockLayer = mock(LayerInfo.class);
        mockFeatureTypeInfo = mock(FeatureTypeInfo.class);
        mockFeatureSource = mock(SimpleFeatureSource.class);

        when(mockCatalog.getFeatureTypeByName("restore-lab:smc_measurements")).thenReturn(mockFeatureTypeInfo);
        when(mockCatalog.getLayerByName("restore-lab:smc_measurements")).thenReturn(mockLayer);
        when(mockLayer.getResource()).thenReturn(mockFeatureTypeInfo);
        when((SimpleFeatureSource) mockFeatureTypeInfo.getFeatureSource(null, null)).thenReturn(mockFeatureSource);
        when(mockFeatureSource.getFeatures((Filter) any())).thenReturn(allFeatures);

        process = new TemporalGridChange(mockCatalog);
    }

    @Test
    public void testExecuteComputesCorrectChange() throws Exception {
        Calendar cal = Calendar.getInstance();
//        cal.set(2020, Calendar.JANUARY, 1); Date start1 = cal.getTime();
//        cal.set(2020, Calendar.JANUARY, 2); Date end1   = cal.getTime();
//        cal.set(2020, Calendar.FEBRUARY, 1); Date start2 = cal.getTime();
//        cal.set(2020, Calendar.FEBRUARY, 28); Date end2   = cal.getTime();
        String start1 = "2025-01-01T00:00:00";
        String end1 = "2025-01-05T00:00:00";
        String start2 = "2025-02-01T00:00:00";
        String end2 = "2025-02-05T00:00:00";

        SimpleFeatureCollection result = process.execute(start1, end1, start2, end2);
        System.out.println(result);
    }
}
