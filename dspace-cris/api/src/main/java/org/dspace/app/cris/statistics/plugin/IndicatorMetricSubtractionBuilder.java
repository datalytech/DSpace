package org.dspace.app.cris.statistics.plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.solr.common.SolrDocument;
import org.dspace.app.cris.metrics.common.services.MetricsPersistenceService;
import org.dspace.app.cris.service.ApplicationService;
import org.dspace.browse.BrowsableDSpaceObject;
import org.dspace.core.Context;

public class IndicatorMetricSubtractionBuilder<ACO extends BrowsableDSpaceObject>
        extends AIndicatorMetricSolrBuilder<ACO>
{

	@Override
	public void computeMetric(Context context, ApplicationService applicationService,
			MetricsPersistenceService pService, Map<String, Integer> mapNumberOfValueComputed,
			Map<String, Double> mapValueComputed, Map<String, List<Double>> mapElementsValueComputed, ACO aco,
			SolrDocument doc, Integer resourceType, UUID resourceId, String uuid) throws Exception {

        Double valueComputed = mapValueComputed.containsKey(this.getName())
                ? mapValueComputed.get(this.getName()) : 0;

        if (doc != null)
        {
            int i = 0;
            for (String field : getFields())
            {
                
                Double count = (Double) doc
                        .getFirstValue(field);
                if(count!=null && count>0) {
                    if(i == 0) {
                        valueComputed += count;
                    }
                    else {
                        valueComputed -= count;
                    }
                }
                i++;                
            }

            mapValueComputed.put(this.getName(), valueComputed);

        }
    }

}
