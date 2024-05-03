# Eval-Service ![](https://img.shields.io/badge/License-Apache2.0-blue.svg)
Eval-Service computes metrics, level2s, and level3s on raw data stored in Elasticsearch. In this context, raw data is produced by a different services (Gitlab, Sonarqube or other services). The Eval-Service aggregates the raw data into metrics, and further on into level2s and level3s, according to a defined quality model.  
This project is forked from [Q-Rapids eval](https://github.com/q-rapids/qrapids-eval). The original authors are mentioned at the bottom of this README.

## Configuration
Eval-Service can be run as a commandline tool and is configured via a set of text files (query- and property-files) that are stored in a special folder structure. The top-folder is named 'projects'. This folder has to be present in the same directory where the eval-service.jar file is stored. Each subfolder defines a quality model for a project to be evaluated.

The folder structure shown below defines the evaluation of one project 'default'.

```
+---projects
    +---default
    |   +---level2s
    |   |     level2.properties
    |   |     level2.query
    |   +---level3s
    |   |     level3.properties
    |   |     level3.query
    |   +---metrics
    |   |     comments.properties
    |   |     comments.query
    |   |     complexity.properties
    |   |     complexity.query
    |   |     duplication.properties
    |   |     duplication.query
    |   +---params
    |   |     01_lastSnapshotDate.properties
    |   |     01_lastSnapshotDate.query
    |   |     02_filesInSnapshot.properties
    |   |     02_filesInSnapshot.query
    |   |
    |   |  level2s.properties
    |   |  level3s.properties
    |   |  project.properties
    |
    |
    |  eval.properties
```
### projects/eval.properties

The *eval.properties* file defines global configuration options. Currently, nothing defined.


### projects/default/project.properties
The project.properties file contains the top-level configuration for a project evaluation. It defines the project.name (which will be appended to the metrics/level2s/level3s/relations index names), the addresses to source and target Elasticsearch servers, the name and other properties of the source indexes(e.g. Sonarqube, Jira), and the names and types of the created (or reused) target indexes (metrics, level2s, level3s, relations). 

**NEW** in this version of Eval-Service is the configurable Error Handling. Error handling takes place when the computation of metrics, level2s, or level3s fails. This can happen because of missing data, errors in formulas (e.g. division by 0) and for other reasons. The onError property allows to set a project-wide default (which can be overwritten for metrics, level2s etc.) how to handle these errors.
+ The 'drop' option just drops the metrics/level2s/level3s item that can't be computed, no record is stored. 
+ The 'set0' option stores a record with value 0.


```properties
# project name
# must be lowercase since it becomes part of the metrics/level2s/level3s/relations index names, mandatory
project.name=default

# Elasticsearch source data, mandatory
elasticsearch.source.ip=localhost

# Elasticsearch target data (metrics, level2s, level3s, relations, ...), mandatory
# Could be same as source
elasticsearch.target.ip=localhost

########################
#### SOURCE INDEXES ####
########################

# sonarqube measures index
sonarqube.measures.index=sonarqube.measures
sonarqube.measures.bcKey=<your-sonarqube-base-component-key>

# sonarqube issues index
sonarqube.issues.index=sonarqube.issues
sonarqube.issues.project=<your-sonarqube-project-key>

########################
#### TARGET INDEXES ####
########################

# rules for index names: lowercase, no special chars (dot "." allowed), no leading numbers, 

# metrics index, mandatory
metrics.index=metrics
metrics.index.type=metrics

# level2s index, mandatory
level2s.index=level2s
level2s.index.type=level2s

# impacts index, mandatory
relations.index=relations
relations.index.type=relations

# level2s index, mandatory
level3s.index=level3s
level3s.index.type=level3s

# global error handling default: 'drop' or 'set0', default is 'drop'.
# Error handling takes place when the computation of a metric/level2/level3/relation fails.
# Strategy 'drop' doesn't store the item, 'set0' sets the item's value to 0.
# The setting can be overwritten for specific metrics, level2s, and level3s
onError=set0
```

Values of the *project.properties* can be used in *params-*  and *metrics* queries. To refer to a project property in a query's property file, prefix the property-name with '$$'. In the example below, the project properties sonarqube.measures.index and sonarqube.measures.bcKey are used in the *01_lastSnapshotDate.properties* in the params folder:

```properties
index=$$sonarqube.measures.index
param.bcKey=$$sonarqube.measures.bcKey
result.lastSnapshotDate=hits.hits[0]._source.snapshotDate
```


### projects/default/params
In the first phase of a project evaluation, Eval-Service executes the queries in the params folder (*params queries*). These do not compute metrics or level2s, but allow for querying arbitrary other values (noted with prefix 'result., which then can be used in subsequent *params* and *metrics* queries as parameters. The results of params queries can be used in subsequent params and metrics queries without declaration in the associated property-files (unlike values of project.properties, where declaration is necessary)

The *params* queries are executed in sequence (alphabetical order). For this reason, it is a good practice to follow the suggested naming scheme for parameter queries and start the name of with a sequence of numbers (e.g. 01_query_name, 02_other_name). Since params queries build on each other, a proper ordering is necessary.


A query consists of a pair of files:
* A .properties file, that declares the index the query should run on, as well as parameters and results of the query
* A .query file that contains the actual query in Elasticsearch syntax (see [Elasticsearch DSL](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html))

__Example (01_lastShnapshotDate)__

01_lastSnapshotDate.properties

```properties
index=$$sonarqube.measures.index
param.bcKey=$$sonarqube.measures.bcKey
result.lastSnapshotDate=hits.hits[0]._source.snapshotDate
```
+ The index property is read from the project.properties files ($$-notation).
+ The query uses one parameter (bcKey), which is also read from the project properties file. Parameters of a query are declared with prefix 'param.' 
+ The query defines one result (lastSnapshotDate), that is specified as a path within the query result delivered by elasticsearch. Results are declared with prefix 'result.'
All results computed by params queries can be used as parameters (without declaration) in subsequent params- and metrics queries. Make sure that the names of the results of params queries are unique, otherwise they will get overwritten.

__Query Parameters__

Eval-Service internally uses [Elasticsearch search templates](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-template.html) to perform *params, metrics*, and other queries. Search templates can receive parameters (noted with double curly braces: {{parameter}} ). The parameters are replaced by actual values, before the query is executed. The replacement is done verbatim and doesn't care about data types. Thus, if you want a string parameter, you'll have to add quotes around the parameter yourself (as seen below with the evaluationDate parameter).
+ The evaluationDate is available to all *params* and *metrics* queries without declaration. Eval-Service started without command-line options sets the evaluationDate to the date of today (string, format yyyy-mm-dd).
+ Elements of the *project.properties* can be declared as a parameter with the $$-notation, as seen above (param.bcKey)
+ Literals (numbers and strings) can be used after declaration as parameters (e.g by *param.myThreshold=15*)
+ Results (noted with prefix 'result.') of *params queries* can be used as parameters in succeeding *params* and *metrics* queries without declaration.

01_lastSnapshotDate.query

```
{
	"query": {
		"bool": {
			"must" : [
				{ "term" : { "bcKey" : "{{bcKey}}" } },
				{ "range" : { "snapshotDate" : { "lte" : "{{evaluationDate}}", "format": "yyyy-MM-dd" } } }
      		]
		}
	},
	"size": 1,
	"sort": [
    	{ "snapshotDate": { "order": "desc" 	} }
	]
}
```

The lastSnapshotDate query is a [bool query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-bool-query.html). It defines two conditions that have to evaluate to TRUE for matching documents:
+ The documents must have the supplied parameter {{bcKey}} as value of the field bcKey (match only records of the specified project)
+ The value of the field snapshotDate must be lower or equal to the evaluationDate. The {{evaluationDate}} parameter is available to all queries without declaration and typically contains the date of today in format yyyy-MM-dd. The evaluationDate can be supplied via command-line (see command-line-options).

The query limits the size of the result to one and sorts in descending order.

Example query result:

```json
{
  "took" : 31,
  "timed_out" : false,
  "_shards" : {...} ,
  "hits" : {
    "total" : 144491,
    "max_score" : null,
    "hits" : [
      {
        "_index" : "sonarqube.measures",
        "_type" : "sonarqube",
        "_id" : "sonarqube.measures+0+149155",
        "_score" : null,
        "_source" : {
          ...
          "snapshotDate" : "2018-12-04",
          "bcKey" : "ptsw_official",
          ...
        },
        "sort" : [
          1543881600000
        ]
      }
    ]
  }
}
```

The result of the query is specified as path in the returned json: __"hits" -> "hits" [0] -> "_source" -> "snapshotDate" = "2018-12-04"__

### projects/default/metrics
The folder contains the metrics definitions of a project. As *params queries*, *metrics queries* consist of a pair of files, a .properties and a .query file. In addition to params queries, metrics queries compute a metric value defined by a formula. The computed metric value is stored in the metrics index (defined in project.properties) after query execution.

Computed metrics get aggregated into level2s. Therefore you have to specify the level2s, a metric is going to influence. Metrics can influence one or more level2s, that are supplied as a comma-separated list of level2-ids together with the weight describing the strength of the influence. In the example below, the metric 'complexity' influences two level2s (codequality and other) with weights 2.0 for codequality and 1.0 for other. The value of a level2 is then computed as a weighted sum of all metrics influencing a level2.

__Example: complexity query__

complexity.properties

```properties
# index the query runs on, mandatory
# values starting with $$ are looked up in project.properties
index=$$sonarqube.measures.index

# metric props, mandatory
enabled=true
name=Complexity
description=Percentage of files that do not exceed a defined average complexity per function
level2s=codequality,other
weights=2.0,1.0

# query parameter
param.bcKey=$$sonarqube.measures.bcKey
param.avgcplx.threshold=15

# query results (can be used in metric calculation)
result.complexity.good=aggregations.goodBad.buckets[0].doc_count
result.complexity.bad=aggregations.goodBad.buckets[1].doc_count

# metric defines a formula based on execution results of params- and metrics-queries
metric=complexity.good / ( complexity.good + complexity.bad )
onError=set0
```

__Note:__ The onError property can be set to 'drop' or 'set0' and overwrites to setting in project.properties.


complextiy.query

```
{ 
  "size" : 0,
  "query": {
    "bool": {
      "must" : [
			{ "term" : { "bcKey" : "{{bcKey}}" } },
			{ "term" : { "snapshotDate" : "{{lastSnapshotDate}}" } },
			{ "term" : { "metric" : "function_complexity"} },
			{ "term" : { "qualifier" : "FIL" } }
      ]
    }
  },
  "aggs": {
    "goodBad" : {
      "range" : {
        "field" : "floatvalue",
        "ranges" : [
          { "to" : {{avgcplx.threshold}} }, 
          { "from" : {{avgcplx.threshold}} }
        ]
      }
    }
  }
}
```

The complexity query is based on a [bool query](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-bool-query.html) and uses a [bucket range aggregation](https://www.elastic.co/guide/en/elasticsearch/reference/current/search-aggregations-bucket-range-aggregation.html) to derive its results.
The query considers documents/records that fulfill the following conditions:
+ Only documents with a specific {{bcKey}} (only files of this project)
+ Only documents with a specific {{snapshotDate}} (parameter derived in *params query* 01_snapshotDate)
+ Only documents for metric "function_complexity"
+ Only documents with qualifier "FIL" (analyze only files, not folders etc.)

In the bucket range aggregation, the matching documents are divided into two buckets: 
+ Files with function_complexity < avgcplx.threshold
+ Files with function_complexity >= avgcplx.threshold

__Example result__

```
{
  "took" : 22,
  "timed_out" : false,
  "_shards" : { ... },
  "hits" : {
    "total" : 53,
    ...
  },
  "aggregations" : {
    "goodBad" : {
      "buckets" : [
        { "key" : "*-15.0", "to" : 15.0, "doc_count" : 53 },
        { "key" : "15.0-*", "from" : 15.0, "doc_count" : 0 }
      ]
    }
  }
}
```


The metric (percentage of files having tolerable complexity) is then computed as: 

```
metric=complexity.good / ( complexity.good + complexity.bad ) = 53 / ( 53 + 0 ) = 100%
```

### projects/default/level2s.properties
The level2s.properties file defines level2s to compute along with their properties. Level2 don't do sophisticated computations, they serve as a point for the aggregation of metric values. Level2s are then aggregated into level3s, so they have to specify the level3s they are influencing along with the weights of the influence. The notation used has to be read as *level2id.property=value* . 


+ The *enabled* attribute enables/disables a level2 (no records written for a level2 when disabled)
+ The *name* property supplies a user-friendly name of a level2 
+ The *decription* attribute describes the intention of the level2
+ The *level3s* attribute contains a list of influenced level3s (which are defined in a separate properties file).
+ The *weights* attribute sets the strength of the influence. Obviously, the lists in 'level3s' and 'weights' have to have the same length!
+ The *onError* attribute tells Eval-Service what to do in case of level2 computation errors (e.g. no metrics influence a level2, which results in a division by zero)

Example level2 definition (codequality):

```properties
codequality.enabled=true
codequality.name=Code Quality
codequality.description=It measures the impact of code changes in source code quality. Specifically, ...
codequality.level3s=productquality
codequality.weights=1.0
codequality.onError=set0
```

__Note:__ The onError property can be set to 'drop' or 'set0' and overwrites to setting in project.properties.

### projects/default/level3s.properties
The level3s.properties file defines the level3s for a project. The parents- and weights-attribute currently have no effect, but could define an additional level of aggregation in future. 

```properties
productquality.enabled=true
productquality.name=Product Quality
productquality.description=Quality of the Product
productquality.parents=meta
productquality.weights=1.0
```

### projects/default/level2s
Defines the query for aggregation of metrics into level2s, based on relations index. DON'T TOUCH, unless you know what you are doing.

### projects/default/level3s
Defines the query for aggregation of level2s into level3s, based on relations index. DON'T TOUCH, unless you know what you are doing.



## Running Eval-Service

### Prerequisites
* Elasticsearch source and target servers are running and contain appropriate data
* Java 1.8 is installed
* A projects folder exists in the directory of eval-service<version>.jar and contains a proper quality model configuration

### Run without commandline parameters
The date of the current day (format yyyy-MM-dd) will be available as parameter 'evaluationDate' in params- and metrics-queries

```
java -jar eval-service-<version>-jar-with-dependencies.jar
```

### Specify a single evaluation date
The specified evaluationDate will be available as parameter 'evaluationDate' in params- and metrics-queries.

```
java -jar eval-service-<version>-jar-with-dependencies.jar evaluationDate 2019-03-01
```

### Specify a date range for evaluation
The defined projects will be evaluated for each day in the specified range.

```
java -jar eval-service-<version>-jar-with-dependencies.jar from 2019-03-01 to 2019-03-30
```

### Build the connector
```
mvn package assembly:single
```
After build, you'll find the generated jar in the target folder

## Model validation
Before the evaluation of a project starts, Eval-Service performs a basic evaluation of the qualtity model. A warning is logged in the following cases:
+ A metrics-query mentions a level2 in the level2s-property, but the level2 isn't defined in the level2s.properties file.
+ A level2 mentioned in a metric is not enabled
+ A level2 is defined in level2s.properties, but not mentioned in any metrics-query
+ An level3 is mentioned in the level3s-property of a defined level2, but is not defined in the level3s.properties file
+ An level3 is mentioned in the level3s-property of a defined level2, but is not enabled
+ An level3 is defined in level3s.properties, but it is not mentioned in any level3s-property of the defined level2s


## Built With

* [Maven](https://maven.apache.org/) - Dependency Management


## Authors

* **Axel Wickenkamp, Fraunhofer IESE, Huy Duong**

