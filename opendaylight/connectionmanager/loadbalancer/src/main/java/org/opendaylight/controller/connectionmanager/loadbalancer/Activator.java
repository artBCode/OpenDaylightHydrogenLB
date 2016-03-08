
/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.connectionmanager.loadbalancer;

import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

import org.opendaylight.controller.clustering.services.ICacheUpdateAware;
import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.clustering.services.ICoordinatorChangeAware;
import org.apache.felix.dm.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opendaylight.controller.protocol_plugin.openflow.core.internal.Controller;
import org.opendaylight.controller.protocol_plugin.openflow.migration.IControllerMigrationService;
import org.opendaylight.controller.sal.core.ComponentActivatorAbstractBase;
import org.opendaylight.controller.sal.flowprogrammer.IFlowProgrammerService;
import org.opendaylight.controller.sal.utils.GlobalConstants;
import org.opendaylight.ovsdb.plugin.IConnectionServiceInternal;
import org.opendaylight.ovsdb.plugin.InventoryServiceInternal;
import org.opendaylight.ovsdb.plugin.OVSDBConfigService;
import org.opendaylight.ovsdb.plugin.OVSDBInventoryListener;

public class Activator extends ComponentActivatorAbstractBase {
    protected static final Logger logger = LoggerFactory
            .getLogger(Activator.class);
    private final String migrationCacheName = "connectionmanager.loadbalancer.contrNodeContr";


    /**
     * Method which tells how many Global implementations are
     * supported by the bundle. This way we can tune the number of
     * components created. This components will be created ONLY at the
     * time of bundle startup and will be destroyed only at time of
     * bundle destruction, this is the major difference with the
     * implementation retrieved via getImplementations where all of
     * them are assumed to be in a container!
     *
     *
     * @return The list of implementations the bundle will support,
     * in Global version
     */
    @Override
    protected Object[] getGlobalImplementations() {
        logger.debug("getGlobalImplementations");
        Object[] res = { ManagerLB.class};
        return res;
    }

    /**
     * Configure the dependency for a given instance Global
     *
     * @param c Component assigned for this instance, this will be
     * what will be used for configuration
     * @param imp implementation to be configured
     * @param containerName container on which the configuration happens
     */
    @Override
    protected void configureGlobalInstance(Component c, Object imp) {
        logger.debug("configureGlobalInstance");
        if (imp.equals(ManagerLB.class)) {
            Dictionary<String, Object> props = new Hashtable<String, Object>();
            Set<String> propSet = new HashSet<String>();
            
            //propSet.add(migrationCacheName);
            propSet.add(Controller.loadStatisticsCacheName);
            propSet.add(Controller.physicalConnectionsCacheName);
            props.put("cachenames", propSet);
            props.put(GlobalConstants.PROTOCOLPLUGINTYPE.toString(), "OVS");
            props.put("scope", "Global");

            // export my service or listen for notifications from another service
            c.setInterface(new String[] {
                    OVSDBInventoryListener.class.getName(),
                    ICacheUpdateAware.class.getName(),
                    INodeControllerAssociations.class.getName(),
                    ICoordinatorChangeAware.class.getName()
                    }, props);
            
            
            c.add(createServiceDependency()
                    .setService(IClusterGlobalServices.class)
                    .setCallbacks("setClusterServices", "unsetClusterServices")
                    .setRequired(true));
            c.add(createServiceDependency().setService(OVSDBConfigService.class)
                    .setCallbacks("setOvsdbConfigService", "unsetOvsdbConfigService")
                    .setRequired(true));
            
            c.add(createServiceDependency().setService(IConnectionServiceInternal.class)
                    .setCallbacks("setOvsdbConnectionService", "setOvsdbConnectionService")
                    .setRequired(true));
            c.add(createServiceDependency().setService(InventoryServiceInternal.class)
                    .setCallbacks("setOvsdbInventoryService", "unsetOvsdbInventoryService")
                    .setRequired(true));
            
          
           
            c.add(createServiceDependency().setService(IControllerMigrationService.class)
                    .setCallbacks("setIControllerMigrationService", "unsetIControllerMigrationService")
                    .setRequired(true));
            c.add(createServiceDependency().setService(IFlowProgrammerService.class).setCallbacks(
                    "setIFlowProgrammerService", "unsetIFlowProgrammerService").setRequired(true));
                
           
        }
       
       
    }
   
}
