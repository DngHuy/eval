package eval2;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.enterprise.context.ApplicationScoped;

import type.*;
import type.Level2;

import java.util.Properties;
import java.util.Set;
import org.jboss.logging.Logger;

import util.Evaluator;
import util.FileUtils;

@ApplicationScoped
public class EvalProject {
	
	private final Logger log = Logger.getLogger(this.getClass().getName());
	
	private String evaluationDate;

	// project folder containing queries, properties etc.
	private final File projectFolder;
	
	// contents of projectFolder/propect.properties
	private final Properties projectProperties;
	
	// Elasticsearch source
	private Elasticsearch elasticSource;
	
	// Elasticsearch target
	private Elasticsearch elasticTarget;

	// param query set of this project
	private Map<String,QueryDef> paramQuerySet;
	
	// metric query set of this project
	private Map<String,QueryDef> metricQuerySet;
	
	private final String projectErrorStrategy;

	public static EvalProject createEvalProject(File projectFolder, String evaluationDate ) {
		return new EvalProject(projectFolder, evaluationDate);
	}


	public EvalProject(File projectFolder, String evaluationDate ) {

		this.projectFolder = projectFolder;

		String projectPropertyFilename = projectFolder.getAbsolutePath() + File.separatorChar + "project.properties";
		this.projectProperties = FileUtils.loadProperties( new File(projectPropertyFilename) );

		projectErrorStrategy = projectProperties.getProperty("onError", IndexItem.ON_ERROR_DROP);

		this.evaluationDate = evaluationDate;

	}
	
	public void validateModel() {
		
		File metricQueryFolder = new File( projectFolder.getAbsolutePath() + File.separatorChar + "metrics" );
		metricQuerySet = getQuerySet( metricQueryFolder ); 
		
		ModelChecker.check( metricQuerySet, readLevel2Map(), readLevel3Map() );
		
	}
	
	public void run() {
		
		validateModel();
		
		File metricQueryFolder = new File( projectFolder.getAbsolutePath() + File.separatorChar + "metrics" );
		metricQuerySet = getQuerySet( metricQueryFolder ); 

		log.info("Connecting to Elasticsearch Source (" + projectProperties.getProperty("elasticsearch.source.ip") + ")\n");
		elasticSource = new Elasticsearch( projectProperties.getProperty("elasticsearch.source.ip") );
		
		log.info("Connecting to Elasticsearch Target (" + projectProperties.getProperty("elasticsearch.target.ip") + ")\n");
		elasticTarget = new Elasticsearch( projectProperties.getProperty("elasticsearch.target.ip") );
		
		File paramQueryFolder = new File( projectFolder.getAbsolutePath() + File.separatorChar + "params" );
		paramQuerySet = getQuerySet( paramQueryFolder ); 
		
		log.info("Executing param queries (" + paramQuerySet.size() + " found)\n");
		Map<String,Object> queryParameter = executeParamQueryset( paramQuerySet, evaluationDate );
		log.info("Param query result: " + queryParameter + "\n"); 

		log.info("Executing metric queries (" + metricQuerySet.size() + " found)\n");
		List<Metric> metrics = executeMetricQueries(queryParameter, metricQuerySet);
		
		log.info("Storing metrics (" + metrics.size() + " computed)\n");
		elasticTarget.storeMetrics( projectProperties, evaluationDate, metrics );


		List<Relation> metricRelations = computeMetricRelations(metrics);
		log.info("Storing metric relations (" + metricRelations.size() + " computed)\n");
		elasticTarget.storeRelations( projectProperties, evaluationDate, metricRelations );

		log.info("Computing Level2 ...\n");
		Collection<Level2> level2s = computeLevel2();

		log.info("Storing Level2 (" + level2s.size() + " computed)\n");
		elasticTarget.storeLevel2( projectProperties, evaluationDate, level2s);
		/*
		log.info("Storing factor relations ... \n");
		List<Relation> factorrelations = computeFactorRelations(factors);
		elasticTarget.storeRelations( projectProperties, evaluationDate, factorrelations );

		// try { Thread.sleep(1000); } catch (InterruptedException e) { e.printStackTrace(); }
		
		log.info("Computing Level3  ...\n");
		Collection<Level3> level3s = computeLevel3();
		
		elasticTarget.storeLevel3( projectProperties, evaluationDate, level3s );
		*/
	}
	

