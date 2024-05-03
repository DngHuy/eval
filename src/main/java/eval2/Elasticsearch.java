package eval2;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.spi.JsonProvider;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.jboss.logging.Logger;
import type.*;
import util.JsonPath;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

@ApplicationScoped
public class Elasticsearch {

    private final Logger log = Logger.getLogger(Elasticsearch.class);


    private String elasticsearchIP;

    private ElasticsearchClient esClient;


    @Inject
    Elasticsearch() {
    }

    /**
     * Create on address of an Elasticsearch Server
     *
     * @param elasticsearchIP
     */
    public Elasticsearch(String elasticsearchIP, int port, String apiKey) {
        this.elasticsearchIP = elasticsearchIP;

        RestClient restClient = RestClient
                .builder(HttpHost.create(new HttpHost(elasticsearchIP, port).toHostString()))
                .setDefaultHeaders(new Header[]{
                        new BasicHeader("Authorization", "ApiKey " + apiKey)
                })
                .build();

        ElasticsearchTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );
        esClient = new ElasticsearchClient(transport);
        validateIndex();
    }

    private void validateIndex() {
        checkCreateIndex("relations", "schema/metric.schema", "relations");
    }

    public String getElasticsearchIP() {
        return elasticsearchIP;
    }

    /**
     * Execute QueryDef with additional parameters
     *
     * @param externalParameters Additional parameters derived by i.e. param-queries
     * @param queryDef           the queryDef to execute
     * @return
     */
    public Map<String, Object> execute(Map<String, Object> externalParameters, QueryDef queryDef) {

        log.info("Executing QueryDef " + queryDef.getName() + "\nIndex: " + queryDef.getProperty("index") + "\nExternal parameters: " + externalParameters + "\n" + "query parameters: " + queryDef.getQueryParameter() + "\n");

        Map<String, Object> execParams = new HashMap<>();

        execParams.putAll(externalParameters);
        execParams.putAll(queryDef.getQueryParameter());

        SearchTemplateResponse<Object> sr = search(queryDef, (String) queryDef.getProperty("index"), execParams);

        Map<String, Object> executionResult = new HashMap<>();


        if (sr == null) {
            log.warn("QueryDef " + queryDef.getName() + " failed.\n");
            return executionResult;
        }

        Map<String, String> queryResults = queryDef.getResults();

        String response = sr.toString();
        log.info("Elasticsearch Response: " + response.trim() + "\n");

        JsonpMapper mapper = esClient._jsonpMapper();
        String result = JsonpUtils.toJsonString(sr, mapper);

        Pattern pattern = Pattern.compile("(range#|date_range#|histogram#|terms#|avg#|sum#|min#|max#|filter#|sterms#|value_count#|cardinality#)");
        String sanitized = pattern.matcher(result).replaceAll("");

        try {
            ObjectMapper oB = new ObjectMapper();
            JsonNode node = oB.readTree(sanitized);

            for (Entry<String, String> e : queryResults.entrySet()) {
                JsonNode value = null;
                Object o = null;
                if (!e.getKey().equals("distribution")) {
                    value = JsonPath.getNode(node, e.getValue());
                    o = convert(value);
                } else {
                    value = JsonPath.getNode(node, e.getValue());
                    JsonArray hits = getBucket(value.toString());
                    List<Integer> taskClosedPerUser = extractDocCounts(hits);
                    o = calculateMAE(taskClosedPerUser, taskClosedPerUser.stream().mapToInt(Integer::intValue).sum(), taskClosedPerUser.size());
                }
                executionResult.put(e.getKey(), o);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }


        return executionResult;
    }

    private JsonArray getBucket(String responseBody) {
        JsonObject json = new JsonObject(responseBody);
        return json.getJsonArray("buckets");
    }

    private List<Integer> extractDocCounts(JsonArray jsonArray) {
        List<Integer> docCounts = new ArrayList<>();
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject obj = jsonArray.getJsonObject(i);
            int docCount = obj.getInteger("doc_count");
            docCounts.add(docCount);
        }
        return docCounts;
    }

    private Double calculateMAE(List<Integer> taskPerUser, int total, int size) {
        double optimalWorkload = (100.00 / size) / 100.00;
        List<Double> workload = taskPerUser
                .stream()
                .mapToDouble(num -> (double) num / total)
                .boxed().toList();

        return 1 - workload.stream()
                .mapToDouble(value -> Math.abs(value - optimalWorkload))
                .sum();
    }

    /**
     * Try to convert a JsonNode into a Number Object.
     * Return at least String value
     *
     * @param node
     * @return
     */
    private static Object convert(JsonNode node) {

        if (node.isLong()) {
            return node.asLong();
        }

        if (node.isInt()) {
            return node.asInt();
        }

        if (node.isDouble()) {
            return node.asDouble();
        }

        if (node.isTextual()) {
            return node.textValue();
        }

        return node.asText();
    }

    /**
     * Perform an Elasticsearch search
     *
     * @param queryDef The query with name & template
     * @param index    The index to run the query on
     * @param params   Parameters for the templateQuery
     * @return
     */
    public SearchTemplateResponse<Object> search(QueryDef queryDef, String index, Map<String, Object> params) {

        try {
            esClient.putScript(r -> r
                    .id(queryDef.getName())
                    .script(s -> s
                            .lang("mustache")
                            .source(queryDef.getQueryTemplate())
                    ));

            Map<String, JsonData> params2 = convertMapValueToJsonData(params);

            return esClient.searchTemplate(r -> r
                    .index(index)
                    .id(queryDef.getName())
                    .params(params2), Object.class
            );
        } catch (RuntimeException rte) {
            log.error(rte.getMessage() + "\n" + rte.toString() + "\n");
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public void storeMetrics(Properties projectProperties, String evaluationDate, Collection<Metric> metrics) {

        String metricIndex = projectProperties.getProperty("metrics.index");

        checkCreateIndex(metricIndex, "schema/metric.schema", "metrics");

        long deleted = deleteCurrentEvaluation(
                metricIndex,
                projectProperties.getProperty("project.name"),
                evaluationDate
        );

        log.info("deleted " + deleted + " metrics (evaluationDate=" + evaluationDate + ")\n");

        long deleted2 = deleteCurrentEvaluation(
                projectProperties.getProperty("relations.index"),
                projectProperties.getProperty("project.name"),
                evaluationDate
        );

        log.info("deleted " + deleted2 + " relations (evaluationDate=" + evaluationDate + ")\n");

        BulkResponse br = writeBulk(evaluationDate, metricIndex, "metrics", metrics);

        log.info(bulkResponseCheck(br) + "\n");


    }

    public void storeRelations(Properties projectProperties, String evaluationDate, Collection<Relation> relations) {

        String indexName = projectProperties.getProperty("relations.index"); // + "." + projectProperties.getProperty("project.name");

        checkCreateIndex(indexName, "schema/relation.schema", "relations");

        BulkResponse br = writeBulk(evaluationDate, indexName, "relations", relations);

        log.info(bulkResponseCheck(br));

    }

    public void storeLevel2(Properties projectProperties, String evaluationDate, Collection<Level2> level2s) {

        String indexName = projectProperties.getProperty("level2.index");

        checkCreateIndex(indexName, "schema/level2.schema", "level2");

        long deleted = deleteCurrentEvaluation(
                indexName,
                projectProperties.getProperty("project.name"),
                evaluationDate
        );

        log.info("deleted " + deleted + " level2 (evaluationDate=" + evaluationDate + ").\n");

        BulkResponse br = writeBulk(evaluationDate, indexName, "level2", level2s);

        log.info(bulkResponseCheck(br));

    }

    public void storeLevel3(Properties projectProperties, String evaluationDate, Collection<Level3> level3s) {
        String indexName = projectProperties.getProperty("level3.index");

        checkCreateIndex(indexName, "schema/level3.schema", "level3");

        long deleted = deleteCurrentEvaluation(
                indexName,
                projectProperties.getProperty("project.name"),
                evaluationDate
        );

        log.info("deleted " + deleted + " level3 (evaluationDate=" + evaluationDate + ").\n");

        BulkResponse br = writeBulk(evaluationDate, indexName, "level3", level3s);

        log.info(bulkResponseCheck(br));

    }

    private void checkCreateIndex(String indexName, String schemaPathname, String mappingType) {
        try {
            BooleanResponse result = esClient.indices().exists(ExistsRequest.of(e -> e.index(indexName)));
            if (!result.value()) {
                IndexManager mgr = new IndexManager(this);
                mgr.createIndex(indexName, schemaPathname, mappingType);
            }
        } catch (Exception e) {
            log.info(e.getMessage() + "\n");
        }

    }

    private BulkResponse writeBulk(String evaluationDate, String index, String mappingType, Collection<? extends IndexItem> items) {

        if (items.size() == 0) {
            log.warn("No items stored");
            return null;
        }

        ObjectMapper oM = new ObjectMapper();

        BulkRequest.Builder br = new BulkRequest.Builder();
        for (IndexItem item : items) {
            try {
                String data = oM.writeValueAsString(item.getMap());
                Reader reader = new StringReader(data);
                JsonpMapper mapper = esClient._jsonpMapper();
                JsonProvider jsonProvider = mapper.jsonProvider();
                JsonData json = JsonData.from(jsonProvider.createParser(reader), mapper);

                br.operations(op -> op
                        .index(c -> c
                                .index(index)
                                .id(item.getElasticId())
                                .document(json)
                        )
                );
            } catch (JsonProcessingException e) {
                log.info(e.getMessage() + "\n");
            }

        }

        BulkResponse result = null;
        try {
            result = esClient.bulk(br.build());
        } catch (IOException e) {
            log.info(e.getMessage() + "\n");
        }

        return result;

    }

    private String bulkResponseCheck(BulkResponse br) {

        if (br == null) {
            log.warn("Response is null");
            return "";
        }

        StringBuilder result = new StringBuilder();

        if (br.errors()) {
            for (BulkResponseItem bri : br.items()) {
                if (bri.error() != null) {
                    result.append(bri.error().type()).append(":").append(bri.error().reason()).append(" \n");
                }
            }
        } else {
            result = new StringBuilder("BulkUpdate success! " + br.items().size() + " items written!\n");
        }

        return result.toString();
    }

    private long deleteCurrentEvaluation(String indexName, String project, String evaluationDate) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(
                    d -> d
                            .index(indexName)
                            .query(q -> q
                                    .bool(b -> b
                                            .must(m -> m
                                                    .term(t -> t
                                                            .field("evaluationDate")
                                                            .value(evaluationDate)))
                                            .must(m -> m
                                                    .term(t -> t
                                                            .field("project_name")
                                                            .value(project)))
                                    )
                            )
            );
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            return response.deleted() == null ? 0 : response.deleted();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public ElasticsearchClient getEsClient() {
        return esClient;
    }

    private static Map<String, JsonData> convertMapValueToJsonData(Map<String, Object> map) throws Exception {
        Map<String, JsonData> jsonMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!value.toString().isEmpty()) jsonMap.put(key, JsonData.of(value));
        }
        return jsonMap;
    }
}
