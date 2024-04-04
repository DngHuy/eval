package eval2;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.Properties;
import java.util.regex.Pattern;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchTemplateResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.script.mustache.SearchTemplateRequestBuilder;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import type.Factor;
import type.IndexItem;
import type.Indicator;
import type.Metric;
import type.Relation;
import util.JsonPath;

public class Elasticsearch {
	
	private Logger log = Logger.getLogger(this.getClass().getName());
	
	private TransportClient client;

	private static ObjectMapper mapper = new ObjectMapper();
	
	private String elasticsearchIP;

	private ElasticsearchClient esClient;
	
	private static Map<String, TransportClient> clientCache = new HashMap<>();


	/**
	 * Create on address of an Elasticsearch Server
	 * @param elasticsearchIP
	 */
	public Elasticsearch( String elasticsearchIP ) {
		RestClient restClient = RestClient
				.builder(HttpHost.create(new HttpHost("localhost", 9200).toHostString()))
				.setDefaultHeaders(new Header[]{
						new BasicHeader("Authorization", "ApiKey " + "Y0p6WmJJNEJVcXFuRjVhVXUwQk46cW9yYktzRFFROXU2ZkNQUkZPeWN3UQ==")
				})
				.build();

		ElasticsearchTransport transport = new RestClientTransport(
				restClient,
				new JacksonJsonpMapper()
		);
		esClient = new ElasticsearchClient(transport);

		
		this.elasticsearchIP = elasticsearchIP;
		
		if ( clientCache.containsKey(elasticsearchIP) ) {
			log.info("Using cached TransportClient.\n");
			client = clientCache.get(elasticsearchIP);
			return;
		} 

		try {
			InetAddress address = InetAddress.getByName(elasticsearchIP);
			client = new PreBuiltTransportClient(Settings.EMPTY).addTransportAddress( new InetSocketTransportAddress( address , 9300 ) );
			if ( client.connectedNodes().size() == 0 ) {
				log.severe( "Could not connect to Elasticsearch on " + elasticsearchIP + ", exiting." );
//				System.exit(0);
				clientCache.put(elasticsearchIP, client);
			} else {
				clientCache.put(elasticsearchIP, client);
			}
		} catch (UnknownHostException e) {
			System.err.println("Could not connect ot Elasticsearch on " + elasticsearchIP);
			e.printStackTrace();
		}
		
	}
	
	/**
	 * Get the TransportClient
	 * @return
	 */
	public TransportClient getClient() {
		return client;
	}
	
	public String getElasticsearchIP() {
		return elasticsearchIP;
	}

	/**
	 * Execute QueryDef with additional parameters
	 * @param externalParameters Additional parameters derived by i.e. param-queries
	 * @param queryDef the queryDef to execute
	 * @return
	 */
	public Map<String,Object> execute( Map<String,Object> externalParameters, QueryDef queryDef ) {
		
		log.info("Executing QueryDef " + queryDef.getName() + "\nIndex: " + queryDef.getProperty("index") + "\nExternal parameters: " + externalParameters + "\n" + "query parameters: " + queryDef.getQueryParameter() + "\n");
		
		Map<String,Object> execParams = new HashMap<>();
		
		execParams.putAll(externalParameters);
		execParams.putAll( queryDef.getQueryParameter() );

		SearchTemplateResponse<Object> sr = search(queryDef, (String) queryDef.getProperty("index"), execParams );
	    
	    Map<String,Object> executionResult = new HashMap<>();
	    
	    
	    if ( sr == null ) {
	    	log.warning("QueryDef " + queryDef.getName() + " failed.\n");
	    	return executionResult;
	    }
	    
	    Map<String,String> queryResults = queryDef.getResults();
	    
	    String response = sr.toString();
	    log.info("Elasticsearch Response: " + response.trim() + "\n");

		JsonpMapper mapper = esClient._jsonpMapper();
		String result = JsonpUtils.toJsonString(sr, mapper);

		Pattern pattern = Pattern.compile("(range#|date_range#|histogram#|terms#|avg#|sum#|min#|max#|filter#)");
		String sanitized = pattern.matcher(result).replaceAll("");

	    try {
			ObjectMapper oB = new ObjectMapper();
			JsonNode node = oB.readTree(sanitized);

		    for ( Entry<String,String> e : queryResults.entrySet() ) { 
		    	
		    	JsonNode value = JsonPath.getNode( node, e.getValue() );
		    	Object o = convert( value );
		    	executionResult.put(e.getKey(), o);
		    }

	    } catch ( Exception e ) {
	    	e.printStackTrace();
	    }
	    

		return executionResult;
	}
	
	/**
	 * Try to convert a JsonNode into a Number Object.
	 * Return at least String value
	 * @param node
	 * @return
	 */
	private static Object convert( JsonNode node ) {
		
		if ( node.isLong() ) {
			return node.asLong();
		}
		
		if ( node.isInt() ) {
			return node.asInt();
		}
		
		if ( node.isDouble() ) {
			return node.asDouble();
		}
		
		if ( node.isTextual() ) {
			return node.textValue();
		}
		
		return node.asText();
	}
	