	/**
	 * Computes Factor values based on relation items
	 * @return List of computed Level2
	 */
	private Collection<Level2> computeLevel2() {
		
		List<Level2> result = new ArrayList<>();
		
		String level2QueryDir = projectFolder.getAbsolutePath() + File.separatorChar + "level2";
		QueryDef level2Query = loadQueryDef(level2QueryDir, "level2");
		level2Query.setIndex( level2Query.getProperty("index"));
		
		Map<String, Level2> factorMap = readLevel2Map();
		
		for ( Entry<String, Level2> e : factorMap.entrySet() ) {
			
			Level2 fact = e.getValue();
			
			if ( !fact.isEnabled() ) {
				log.info("Factor " + fact.getLevel2() + " is disabled.\n");
				continue;
			} else {
				log.info("Computing factor  " + fact.getLevel2() + ".\n") ;
			}

			Map<String,Object> parameters = new HashMap<>();
			parameters.put( "evaluationDate", evaluationDate);
			parameters.put( "project", projectProperties.getProperty("project.name") );
			parameters.put( "targetType", e.getValue().getType() );
			parameters.put( "targetId", e.getValue().getElasticId() ); 
			
			Map<String,Object> results = elasticTarget.execute(parameters, level2Query);
			String metricDef = level2Query.getProperty("metric");


			Double level2Value=null;
			try {
				level2Value = evaluate( metricDef, results );
			} catch( RuntimeException rte ) {
				
				log.warn("Evaluation of formula " + metricDef + " failed. \nFactor: " + fact.getName() + "\n");

				if ( fact.onErrorSet0() ) {
					log.warn("Factor " + fact.getLevel2() + " set to 0.\n");
					level2Value = 0.0;
				} else {
					log.warn("Factor " + fact.getLevel2() + " is dropped.\n");
					continue;
				}

			}
				
			// level2Value not numeric?
			if ( level2Value.isNaN() || level2Value.isInfinite() ) {
				
				log.warn("Evaluation of Factor " + fact.getLevel2() + " resulted in non-numeric value.\n" );
				
				if ( fact.onErrorSet0() ) {
					log.warn("Factor " + fact.getLevel2() + " set to 0.\n");
					level2Value = 0.0;
				} else {
					log.warn("Factor " + fact.getLevel2() + " is dropped.\n");
					continue;
				}
			} else {
				log.info("Value of factor " + fact.getLevel2() + " = " + level2Value + "\n");
			}
			
			fact.setValue(level2Value);
			fact.setEvaluationDate(evaluationDate);
			
			String info;
			info = "parameters: " + parameters.toString() + "\n";
			info += "query-properties: " + level2Query.getQueryParameter().toString() + "\n";
			info += "executionResults: " + results.toString() + "\n";
			info += "formula: " + metricDef + "\n";
			info += "value: " + level2Value;

			fact.setInfo(info);
			
			result.add(fact);
		}
		
		return result;
		
		
	}
	
	/**
	 * Compute Relations between Level2 and Level 3s
	 * @param level2s
	 * @return List of Relation
	 */
	private List<Relation> computeFactorRelations( Collection<Level2> level2s) {
		
		List<Relation> result = new ArrayList<>();
		
		Map<String, Level3> level3Map = readLevel3Map();
		
		for ( Level2 level2 : level2s) {
			for (int i = 0; i < level2.getLevel3s().length; i++ ) {
				
				String level3Id = level2.getLevel3s()[i];
				Double weight = level2.getWeights()[i];

				Level3 level3 = level3Map.get(level3Id);
				
				if ( level3 == null ) {
					log.info( "Warning: Impact of Level 2 " + level2.getName() + " on undefined Level 3 " + level3Id + "is not stored."  );
				} else {
					if ( !level3.isEnabled() ) {
						log.info("Level 3 " + level3.getName() + " is disabled. No relation created.\n");
						continue;
					}
					
					Relation imp = new Relation(level2.getProject(), level2, level3, evaluationDate, level2.getValue() * weight, weight);
					result.add(imp);
				}
				
			}
		}
		
		
		return result;
		
	}
	
