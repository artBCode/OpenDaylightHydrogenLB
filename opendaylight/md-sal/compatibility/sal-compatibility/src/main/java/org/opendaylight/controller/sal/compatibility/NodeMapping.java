/**
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.sal.compatibility;

import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.opendaylight.controller.sal.common.util.Arguments;
import org.opendaylight.controller.sal.core.AdvertisedBandwidth;
import org.opendaylight.controller.sal.core.Bandwidth;
import org.opendaylight.controller.sal.core.Buffers;
import org.opendaylight.controller.sal.core.Capabilities;
import org.opendaylight.controller.sal.core.Config;
import org.opendaylight.controller.sal.core.ConstructionException;
import org.opendaylight.controller.sal.core.MacAddress;
import org.opendaylight.controller.sal.core.Name;
import org.opendaylight.controller.sal.core.NodeConnector.NodeConnectorIDType;
import org.opendaylight.controller.sal.core.PeerBandwidth;
import org.opendaylight.controller.sal.core.Property;
import org.opendaylight.controller.sal.core.SupportedBandwidth;
import org.opendaylight.controller.sal.core.Tables;
import org.opendaylight.controller.sal.core.TimeStamp;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FeatureCapability;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNodeConnectorUpdated;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowCapableNodeUpdated;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityArpMatchIp;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityFlowStats;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityIpReasm;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityPortStats;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityQueueStats;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityStp;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowFeatureCapabilityTableStats;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.FlowNodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.inventory.rev130819.flow.node.SwitchFeatures;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.port.rev130925.PortConfig;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.port.rev130925.PortFeatures;
import org.opendaylight.yang.gen.v1.urn.opendaylight.flow.types.port.rev130925.flow.capable.port.State;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeConnectorUpdated;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.NodeUpdated;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.Nodes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnector;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.node.NodeConnectorKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.Node;
import org.opendaylight.yang.gen.v1.urn.opendaylight.inventory.rev130819.nodes.NodeKey;
import org.opendaylight.yangtools.yang.binding.Identifier;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier.IdentifiableItem;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier.PathArgument;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

public final class NodeMapping {
  public final static String MD_SAL_TYPE = "MD_SAL";

  private final static Class<Node> NODE_CLASS = Node.class;

  private final static Class<NodeConnector> NODECONNECTOR_CLASS = NodeConnector.class;

  private NodeMapping() {
    throw new UnsupportedOperationException("Utility class. Instantiation is not allowed.");
  }

  public static org.opendaylight.controller.sal.core.Node toADNode(final InstanceIdentifier<? extends Object> node) throws ConstructionException {
    NodeId nodeId = NodeMapping.toNodeId(node);
    return NodeMapping.toADNode(nodeId);
  }

  public static org.opendaylight.controller.sal.core.Node toADNode(final NodeId id) throws ConstructionException {
      String aDNodeId = NodeMapping.toADNodeId(id);
      return  new org.opendaylight.controller.sal.core.Node(NodeMapping.MD_SAL_TYPE, aDNodeId);
  }

  public static NodeId toNodeId(final InstanceIdentifier<? extends Object> node) {
    Preconditions.<InstanceIdentifier<? extends Object>>checkNotNull(node);
    List<PathArgument> path = node.getPath();
    Preconditions.<List<PathArgument>>checkNotNull(path);
    int size = path.size();
    Preconditions.checkArgument(size >= 2);
    final PathArgument arg = path.get(1);
    final IdentifiableItem item = Arguments.<IdentifiableItem>checkInstanceOf(arg, IdentifiableItem.class);
    Identifier<?> key = item.getKey();
    final NodeKey nodeKey = Arguments.<NodeKey>checkInstanceOf(key, NodeKey.class);
    return nodeKey.getId();
  }

  public static String toADNodeId(final NodeId nodeId) {
    Preconditions.<NodeId>checkNotNull(nodeId);
    return nodeId.getValue();
  }

  public static org.opendaylight.controller.sal.core.NodeConnector toADNodeConnector(final NodeConnectorRef source) throws ConstructionException {
    Preconditions.<NodeConnectorRef>checkNotNull(source);
    final InstanceIdentifier<?> path = Preconditions.<InstanceIdentifier<? extends Object>>checkNotNull(source.getValue());
    Preconditions.checkArgument(path.getPath().size() >= 3);
    final PathArgument arg = path.getPath().get(2);
    final IdentifiableItem item = Arguments.<IdentifiableItem>checkInstanceOf(arg,IdentifiableItem.class);
    final NodeConnectorKey connectorKey = Arguments.<NodeConnectorKey>checkInstanceOf(item.getKey(), NodeConnectorKey.class);
    return NodeMapping.toADNodeConnector(connectorKey.getId(), NodeMapping.toNodeId(path));
  }

  public static org.opendaylight.controller.sal.core.NodeConnector toADNodeConnector(final NodeConnectorId ncid, final NodeId nid) throws ConstructionException {
    String nodeConnectorType = NodeMapping.toNodeConnectorType(ncid, nid);
    Object aDNodeConnectorId = NodeMapping.toADNodeConnectorId(ncid, nid);
    org.opendaylight.controller.sal.core.Node aDNode = NodeMapping.toADNode(nid);
    return new org.opendaylight.controller.sal.core.NodeConnector(nodeConnectorType, aDNodeConnectorId, aDNode);
  }

  public static String toNodeConnectorType(final NodeConnectorId ncId, final NodeId nodeId) {
    if (ncId.equals(toLocalNodeConnectorId(nodeId))) {
        return NodeConnectorIDType.SWSTACK;
    } else if (ncId.equals(toNormalNodeConnectorId(nodeId))) {
        return NodeConnectorIDType.HWPATH;
    } else if (ncId.equals(toControllerNodeConnectorId(nodeId))) {
        return NodeConnectorIDType.CONTROLLER;
    }
    return MD_SAL_TYPE;
  }

  public static Object toADNodeConnectorId(final NodeConnectorId nodeConnectorId, final NodeId nodeId) {
    if (nodeConnectorId.equals(toLocalNodeConnectorId(nodeId)) ||
        nodeConnectorId.equals(toNormalNodeConnectorId(nodeId)) ||
        nodeConnectorId.equals(toControllerNodeConnectorId(nodeId))) {
        return org.opendaylight.controller.sal.core.NodeConnector.SPECIALNODECONNECTORID;
    }
    return nodeConnectorId.getValue();
  }

  public static NodeConnectorId toControllerNodeConnectorId(final NodeId node) {
    return new NodeConnectorId(node.getValue() + ":" + 4294967293L);
  }

  public static NodeConnectorId toLocalNodeConnectorId(final NodeId node) {
    return new NodeConnectorId(node.getValue() + ":" + 4294967294L);
  }

  public static NodeConnectorId toNormalNodeConnectorId(final NodeId node) {
    return new NodeConnectorId(node.getValue() + ":" + 4294967290L);
  }

  public static NodeRef toNodeRef(final org.opendaylight.controller.sal.core.Node node) {
    Preconditions.checkArgument(MD_SAL_TYPE.equals(node.getType()));
    final String nodeId = Arguments.<String>checkInstanceOf(node.getID(), String.class);
    final NodeKey nodeKey = new NodeKey(new NodeId(nodeId));
    final InstanceIdentifier<Node> nodePath = InstanceIdentifier.builder(Nodes.class).child(NODE_CLASS, nodeKey).toInstance();
    return new NodeRef(nodePath);
  }

  public static NodeConnectorRef toNodeConnectorRef(final org.opendaylight.controller.sal.core.NodeConnector nodeConnector) {

    final NodeRef node = NodeMapping.toNodeRef(nodeConnector.getNode());
    final InstanceIdentifier<Node> nodePath = ((InstanceIdentifier<Node>) node.getValue());
    NodeConnectorId nodeConnectorId = null;

    if (nodeConnector.getID().equals(org.opendaylight.controller.sal.core.NodeConnector.SPECIALNODECONNECTORID)) {
        final NodeId nodeId = toNodeId(nodePath);
        final String nodeConnectorType = nodeConnector.getType();
        if (nodeConnectorType.equals(NodeConnectorIDType.SWSTACK)) {
            nodeConnectorId = toLocalNodeConnectorId(nodeId);
        } else if (nodeConnectorType.equals(NodeConnectorIDType.HWPATH)) {
            nodeConnectorId = toNormalNodeConnectorId(nodeId);
        } else if (nodeConnectorType.equals(NodeConnectorIDType.CONTROLLER)) {
            nodeConnectorId = toControllerNodeConnectorId(nodeId);
        }
    } else {
        nodeConnectorId = new NodeConnectorId(Arguments.<String>checkInstanceOf(nodeConnector.getID(), String.class));
    }
    final NodeConnectorKey connectorKey = new NodeConnectorKey(nodeConnectorId);
    final InstanceIdentifier<NodeConnector> path = nodePath.child(NODECONNECTOR_CLASS, connectorKey);
    return new NodeConnectorRef(path);
  }

  public static org.opendaylight.controller.sal.core.Node toADNode(final NodeRef node) throws ConstructionException {
    return NodeMapping.toADNode(node.getValue());
  }

  public static HashSet<Property> toADNodeConnectorProperties(final NodeConnectorUpdated nc) {
    final FlowCapableNodeConnectorUpdated fcncu = nc.<FlowCapableNodeConnectorUpdated>getAugmentation(FlowCapableNodeConnectorUpdated.class);
    if (!Objects.equal(fcncu, null)) {
      return NodeMapping.toADNodeConnectorProperties(fcncu);
    }
    return new HashSet<Property>();
  }

  public static HashSet<Property> toADNodeConnectorProperties(final NodeConnector nc) {
    final FlowCapableNodeConnector fcnc = nc.<FlowCapableNodeConnector>getAugmentation(FlowCapableNodeConnector.class);
    if (!Objects.equal(fcnc, null)) {
      return NodeMapping.toADNodeConnectorProperties(fcnc);
    }
    return new HashSet<Property>();
  }

  public static HashSet<Property> toADNodeConnectorProperties(final FlowNodeConnector fcncu) {

    final HashSet<org.opendaylight.controller.sal.core.Property> props = new HashSet<>();
    if (fcncu != null) {
        if (fcncu.getCurrentFeature() != null && toAdBandwidth(fcncu.getCurrentFeature()) != null) {
            props.add(toAdBandwidth(fcncu.getCurrentFeature()));
        }
        if (fcncu.getAdvertisedFeatures() != null && toAdAdvertizedBandwidth(fcncu.getAdvertisedFeatures()) != null) {
            props.add(toAdAdvertizedBandwidth(fcncu.getAdvertisedFeatures()));
        }
        if (fcncu.getSupported() != null && toAdSupportedBandwidth(fcncu.getSupported()) != null) {
            props.add(toAdSupportedBandwidth(fcncu.getSupported()));
        }
        if (fcncu.getPeerFeatures() != null && toAdPeerBandwidth(fcncu.getPeerFeatures()) != null) {
            props.add(toAdPeerBandwidth(fcncu.getPeerFeatures()));
        }
        if (fcncu.getName() != null && toAdName(fcncu.getName()) != null) {
            props.add(toAdName(fcncu.getName()));
        }
        if (fcncu.getConfiguration() != null && toAdConfig(fcncu.getConfiguration()) != null) {
            props.add(toAdConfig(fcncu.getConfiguration()));
        }
        if (fcncu.getState() != null && toAdState(fcncu.getState()) != null) {
            props.add(toAdState(fcncu.getState()));
        }
    }
    return props;
  }

  public static Name toAdName(final String name) {
    return new Name(name);
  }

  public static Config toAdConfig(final PortConfig pc) {
    Config config = null;
    if (pc.isPORTDOWN()) {
        config = new Config(Config.ADMIN_DOWN);
    } else {
        config = new Config(Config.ADMIN_UP);
    }
    return config;
  }

  public static org.opendaylight.controller.sal.core.State toAdState(final State s) {

    org.opendaylight.controller.sal.core.State state = null;
    if (s.isLinkDown()) {
        state = new org.opendaylight.controller.sal.core.State(org.opendaylight.controller.sal.core.State.EDGE_DOWN);
    } else {
        state = new org.opendaylight.controller.sal.core.State(org.opendaylight.controller.sal.core.State.EDGE_UP);
    }
    return state;
  }

  public static Bandwidth toAdBandwidth(final PortFeatures pf) {
    Bandwidth bw = null;
    if (pf.isTenMbHd() || pf.isTenMbFd()) {
        bw = new Bandwidth(Bandwidth.BW10Mbps);
    } else if (pf.isHundredMbHd() || pf.isHundredMbFd()) {
        bw = new Bandwidth(Bandwidth.BW100Mbps);
    } else if (pf.isOneGbHd() || pf.isOneGbFd()) {
        bw = new Bandwidth(Bandwidth.BW1Gbps);
    } else if (pf.isOneGbFd()) {
        bw = new Bandwidth(Bandwidth.BW10Gbps);
    } else if (pf.isTenGbFd()) {
        bw = new Bandwidth(Bandwidth.BW10Gbps);
    } else if (pf.isFortyGbFd()) {
        bw = new Bandwidth(Bandwidth.BW40Gbps);
    } else if (pf.isHundredGbFd()) {
        bw = new Bandwidth(Bandwidth.BW100Gbps);
    } else if (pf.isOneTbFd()) {
        bw = new Bandwidth(Bandwidth.BW1Tbps);
    }
    return bw;
  }

  public static AdvertisedBandwidth toAdAdvertizedBandwidth(final PortFeatures pf) {
    AdvertisedBandwidth abw = null;
    final Bandwidth bw = toAdBandwidth(pf);
    if (bw != null) {
        abw = new AdvertisedBandwidth(bw.getValue());
    }
    return abw;
  }

  public static SupportedBandwidth toAdSupportedBandwidth(final PortFeatures pf) {
    SupportedBandwidth sbw = null;
    final Bandwidth bw = toAdBandwidth(pf);
    if (bw != null) {
        sbw = new SupportedBandwidth(bw.getValue());
    }
    return sbw;
  }

  public static PeerBandwidth toAdPeerBandwidth(final PortFeatures pf) {
    PeerBandwidth pbw = null;
    final Bandwidth bw = toAdBandwidth(pf);
    if (bw != null) {
        pbw = new PeerBandwidth(bw.getValue());
    }
    return pbw;
  }

  public static HashSet<Property> toADNodeProperties(final NodeUpdated nu) {
    final FlowCapableNodeUpdated fcnu = nu.getAugmentation(FlowCapableNodeUpdated.class);
    if (fcnu != null) {
        return toADNodeProperties(fcnu, nu.getId());
    }
    return new HashSet<org.opendaylight.controller.sal.core.Property>();
  }

  public static HashSet<Property> toADNodeProperties(final FlowNode fcnu, final NodeId id) {

    final HashSet<org.opendaylight.controller.sal.core.Property> props = new HashSet<>();

    if (fcnu != null) {
        props.add(toADTimestamp());

        // props.add(fcnu.supportedActions.toADActions) - TODO
        if (id != null) {
            props.add(toADMacAddress(id));
        }
        SwitchFeatures switchFeatures = fcnu.getSwitchFeatures();
        if (switchFeatures != null) {
            if (switchFeatures.getMaxTables() != null) {
                props.add(toADTables(switchFeatures.getMaxTables()));
            }
            if (switchFeatures.getCapabilities() != null) {
                props.add(toADCapabiliities(switchFeatures.getCapabilities()));
            }
            if (switchFeatures.getMaxBuffers() != null) {
                props.add(toADBuffers(switchFeatures.getMaxBuffers()));
            }
        }
    }
    return props;
  }

  public static TimeStamp toADTimestamp() {
    final Date date = new Date();
    final TimeStamp timestamp = new TimeStamp(date.getTime(), "connectedSince");
    return timestamp;
  }

  public static MacAddress toADMacAddress(final NodeId id) {
    final String nodeId = id.getValue().replaceAll("openflow:", "");
    long lNodeId = Long.parseLong(nodeId);
    lNodeId = Long.valueOf(lNodeId).longValue();
    byte[] bytesFromDpid = ToSalConversionsUtils.bytesFromDpid(lNodeId);
    return new MacAddress(bytesFromDpid);
  }

  public static Tables toADTables(final Short tables) {
    return new Tables(tables.byteValue());
  }

  public static Capabilities toADCapabiliities(final List<Class<? extends FeatureCapability>> capabilities) {

    int b = 0;
    for (Class<? extends FeatureCapability> capability : capabilities) {
        if (capability.equals(FlowFeatureCapabilityFlowStats.class)) {
            b = Capabilities.CapabilitiesType.FLOW_STATS_CAPABILITY.getValue() | b;
        } else if (capability.equals(FlowFeatureCapabilityTableStats.class)) {
            b = Capabilities.CapabilitiesType.TABLE_STATS_CAPABILITY.getValue() | b;
        } else if (capability.equals(FlowFeatureCapabilityPortStats.class)) {
            b = Capabilities.CapabilitiesType.PORT_STATS_CAPABILITY.getValue() | b;
        } else if (capability.equals(FlowFeatureCapabilityStp.class)) {
            b = Capabilities.CapabilitiesType.STP_CAPABILITY.getValue() | b;
        } else if (capability.equals(FlowFeatureCapabilityIpReasm.class)) {
            b = Capabilities.CapabilitiesType.IP_REASSEM_CAPABILITY.getValue() | b;
        } else if (capability.equals(FlowFeatureCapabilityQueueStats.class)) {
            b = Capabilities.CapabilitiesType.QUEUE_STATS_CAPABILITY.getValue() | b;
        } else if (capability.equals(FlowFeatureCapabilityArpMatchIp.class)) {
            b = Capabilities.CapabilitiesType.ARP_MATCH_IP_CAPABILITY.getValue() | b;
        }
    }
    return new Capabilities(b);
  }

  public static Buffers toADBuffers(final Long buffers) {
    return new Buffers(buffers.intValue());
  }
}
