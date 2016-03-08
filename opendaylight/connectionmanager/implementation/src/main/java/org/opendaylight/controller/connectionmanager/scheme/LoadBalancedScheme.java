/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.connectionmanager.scheme;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.connectionmanager.ConnectionMgmtScheme;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load Balancing scheme will let the nodes connect with controller based on the
 * resource usage in each of the controllers in a cluster.
 * 
 * Incomplete and Currently not used.
 */
class LoadBalancedScheme extends AbstractScheme {
    private static AbstractScheme myScheme = null;
    private static final Logger logger = LoggerFactory
            .getLogger(LoadBalancedScheme.class);
    private final int retryToConnAfter = 500; // milliseconds
    private final int retryForSeconds = 120;
    Map<Node, Integer> nr_retrial_left;

    protected LoadBalancedScheme(IClusterGlobalServices clusterServices) {
        super(clusterServices, ConnectionMgmtScheme.LOAD_BALANCED);
        nr_retrial_left = new ConcurrentHashMap<Node, Integer>();

    }

    public static AbstractScheme getScheme(
            IClusterGlobalServices clusterServices) {
        if (myScheme == null) {
            myScheme = new LoadBalancedScheme(clusterServices);
        }
        return myScheme;
    }

    // the logic of this function has been implemented in the ManagerLB.
    // the coordinator decides the hole map node --> setControllers
    @Override
    public boolean isConnectionAllowedInternal(Node node) {
        Set<InetAddress> setContr = nodeConnections.get(node);
        return (setContr != null && setContr.contains(clusterServices
                .getMyAddress()));
    }

    /**
     * this function is it is only responsible to return the correct Status.
     * that may be used in other bundles. All the connection operations are
     * done in the load balancing bundle.
     */
    @Override
    public synchronized Status addNode(Node node, InetAddress controller) {
        if (node == null || controller == null) {
            if (node == null) {
                logger.warn("addNode: node is null");
            } else if (controller == null) {
                logger.error(
                        "Failed to add node {}. The controller address retrieved from clusterServices is null.",
                        node);
            }
            return new Status(StatusCode.BADREQUEST);
        }
        if ("OVS".equals(node.getType()))
            return new Status(StatusCode.SUCCESS);
        if (isLocal(node)) {
            nr_retrial_left.remove(node); // there is no need to retry
            return new Status(StatusCode.SUCCESS);
        }
        if (isConnectionAllowedInternal(node)) { // check if it has been
                                                 // connected
            return super.putNodeToController(node, controller);
        } else {
            // used if the node is not connected to any controller
            class RetryConnection extends TimerTask {
                AbstractScheme scheme;
                Node node;

                public RetryConnection(Node node, AbstractScheme sch) {
                    this.scheme = sch;
                    this.node = node;
                }

                @Override
                public void run() {
                    this.scheme.addNode(node);

                }
            }
            // checking if the node is not connected to any controller
            Set<InetAddress> setContr = nodeConnections.get(node);
            if ((setContr == null || (setContr != null && setContr.size() == 0))
                    && clusterServices.getClusteredControllers().size() != 0) {
                Integer i = nr_retrial_left.get(node);
                if (i == null)
                    nr_retrial_left
                            .put(node,
                                    new Integer(
                                            (int) (retryForSeconds * 1000 / retryToConnAfter) - 1));
                else {
                    if (i.intValue() <= 0) {
                        nr_retrial_left.remove(node);
                        return new Status(StatusCode.NOTALLOWED);
                    } else {
                        nr_retrial_left
                                .put(node, new Integer(i.intValue() - 1));
                    }
                }
                Timer timer = new Timer();
                timer.schedule(new RetryConnection(node, this),
                        retryToConnAfter);
            } else {
                nr_retrial_left.remove(node); // there is no need to retry(some
                                              // other con)
            }
            // hopefully in one of the next calls it will succeed (the
            // coordinator adds some contr)
            return new Status(StatusCode.NOTALLOWED);
        }
    }


}
