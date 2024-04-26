package type;

import java.util.HashMap;
import java.util.Map;

public class Level3 extends IndexItem {

    public Level3(

            Boolean enabled,
            String project,
            String level3,
            String evaluationDate,

            String[] parents,
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
        this.id = level3;
        this.evaluationDate = evaluationDate;

        this.parents = parents;
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
        return "level3";
    }

    public String getLevel3() {
        return id;
    }

    public void setLevel2(String level2) {
        this.id = level2;
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
        result.put("level3", id);
        result.put("evaluationDate", evaluationDate);

        result.put("parents", parents);
        result.put("weights", weights);

        result.put("name", name);
        result.put("description", description);
        result.put("datasource", datasource);

        result.put("value", value);
        result.put("info", info);

        return result;

    }


}
