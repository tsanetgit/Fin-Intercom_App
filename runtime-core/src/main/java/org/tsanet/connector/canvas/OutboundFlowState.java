package org.tsanet.connector.canvas;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.tsanet.connector.tsanet.dto.PartnerHit;
import org.tsanet.connector.tsanet.dto.ProcessForm;
import org.tsanet.connector.tsanet.dto.SubmissionResult;

/**
 * Mutable server-side state for one outbound submission flow (see FlowStateStore).
 *
 * <p>The submission's status is a single explicit machine, not derivable booleans:
 * DRAFT -> SUBMITTING -> (LANDED | UNRESOLVED), UNRESOLVED -> DRAFT only through a
 * reconcile lookup that found nothing, UNRESOLVED/SUBMITTING -> LANDED when a lookup
 * finds the case. Transitions are synchronized and checked, so two views racing the
 * same flow cannot both start a gateway submit, a retry cannot bypass the reconcile,
 * and no exit from an unresolved submission can mint a new internalCaseNumber. The
 * canvas layer renders by status; it never writes status directly.
 */
public final class OutboundFlowState {

    /** Lifecycle of this flow's single submission (internalCaseNumber == flowId). */
    public enum Status {
        /** Editable: no submit in flight and none unresolved. The only state that may submit. */
        DRAFT,
        /** A gateway.submit call is in flight somewhere; every other view waits. */
        SUBMITTING,
        /** A submit timed out; the case may or may not exist. Exit only via reconcile. */
        UNRESOLVED,
        /** The case exists; result is set and final. */
        LANDED
    }

    private final String flowId;
    private List<PartnerHit> hits = List.of();
    private PartnerHit partner;
    private ProcessForm form;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private SubmissionResult result;
    private Status status = Status.DRAFT;

    OutboundFlowState(String flowId) {
        this.flowId = flowId;
    }

    public String flowId() {
        return flowId;
    }

    public synchronized List<PartnerHit> hits() {
        return hits;
    }

    public synchronized void hits(List<PartnerHit> hits) {
        this.hits = List.copyOf(hits);
    }

    public synchronized PartnerHit partner() {
        return partner;
    }

    public synchronized void partner(PartnerHit partner) {
        this.partner = partner;
    }

    public synchronized ProcessForm form() {
        return form;
    }

    public synchronized void form(ProcessForm form) {
        this.form = form;
    }

    public Map<String, String> values() {
        return values;
    }

    /** Drops form-field values (keys {@code f_*}); summary/description survive a partner change. */
    public synchronized void clearFieldValues() {
        values.keySet().removeIf(k -> k.startsWith("f_"));
    }

    public synchronized SubmissionResult result() {
        return result;
    }

    public synchronized Status status() {
        return status;
    }

    /**
     * Claims the one permitted submit. Only the caller that flips DRAFT -> SUBMITTING
     * may call the gateway; a false return means another view already did (or the
     * outcome is already known) and the caller renders by status instead.
     */
    public synchronized boolean beginSubmit() {
        if (status != Status.DRAFT) {
            return false;
        }
        status = Status.SUBMITTING;
        return true;
    }

    /** The case exists (submit returned, or a reconcile lookup found it). Terminal. */
    public synchronized void landed(SubmissionResult result) {
        this.result = result;
        this.status = Status.LANDED;
    }

    /** The in-flight submit timed out; outcome unknown until a reconcile lookup answers. */
    public synchronized void submitUnresolved() {
        if (status == Status.SUBMITTING) {
            status = Status.UNRESOLVED;
        }
    }

    /** The in-flight submit failed cleanly (the API answered with an error); editable again. */
    public synchronized void submitFailed() {
        if (status == Status.SUBMITTING) {
            status = Status.DRAFT;
        }
    }

    /**
     * Records that a reconcile lookup found no case. True only from UNRESOLVED — this
     * transition IS the server-side "reconcile happened" marker that re-arms submit.
     * Deliberately a no-op while SUBMITTING: a lookup racing the original in-flight
     * call must not unlock a retry.
     */
    public synchronized boolean reconcileNotFound() {
        if (status != Status.UNRESOLVED) {
            return false;
        }
        status = Status.DRAFT;
        return true;
    }
}
