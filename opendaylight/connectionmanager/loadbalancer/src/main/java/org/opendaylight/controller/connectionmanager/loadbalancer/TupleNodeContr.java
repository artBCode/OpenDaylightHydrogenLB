package org.opendaylight.controller.connectionmanager.loadbalancer;

import java.net.InetAddress;

import org.opendaylight.controller.sal.core.Node;

public class TupleNodeContr {
    private Node node;
    private InetAddress contr;
    private int loadImpact;
    public TupleNodeContr(Node node, InetAddress contr, int loadImpact) {
        super();
        this.node = node;
        this.contr = contr;
        this.loadImpact = loadImpact;
    }
    
    public InetAddress getContr() {
        return contr;
    }

    public int getLoadImpact() {
        return loadImpact;
    }
    public Node getNode() {
        return node;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((contr == null) ? 0 : contr.hashCode());
        result = prime * result + loadImpact;
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
        TupleNodeContr other = (TupleNodeContr) obj;
        if (contr == null) {
            if (other.contr != null)
                return false;
        } else if (!contr.equals(other.contr))
            return false;
        if (loadImpact != other.loadImpact)
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
        return "TupleNodeContr [node=" + node + ", contr=" + contr
                + ", loadImpact=" + loadImpact + "]";
    }
   
    
    
    
}