	/**
	 * Perform an Elasticsearch search
	 * @param queryDef The query with name & template
	 * @param index The index to run the query on
	 * @param params Parameters for the templateQuery
	 * @return
	 */
	public SearchTemplateResponse<Object> search(QueryDef queryDef, String index, Map<String,Object> params) {
		
		try {
			esClient.putScript(r -> r
					.id(queryDef.getName())
					.script(s -> s
							.lang("mustache")
							.source(queryDef.getQueryTemplate())
					));

			Map<String, JsonData> params2 = convertMapToJsonStrings(params);
			SearchTemplateResponse<Object> response = esClient.searchTemplate(r -> r
					.index("sonar_measures")
					.id(queryDef.getName())
					.params(params2), Object.class
			);

//			SearchResponse sr = new SearchTemplateRequestBuilder(client)
//		    		.setScript(queryDef.getQueryTemplate())
//		    		.setScriptType(ScriptType.INLINE)
//		    		.setRequest(new SearchRequest(index))
//		    		.setScriptParams(params)
//		    		.get()
//		    		.getResponse();
			
			return response;
		} catch (RuntimeException rte) {
			log.severe(rte.getMessage() + "\n" + rte.toString() + "\n");
			return null;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}

	private static Map<String, JsonData> convertMapToJsonStrings(Map<String, Object> map) throws Exception {
		Map<String, JsonData> jsonMap = new HashMap<>();
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (!value.toString().isEmpty()) jsonMap.put(key, JsonData.of(value));
		}
		return jsonMap;
	}

	public void storeMetrics(Properties projectProperties, String evaluationDate, Collection<Metric> metrics) {
		
		String metricIndex = projectProperties.getProperty("metrics.index") + "." + projectProperties.getProperty("project.name");
		
		checkCreateIndex(  metricIndex, "schema/metric.schema", "metrics" );
		
		long deleted = deleteCurrentEvaluation(
				metricIndex,
				projectProperties.getProperty("project.name"),
				evaluationDate
		);
		
		log.info("deleted " + deleted + " metrics (evaluationDate=" + evaluationDate + ")\n");
		
		long deleted2 = deleteCurrentEvaluation(
				projectProperties.getProperty("relations.index") + "." + projectProperties.getProperty("project.name"),  
				projectProperties.getProperty("project.name"),
				evaluationDate
		);
		
		log.info("deleted " + deleted2 + " relations (evaluationDate=" + evaluationDate + ")\n");
		
		BulkResponse br = writeBulk(evaluationDate, metricIndex, "metrics", metrics);
		
		log.info( bulkResponseCheck(br) + "\n" );
		
		
	}
	
	public void storeRelations(Properties projectProperties, String evaluationDate, Collection<Relation> relations) {
		
		String indexName = projectProperties.getProperty("relations.index") + "." + projectProperties.getProperty("project.name");;
		
		checkCreateIndex(  indexName, "schema/relation.schema", "relations" ); 
		
		BulkResponse br = writeBulk(evaluationDate, indexName, "relations", relations);
		
		log.info( bulkResponseCheck(br) );
		
	}
	

	public void storeFactors(Properties projectProperties, String evaluationDate, Collection<Factor> factors ) {
		
		String indexName = projectProperties.getProperty("factors.index") + "." + projectProperties.getProperty("project.name");;
		
		checkCreateIndex(  indexName, "schema/factor.schema", "factors" );
		
		long deleted = deleteCurrentEvaluation(
				indexName, 
				projectProperties.getProperty("project.name"),
				evaluationDate
		);
		
		log.info("deleted " + deleted + " factors (evaluationDate=" + evaluationDate + ").\n");
		
		BulkResponse br = writeBulk(evaluationDate, indexName, "factors", factors);
		
		log.info( bulkResponseCheck(br) );
		
	}
	
	public void storeIndicators(Properties projectProperties, String evaluationDate, Collection<Indicator> indicators) {
		String indexName = projectProperties.getProperty("indicators.index") + "." + projectProperties.getProperty("project.name");;
		
		checkCreateIndex(  indexName, "schema/indicator.schema", "indicators" );
		
		long deleted = deleteCurrentEvaluation(
				indexName, 
				projectProperties.getProperty("project.name"),
				evaluationDate
		);
		
		log.info("deleted " + deleted + " indicators (evaluationDate=" + evaluationDate + ").\n");
		
		BulkResponse br = writeBulk(evaluationDate, indexName, "indicators", indicators);
		
		log.info( bulkResponseCheck(br) );
		
	}
	
	private void checkCreateIndex( String indexName, String schemaPathname, String mappingType ) {
		try {
			BooleanResponse result = esClient.indices().exists(ExistsRequest.of(e -> e.index(indexName)));
			if (!result.value()) {
				IndexManager mgr = new IndexManager(this);
				mgr.createIndex(indexName, schemaPathname, mappingType);
			}
		} catch ( Exception e) {
			log.info( e.getMessage() + "\n"); 
		}
		
	}
	
	private BulkResponse writeBulk( String evaluationDate, String index, String mappingType, Collection<? extends IndexItem> items) {
		
		if ( items.size() == 0 ) {
			log.warning("No items stored");
			return null;
		}

		BulkRequest.Builder br = new BulkRequest.Builder();
		for (IndexItem item : items) {
			br.operations(op -> op
					.index(idx -> idx
							.index(index)
							.id(item.getElasticId())
							.document(item)
					)
			);
		}

		BulkResponse result = null;
		try {
			result = esClient.bulk(br.build());
		} catch (IOException e) {
			log.info( e.getMessage() + "\n");
		}

		return result;

	}
	
	private String bulkResponseCheck( BulkResponse br ) {
		
		if ( br == null ) {
			log.warning("Response is null");
			return "";
		}
		
		String result = "";

		if ( br.errors() ) {
			for ( BulkResponseItem bri : br.items() ) {
				if (bri.error() != null) {
					result += bri.error().type() + ":" + bri.error().reason() + " \n";
				}
			}
		} else {
			result = "BulkUpdate success! " + br.items().size() + " items written!\n" ;
		}
		
		return result;
	}
	
	private long deleteCurrentEvaluation( String indexName, String project, String evaluationDate ) {
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
}
