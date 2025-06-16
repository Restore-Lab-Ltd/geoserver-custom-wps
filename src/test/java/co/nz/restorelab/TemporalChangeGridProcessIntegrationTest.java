package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.Feature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.process.ProcessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TemporalChangeGridProcessIntegrationTest {

    Catalog mockCatalog;
    LayerInfo mockLayer;
    FeatureTypeInfo mockFeatureTypeInfo;
    SimpleFeatureSource mockFeatureSource;
    TemporalGridChange process;

    SimpleFeatureTypeBuilder tb;
    GeometryFactory gf;

    Random random = new Random();

    float smcMax = 100f;

    List<Double> dateRange1Data = new ArrayList<>();
    List<Double> dateRange2Data = new ArrayList<>();

    @BeforeEach
    public void init() throws Exception {
        dateRange1Data.clear();
        dateRange2Data.clear();
        tb = new SimpleFeatureTypeBuilder();
        tb.setName("testing");
        tb.add("geometry", Point.class);
        tb.add("utc_time", Date.class);
        tb.add("smc_mat", Double.class);
        SimpleFeatureType featureType = tb.buildFeatureType();

        gf = new GeometryFactory();
        DefaultFeatureCollection dateRange1 = new DefaultFeatureCollection();
        DefaultFeatureCollection dateRange2 = new DefaultFeatureCollection();

        int featureCount = 10;

        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(featureType);
        for (int i = 0; i < featureCount; i++) {
            Point p = gf.createPoint(new Coordinate((i * 0.001) + 170, -45));
            // Date range 1 data
            double smcMat = random.nextDouble() * smcMax;
            dateRange1Data.add(smcMat);
            featureBuilder.set("geometry", p);
            featureBuilder.set("utc_time", null);
            featureBuilder.set("smc_mat", smcMat);
            dateRange1.add(featureBuilder.buildFeature("fid" + i));
            // date range 2 data
            smcMat = random.nextDouble() * smcMax;
            dateRange2Data.add(smcMat);
            featureBuilder.set("geometry", p);
            featureBuilder.set("utc_time", null);
            featureBuilder.set("smc_mat", smcMat);
            dateRange2.add(featureBuilder.buildFeature("fid" + i));
        }

        mockCatalog = mock(Catalog.class);
        mockLayer = mock(LayerInfo.class);
        mockFeatureTypeInfo = mock(FeatureTypeInfo.class);
        mockFeatureSource = mock(SimpleFeatureSource.class);

        when(mockCatalog.getFeatureTypeByName("restore-lab:smc_measurements")).thenReturn(mockFeatureTypeInfo);
        when(mockCatalog.getLayerByName("restore-lab:smc_measurements")).thenReturn(mockLayer);
        when(mockLayer.getResource()).thenReturn(mockFeatureTypeInfo);
        when((SimpleFeatureSource) mockFeatureTypeInfo.getFeatureSource(null, null)).thenReturn(mockFeatureSource);
        when(mockFeatureSource.getFeatures((Filter) any())).thenReturn(dateRange1, dateRange2);

        process = new TemporalGridChange(mockCatalog);
    }

    @Test
    public void testExecuteComputesCorrectChange() throws Exception {

        String start1 = "2025-01-01T00:00:00";
        String end1 = "2025-01-05T00:00:00";
        String start2 = "2025-02-01T00:00:00";
        String end2 = "2025-02-05T00:00:00";

        SimpleFeatureCollection result = process.execute(start1, end1, start2, end2, "EPSG:3857");

        assertEquals(1, result.size());

        try (SimpleFeatureIterator iterator = result.features()) {
            while (iterator.hasNext()) {
                Feature feature = iterator.next();
                double dateRange1Avg = dateRange1Data.stream().reduce(Double::sum).orElse(0d) / dateRange1Data.size();
                double dateRange2Avg = dateRange2Data.stream().reduce(Double::sum).orElse(0d) / dateRange2Data.size();
                double expectedValue = dateRange2Avg - dateRange1Avg;
                assertEquals(expectedValue, (double) feature.getProperty("value").getValue(), 0.001f);
            }
        }
    }

    @Test public void testInvalidDate() {
        String start1 = "2025-0100:00:00";
        String end1 = "2025-01-05T00:00:00";
        String start2 = "2025-02-01T00:00:00";
        String end2 = "2025-02-05T00:00:00";
        ProcessException exception = assertThrows(ProcessException.class,() -> process.execute(start1, end1, start2, end2, "EPSG:3857"));

        assertEquals("Error parsing date", exception.getMessage());
    }

    @Test public void testEndBehindStart() {
        String start1 = "2025-01-01T00:00:00";
        String end1 = "2025-01-05T00:00:00";
        String start2 = "2025-02-01T00:00:00";
        String end2 = "2025-02-05T00:00:00";

        ProcessException exception = assertThrows(ProcessException.class, () -> process.execute(end1,start1,start2,end2,"EPSG:3857"));
        ProcessException exception1 = assertThrows(ProcessException.class, () -> process.execute(start1,end1,end2,start2,"EPSG:3857"));

        assertEquals("Start date is after end date for date range 1", exception.getMessage());
        assertEquals("Start date is after end date for date range 2", exception1.getMessage());
    }

    @Test public void testEqualStartAndEnd() {
        String start1 = "2025-01-01T00:00:00";
        String end1 = "2025-01-05T00:00:00";
        String start2 = "2025-02-01T00:00:00";
        String end2 = "2025-02-05T00:00:00";

        ProcessException exception = assertThrows(ProcessException.class, () -> process.execute(start1,start1,start2,end2,"EPSG:3857"));
        ProcessException exception1 = assertThrows(ProcessException.class, () -> process.execute(start1,end1,start2,start2,"EPSG:3857"));

        assertEquals("Start date is equal to end date for date range 1", exception.getMessage());
        assertEquals("Start date is equal to end date for date range 2", exception1.getMessage());
    }
}
