package org.opendaylight.controller.protocol_plugin.openflow.migration;

import java.io.Serializable;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.opendaylight.controller.sal.core.Node;

public class LoadStatistics implements Serializable, Cloneable {

    private static final long serialVersionUID = 4794199688006396074L;
    /** the interval of time that this statistics cover */
    private long time;
    /** average CPU load during time */
    private int cpuLoad;
    /** the length of the incoming queue */
    private long incomingQueueLength;
    /** map with the number of requests from every sw */
    private HashMap<Node, Long> nodeNrReq;
    /** the id of the request */
    private Long req_id;

    public LoadStatistics() {
    }

    public LoadStatistics(long time, int cpuLoad,
            HashMap<Node, Long> nodeNrReq, long incomingQueueLength,
            Long req_id) {
        this.time = time;
        this.cpuLoad = cpuLoad;
        this.nodeNrReq = nodeNrReq;
        this.req_id = req_id;
        this.incomingQueueLength = incomingQueueLength;

    }

    public long getTime() {
        return time;
    }

    public int getCpuLoad() {
        return cpuLoad;
    }

    public void setCpuLoad(int cpuLoad) {
        this.cpuLoad = cpuLoad;

    }

    public HashMap<Node, Long> getNodeNrReq() {
        return nodeNrReq;
    }

    public Map<Node, Long> getNodeNrReqSortedByNrMsgs() {
        List<Map.Entry<Node, Long>> list = new LinkedList<>(
                nodeNrReq.entrySet());
        Collections.sort(list,
                new Comparator<Map.Entry<Node, Long>>() {

                    @Override
                    public int compare(Entry<Node, Long> o1,
                            Entry<Node, Long> o2) {
                        return (int) (o1.getValue().longValue() - o2
                                .getValue().intValue());
                    }
                });

        Map<Node, Long> result = new LinkedHashMap<>();
        for (Map.Entry<Node, Long> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public Long getReq_id() {
        return req_id;
    }

    public long nrTotalMsgs() {
        long nr = 0;
        for (Node n : nodeNrReq.keySet()) {
            nr += nodeNrReq.get(n);
        }
        return nr;
    }

    public void addNodeConnected(Node n, Long load) {
        this.nodeNrReq.put(n, load);
    }

    public void removeNodeConnected(Node n) {
        this.nodeNrReq.remove(n);
    }

    public LoadStatistics getClone() {
        HashMap<Node, Long> clonedNodeNrReq = new HashMap<Node, Long>(
                this.nodeNrReq);
        return new LoadStatistics(this.time, this.cpuLoad,
                clonedNodeNrReq, incomingQueueLength, req_id);
    }

    public long getIncomingQueueLength() {
        return incomingQueueLength;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + cpuLoad;
        result = prime
                * result
                + (int) (incomingQueueLength ^ (incomingQueueLength >>> 32));
        result = prime * result
                + ((nodeNrReq == null) ? 0 : nodeNrReq.hashCode());
        result = prime * result
                + ((req_id == null) ? 0 : req_id.hashCode());
        result = prime * result + (int) (time ^ (time >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LoadStatistics other = (LoadStatistics) obj;
        if (cpuLoad != other.cpuLoad)
            return false;
        if (incomingQueueLength != other.incomingQueueLength)
            return false;
        if (nodeNrReq == null) {
            if (other.nodeNrReq != null)
                return false;
        } else if (!nodeNrReq.equals(other.nodeNrReq))
            return false;
        if (req_id == null) {
            if (other.req_id != null)
                return false;
        } else if (!req_id.equals(other.req_id))
            return false;
        if (time != other.time)
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "[time=" + time + ", cpuLoad=" + cpuLoad
                + ", incomingQueueLength=" + incomingQueueLength
                + ", nodeNrReq=" + nodeNrReq + ", req_id=" + req_id
                + "]";
    }

}
