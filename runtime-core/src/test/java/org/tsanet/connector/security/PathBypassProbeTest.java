package org.tsanet.connector.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Bypass probes against a REAL container: MockMvc does not reproduce Tomcat's
 * decode-before-mapping, so an encoded-path bypass would pass MockMvc while failing
 * live. Default-deny means every spelling that is not the literal open route must 401
 * (docs/decisions.md D1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class PathBypassProbeTest {

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @org.junit.jupiter.api.BeforeEach
    void useModernHttpClient() {
        // The legacy HttpURLConnection factory cannot handle a 401 answer to a streamed
        // POST ("cannot retry due to server authentication"); the JDK HttpClient factory
        // can, and it sends the URI path exactly as spelled.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    private ResponseEntity<String> postRaw(String pathSpelling) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // URI constructed from the already-encoded string so the client does not
        // re-encode and the wire path is exactly the probe spelling.
        URI uri = new URI("http://127.0.0.1:" + port + pathSpelling);
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }

    @Test
    void encodedPathSpellingCannotReachCanvasUnsigned() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, postRaw("/%63anvas/initialize").getStatusCode());
    }

    @Test
    void semicolonPathParamsCannotReachCanvasUnsigned() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, postRaw("/canvas;x=y/submit").getStatusCode());
    }

    @Test
    void unmatchedPathsAreDeniedByDefault() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, postRaw("/definitely-not-a-route").getStatusCode());
    }

    @Test
    void healthzStaysOpenOnTheRealContainer() throws Exception {
        URI uri = new URI("http://127.0.0.1:" + port + "/healthz");
        assertEquals(HttpStatus.OK, rest.exchange(uri, HttpMethod.GET, HttpEntity.EMPTY, String.class).getStatusCode());
    }
}
