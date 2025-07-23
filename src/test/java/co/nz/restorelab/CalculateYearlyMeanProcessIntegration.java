package co.nz.restorelab;

import co.nz.restorelab.utils.MockSoilMoisture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CalculateYearlyMeanProcessIntegration {

    CalculateYearlyMean process;

    @BeforeEach
    public void init() throws Exception {
        MockSoilMoisture mock = new MockSoilMoisture(10);
        process = new CalculateYearlyMean(mock.mockCatalog);
    }

//    @Test
//    public void testExecuteCorrect() {
//        process.execute(2025);
//    }

}
