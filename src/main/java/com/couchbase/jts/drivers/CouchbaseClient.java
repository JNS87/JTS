package com.couchbase.jts.drivers;

// Imports of general utilities / java library

import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.lang.System.* ;
import java.util.concurrent.TimeUnit;

// Imports for Collections
import com.couchbase.client.java.Collection;

// Imports of JTS loggers and other utils

import com.couchbase.jts.properties.TestProperties;
import com.couchbase.jts.logger.GlobalStatusLogger;

// Imports of Cluster settings / managements

import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.core.env.IoConfig;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.core.env.TimeoutConfig;
import com.couchbase.client.java.json.JsonObject;

// Imports of the other dependent services
import com.couchbase.client.java.kv.*;
import com.couchbase.client.java.json.*;
import com.couchbase.client.java.query.*;
// Search related imports
import com.couchbase.client.java.search.SearchQuery;
import com.couchbase.client.java.search.result.SearchResult;
import com.couchbase.client.java.search.result.SearchMetrics;
import com.couchbase.client.java.search.result.SearchRow;
import com.couchbase.client.java.search.SearchOptions;
import com.couchbase.client.java.search.queries.TermQuery;




//import com.couchbase.client.core.metrics.LatencyMetricsCollectorConfig;



public class  CouchbaseClient extends Client{
	private GlobalStatusLogger logWriter = new GlobalStatusLogger();
	// private static volatile CouchbaseEnvironment env = null;
	private static volatile ClusterEnvironment env =  null ;
	private static final Object INIT_COORDINATOR = new Object();

	private int networkMetricsInterval = 0;
	private int runtimeMetricsInterval = 0;
	private int queryEndpoints = 1;
	private int kvEndpoints = 1;
	private int boost = 3;
	private boolean epoll = false;
	private int kvTimeout = 10000;
	private int connectTimeout = 100000;
	private int socketTimeout = 100000;
	private boolean enableMutationToken = false;
	private volatile Collection collection;

	// An indicator to indicate the number of collections present for the test
	// -1 => no collections
	// 0 Default collection
	// >1 N collections
	private int collectionIndicator;

	// Setting the searchQuery Options
	int limit = Integer.parseInt(settings.get(TestProperties.TESTSPEC_QUERY_LIMIT));
	String indexName = settings.get(TestProperties.CBSPEC_INDEX_NAME);


	private Cluster cluster;
	private volatile ClusterOptions clusterOptions;
	private Bucket bucket;
	private SearchQuery[] queries;

	private Random rand = new Random();
	private int totalQueries = 0;
	private SearchQuery queryToRun;

	public CouchbaseClient(TestProperties workload) throws Exception{
        super(workload);
				connect();
				generateQueries();
    }


		private void connect() throws Exception{

		try {

			synchronized (INIT_COORDINATOR) {
				if(env == null) {
					// This creates a new ClusterEnvironment with Default Settings
					env = ClusterEnvironment
							.builder()
							.timeoutConfig(TimeoutConfig.kvTimeout(Duration.ofMillis(kvTimeout)))
							.ioConfig(IoConfig.enableMutationTokens(enableMutationToken).numKvConnections(kvEndpoints))
				      .build();
				}
			}
			clusterOptions = ClusterOptions.clusterOptions(getProp(TestProperties.CBSPEC_USER),getProp(TestProperties.CBSPEC_PASSWORD));
			clusterOptions.environment(env);

			cluster = Cluster.connect(getProp(TestProperties.CBSPEC_SERVER),clusterOptions);
			bucket = cluster.bucket(getProp(TestProperties.CBSPEC_CBBUCKET));

			collectionIndicator = Integer.parseInt(settings.get(TestProperties.TESTSPEC_COLLECTIONS));

			//adding in logic for collection enabling for CC
			if(collectionIndicator == -1 || collectionIndicator == 0 ) {
				// In CC the data is present in the default collection for even the bucket level tests
				// This is default collections on the KV side
				collection = bucket.defaultCollection();
			}else {
				// Adding the code for a single non-default scope and non-default collection
				collection = bucket.scope("scope1").collection("collection1");
			}
			 
		}catch(Exception ex) {
            throw new Exception("Could not connect to Couchbase Bucket.", ex);
        }
	}

