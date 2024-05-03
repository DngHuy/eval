package eval2;

import org.jboss.logging.Logger;
import type.Level2;
import type.Level3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ModelChecker {

    public static Logger log = Logger.getLogger(ModelChecker.class);

    public static void check(Map<String, QueryDef> metricQueries, Map<String, Level2> level2Map, Map<String, Level3> level3Map) {

        Set<String> allInfluencedFactors = new HashSet<>();

        // Factors referenced in <metric>.properties are defined in level2.properties
        for (QueryDef qd : metricQueries.values()) {

            if (!qd.isEnabled()) continue;

            String[] influencedFactors = qd.getPropertyAsStringArray("level2");
            for (String f : influencedFactors) {
                allInfluencedFactors.add(f);
                if (!level2Map.containsKey(f)) {
                    log.warn("Level 2 " + f + " is influenced by Metric " + qd.getName() + " but not defined in level2.properties.\n");
                } else {
                    Level2 fact = level2Map.get(f);
                    if (!fact.isEnabled()) {
                        log.warn("Level 2 " + f + " is influenced by Metric " + qd.getName() + " but is not enabled in level2.properties.\n");
                    }
                }
            }
        }

        // for each factor defined in level2.properties
        // check, that it is influenced by a metric (factor is listed under 'factors' in metric.properties)
        for (String f : level2Map.keySet()) {

            if (!level2Map.get(f).isEnabled()) continue;

            if (!allInfluencedFactors.contains(f)) {
                log.warn("Level 2 " + f + " is defined in level2.properties but not influenced by any Metric.\n");
            }
        }

        // level3s referenced in level2.properties are defined in level3.properties
        Set<String> allinfluencedLevel3 = new HashSet<>();
        for (Level2 f : level2Map.values()) {
            if (!f.isEnabled()) continue;
            for (String i : f.getLevel3s()) {
                allinfluencedLevel3.add(i);
                if (!level3Map.containsKey(i)) {
                    log.warn("Level 3 " + i + " is influenced by Level 2 " + f.getLevel2() + " but not defined in level3.properties.\n");
                } else {
                    if (!level3Map.get(i).isEnabled()) {
                        log.warn("Level 3 " + i + " is influenced by Level 2 " + f.getLevel2() + " but is not enabled in level3.properties.\n");
                    }
                }
            }
        }

        // for each level3 defined in level3.properties
        // check, that it is influenced by a factor (level3 is listed under 'level3s' in level2.properties)
        for (String i : level3Map.keySet()) {
            if (!level3Map.get(i).isEnabled()) continue;

            if (!allinfluencedLevel3.contains(i)) {
                log.warn("Level 3 " + i + " is defined in level3.properties but not influenced by any Factor defined in level2.properties.\n");
            }
        }


    }

}
