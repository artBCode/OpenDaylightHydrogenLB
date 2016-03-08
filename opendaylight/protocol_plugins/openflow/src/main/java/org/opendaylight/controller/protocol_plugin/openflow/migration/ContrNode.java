package org.opendaylight.controller.protocol_plugin.openflow.migration;

import java.io.Serializable;
import java.net.InetAddress;

import org.opendaylight.controller.sal.core.Node;

public class ContrNode implements Serializable{
    private static final long serialVersionUID = 6083948523876349170L;
    private InetAddress controller;
    private Node node;
    
    public ContrNode(InetAddress controller, Node node){
        this.controller=controller;
        this.node=node;
    }
    
    public InetAddress getController() {
        return controller;
    }
    public void setController(InetAddress controller) {
        this.controller = controller;
    }
    public Node getNode() {
        return node;
    }
    public void setNode(Node node) {
        this.node = node;
    }
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((controller == null) ? 0 : controller.hashCode());
        result = prime * result + ((node == null) ? 0 : node.hashCode());
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
        ContrNode other = (ContrNode) obj;
        if (controller == null) {
            if (other.controller != null)
                return false;
        } else if (!controller.equals(other.controller))
            return false;
        if (node == null) {
            if (other.node != null)
                return false;
        } else if (!node.equals(other.node))
            return false;
        return true;
    }
    @Override
    public String toString() {
        return "ContrNode [controller=" + controller + ", node=" + node + "]";
    }
    
    
}
