/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.connectionmanager.scheme;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.osgi.framework.console.CommandInterpreter;
import org.opendaylight.controller.clustering.services.CacheConfigException;
import org.opendaylight.controller.clustering.services.CacheExistException;
import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.clustering.services.IClusterServices;
import org.opendaylight.controller.connectionmanager.ConnectionMgmtScheme;
import org.opendaylight.controller.sal.connection.ConnectionLocality;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractScheme{
    private static final Logger log = LoggerFactory
            .getLogger(AbstractScheme.class);
    protected IClusterGlobalServices clusterServices = null;
    /*
     * A more natural Map data-structure is to have a Key=Controller IP-address
     * with value = a set of Nodes. But, such a data-structure results in some
     * complex event processing during the Cluster operations to sync up the
     * Connection states.
     * 
     * A data-structure with Node as the key and set of controllers provides a
     * good balance between the needed functionality and simpler clustering
     * implementation for Connection Manager.
     */
    /** A map node(switch): controllers */
    protected ConcurrentMap<Node, Set<InetAddress>> nodeConnections;

    protected abstract boolean isConnectionAllowedInternal(Node node);

    private final String name;
    private final String nodeConnectionsCacheName;

    protected AbstractScheme(IClusterGlobalServices clusterServices,
            ConnectionMgmtScheme type) {
        this.clusterServices = clusterServices;
        name = (type != null ? type.name() : "UNKNOWN");
        nodeConnectionsCacheName = "connectionmanager." + name
                + ".nodeconnections";
        if (clusterServices != null) {
            allocateCaches();
            retrieveCaches();
        } else {
            log.error(
                    "Couldn't retrieve caches for scheme {}. Clustering service unavailable",
                    name);
        }
    }

    /**
     * Returns a map controller: set of nodes/switches. It basically reverse the
     * nodeConnections map
     */
    protected ConcurrentMap<InetAddress, Set<Node>> getControllerToNodesMap() {
        ConcurrentMap<InetAddress, Set<Node>> controllerNodesMap = new ConcurrentHashMap<InetAddress, Set<Node>>();
        for (Node node : nodeConnections.keySet()) {
            Set<InetAddress> controllers = nodeConnections.get(node);
            if (controllers == null)
                continue;
            for (InetAddress controller : controllers) {
                Set<Node> nodes = controllerNodesMap.get(controller);
                // if there is no set of nodes associated with this controller,
                // create an empty one
                if (nodes == null) {
                    nodes = new HashSet<Node>();
                }
                nodes.add(node);
                controllerNodesMap.put(controller, nodes);
            }
        }
        return controllerNodesMap;
    }

    /** Is this controller allowed to connect to the node node? */
    public boolean isConnectionAllowed(Node node) {
        log.debug("isConnectionAllowed from the controller {} to the node {}",
                clusterServices.getMyAddress().getHostAddress(),node);
        if (clusterServices == null || nodeConnections == null) {
            return false;
        }

        return isConnectionAllowedInternal(node);
    }
    /** it only removes the controllers that have left the cluster*/
    public void handleClusterViewChanged() {
        log.debug("Handling Cluster View changed notification");
        List<InetAddress> controllers = clusterServices
                .getClusteredControllers();
        ConcurrentMap<InetAddress, Set<Node>> controllerNodesMap = getControllerToNodesMap();
        List<InetAddress> toRemove = new ArrayList<InetAddress>();
        for (InetAddress c : controllerNodesMap.keySet()) {
            // the controller is in the map but it is not in the cluster
            if (!controllers.contains(c)) {
                toRemove.add(c);
            }
        }

        boolean retry = false;
        for (InetAddress c : toRemove) {
            log.debug("Removing Controller : {} from the map node : set of controllers", c);
            for (Iterator<Node> nodeIterator = nodeConnections.keySet()
                    .iterator(); nodeIterator.hasNext();) {
                Node node = nodeIterator.next();
                Set<InetAddress> oldControllers = nodeConnections.get(node);
                Set<InetAddress> newControllers = new HashSet<InetAddress>(
                        oldControllers);
                if (newControllers.remove(c)) {
                    try {
                        clusterServices.tbegin();
                        if (!nodeConnections.replace(node, oldControllers,
                                newControllers)) {
                            log.debug("Replace Failed for {} ", node.toString());
                            retry = true;
                            clusterServices.trollback();
                            break;
                        } else {
                            clusterServices.tcommit();
                            log.debug("The controller {} has been removed from the map node : set of controllers", c);
                        }
                    } catch (Exception e) {
                        log.debug("Exception in replacing nodeConnections ", e);
                        retry = true;
                        try {
                            clusterServices.trollback();
                        } catch (Exception e1) {
                        }
                        break;
                    }
                }
            }
        }
        if (retry) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            handleClusterViewChanged();
        }
    }

    /**
     * Returns the set of nodes/switches associated with the controller given in
     * input
     */
    public Set<Node> getNodes(InetAddress controller) {
        ConcurrentMap<InetAddress, Set<Node>> controllerNodesMap = getControllerToNodesMap();
        return controllerNodesMap.get(controller);
    }

    /** Returns the set of nodes/switches associated with this controller */
    public Set<Node> getNodes() {
        return getNodes(clusterServices.getMyAddress());
    }

    /** Returns the set of controllers the node node is connected to */
    public Set<InetAddress> getControllers(Node node) {
        if (nodeConnections != null)
            return nodeConnections.get(node);
        return Collections.emptySet();
    }

    /** Returns the map node/switch : set of controllers */
    public ConcurrentMap<Node, Set<InetAddress>> getNodeConnections() {
        return nodeConnections;
    }

    /** Returns true if the node node is connected to this controller.
     * This is the method used to decide if a controller is allow to process messages from the switch*/
    public boolean isLocal(Node node) {
        if (nodeConnections == null){
            //log.trace("isLocal returns false");
            return false;
        }
        InetAddress myController = clusterServices.getMyAddress();
        Set<InetAddress> controllers = nodeConnections.get(node);
         
         boolean r=(controllers != null && controllers.contains(myController));
         //log.trace("isLocal returns {}",r);
         return r;
    }

    /**
     * Returns the connection status of the node node:
     * NOT_CONNECTED/LOCAL/NOT_LOCAL. It is LOCAL only if it is connected to
     * this controller
     */
    public ConnectionLocality getLocalityStatus(Node node) {
        if (nodeConnections == null)
            return ConnectionLocality.NOT_CONNECTED;
        Set<InetAddress> controllers = nodeConnections.get(node);
        if (controllers == null || controllers.size() == 0)
            return ConnectionLocality.NOT_CONNECTED;
        InetAddress myController = clusterServices.getMyAddress();
        return controllers.contains(myController) ? ConnectionLocality.LOCAL
                : ConnectionLocality.NOT_LOCAL;
    }

    /**
     * Removes this controller from the list of controllers associated with node
     * node
     */
    public Status removeNode(Node node) {
        return removeNodeFromController(node, clusterServices.getMyAddress());
    }

    /**
     * Removes the controller controller from the list of controllers associated
     * with node node.
     */
    private Status removeNodeFromController(Node node, InetAddress controller) {
        if (node == null || controller == null) {
            return new Status(StatusCode.BADREQUEST,
                    "Invalid Node or Controller Address Specified.");
        }

        if (clusterServices == null || nodeConnections == null) {
            return new Status(StatusCode.SUCCESS);
        }
        log.debug(
                "Trying to remove the controller {} from the list of controllers associated with {}",
                controller.toString(), node.toString());
        Set<InetAddress> oldControllers = nodeConnections.get(node);

        if (oldControllers != null && oldControllers.contains(controller)) {
            Set<InetAddress> newControllers = new HashSet<InetAddress>(
                    oldControllers);
            if (newControllers.remove(controller)) {
                try {
                    clusterServices.tbegin();
                    if (newControllers.size() > 0) {
                        // modifies the map in the distributed cluster
                        if (!nodeConnections.replace(node, oldControllers,
                                newControllers)) {
                            clusterServices.trollback();
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                            }
                            return removeNodeFromController(node, controller);
                        }
                    } else {
                        nodeConnections.remove(node);
                    }
                    clusterServices.tcommit();
                    log.debug(
                            "The controller {} has been removed from the list of controllers associated with {}",
                            controller.toString(), node.toString());
                } catch (Exception e) {
                    log.error("Exception in removing Controller from a Node", e);
                    try {
                        clusterServices.trollback();
                    } catch (Exception e1) {
                        log.error(
                                "Error Rolling back the node Connections Changes ",
                                e);
                    }
                    return new Status(StatusCode.INTERNALERROR);
                }

            }
        }
        return new Status(StatusCode.SUCCESS);

    }

    /*
     * A few race-conditions were seen with the Clustered caches in putIfAbsent
     * and replace functions. Leaving a few debug logs behind to assist in
     * debugging if strange things happen.
     */
    protected synchronized Status putNodeToController(Node node, InetAddress controller) {
        if (clusterServices == null || nodeConnections == null) {
            return new Status(StatusCode.INTERNALERROR,
                    "Cluster service unavailable, or node connections info missing.");
        }
        log.debug("Trying to Put {} to {}", controller.getHostAddress(),
                node.toString());

        Set<InetAddress> oldControllers = nodeConnections.get(node);
        if (oldControllers!=null && oldControllers.contains(clusterServices.getMyAddress()))
            new Status(StatusCode.SUCCESS);
        Set<InetAddress> newControllers = null;
        if (oldControllers == null) {
            newControllers = new HashSet<InetAddress>();
        } else {
            if (oldControllers.size() > 0 && !isConnectionAllowed(node)) {
                /*
                 * In certain race conditions, the putIfAbsent fails to be
                 * atomic. This check is added to identify such cases and report
                 * an warning for debugging.
                 */
                log.warn(
                        "States Exists for {} : {}. There is already one or more masters.",
                        node, oldControllers.toString());
                return new Status(StatusCode.CONFLICT);
            }
            newControllers = new HashSet<InetAddress>(oldControllers);
        }
        newControllers.add(controller);

        try {
            clusterServices.tbegin();
            if (nodeConnections.putIfAbsent(node, newControllers) != null) {
                log.debug(
                        "PutIfAbsent failed {} to {}. There is/are already another controllers associated with the node",
                        controller.getHostAddress(), node.toString());
                /*
                 * This check is needed again to take care of the case where
                 * some schemes would not allow nodes to be connected to
                 * multiple controllers. Hence, if putIfAbsent fails, that
                 * means, some other controller is competing with this
                 * controller to take hold of a Node.
                 */
                if (isConnectionAllowed(node)) {
                    if (oldControllers == null
                            || !nodeConnections.replace(node, oldControllers,
                                    newControllers)) {
                        clusterServices.trollback();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                        }
                        log.debug("Retrying ... {} with {}",
                                controller.getHostAddress(), node.toString());
                        return putNodeToController(node, controller);
                    } else {
                        log.debug(
                                "Replace successful old={} with new={} for {} to {}",
                                oldControllers.toString(),
                                newControllers.toString(),
                                controller.getHostAddress(), node.toString());
                    }
                } else {
                    clusterServices.trollback();
                    return new Status(StatusCode.CONFLICT);
                }
            } else {
                log.debug("Added {} to {}", controller.getHostAddress(),
                        node.toString());
            }
            clusterServices.tcommit();
        } catch (Exception e) {
            log.error("Excepion in adding Controller to a Node", e);
            try {
                clusterServices.trollback();
            } catch (Exception e1) {
                log.error("Error Rolling back the node Connections Changes ", e);
            }
            return new Status(StatusCode.INTERNALERROR);
        }
        return new Status(StatusCode.SUCCESS);
    }
    /**It adds the controller controller to the list of nodes associated with the node node*/
    public Status addNode(Node node, InetAddress controller) {
        if (node == null || controller == null) {
            if (node == null) {
                log.warn("addNode: node is null");
            } else if (controller == null) {
                log.error(
                        "Failed to add node {}. The controller address retrieved from clusterServices is null.",
                        node);
            }
            return new Status(StatusCode.BADREQUEST);
        }
        if (isLocal(node)) {
            return new Status(StatusCode.SUCCESS);
        }
        if (isConnectionAllowed(node)) {
            return putNodeToController(node, controller);
        } else {
            return new Status(StatusCode.NOTALLOWED);
        }
    }
    /**It adds this controller to the list of nodes associated with this node node*/
    public Status addNode(Node node) {
        return addNode(node, clusterServices.getMyAddress());
    }
    
    /**Retrieves the map node: set of controllers from the cache*/
    @SuppressWarnings({ "unchecked" })
    private void retrieveCaches() {
        if (this.clusterServices == null) {
            log.error(
                    "Un-initialized Cluster Services, can't retrieve caches for scheme: {}",
                    name);
            return;
        }

        nodeConnections = (ConcurrentMap<Node, Set<InetAddress>>) clusterServices
                .getCache(nodeConnectionsCacheName);
        log.debug("nodeConnections= {}", mapNodeControllersToString().toString());
        if (nodeConnections == null) {
            log.error("\nFailed to get cache: {}", nodeConnectionsCacheName);
        }
    }
    /**Auxiliary method. Used only for debugging and testing purposes.*/
    public String mapNodeControllersToString() {
        StringBuffer sb = new StringBuffer();
        for (Node n : nodeConnections.keySet()) {
            sb.append(n + " : [ ");
            for (InetAddress ia : nodeConnections.get(n)) {
                sb.append(ia.getHostAddress() + ", ");
            }
            sb.append("]\n");
        }
        return sb.toString()+"\n";
    }
    /**Auxiliary method. Used only for debugging and testing purposes.*/
    public String mapControllerNodesToString() {
        ConcurrentMap<InetAddress, Set<Node>> cn=getControllerToNodesMap();
        StringBuffer sb = new StringBuffer();
        for (InetAddress ia : cn.keySet()) {
            sb.append(ia.getHostAddress() + " : [ ");
            for (Node n : cn.get(ia)) {
                sb.append(n + ", ");
            }
            sb.append("]\n\n");
        }
        return sb.toString()+"\n";
    }
    /**Allocates a new cache*/
    private void allocateCaches() {
        if (this.clusterServices == null) {
            log.error("Un-initialized clusterServices, can't create cache");
            return;
        }

        try {
            clusterServices.createCache(nodeConnectionsCacheName,
                    EnumSet.of(IClusterServices.cacheMode.TRANSACTIONAL));
            log.debug(
                    "A new cache has been created with nodeConnectionsCacheName={}",
                    nodeConnectionsCacheName);
        } catch (CacheExistException cee) {
            log.debug("\nCache already exists: {}", nodeConnectionsCacheName);
        } catch (CacheConfigException cce) {
            log.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            log.error("An error occured", e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result
                + ((nodeConnections == null) ? 0 : nodeConnections.hashCode());
        result = prime
                * result
                + ((nodeConnectionsCacheName == null) ? 0
                        : nodeConnectionsCacheName.hashCode());
        return result;
    }
    
    
    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof AbstractScheme)) {
            return false;
        }
        AbstractScheme other = (AbstractScheme) obj;
        if (name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!name.equals(other.name)) {
            return false;
        }
        if (nodeConnections == null) {
            if (other.nodeConnections != null) {
                return false;
            }
        } else if (!nodeConnections.equals(other.nodeConnections)) {
            return false;
        }
        if (nodeConnectionsCacheName == null) {
            if (other.nodeConnectionsCacheName != null) {
                return false;
            }
        } else if (!nodeConnectionsCacheName
                .equals(other.nodeConnectionsCacheName)) {
            return false;
        }
        return true;
    }

}
