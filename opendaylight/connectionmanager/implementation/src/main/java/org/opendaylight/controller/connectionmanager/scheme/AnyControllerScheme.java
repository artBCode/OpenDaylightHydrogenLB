/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.connectionmanager.scheme;

import java.net.InetAddress;
import java.util.Set;

import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.connectionmanager.ConnectionMgmtScheme;
import org.opendaylight.controller.sal.core.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AnyControllerScheme extends AbstractScheme {
    private static AbstractScheme myScheme= null;
    private static final Logger log = LoggerFactory.getLogger(AbstractScheme.class);
    protected AnyControllerScheme(IClusterGlobalServices clusterServices) {
        super(clusterServices, ConnectionMgmtScheme.ANY_CONTROLLER_ONE_MASTER);
        
    }

    public static AbstractScheme getScheme(IClusterGlobalServices clusterServices) {
        if (myScheme == null) {
            myScheme = new AnyControllerScheme(clusterServices);
        }
        return myScheme;
    }

    @Override
    public boolean isConnectionAllowedInternal(Node node) {
        Set <InetAddress> controllers = nodeConnections.get(node);
        // if the node is not connected to any controller
        if (controllers == null || controllers.size() == 0){ 
            log.debug("isConnectionAllowedInternal returns true.");
            return true;
        }
        boolean res= (controllers.size() == 1 && controllers.contains(clusterServices.getMyAddress()));
        log.debug("isConnectionAllowedInternal returns {}",String.valueOf(res));
        return res;
    }
}
