package co.nz.restorelab;

import co.nz.restorelab.utils.MockSoilMoisture;
import org.geoserver.catalog.*;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.FeatureWriter;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.filter.Filter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class CalculateYearlyMeanProcessIntegrationTest {

    CalculateYearlyMean process;
    MockSoilMoisture mock;

    @Mock
    WorkspaceInfo mockWorkspaceInfo;

    @Mock
    DataStoreInfo mockDataStoreInfo;

    @Mock
    DataStore mockDataStore;

    @Mock
    CatalogFactory mockCatalogFactory;

    @Mock
    NamespaceInfo mockNamespaceInfo;

    @Mock
    FeatureWriter<SimpleFeatureType, SimpleFeature> mockWriter;

    @Mock
    SimpleFeatureSource mockMeanLayer;

    @Mock
    SimpleFeatureCollection mockFeatureCollection;


    @BeforeEach
    public void init() throws Exception {
        MockitoAnnotations.openMocks(this);
        // init mock soil moisture layer
        mock = new MockSoilMoisture(10);

        // setup mock workspace
        when(mock.mockCatalog.getWorkspaceByName("restore-lab")).thenReturn(mockWorkspaceInfo);

        // setup mock data store
        when(mock.mockCatalog.getDefaultDataStore(mockWorkspaceInfo)).thenReturn(mockDataStoreInfo);
        when((DataStore) mockDataStoreInfo.getDataStore(any())).thenReturn(mockDataStore);

        // setup mock catalog factory
        when(mock.mockCatalog.getFactory()).thenReturn(mockCatalogFactory);
        when(mockCatalogFactory.createFeatureType()).thenReturn(Mockito.mock(FeatureTypeInfo.class));
        when(mockCatalogFactory.createLayer()).thenReturn(Mockito.mock(LayerInfo.class));

        // setup namespace mock
        when(mock.mockCatalog.getNamespaceByPrefix("restore-lab")).thenReturn(mockNamespaceInfo);

        // setup feature writer mock
        when(mockDataStore.getFeatureWriterAppend(anyString(), any())).thenReturn(mockWriter);
        when(mockWriter.next()).thenReturn(Mockito.mock(SimpleFeature.class));

        when(mockMeanLayer.getFeatures((Filter) any())).thenReturn(mockFeatureCollection);
        when (mockFeatureCollection.isEmpty()).thenReturn(true);

        // setup feature type mock for creating new layer
        FeatureTypeInfo mockFeatureTypeInfo = Mockito.mock(FeatureTypeInfo.class);
        when(mockCatalogFactory.createFeatureType()).thenReturn(mockFeatureTypeInfo);
        when(mockFeatureTypeInfo.getMetadata()).thenReturn(Mockito.mock(MetadataMap.class));
        when((SimpleFeatureSource) mockFeatureTypeInfo.getFeatureSource(any(), any())).thenReturn(mockMeanLayer);

        process = new CalculateYearlyMean(mock.mockCatalog);
    }

    @Test
    public void testGoodDataMonthly() {
        String result = process.execute(0, mock.mockProgressListener);
    }

    @Test
    public void testGoodDataWeekly() {
        String result = process.execute(1, mock.mockProgressListener);
    }

}
