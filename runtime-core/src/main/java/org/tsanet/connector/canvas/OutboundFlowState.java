package org.tsanet.connector.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/** Mutable server-side state for one outbound submission flow (see FlowStateStore). */
public final class OutboundFlowState {

    private final String flowId;
    private List<PartnerHit> hits = new ArrayList<>();
    private PartnerHit partner;
    private ProcessForm form;
    private final Map<String, String> values = new HashMap<>();
    private SubmissionResult result;
    private boolean submitPending;

    OutboundFlowState(String flowId) {
        this.flowId = flowId;
    }

    public String flowId() {
        return flowId;
    }

    public List<PartnerHit> hits() {
        return hits;
    }

    public void hits(List<PartnerHit> hits) {
        this.hits = hits;
    }

    public PartnerHit partner() {
        return partner;
    }

    public void partner(PartnerHit partner) {
        this.partner = partner;
    }

    public ProcessForm form() {
        return form;
    }

    public void form(ProcessForm form) {
        this.form = form;
    }

    public Map<String, String> values() {
        return values;
    }

    public SubmissionResult result() {
        return result;
    }

    public void result(SubmissionResult result) {
        this.result = result;
    }

    public boolean submitPending() {
        return submitPending;
    }

    public void submitPending(boolean submitPending) {
        this.submitPending = submitPending;
    }
}