	/**
	 * Compute Level 3 values based on Level2-Level3 relations
	 * @return List of Level 3
	 */
	private Collection<Level3> computeLevel3() {
		
		List<Level3> result = new ArrayList<>();
		
		String level3QueryDir = projectFolder.getAbsolutePath() + File.separatorChar + "level3";
		QueryDef level3Query = loadQueryDef(level3QueryDir, "level3");
		level3Query.setIndex(level3Query.getProperty("index") + "." + projectProperties.getProperty("project.name"));
		 
		Map<String, Level3> level3Map = readLevel3Map();
		
		for ( Entry<String, Level3> e : level3Map.entrySet() ) {
			
			Level3 l3 = e.getValue();
			
			if ( !l3.isEnabled() ) {
				log.info("Level 3 " + l3.getLevel3() + " is disabled.\n");
				continue;
			} else {
				log.info("Computing level 3 " + l3.getLevel3() + ".\n") ;
			}
			
			Map<String,Object> parameters = new HashMap<>();
			parameters.put( "evaluationDate", evaluationDate);
			parameters.put( "project", projectProperties.getProperty("project.name") );
			parameters.put( "targetType", e.getValue().getType() );
			parameters.put( "targetId", e.getValue().getElasticId() ); 
			
			Map<String,Object> results = elasticTarget.execute(parameters, level3Query);
			String metricDef = level3Query.getProperty( "metric" );

			Double level3Value;
			try {
				level3Value = evaluate( metricDef, results );
			} catch (RuntimeException rte) {

				log.warn("Evaluation of formula " + metricDef + " failed.\nLevel 3: " + l3.getName());

				if ( l3.onErrorSet0() ) {
					log.warn("Level 3 " + l3.getName() + " set to 0.\n");
					level3Value = 0.0;
				} else {
					log.warn("Level 3 " + l3.getName() + " is dropped.\n");
					continue;
				}
				
			}

			if ( level3Value.isNaN() || level3Value.isInfinite() ) {
				if ( l3.onErrorSet0() ) {
					log.warn("Level 3 " + l3.getName() + " set to 0.\n");
					level3Value = 0.0;
				} else {
					log.warn("Level 3 " + l3.getName() + " is dropped.\n");
					continue;
				}
			} else {
				log.info("Value of Level 3 " + l3.getLevel3() + " = " + level3Value + "\n");
			}
			
			l3.setValue(level3Value);
			l3.setEvaluationDate(evaluationDate);
			
			String info;
			info = "parameters: " + parameters.toString() + "\n";
			info += "query-properties: " + level3Query.getQueryParameter().toString() + "\n";
			info += "executionResults: " + results.toString() + "\n";
			info += "formula: " + metricDef + "\n";
			info += "value: " + level3Value;

			l3.setInfo(info);
			
			result.add(l3);
		}
		
		return result;
		
	}

	/**
	 * Execute a Set of Queries
	 * @param querySets Map of QueryDef
	 * @return Map of execution results Name->Value
	 */
	private Map<String, Object> executeParamQueryset( Map<String, QueryDef> querySets, String evaluationDate ) {
		
		Map<String,Object> allExecutionResults = new HashMap<>();
		allExecutionResults.put("evaluationDate", evaluationDate);

		for ( String key : querySets.keySet() ) {

			Map<String,Object> executionResult = elasticSource.execute( allExecutionResults, querySets.get(key) );
			allExecutionResults.putAll(executionResult);

		}
		
		return allExecutionResults;
		
	}
	
