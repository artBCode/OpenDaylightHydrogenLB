package org.opendaylight.controller.protocol_plugin.openflow.migration;

import java.io.BufferedWriter;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.opendaylight.controller.sal.action.Drop;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.flowprogrammer.Flow;
import org.opendaylight.controller.sal.match.Match;
import org.opendaylight.controller.sal.match.MatchType;
import org.opendaylight.controller.sal.utils.EtherTypes;
import org.opendaylight.controller.sal.utils.IPProtocols;

public class SwMigrInfo {
    /** migraion of this node */
    private Node node;
    /** the final master controller (B) */
    private InetAddress finalContr;
    /** indicates that there is a migration in progress for node */
    private boolean migrInProgress = false;
    /**
     * If the node is the initial master(A) this is true and if node
     * is the final controller(B) this is false. It stores how to
     * react to asynchronous messages. When true they are processed
     * till the receipt of dummy-flow remove. When true they are not
     * processed till the receipt of dummy-flow remove.
     */
    private boolean amIAllowedToProcess = false;
    private final Short matchDummyFlowPortNr = new Short((short) 12508);
    /** buffer for the communication of end migration to the client */
    private BufferedWriter outForEndMigr;
    /** the dummy flow that should be installed */
    private Flow dummyFlow;

    public SwMigrInfo(Node node, InetAddress srcContr, InetAddress finalContr,
            boolean amIAllowedToProcess, BufferedWriter outForEndMigr) {
        this.finalContr = finalContr;
        this.migrInProgress = true;
        this.amIAllowedToProcess = amIAllowedToProcess;
        this.dummyFlow = createDummyFlow(node, srcContr, finalContr);
        this.outForEndMigr = outForEndMigr;
    }

    public Node getNode() {
        return node;
    }

    public void setNode(Node node) {
        this.node = node;
    }

    public boolean isMigrInProgress() {
        return migrInProgress;
    }

    public void setMigrInProgress(boolean migrInProgress) {
        this.migrInProgress = migrInProgress;
    }

    public boolean isAmIAllowedToProcess() {
        return amIAllowedToProcess;
    }

    public void setAmIAllowedToProcess(boolean amIAllowedToProcess) {
        this.amIAllowedToProcess = amIAllowedToProcess;
    }

    public InetAddress getFinalContr() {
        return finalContr;
    }

    public Flow getDummyFlow() {
        return dummyFlow;
    }

    public BufferedWriter getBufferForEndMigr() {
        return outForEndMigr;
    }

    public static boolean checkFlowRemoveEqual(Flow dummyFlow, Flow flow) {
        if (flow == null)
            return false;
        Match dummyMatch = dummyFlow.getMatch();
        Match flowMatch = flow.getMatch();
        if (dummyMatch == null) {
            if (flowMatch != null) {
                return false;
            }
        } else if (!dummyMatch.equals(flowMatch)) {
            return false;
        }
        if (dummyFlow.getHardTimeout() != flow.getHardTimeout()) {
            return false;
        }
        if (dummyFlow.getId() != flow.getId()) {
            return false;
        }
        if (dummyFlow.getIdleTimeout() != flow.getIdleTimeout()) {
            return false;
        }

        if (dummyFlow.getPriority() != flow.getPriority()) {
            return false;
        }
        return true;
    }

    /**
     * This function created a dummy flow: it matches the TCP packets
     * sent form src address to dst address on port identified by the
     * constant value of matchDummyFlowPortNr.
     */
    private Flow createDummyFlow(Node node, InetAddress src, InetAddress dst) {
        // (4 tuple source IP, source port, destination IP,
        // destination port)
        Match match = new Match();
        match.setField(MatchType.DL_TYPE, EtherTypes.IPv4.shortValue()); // IP
                                                                         // packet
        match.setField(MatchType.NW_SRC, src);
        match.setField(MatchType.NW_DST, dst);
        match.setField(MatchType.NW_PROTO, IPProtocols.TCP.byteValue());
        match.setField(MatchType.TP_SRC, matchDummyFlowPortNr);
        match.setField(MatchType.TP_DST, matchDummyFlowPortNr);

        // List of actions applied to the packet
        List dropAction = new LinkedList();

        // Re-write destination IP to server instance IP
        dropAction.add(new Drop());
        // Create the dummy flow
        Flow flow = new Flow(match, dropAction);
        return flow;
    }

}