		private void generateQueries() throws Exception {
	String[][] terms = importTerms();
	List <SearchQuery> queryList = null;

			String fieldName = settings.get(TestProperties.TESTSPEC_QUERY_FIELD);
	queryList = generateTermQueries(terms,fieldName);
	if((queryList ==null) || (queryList.size()==0)) {
		throw new Exception("Query list is empty! ");
	}
	queries = queryList.stream().toArray(SearchQuery[]::new);
	totalQueries = queries.length;

}

private List<SearchQuery> generateTermQueries(String[][] terms,  String fieldName)
	throws IllegalArgumentException{
	List<SearchQuery> queryList = new ArrayList<>();
	int size = terms.length;

	for (int i = 0; i<size; i++) {
		int lineSize = terms[i].length;
		if(lineSize > 0) {
			try {
				SearchQuery query = buildQuery(terms[i], fieldName);
				queryList.add(query);
			}catch(IndexOutOfBoundsException ex) {
				continue;
			}
		}
	}
	return queryList ;

}

//Query builders
private SearchQuery buildQuery(String[] terms, String fieldName)
	throws IllegalArgumentException, IndexOutOfBoundsException {

	switch (settings.get(settings.TESTSPEC_QUERY_TYPE)) {
			case TestProperties.CONSTANT_QUERY_TYPE_TERM:
				return buildTermQuery(terms,fieldName);
	}
	throw new IllegalArgumentException("Couchbase query builder: unexpected query type - "
									+ settings.get(settings.TESTSPEC_QUERY_TYPE));
}

private SearchQuery buildTermQuery(String[] terms, String fieldName) {
	return SearchQuery.term(terms[0]).field(fieldName);
}

public float queryAndLatency() {
	long st = System.nanoTime();
	queryToRun = queries[rand.nextInt(totalQueries)];
	SearchOptions opt = SearchOptions.searchOptions().limit(limit);
	SearchResult res = cluster.searchQuery(indexName,queryToRun,opt);
	logWriter.logMessage(res.toString());
	long en = System.nanoTime();
	float latency = (float) (en - st) / 1000000;
	int res_size = res.rows().size();
	SearchMetrics metrics = res.metaData().metrics();
	if (res_size > 0 && metrics.maxScore()!= 0 && metrics.totalRows()!= 0){ return latency;}
	return 0;
}


public void mutateRandomDoc() {
	long totalDocs = Long.parseLong(settings.get(TestProperties.TESTSPEC_TOTAL_DOCS));
	long docIdLong = Math.abs(rand.nextLong() % totalDocs);
	String docIdHex = Long.toHexString(docIdLong);
	String originFieldName = settings.get(TestProperties.TESTSPEC_QUERY_FIELD);
	String replaceFieldName = settings.get(TestProperties.TESTSPEC_MUTATION_FIELD);

	// Getting the document content
	GetResult doc = collection.get(docIdHex);
	// converting that to a JSON object
	JsonObject mutate_doc = doc.contentAsObject();
	// To get the values we are changing
	Object origin = doc.contentAsObject().getString(originFieldName);
	Object replace = doc.contentAsObject().getString(replaceFieldName);
	// mutating this by interchanging
	mutate_doc.put(originFieldName, replace);
	mutate_doc.put(replaceFieldName, origin);
	// pushing the document
	collection.upsert(docIdHex, mutate_doc);


}

public String queryDebug() {
	return cluster.searchQuery(indexName,queries[rand.nextInt(totalQueries)],SearchOptions.searchOptions().limit(limit)).toString();
}

public void query() {
	cluster.searchQuery(indexName,SearchQuery.term("a").field("text"),SearchOptions.searchOptions().limit(limit)).toString();
}

public Boolean queryAndSuccess() {
		cluster.searchQuery(indexName,queries[rand.nextInt(totalQueries)],SearchOptions.searchOptions().limit(limit));
		return true;
}


 private String getProp(String name) {
				return settings.get(name);
		}
 private void fileError(String err) {
				System.out.println(err);
		}


}
