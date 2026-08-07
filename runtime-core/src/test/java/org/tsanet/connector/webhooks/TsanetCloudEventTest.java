package org.tsanet.connector.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Parsing probes for the v2 CloudEvents intake (docs/decisions.md D13). */
class TsanetCloudEventTest {

    private static Map<String, Object> cloudEvent(String type, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("specversion", "1.0");
        body.put("id", "f47ac10b-58cc-4372-a567-0e02b2c3d479");
        body.put("source", "https://connect2.tsanet.net");
        body.put("type", type);
        body.put("time", "2026-08-07T10:30:00.000Z");
        body.put("data", Map.of("requestToken", token));
        return body;
    }

    @Test
    void parsesEveryTypeTheAsyncSpecDefines() {
        // If Connect adds a type we do not know, it must not parse as some other type.
        for (TsanetCloudEvent.Type type : TsanetCloudEvent.Type.values()) {
            TsanetCloudEvent event = TsanetCloudEvent.parse(cloudEvent(type.wireValue(), "tok_abc"))
                    .orElseThrow(() -> new AssertionError("did not parse " + type.wireValue()));
            assertEquals(type, event.type());
            assertEquals("tok_abc", event.requestToken());
            assertEquals("f47ac10b-58cc-4372-a567-0e02b2c3d479", event.id());
        }
    }

    @Test
    void aV1DeliveryIsRefusedRatherThanQueuedWithNullFields() {
        // The regression this intake exists to fix. The previous parser read v1 field
        // names, so a v2 body produced kind=null token=null and was queued anyway: an
        // event no handler could ever claim, reporting nothing. The mirror case is
        // this one — a v1 body must be refused loudly, not half-read, because it means
        // the push registration was made on the wrong version.
        Map<String, Object> v1 = Map.of(
                "eventType", "collaboration-request.created",
                "requestToken", "tok_abc",
                "timestamp", "2026-08-07T10:30:00.000Z");
        assertTrue(TsanetCloudEvent.parse(v1).isEmpty());
    }

    @Test
    void unknownTypeOrMissingTokenIsRefused() {
        assertTrue(TsanetCloudEvent.parse(
                cloudEvent("org.tsanet.connect.collaboration-request.invented", "tok_abc")).isEmpty());
        assertTrue(TsanetCloudEvent.parse(
                cloudEvent(TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), "  ")).isEmpty());

        Map<String, Object> noData = new LinkedHashMap<>(
                cloudEvent(TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), "tok_abc"));
        noData.remove("data");
        assertTrue(TsanetCloudEvent.parse(noData).isEmpty());
        assertTrue(TsanetCloudEvent.parse(null).isEmpty());
    }

    @Test
    void aMissingCloudEventIdStillRoutes() {
        // id is required by the spec, but the token is what creation keys on, so a
        // delivery without one is routable rather than dropped.
        Map<String, Object> body = new LinkedHashMap<>(
                cloudEvent(TsanetCloudEvent.Type.REQUEST_CREATED.wireValue(), "tok_abc"));
        body.remove("id");
        Optional<TsanetCloudEvent> parsed = TsanetCloudEvent.parse(body);
        assertTrue(parsed.isPresent());
        assertNull(parsed.get().id());
        assertEquals("tok_abc", parsed.get().requestToken());
    }
}
