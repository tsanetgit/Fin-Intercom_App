package org.tsanet.connector.canvas;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "connector.intercom-client-secret=test-intercom-secret",
        "connector.tsanet-webhook-secret=test-tsanet-secret",
})
class CanvasControllerTest {

    @Autowired
    private MockMvc mvc;

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-intercom-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(body)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void initializeRendersHomeWithSearchAndFlowPointer() throws Exception {
        byte[] body = "{\"workspace_id\":\"w1\",\"conversation\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(body)).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components[0].text").value("TSANet Connect"))
                .andExpect(jsonPath("$.canvas.content.components[?(@.id=='q')]").exists())
                .andExpect(jsonPath("$.canvas.content.components[?(@.id=='search')]").exists())
                .andExpect(jsonPath("$.canvas.stored_data.flow_id").isNotEmpty());
    }

    @Test
    void detectsTicketSurfaceOnTheHomeCanvas() throws Exception {
        byte[] ticket = "{\"workspace_id\":\"w1\",\"ticket\":{\"id\":9}}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(ticket)).content(ticket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components[1].text").value(containsString("from this ticket")));

        byte[] conversation = "{\"workspace_id\":\"w1\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/initialize").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(conversation)).content(conversation))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.canvas.content.components[1].text").value(containsString("from this conversation")));
    }

    @Test
    void expiredFlowRestartsAtHomeWithNotice() throws Exception {
        byte[] body = ("{\"workspace_id\":\"w1\",\"component_id\":\"search\","
                + "\"current_canvas\":{\"stored_data\":{\"flow_id\":\"no-such-flow\"}},"
                + "\"input_values\":{\"q\":\"netapp\"}}").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/canvas/submit").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Body-Signature", sign(body)).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canvas.content.components[1].text").value(containsString("expired")));
    }

    // ---- htmlNoteParagraphs: partner-authored HTML -> canvas markdown ----
    // Canvas text renders only [text](url) links and **bold**, never HTML; these pin
    // the conversion AND the injection defenses (ordering is load-bearing, see the
    // converter's javadoc).

    @Test
    void plainTextNoteIsReturnedUntouched() {
        assertEquals(List.of("Call the duty manager for P1s."),
                CanvasController.htmlNoteParagraphs("Call the duty manager for P1s."));
    }

    @Test
    void nullAndEffectivelyEmptyNotesEmitNothing() {
        assertEquals(List.of(), CanvasController.htmlNoteParagraphs(null));
        assertEquals(List.of(), CanvasController.htmlNoteParagraphs("   "));
        // Raw-non-blank but empty after conversion: must not render a bare label.
        assertEquals(List.of(), CanvasController.htmlNoteParagraphs("<p>&nbsp;</p>"));
    }

    @Test
    void realWorldNoteConvertsToParagraphsWithMarkdownLinks() {
        // Shape observed live on BETA (Microsoft | Standard, 2026-08-05).
        String html = "<p><strong>How to find Tenant ID:&nbsp;&nbsp;</strong>View the instructions."
                + "&nbsp; <a href=\"https://docs.microsoft.com/en-us/azure/active-directory\">"
                + "Find the Tenant ID in Azure</a>.</p>"
                + "<p>Send an email to <a href='mailto:escalate@example.com'>escalate@example.com</a></p>";
        List<String> out = CanvasController.htmlNoteParagraphs(html);
        assertEquals(2, out.size());
        assertEquals("**How to find Tenant ID:** View the instructions."
                + " [Find the Tenant ID in Azure](https://docs.microsoft.com/en-us/azure/active-directory)"
                + " (docs.microsoft.com).",
                out.get(0));
        assertEquals("Send an email to [escalate@example.com](mailto:escalate@example.com)", out.get(1));
    }

    @Test
    void literalBracketsAreNeutralizedSoTheyCannotFormLinks() {
        assertEquals(List.of("(P1) page the (on-call)"),
                CanvasController.htmlNoteParagraphs("[P1] page the [on-call]"));
        // A partner-typed markdown link must not survive as live link syntax.
        assertEquals(List.of("(evil)(https://evil.example)"),
                CanvasController.htmlNoteParagraphs("[evil](https://evil.example)"));
    }

    @Test
    void entityEncodedBracketsAreDecodedBeforeNeutralization() {
        // &#91;x&#93;(url) decodes to [x](url); entity decode must run BEFORE bracket
        // neutralization or this smuggles a live link past the scheme allowlist.
        assertEquals(List.of("(evil)(https://evil.example)"),
                CanvasController.htmlNoteParagraphs("&#91;evil&#93;(https://evil.example)"));
    }

    @Test
    void nonWebSchemesKeepTheWordsAndDropTheTarget() {
        assertEquals(List.of("click me"),
                CanvasController.htmlNoteParagraphs("<a href=\"javascript:alert(1)\">click me</a>"));
        // Entity-obfuscated scheme fails closed against the allowlist.
        assertEquals(List.of("click me"),
                CanvasController.htmlNoteParagraphs("<a href=\"&#106;avascript:alert(1)\">click me</a>"));
    }

    @Test
    void hrefEntitiesDecodeAndLinkBreakingCharactersAreEncoded() {
        assertEquals(List.of("[docs](https://x.example/?a=1&b=2) (x.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://x.example/?a=1&amp;b=2\">docs</a>"));
        assertEquals(List.of("[docs](https://x.example/%28v2%29%20final) (x.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://x.example/(v2) final\">docs</a>"));
    }

    @Test
    void nestedTagsInsideAnchorTextAreFlattened() {
        assertEquals(List.of("[Tenant ID](https://x.example/) (x.example)"),
                CanvasController.htmlNoteParagraphs(
                        "<a href=\"https://x.example/\"><strong>Tenant ID</strong></a>"));
    }

    @Test
    void boldConvertsAndUnclosedBoldDoesNotCrash() {
        assertEquals(List.of("**Important** detail"),
                CanvasController.htmlNoteParagraphs("<strong>Important</strong> detail"));
        assertEquals(List.of("**Important detail"),
                CanvasController.htmlNoteParagraphs("<b>Important detail"));
    }

    @Test
    void whitespaceHuggingBoldDelimitersMovesOutsideThePair() {
        // A strict markdown parser will not close ** across a space; &nbsp; inside
        // <strong> is exactly how the live Microsoft note is authored.
        assertEquals(List.of("**Heading:** body"),
                CanvasController.htmlNoteParagraphs("<strong>Heading:&nbsp;</strong>body"));
        assertEquals(List.of("lead **tail**"),
                CanvasController.htmlNoteParagraphs("lead<strong> tail</strong>"));
        // A pair emptied by the move must not leave a bare "****".
        assertEquals(List.of("a b"),
                CanvasController.htmlNoteParagraphs("a<strong> </strong>b"));
    }

    @Test
    void scriptStyleAndCommentContentIsDroppedNotLeaked() {
        assertEquals(List.of("before after"),
                CanvasController.htmlNoteParagraphs(
                        "before<script>steal()</script><style>.x{}</style><!-- hidden -->after"));
    }

    @Test
    void hexAndDecimalEntitiesDecodeAndAmpDecodesLast() {
        assertEquals(List.of("café → café"),
                CanvasController.htmlNoteParagraphs("caf&#233; &#x2192; caf&#xE9;<br>"));
        // &amp;lt; must yield the literal text "&lt;", never "<".
        assertEquals(List.of("a &lt; b"),
                CanvasController.htmlNoteParagraphs("a &amp;lt; b<br>"));
    }

    @Test
    void surrogateRangeEntitiesDecodeToNothing() {
        // isValidCodePoint(0xD800) is true, but a lone surrogate is ill-formed UTF-16
        // and can break the canvas JSON downstream; the range must vanish entirely.
        assertEquals(List.of("ab"),
                CanvasController.htmlNoteParagraphs("a&#xD800;b&#xDFFF;<br>"));
    }

    @Test
    void plainTextWithRawNewlinesNormalizesLikeTheSlowPath() {
        // Raw newlines disqualify the identity fast-path; no output paragraph may
        // carry an embedded newline.
        assertEquals(List.of("line1 line2"),
                CanvasController.htmlNoteParagraphs("line1\nline2"));
    }

    @Test
    void entityMintedWhitespaceInsideAnHrefIsPercentEncoded() {
        // &#10; decodes to a newline inside the URL; unencoded it would split the
        // generated link across two paragraphs.
        assertEquals(List.of("[docs](https://x.example/a%20b) (x.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://x.example/a&#10;b\">docs</a>"));
    }

    @Test
    void rawFormatAndControlCharactersAreStripped() {
        // QA note on #6: U+202E (RTL override), U+200B, U+2028, U+FEFF reached the
        // agent card while problemDetail strips the same class. Parity now holds.
        assertEquals(List.of("Call the duty manager now"),
                CanvasController.htmlNoteParagraphs(
                        "<p>Call \u202Ethe duty\u200B manager\u2028now\uFEFF</p>"));
    }

    @Test
    void plainTextFormatCharsAndNulDisqualifyTheFastPath() {
        // Without the disqualifier the identity fast-path would return these
        // verbatim, bypassing the strip (QA note 2: the fast-path NUL leak).
        assertEquals(List.of("Call the duty manager"),
                CanvasController.htmlNoteParagraphs("Call \u202Ethe duty manager"));
        assertEquals(List.of("ab"),
                CanvasController.htmlNoteParagraphs("a\u0000b"));
    }

    @Test
    void entityMintedInvisiblesBecomeSpacesButNewlineSurvives() {
        // Entities must not re-mint what the raw strip removes; the refusal
        // mirrors the raw strip's space so &#9; layout does not merge words
        // (PR #8 QA round: ContactNOCimmediately)...
        assertEquals(List.of("a b c"),
                CanvasController.htmlNoteParagraphs("a&#8238;b&#x202E;c<br>"));
        assertEquals(List.of("a b c"),
                CanvasController.htmlNoteParagraphs("a&#9;b&#13;c<br>"));
        // ...except \n: &#10; in body text still buys a paragraph break.
        assertEquals(List.of("a", "b"),
                CanvasController.htmlNoteParagraphs("a&#10;b"));
    }

    @Test
    void unicodeSpacesAreStrippedFromTheSuffixAndEncodedInTheUrl() {
        // Java's plain \s is ASCII-only; without (?U) a U+00A0 or U+3000 in the
        // authority rides raw into the destination AND splits the suffix into
        // two tokens, letting an agent anchor on the benign-looking first half
        // (PR #8 QA round, the blocking find).
        assertEquals(List.of("[docs](https://good.example%20.evil/) (good.example.evil)"),
                CanvasController.htmlNoteParagraphs(
                        "<a href=\"https://good.example&#160;.evil/\">docs</a>"));
        assertEquals(List.of("[docs](https://good.example%20.evil/) (good.example.evil)"),
                CanvasController.htmlNoteParagraphs(
                        "<a href=\"https://good.example" + Character.toString(0x3000)
                                + ".evil/\">docs</a>"));
    }

    @Test
    void anchorTextEntityNewlineCannotSplitTheLink() {
        // An entity-minted \n in link text would let the final paragraph split cut
        // the generated link apart, leaking bracket fragments.
        assertEquals(List.of("[a b](https://ok.test) (ok.test)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://ok.test\">a&#10;b</a>"));
    }

    @Test
    void hostSuffixShowsTheRealHost() {
        // Userinfo splits on the LAST @: the text says microsoft, the suffix must
        // say where the link actually goes.
        assertEquals(List.of("[Microsoft docs](https://microsoft.com@evil.example/x) (evil.example)"),
                CanvasController.htmlNoteParagraphs(
                        "<a href=\"https://microsoft.com@evil.example/x\">Microsoft docs</a>"));
    }

    @Test
    void hostSuffixSkipsWhenTextAlreadyShowsTheTarget() {
        // Autolink: text equals the URL.
        assertEquals(List.of("[https://ok.test](https://ok.test)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://ok.test\">https://ok.test</a>"));
        // Text equals the host.
        assertEquals(List.of("[ok.test](https://ok.test/deep/path)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://ok.test/deep/path\">ok.test</a>"));
        // Bare-address mailto (the live Microsoft escalation shape).
        assertEquals(List.of("[x@y.example](mailto:x@y.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"mailto:x@y.example\">x@y.example</a>"));
    }

    @Test
    void hostSuffixHandlesPortsIpv6AndMailtoQuery() {
        assertEquals(List.of("[console](https://host.example:8443/x) (host.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://host.example:8443/x\">console</a>"));
        // Portless IPv6 keeps its colons; brackets neutralize in the suffix.
        assertEquals(List.of("[local](https://%5B::1%5D/x) ((::1))"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://[::1]/x\">local</a>"));
        assertEquals(List.of("[local](https://%5B::1%5D:8443/x) ((::1))"),
                CanvasController.htmlNoteParagraphs("<a href=\"https://[::1]:8443/x\">local</a>"));
        assertEquals(List.of("[write us](mailto:x@y.example?subject=Hi) (x@y.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"mailto:x@y.example?subject=Hi\">write us</a>"));
    }

    @Test
    void entityNewlineInTheAuthorityCannotTruncateTheSuffix() {
        // &#10; is the one decodable control; in the authority it would put a raw
        // \n into the suffix and the final paragraph split would display a
        // truncated host — the anti-phish feature lying. Whitespace strips.
        assertEquals(List.of("[docs](https://good.example%20.evil/) (good.example.evil)"),
                CanvasController.htmlNoteParagraphs(
                        "<a href=\"https://good.example&#10;.evil/\">docs</a>"));
        assertEquals(List.of("[write](mailto:x%20@y.example) (x@y.example)"),
                CanvasController.htmlNoteParagraphs("<a href=\"mailto:x&#10;@y.example\">write</a>"));
    }

    @Test
    void paragraphCountIsCappedWithAnOverflowMarker() {
        StringBuilder html = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            html.append("<p>para ").append(i).append("</p>");
        }
        List<String> out = CanvasController.htmlNoteParagraphs(html.toString());
        assertEquals(9, out.size());
        assertEquals("para 8", out.get(7));
        assertEquals("(note truncated; 4 more paragraph(s) in the TSANet web app)", out.get(8));
    }
}
