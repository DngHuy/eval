package eval2;

import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Map;

public class IndexManager {

    private final Logger log = Logger.getLogger(IndexManager.class);

    private final Elasticsearch es;

    /**
     * Create a IndexManager for an Elasticsearch instance
     *
     * @param es
     */
    public IndexManager(Elasticsearch es) {
        this.es = es;
    }


    /**
     * Create an Index using a Schema stored in the resource folder
     *
     * @param indexName   name of index to be created
     * @param schemaPath  Pathname of schema file
     * @param mappingType Mapping type
     */
    public void createIndex(String indexName, String schemaPath, String mappingType) throws IOException {

        String schema = loadSchema(schemaPath);

        es.getEsClient().indices().create(cir -> cir.index(indexName));
        PutMappingRequest.Builder putMappingRequestBuilder = new PutMappingRequest.Builder();
        putMappingRequestBuilder.index(indexName);
        putMappingRequestBuilder.withJson(new StringReader(schema));
        PutMappingRequest putMappingRequest = putMappingRequestBuilder.build();
        es.getEsClient().indices().putMapping(putMappingRequest);
    }


    /**
     * Load a schema file form the resource folder
     *
     * @param name
     * @return
     */
    private static String loadSchema(String name) {
        // java.net.URL
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
        InputStream is = classloader.getResourceAsStream(name);

        java.util.Scanner scanner = new java.util.Scanner(is);
        scanner.useDelimiter("\\A");
        String result = scanner.hasNext() ? scanner.next() : "";

        try {

            scanner.close();
            is.close();

        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

}

