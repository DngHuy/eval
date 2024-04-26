package type;

import java.util.HashMap;
import java.util.Map;

public class Level2 extends IndexItem {

    public Level2(

            Boolean enabled,
            String project,
            String level2,
            String evaluationDate,

            String[] level3s,
            Double[] weights,

            String name,
            String description,
            String datasource,
            Double value,
            String info,
            String onError

    ) {

        this.enabled = enabled;

        this.project = project;
        this.id = level2;
        this.evaluationDate = evaluationDate;

        this.parents = level3s;
        this.weights = weights;

        this.name = name;
        this.description = description;
        this.datasource = datasource;

        this.value = value;
        this.info = info;

        this.onError = onError;

    }

    @Override
    public String getType() {
        return "factors";
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getLevel2() {
        return id;
    }

    public void setLevel2(String factor) {
        this.id = factor;
    }

    public String[] getLevel3s() {
        return parents;
    }

    public void setLevel3s(String[] level3s) {
        this.parents = level3s;
    }

    public Map<String, Object> getMap() {

        Map<String, Object> result = new HashMap<String, Object>();

        result.put("project", project);
        result.put("level2", id);
        result.put("evaluationDate", evaluationDate);

        result.put("level3s", parents);
        result.put("weights", weights);

        result.put("name", name);
        result.put("description", description);
        result.put("datasource", datasource);

        result.put("value", value);
        result.put("info", info);

        return result;

    }


}
