package org.opendaylight.controller.protocol_plugin.openflow.migration;

import java.io.BufferedWriter;
import java.net.InetAddress;

import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.flowprogrammer.Flow;

/***/
public interface IControllerMigrationService {
    /**Sets the state to migration in progress for switch node*/
    public boolean migrationInProgressON(Node node, InetAddress srcContr,
            InetAddress finalContr, boolean initiallyMaster, BufferedWriter out);
    /**Unsets the state: migration ended for node n*/
    public boolean migrationInProgressOFF(Node node);
    /**triggers the dummy-flow installation and removal*/
    public boolean installAndRemoveDummyFlow(Node node);

}