	/**
	 * Execute Metric queries
	 * @param parameters Parameter Map
	 * @param metricQuerySet Query Map
	 * @return List of Metric
	 */
	private List<Metric> executeMetricQueries( Map<String,Object> parameters, Map<String, QueryDef> metricQuerySet) {
		
		List<Metric> result = new ArrayList<>();
		

		for ( String key : metricQuerySet.keySet() ) {
			
			QueryDef metricQueryDef = metricQuerySet.get(key);
			
			if ( !metricQueryDef.isEnabled() ) {
				log.info("Metric " + metricQueryDef.getName() + " is disabled.\n");
				continue;
			}
			
			String info;
			info = "parameters: " + parameters.toString() + "\n";
			
			info += "query-properties: " + metricQueryDef.getQueryParameter().toString() + "\n";
			
			log.info("Executing metric query: " + key + "\n");
			Map<String,Object> executionResult = elasticSource.execute( parameters, metricQueryDef );
			log.info("result: " + executionResult + "\n");
			
			info += "executionResults: " + executionResult.toString() + "\n";
			
			String metricDef = metricQueryDef.getProperty("metric");
			
			info += "formula: " + metricDef + "\n";
			
			Map<String,Object> evalParameters = new HashMap<>();
			evalParameters.putAll(parameters);
			evalParameters.putAll(executionResult);
			
			Double metricValue=null;
			try {
				
				metricValue = evaluate( metricDef, evalParameters );
				info += "value: " + metricValue;
				
			} catch (RuntimeException rte) {
				
				log.warn("Evaluation of formula " + metricDef + " failed. \nMetric: " + key);
				if ( metricQueryDef.onErrorDrop() ) {
					log.warn("Metric " + key + " is dropped.");
					continue;
				} else {
					metricValue = metricQueryDef.getErrorValue();
					log.warn("Metric " + key + " set to " + metricValue + ".");
				}
				
			}
			
			log.info("Metric " + metricQueryDef.getName() +" = " + metricValue + "\n");
			
			if( metricValue.isInfinite() || metricValue.isNaN() ) {
				log.warn("Formula evaluated as NaN or inifinite.");
				if ( metricQueryDef.onErrorDrop() ) {
					log.warn("Metric " + key + " is dropped.");
					continue;
				} else {
					metricValue = metricQueryDef.getErrorValue();
					log.warn("Metric " + key + " set to " + metricValue + ".");
				}
			}
			
			String project = projectProperties.getProperty("project.name");
			String metric = metricQueryDef.getName();
			String name = metricQueryDef.getProperty("name");
			String description = metricQueryDef.getProperty("description");
			String[] level2s = metricQueryDef.getPropertyAsStringArray("level2");
			Double[] weights = metricQueryDef.getPropertyAsDoubleArray("weights");
			String datasource = elasticSource.getElasticsearchIP() + ":9200/" + metricQueryDef.getProperty("index");
		
			String onError = metricQueryDef.getProperty("onError");
			if ( onError == null ) {
				onError = projectErrorStrategy;
			}
		
			Metric m = new Metric(project, metric, evaluationDate, level2s, weights, name, description, datasource, metricValue, info, onError );
			result.add(m);
			
		}
		
		return result;
		
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
	 * Compute relations between (enabled) Metrics and Level 2
	 * @param metrics List of Level 1 Metrics
	 * @return List of Relation
	 */
	private List<Relation> computeMetricRelations( List<Metric> metrics ) {
		
		List<Relation> result = new ArrayList<>();
		
		Map<String, Level2> factorMap = readLevel2Map();
		
		for ( Metric metric : metrics ) {
			for (int i = 0; i < metric.getLevel2s().length; i++ ) {
				
				String factorId = metric.getLevel2s()[i];
				Double weight = metric.getWeights()[i];

				Level2 level2 = factorMap.get(factorId);
				
				if ( level2 == null ) {
					log.info( "Warning: Impact of Metric " + metric.getName() + " on undefined Factor " + level2 + "is not stored."  );
				} else {
					if ( !level2.isEnabled() ) {
						log.info("Factor " + level2.getName() + " is disabled. No relation created.\n");
						continue;
					}
					
					Relation imp = new Relation(metric.getProject(), metric, level2, evaluationDate, metric.getValue() * weight, weight);
					result.add(imp);
				}
			}
		}

		return result;
		
	}

	/**
	 * Read Map of Level 2 (id->level2) from level2.properties file
	 * @return Map of Level 2
	 */
	private Map<String, Level2> readLevel2Map() {
		
		
		Map<String, Level2> result = new HashMap<>();
		
		File factorPropFile = new File( projectFolder.getAbsolutePath() + File.separatorChar + "level2.properties" );
		
		Properties level2Properties = FileUtils.loadProperties(factorPropFile);
		List<String> level2s = getLevel2s( level2Properties );
		
		for ( String f : level2s ) {
			
			Boolean enabled = Boolean.parseBoolean(  level2Properties.getProperty(f + ".enabled") );
			String project = projectProperties.getProperty("project.name");

			String[] level3s = level2Properties.getProperty(f + ".level3").split(",");
			Double[] weights = getAsDoubleArray( level2Properties.getProperty(f + ".weights") );
			
			String name = level2Properties.getProperty(f + ".name");
			String description = level2Properties.getProperty(f + ".description");
			String datasource = null;
			
			Double value = null;
			String info = null;
			
			String onError = level2Properties.getProperty(f + ".onError");
			
			if ( onError == null ) {
				onError = projectErrorStrategy;
			}

			Level2 fact = new Level2(enabled, project, f, evaluationDate, level3s, weights, name, description, datasource, value, info, onError );
			result.put(f, fact);
			
		}
		
		return result;
		
	}
	
	/**
	 * Read Level 3 from level3.properties file
	 * @return
	 */
	private Map<String, Level3> readLevel3Map() {
		
		Map<String, Level3> result = new HashMap<>();
		
		File level3PropFile = new File( projectFolder.getAbsolutePath() + File.separatorChar + "level3.properties" );
		
		Properties level3Properties = FileUtils.loadProperties(level3PropFile);
		List<String> level3s = getLevel2s( level3Properties );
		
		for ( String level3 : level3s ) {
			
			Boolean enabled = Boolean.parseBoolean(  level3Properties.getProperty(level3 + ".enabled") );
			String project = projectProperties.getProperty("project.name");

			String[] parents = level3Properties.getProperty(level3 + ".parents").split(",");
			Double[] weights = getAsDoubleArray( level3Properties.getProperty(level3 + ".weights") );
			
			String name = level3Properties.getProperty(level3 + ".name");
			String description = level3Properties.getProperty(level3 + ".description");
			String datasource = null;
			
			Double value = null;
			String info = null;
			
			String onError = level3Properties.getProperty(level3 + ".onError");
			
			if ( onError == null ) {
				onError = projectErrorStrategy;
			}

			Level3 ind = new Level3(enabled, project, level3, evaluationDate, parents, weights, name, description, datasource, value, info, onError );
			result.put(level3, ind);
			
		}
		
		return result;
		
	}
	
	/**
	 * Read List of Level 2 ids from level2.properties file
	 * @param props
	 * @return
	 */
	private List<String> getLevel2s(Properties props ) {
		
		List<String> result = new ArrayList<String>();
				
		Set<Object> keys = props.keySet();
		
		for ( Object k : keys ) {
			String ks = (String) k;
			if ( ks.endsWith(".name") ) {
				result.add(ks.substring(0, ks.indexOf(".name")));	
			}
		}

		return result;
		
	}

	/**
	 * Evaluate metric formula for given named parameters
	 * @param metric
	 * @param evalParameters
	 * @return
	 */
	private Double evaluate(String metric, Map<String, Object> evalParameters) {
		
		for ( String key : evalParameters.keySet() ) {
			metric = metric.replaceAll( key, evalParameters.get(key).toString() );
		}
		
		return Evaluator.eval(metric);
	}

	/**
	 * Read Map of QueryDefs from directory
	 * @param queryDirectory
	 * @return Map of QueryDefs
	 */
	private Map<String,QueryDef> getQuerySet(File queryDirectory) {
		
		Map<String,QueryDef> querySets = new HashMap<>();
		
		String[] filenames = queryDirectory.list();
		Arrays.sort(filenames);
		
		
		for ( String fname : filenames ) {
			
			String pathName = queryDirectory.getAbsolutePath() + File.separatorChar + fname;
			
			File f = new File(pathName);
			if ( f.isFile() ) {
				String filename = f.getName();
				String[] parts = filename.split("\\.");
				String name = parts[0];
				String type = parts[1];
				
				if ( type.equals("query") ) {
					
					String queryTemplate = FileUtils.readFile(f);
					
					if ( querySets.containsKey(name) ) {
						querySets.get(name).setQueryTemplate( queryTemplate ); 
					} else {
						querySets.put(name,  new QueryDef(name, projectProperties, queryTemplate, null) );
					}
					
				}
				
				if ( type.equals("properties") ) {

					Properties props = FileUtils.loadProperties(f);
						
					if ( querySets.containsKey(name) ) {
						querySets.get(name).setProperties( props );
					} else {
						querySets.put(name,  new QueryDef(name, projectProperties, null, props) );
					}

				}
			}
		}
		
		return querySets;

	}
	
	public QueryDef loadQueryDef( String directory, String name ) {
		
		File templateFile = new File( directory + File.separatorChar + name + ".query");
		String queryTemplate = FileUtils.readFile(templateFile);
		
		File propertyFile = new File( directory + File.separatorChar + name + ".properties");
		Properties props = FileUtils.loadProperties(propertyFile);
		
		return new QueryDef(name, projectProperties, queryTemplate, props);

	}
	
	/**
	 * Return Property values with comma as a Double array:
	 * "1.5,2.3" becomes [1.5,2.3]
	 * 
	 * @param commaSeparated Comma separated string with double values
	 * @return Double-Array
	 */
	public Double[] getAsDoubleArray(String commaSeparated) {
		
		String[] parts = commaSeparated.split(",");
		Double[] doubleArray = new Double[parts.length];
		
		for ( int i=0; i<parts.length; i++ ) {
			doubleArray[i] = Double.parseDouble(parts[i]);
		}
		
		return doubleArray;
		
	}
	
	
	
}
