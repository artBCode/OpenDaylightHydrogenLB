package org.opendaylight.controller.connectionmanager.loadbalancer;

import java.io.Serializable;
import java.net.InetAddress;

import org.opendaylight.controller.protocol_plugin.openflow.migration.NodeBridge;
import org.opendaylight.controller.sal.core.Node;

class TripleContrContrNode implements Serializable{
    /**
     * 
     */
    private static final long serialVersionUID = 2869677268779754210L;
    private InetAddress srcContr;
    private InetAddress dstContr;
    private NodeBridge nodeBridge;
    public TripleContrContrNode(InetAddress srcContr, InetAddress destContr,NodeBridge nodeBridge){
        this.srcContr=srcContr;
        this.dstContr=destContr;
        this.nodeBridge=nodeBridge;
    }
    public InetAddress getSrcContr(){
        return this.srcContr;
    }
    public InetAddress getDstContr(){
        return this.dstContr;
    }
    public NodeBridge getNodeBridge(){
        return nodeBridge;
    }
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((dstContr == null) ? 0 : dstContr.hashCode());
        result = prime * result
                + ((nodeBridge == null) ? 0 : nodeBridge.hashCode());
        result = prime * result
                + ((srcContr == null) ? 0 : srcContr.hashCode());
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
        TripleContrContrNode other = (TripleContrContrNode) obj;
        if (dstContr == null) {
            if (other.dstContr != null)
                return false;
        } else if (!dstContr.equals(other.dstContr))
            return false;
        if (nodeBridge == null) {
            if (other.nodeBridge != null)
                return false;
        } else if (!nodeBridge.equals(other.nodeBridge))
            return false;
        if (srcContr == null) {
            if (other.srcContr != null)
                return false;
        } else if (!srcContr.equals(other.srcContr))
            return false;
        return true;
    }
    @Override
    public String toString() {
        return "[srcContr=" + srcContr + ", dstContr="
                + dstContr + ", nodeBridge=" + nodeBridge + "]";
    }
    
}
