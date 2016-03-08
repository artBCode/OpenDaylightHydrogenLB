package org.opendaylight.controller.connectionmanager.loadbalancer;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;

public interface INodeControllerAssociations {
   public boolean isConnectionAllowedLB();
}
