package org.opendaylight.controller.protocol_plugin.openflow.migration;

import org.opendaylight.controller.sal.core.Node;


public interface ILocalityMigrationCore {
    /**returns true if the current controller is the master for switch node. 
     * @param node the switch we are interested in
     * @param localityScheme the locality view of connection manager*/
    public boolean localityMigrationCore(Node node, boolean localityScheme);
}
