package co.nz.restorelab;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.wps.gs.GeoServerProcess;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.process.ProcessException;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;

import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

@DescribeProcess(title = "Calculate Yearly Mean", description = "Publishes the yearly mean for soil moisture content to a new layer")
public class CalculateYearlyMean implements GeoServerProcess {
    Catalog catalog;

    CalculateYearlyMean(Catalog catalog) {
        this.catalog = catalog;
    }

    public void execute(
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
        Date startDate1 = new GregorianCalendar(year, Calendar.JANUARY, 1).getTime();

        
    }

}
