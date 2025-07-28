package co.nz.restorelab;

import co.nz.restorelab.utils.MockSoilMoisture;
import org.geotools.api.feature.Feature;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.process.ProcessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TemporalChangeGridProcessIntegrationTest {

    TemporalGridChange process;
    MockSoilMoisture mock;

    @BeforeEach
    public void init() throws Exception {
        mock = new MockSoilMoisture(10);
        process = new TemporalGridChange(mock.mockCatalog);
    }

//    @Test
//    public void testExecuteComputesCorrectChange() {
//        String start1 = "2025-05-20T00:00:00";
//        String end1 = "2025-06-05T00:00:00";
//
//        SimpleFeatureCollection result = process.execute(start1, end1, 2024, "EPSG:3857", mock.mockProgressListener);
//
//        assertEquals(10, result.size());
//
//        try (SimpleFeatureIterator iterator = result.features()) {
//            while (iterator.hasNext()) {
//                Feature feature = iterator.next();
//                // We expect a consistent 10.0 difference (60.0 - 50.0)
//                assertEquals(-10.0, (double) feature.getProperty("smc_mat").getValue(), 0.001f);
//            }
//        }
//    }
//
//    @Test public void testInvalidDate() {
//        String start1 = "2025-0100:00:00";
//        String end1 = "2025-01-05T00:00:00";
//        ProcessException exception = assertThrows(ProcessException.class,() -> process.execute(start1, end1, 2024, "EPSG:3857", mock.mockProgressListener));
//
//        assertEquals("Error parsing date", exception.getMessage());
//    }
//
//    @Test public void testEndBehindStart() {
//        String start1 = "2025-01-01T00:00:00";
//        String end1 = "2025-01-05T00:00:00";
//
//        ProcessException exception = assertThrows(ProcessException.class, () -> process.execute(end1,start1, 2024,"EPSG:3857", mock.mockProgressListener));
//
//        assertEquals("Start date is after end date for date range 1", exception.getMessage());
//    }
//
//    @Test public void testEqualStartAndEnd() {
//        String start1 = "2025-01-01T00:00:00";
//
//        ProcessException exception = assertThrows(ProcessException.class, () -> process.execute(start1,start1, 2024,"EPSG:3857", mock.mockProgressListener));
//
//        assertEquals("Start date is equal to end date for date range 1", exception.getMessage());
//    }
}
