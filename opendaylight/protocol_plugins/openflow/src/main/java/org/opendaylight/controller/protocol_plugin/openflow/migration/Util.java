package org.opendaylight.controller.protocol_plugin.openflow.migration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.opendaylight.controller.sal.core.Node;

public class Util {
    /**
     * Given an exception e this method converts the message from
     * e.printStackTrace() into a String.
     */
    public static String exceptionToString(Exception e) {
        Writer writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        e.printStackTrace(printWriter);
        return writer.toString();
    }

    /**
     * This returns the map bridge -> controllers. Notice that this will not be
     * consistent if there are multiple bridges having the same id.
     */
    public static Map<Node, Set<InetAddress>> getCompatibleMapNodeContr(
            Map<NodeBridge, Set<InetAddress>> bridgeContrPhysicalConn) {
        Map<Node, Set<InetAddress>> map = new HashMap<Node, Set<InetAddress>>();
        for (NodeBridge nb : bridgeContrPhysicalConn.keySet()) {
            Node node = nb.getBridgeVirtualNode();
            if (node == null)
                continue;
            map.put(node, bridgeContrPhysicalConn.get(nb));
        }
        return map;
    }

    /**
     * Given a map of bridge --> controllers returns the enriched map nodeBridge
     * --> controllers, using the complete map physConn.
     */
    public static Map<NodeBridge, Set<InetAddress>> getCompatibleMap(
            Map<Node, Set<InetAddress>> nodeContrs,
            Map<NodeBridge, Set<InetAddress>> physConn) {
        Map<NodeBridge, Set<InetAddress>> map = new HashMap<NodeBridge, Set<InetAddress>>();
        for (NodeBridge nb : physConn.keySet()) {
            Node bridge = nb.getBridgeVirtualNode();
            if (nodeContrs.containsKey(bridge))
                map.put(nb, nodeContrs.get(bridge));
        }

        return map;
    }

    /**
     * Given a bridge, the method returns the a more detailed info about it:
     * Container, UUid, bridge
     */
    public static NodeBridge getNodeBridgeFromNode(Node node,
            Map<NodeBridge, Set<InetAddress>> physConn) {
        if (node == null)
            return null;
        for (NodeBridge nb : physConn.keySet()) {
            Node bridge = nb.getBridgeVirtualNode();
            if (bridge != null && bridge.equals(node))
                return nb;
        }
        return null;
    }

    public static NodeBridge getNodeBridgeFromUuid(String uuid,
            Map<NodeBridge, Set<InetAddress>> physConn) {
        if (uuid == null)
            return null;
        for (NodeBridge nb : physConn.keySet()) {
            String uuid_ = nb.getBridgeUUID();
            if (uuid != null && uuid.equals(uuid_))
                return nb;
        }
        return null;
    }

    public static List<Node> nodesListSorted(Set<Node> setNodes) {

        List<Node> nodes = new LinkedList<Node>(setNodes);
        Collections.sort(nodes, new Comparator<Node>() {

            @Override
            public int compare(Node o1, Node o2) {
                // TODO Auto-generated method stub
                return o1.toString().compareTo(o2.toString());
            }
        });
        return nodes;

    }

    public static List<NodeBridge> nodeBrListSorted(Set<NodeBridge> setNodes) {

        List<NodeBridge> nodes = new LinkedList<NodeBridge>(setNodes);
        Collections.sort(nodes, new Comparator<NodeBridge>() {

            @Override
            public int compare(NodeBridge o1, NodeBridge o2) {
                return o1.getBridgeVirtualNode().toString()
                        .compareTo(o2.getBridgeVirtualNode().toString());
            }
        });
        return nodes;

    }

    public static String nodeMastersToStr(
            Map<Node, Set<InetAddress>> nodeMasterControllers) {
        List<Node> sortednodes = nodesListSorted(nodeMasterControllers.keySet());
        StringBuilder sb = new StringBuilder();
        for (Node n : sortednodes) {
            sb.append(n);
            sb.append(" ");
            sb.append(nodeMasterControllers.get(n));
            sb.append(",");
        }
        return sb.length()!=0 ? sb.toString().substring(0, sb.length()-1) : "";
    }

    public static String nodeConnectionsToStr(
            Map<NodeBridge, Set<InetAddress>> bridgeConn) {
        List<NodeBridge> sortednodes = nodeBrListSorted(bridgeConn.keySet());
        StringBuilder sb = new StringBuilder();
        for (NodeBridge n : sortednodes) {
            sb.append(n.getBridgeVirtualNode());
            sb.append(" ");
            sb.append(bridgeConn.get(n));
            sb.append(",");
        }
        return sb.length()!=0 ? sb.toString().substring(0, sb.length()-1) : "";
    }
}
