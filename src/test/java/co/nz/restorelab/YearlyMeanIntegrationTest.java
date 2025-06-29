package co.nz.restorelab;

import co.nz.restorelab.utils.MockSoilMoisture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class YearlyMeanIntegrationTest {
    CalculateYearlyMean process;
    @BeforeEach
    public void init() throws IOException {
        MockSoilMoisture mock = new MockSoilMoisture(10);
        process = new CalculateYearlyMean(mock.mockCatalog);
    }

    @Test
    public void testYearlyMean() {
        process.execute(2025);
    }
}
