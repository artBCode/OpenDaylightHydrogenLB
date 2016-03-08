
/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.protocol_plugin.openflow.internal;

import java.util.Collections;
import java.util.Dictionary;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.felix.dm.Component;
import org.opendaylight.controller.protocol_plugin.openflow.IReadFilterInternalListener;
import org.opendaylight.controller.protocol_plugin.openflow.IReadServiceFilter;
import org.opendaylight.controller.sal.connection.IPluginOutConnectionService;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.core.Node.NodeIDType;
import org.opendaylight.controller.sal.core.NodeConnector;
import org.opendaylight.controller.sal.core.NodeTable;
import org.opendaylight.controller.sal.flowprogrammer.Flow;
import org.opendaylight.controller.sal.reader.FlowOnNode;
import org.opendaylight.controller.sal.reader.IPluginInReadService;
import org.opendaylight.controller.sal.reader.IPluginOutReadService;
import org.opendaylight.controller.sal.reader.NodeConnectorStatistics;
import org.opendaylight.controller.sal.reader.NodeDescription;
import org.opendaylight.controller.sal.reader.NodeTableStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container Instance of IPluginInReadService implementation class
 */
public class ReadService implements IPluginInReadService, IReadFilterInternalListener {
    private static final Logger logger = LoggerFactory
            .getLogger(ReadService.class);
    private IReadServiceFilter filter;
    private Set<IPluginOutReadService> pluginOutReadServices = new CopyOnWriteArraySet<IPluginOutReadService>();
    private String containerName;
    private IPluginOutConnectionService connectionOutService;

    /**
     * Function called by the dependency manager when all the required
     * dependencies are satisfied
     *
     */
    @SuppressWarnings("unchecked")
    void init(Component c) {
        Dictionary<Object, Object> props = c.getServiceProperties();
        containerName = (props != null) ? (String) props.get("containerName") : null;
    }

    /**
     * Function called by the dependency manager when at least one
     * dependency become unsatisfied or when the component is shutting
     * down because for example bundle is being stopped.
     *
     */
    void destroy() {
        pluginOutReadServices.clear();
    }

    /**
     * Function called by dependency manager after "init ()" is called
     * and after the services provided by the class are registered in
     * the service registry
     *
     */
    void start() {
    }

    /**
     * Function called by the dependency manager before the services
     * exported by the component are unregistered, this will be
     * followed by a "destroy ()" calls
     *
     */
    void stop() {
    }

    public void setService(IReadServiceFilter filter) {
        this.filter = filter;
    }

    public void unsetService(IReadServiceFilter filter) {
        this.filter = null;
    }

    public void setPluginOutReadServices(IPluginOutReadService service) {
        logger.trace("Got a service set request {}", service);
        if (this.pluginOutReadServices != null) {
            this.pluginOutReadServices.add(service);
        }
    }

    public void unsetPluginOutReadServices(
            IPluginOutReadService service) {
        logger.trace("Got a service UNset request");
        if (this.pluginOutReadServices != null) {
            this.pluginOutReadServices.remove(service);
        }
    }
    @Override
    public FlowOnNode readFlow(Node node, Flow flow, boolean cached) {
        logger.debug("readFlow: node={} flow={} cached={}",node,flow,cached);
        if (!node.getType().equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return null;
        }

        if (!connectionOutService.isLocal(node)) {
            logger.debug("readFlow: This Controller is not the master for the node : " + node);
            return null;
        }
        return filter.readFlow(containerName, node, flow, cached);
    }

    @Override
    public List<FlowOnNode> readAllFlow(Node node, boolean cached) {
        logger.debug("readAllFlow: node={} cached={}",node,cached);
        if (!node.getType().equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return Collections.emptyList();
        }

        if (!connectionOutService.isLocal(node)) {
            logger.debug("readAllFlow: This Controller is not the master for the node : " + node);
            return Collections.emptyList();
        }

        return filter.readAllFlow(containerName, node, cached);
    }

    @Override
    public NodeDescription readDescription(Node node, boolean cached) {
        logger.debug("readDescription: node={} cached={}",node,cached);
        if (!node.getType().equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return null;
        }

        if (!connectionOutService.isLocal(node)) {
            logger.debug("readDescription: This Controller is not the master for the node : " + node);
            return null;
        }

        return filter.readDescription(node, cached);
    }

    @Override
    public NodeConnectorStatistics readNodeConnector(NodeConnector connector,
            boolean cached) {
        logger.debug("readNodeConnector: nodeConnector={} cached={}",connector,cached);
        if (!connector.getNode().getType()
            .equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return null;
        }

        if (!connectionOutService.isLocal(connector.getNode())) {
            logger.debug("readNodeConnector: This Controller is not the master for connector : "+connector);
            return null;
        }

        return filter.readNodeConnector(containerName, connector, cached);
    }

