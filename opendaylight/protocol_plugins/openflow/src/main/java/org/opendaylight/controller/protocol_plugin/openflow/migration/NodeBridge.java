package org.opendaylight.controller.protocol_plugin.openflow.migration;

import java.io.Serializable;
import java.net.InetAddress;

import org.opendaylight.controller.sal.core.Node;

public class NodeBridge implements Serializable{
    private static final long serialVersionUID = 8391405295860940868L;
    private Node containerNode; //for example OVS
    private String bridgeUUID;  //one of bridge's ids  
    private Node bridgeVirtualNode;   // identifies the same bridge in the SAL
    
    public NodeBridge(Node containerNode, String bridgeUUID,Node bridgeVirtualNode) {
        this.containerNode=containerNode;
        this.bridgeUUID=bridgeUUID;
        this.bridgeVirtualNode=bridgeVirtualNode;
    }

    public Node getContainerNode() {
        return containerNode;
    }

    public String getBridgeUUID() {
        return bridgeUUID;
    }

    public Node getBridgeVirtualNode() {
        return bridgeVirtualNode;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((bridgeUUID == null) ? 0 : bridgeUUID.hashCode());
        result = prime
                * result
                + ((bridgeVirtualNode == null) ? 0 : bridgeVirtualNode
                        .hashCode());
        result = prime * result
                + ((containerNode == null) ? 0 : containerNode.hashCode());
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
        NodeBridge other = (NodeBridge) obj;
        if (bridgeUUID == null) {
            if (other.bridgeUUID != null)
                return false;
        } else if (!bridgeUUID.equals(other.bridgeUUID))
            return false;
        if (bridgeVirtualNode == null) {
            if (other.bridgeVirtualNode != null)
                return false;
        } else if (!bridgeVirtualNode.equals(other.bridgeVirtualNode))
            return false;
        if (containerNode == null) {
            if (other.containerNode != null)
                return false;
        } else if (!containerNode.equals(other.containerNode))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "[containerNode=" + containerNode + ", bridgeUUID="
                + bridgeUUID + ", bridgeVirtualNode=" + bridgeVirtualNode + "]";
    }
    
    
   
    
    
    
}
