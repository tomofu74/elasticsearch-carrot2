package org.carrot2.elasticsearch;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.carrot2.core.LanguageCode;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.collect.Sets;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.RestRequest.Method;
import org.fest.assertions.api.Assertions;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;

/**
 * 
 */
public class RestApiTests extends AbstractApiTest {
    @DataProvider(name = "postJsonResources")
    public static Object[][] postJsonResources() {
        return new Object[][] {
                {"post_with_fields.json"},
                {"post_with_source_fields.json"},
                {"post_with_highlighted_fields.json"},
                {"post_multiple_field_mapping.json"},
                {"post_cluster_by_url.json"}
        };
    }

    @DataProvider(name = "postOrGet")
    public static Object[][] postOrGet() {
        return new Object[][] {{Method.POST}, {Method.GET}};
    }

    @SuppressWarnings("unchecked")
    @Test(dataProvider = "postOrGet")
    public void testListAlgorithms(Method method) throws IOException {
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        try {
            HttpRequestBase request;
            String requestString = restBaseUrl + "/" 
                    + ListAlgorithmsAction.RestListAlgorithmsAction.NAME + "?pretty=true";

            switch (method) {
                case POST:
                    request = new HttpPost(requestString);
                    break;

                case GET:
                    request = new HttpGet(requestString);
                    break;

                default: throw Preconditions.unreachable();
            }
            
            HttpResponse response = httpClient.execute(request);
            Map<?,?> map = checkHttpResponse(response);

            // Check that we do have some algorithms.
            Assertions.assertThat(map.get("algorithms"))
                .describedAs("A list of algorithms")
                .isInstanceOf(List.class);

            Assertions.assertThat((List<String>) map.get("algorithms"))
                .describedAs("A list of algorithms")
                .contains("stc", "lingo", "kmeans");            
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }    

    @Test(dataProvider = "postJsonResources")
    public void testRestApiViaPostBody(String queryJsonResource) throws Exception {
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        try {
            String requestJson = resourceToString(queryJsonResource);

            HttpPost post = new HttpPost(restBaseUrl + "/" + RestClusteringAction.NAME + "?pretty=true");
            post.setEntity(new StringEntity(requestJson, Charsets.UTF_8));
            HttpResponse response = httpClient.execute(post);
            
            Map<?,?> map = checkHttpResponseContainsClusters(response);

            List<?> clusterList = (List<?>) map.get("clusters");
            Assertions.assertThat(clusterList)
                .isNotNull()
                .isNotEmpty();

            Assertions.assertThat(clusterList.size())
                .isGreaterThan(5);
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testRestApiPathParams() throws Exception {
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        try {
            String requestJson = resourceToString("post_with_fields.json");

            HttpPost post = new HttpPost(restBaseUrl 
                    + "/" + INDEX_NAME 
                    + "/empty/" 
                    + RestClusteringAction.NAME + "?pretty=true");
            post.setEntity(new StringEntity(requestJson, Charsets.UTF_8));
            HttpResponse response = httpClient.execute(post);
            Map<?,?> map = checkHttpResponseContainsClusters(response);

            List<?> clusterList = (List<?>) map.get("clusters");
            Assertions.assertThat(clusterList)
                .isNotNull()
                .isEmpty();
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testRestApiRuntimeAttributes() throws Exception {
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        try {
            String requestJson = resourceToString("post_runtime_attributes.json");

            HttpPost post = new HttpPost(restBaseUrl + "/" + RestClusteringAction.NAME + "?pretty=true");
            post.setEntity(new StringEntity(requestJson, Charsets.UTF_8));
            HttpResponse response = httpClient.execute(post);
            Map<?,?> map = checkHttpResponseContainsClusters(response);

            List<?> clusterList = (List<?>) map.get("clusters");
            Assertions.assertThat(clusterList)
                .isNotNull();
            Assertions.assertThat(clusterList)
                .hasSize(/* max. cluster size cap */ 5 + /* other topics */ 1);
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testLanguageField() throws IOException {
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        try {
            String requestJson = resourceToString("post_language_field.json");

            HttpPost post = new HttpPost(restBaseUrl + "/" + RestClusteringAction.NAME + "?pretty=true");
            post.setEntity(new StringEntity(requestJson, Charsets.UTF_8));
            HttpResponse response = httpClient.execute(post);
            Map<?,?> map = checkHttpResponseContainsClusters(response);

            // Check top level clusters labels.
            Set<String> allLanguages = Sets.newHashSet();
            for (LanguageCode code : LanguageCode.values()) {
                allLanguages.add(code.toString());
            }

            List<?> clusterList = (List<?>) map.get("clusters");
            for (Object o : clusterList) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cluster = (Map<String, Object>) o; 
                allLanguages.remove(cluster.get("label"));
            }
            
            Assertions.assertThat(allLanguages.size())
                .describedAs("Expected a lot of languages to appear in top groups.")
                .isLessThan(LanguageCode.values().length / 2);            
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }    
    
    protected static Map<String, Object> checkHttpResponseContainsClusters(HttpResponse response) throws IOException {
        Map<String, Object> map = checkHttpResponse(response);

        // We should have some clusters.
        Assertions.assertThat(map).containsKey("clusters");

        return map;
    }

    private static Map<String, Object> checkHttpResponse(HttpResponse response) throws IOException {
        String responseString = new String(
                ByteStreams.toByteArray(response.getEntity().getContent()), 
                Charsets.UTF_8); 
    
        String responseDescription = 
                "HTTP response status: " + response.getStatusLine().toString() + ", " + 
                "HTTP body: " + responseString;
    
        Assertions.assertThat(response.getStatusLine().getStatusCode())
            .describedAs(responseDescription)
            .isEqualTo(HttpStatus.SC_OK);
    
        XContentParser parser = JsonXContent.jsonXContent.createParser(responseString);
        Map<String, Object> map = parser.mapAndClose();
        Assertions.assertThat(map)
            .describedAs(responseDescription)
            .doesNotContainKey("error");

        return map; 
    }

    protected String resourceToString(String resourceName) throws IOException {
        return Resources.toString(
                Resources.getResource(this.getClass(), resourceName),
                Charsets.UTF_8);
    }    
}