    @Override
    public List<NodeConnectorStatistics> readAllNodeConnector(Node node,
            boolean cached) {
        logger.debug("readAllNodeConnector: nodeConnector={} cached={}",node,cached);
        if (!node.getType().equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return Collections.emptyList();
        }

        if (!connectionOutService.isLocal(node)) {
            logger.debug("readAllNodeConnector: This Controller is not the master for node : " + node);
            return Collections.emptyList();
        }

        return filter.readAllNodeConnector(containerName, node, cached);
    }

    @Override
    public long getTransmitRate(NodeConnector connector) {
        logger.debug("getTransmitRate: nodeConnector={}",connector);
        if (!connector.getNode().getType()
            .equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return 0;
        }

        if (!connectionOutService.isLocal(connector.getNode())) {
            logger.debug("getTransmitRate: This Controller is not the master for connector : "+connector);
            return 0;
        }

        return filter.getTransmitRate(containerName, connector);
    }

    @Override
    public NodeTableStatistics readNodeTable(NodeTable table, boolean cached) {
        logger.debug("readNodeTable: table={} cached={}",table,cached);
        if (!table.getNode().getType()
                .equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return null;
        }

        if (!connectionOutService.isLocal(table.getNode())) {
            logger.debug("readNodeTable: This Controller is not the master for connector : "+table);
            return null;
        }

        return filter.readNodeTable(containerName, table, cached);
    }

    @Override
    public List<NodeTableStatistics> readAllNodeTable(Node node, boolean cached) {
        logger.debug("readAllNodeTable: node={} cached={}",node,cached);
        if (!node.getType().equals(NodeIDType.OPENFLOW)) {
            logger.error("Invalid node type");
            return Collections.emptyList();
        }

        if (!connectionOutService.isLocal(node)) {
            logger.debug("readAllNodeTable: This Controller is not the master for node : " + node);
            return Collections.emptyList();
        }

        return filter.readAllNodeTable(containerName, node, cached);
    }

    @Override
    public void nodeFlowStatisticsUpdated(Node node, List<FlowOnNode> flowStatsList) {
        logger.trace("readFlowStatisticsUpdated: nodeConnector={}",node);
        if (!connectionOutService.isLocal(node)) {
            logger.debug("nodeFlowStatisticsUpdated: This Controller is not the master for node : " + node);
            return;
        }
        for (IPluginOutReadService service : pluginOutReadServices) {
            service.nodeFlowStatisticsUpdated(node, flowStatsList);
        }
    }

    @Override
    public void nodeConnectorStatisticsUpdated(Node node, List<NodeConnectorStatistics> ncStatsList) {
        logger.trace("nodeConnectorStatisticsUpdated: node={}",node);
        if (!connectionOutService.isLocal(node)) {
            logger.debug("nodeConnectorStatisticsUpdated: This Controller is not the master for node : " + node);
            return;
        }
        for (IPluginOutReadService service : pluginOutReadServices) {
            service.nodeConnectorStatisticsUpdated(node, ncStatsList);
        }
    }

    @Override
    public void nodeTableStatisticsUpdated(Node node, List<NodeTableStatistics> tableStatsList) {
        logger.trace("nodeTableStatisticsUpdated: node={}",node);
        if (!connectionOutService.isLocal(node)) {
            logger.debug("nodeTableStatisticsUpdated: This Controller is not the master for node : " + node);
            return;
        }
        for (IPluginOutReadService service : pluginOutReadServices) {
            service.nodeTableStatisticsUpdated(node, tableStatsList);
        }
    }

    @Override
    public void nodeDescriptionStatisticsUpdated(Node node, NodeDescription nodeDescription) {
        logger.debug("nodeDescriptionStatisticsUpdated: node={} nodeDescriptor={}",node,nodeDescription);
        if (!connectionOutService.isLocal(node)) {
            logger.debug("nodeDescriptionStatisticsUpdated: This Controller is not the master for node : " + node);
            return;
        }
        for (IPluginOutReadService service : pluginOutReadServices) {
            service.descriptionStatisticsUpdated(node, nodeDescription);
        }
    }

    void setIPluginOutConnectionService(IPluginOutConnectionService s) {
        connectionOutService = s;
    }

    void unsetIPluginOutConnectionService(IPluginOutConnectionService s) {
        if (connectionOutService == s) {
            connectionOutService = null;
        }
    }
}
