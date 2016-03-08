package org.opendaylight.myctrlapp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.opendaylight.controller.clustering.services.CacheConfigException;
import org.opendaylight.controller.clustering.services.CacheExistException;
import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.clustering.services.IClusterServices;
import org.opendaylight.controller.hosttracker.hostAware.HostNodeConnector;
import org.opendaylight.controller.sal.action.Drop;
import org.opendaylight.controller.sal.core.ConstructionException;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.core.NodeConnector;
import org.opendaylight.controller.sal.flowprogrammer.Flow;
import org.opendaylight.controller.sal.flowprogrammer.IFlowProgrammerService;
import org.opendaylight.controller.sal.flowprogrammer.IPluginInFlowProgrammerService;
import org.opendaylight.controller.sal.match.Match;
import org.opendaylight.controller.sal.match.MatchType;
import org.opendaylight.controller.sal.packet.Ethernet;
import org.opendaylight.controller.sal.packet.IDataPacketService;
import org.opendaylight.controller.sal.packet.IListenDataPacket;
import org.opendaylight.controller.sal.packet.ARP;
import org.opendaylight.controller.sal.packet.Packet;
import org.opendaylight.controller.sal.packet.PacketResult;
import org.opendaylight.controller.sal.packet.RawPacket;
import org.opendaylight.controller.sal.utils.EtherTypes;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.switchmanager.ISwitchManager;
import org.opendaylight.controller.sal.connection.IPluginOutConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketHandler implements IListenDataPacket {

    private static final Logger logger = LoggerFactory
            .getLogger(PacketHandler.class);
    private IDataPacketService dataPacketService;
    private final int NUMBER_CORES = 4;
    private final int MIN_WORKING_THREADS=4; 
    private IFlowProgrammerService flowProgrammerService;
    private ISwitchManager switchManager;
    private List dropAction;
    private IPluginOutConnectionService connectionService;
    private int service_delay;
    private IClusterGlobalServices clusterServices;
    private ArrayBlockingQueue<Runnable> packetsQueueTasks;
    private ThreadPoolExecutor executorService;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void init() {
        logger.trace("myCtrlApp is being initialized");
        // List of actions applied to the packet
        dropAction = new LinkedList();

        // Re-write destination IP to server instance IP
        dropAction.add(new Drop());
        service_delay = 0;
        String s = System.getProperty("myContrApp.service_delay");
        if (s != null)
            try {
                service_delay = Integer.parseInt(s);
            } catch (Exception e) {
                logger.warn("the service delay value {} is not valid. "
                        + "The service delay will be set to 0.", s);
            }
        packetsQueueTasks = new ArrayBlockingQueue<Runnable>(10000000,true);

        executorService = new ThreadPoolExecutor(MIN_WORKING_THREADS, NUMBER_CORES, 60,
                TimeUnit.SECONDS, packetsQueueTasks);

    }

    private class PacketInTask implements Runnable {
        private PacketHandler ph;
        private RawPacket packet;

        private PacketInTask(PacketHandler packetHandler, RawPacket inPkt) {
            this.ph = packetHandler;
            this.packet = inPkt;
        }

        @Override
        public void run() {
            ph.processIt(packet);
        }

    }

    /*
     * public void start(){ log.trace("myCtrlApp is being started"); }
     */
    static private InetAddress intToInetAddress(int i) {
        byte b[] = new byte[] { (byte) ((i >> 24) & 0xff),
                (byte) ((i >> 16) & 0xff), (byte) ((i >> 8) & 0xff),
                (byte) (i & 0xff) };
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }

        return addr;
    }

    static private String bytesToMACAddress(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X%s", mac[i],
                    (i < mac.length - 1) ? "-" : ""));
        }

        return sb.toString();
    }

    private long inetToInt(InetAddress a) {
        long sum = 0;
        byte[] bytes = a.getAddress();
        int len = bytes.length;
        int base = (len == 4) ? 256 : 65536;
        for (int i = 0; i < len; i++) {
            int tmp = (int) bytes[i] & 0xFF;
            sum += tmp * (long) Math.pow(base, (len - i - 1));

        }
        // log.warn("returning {}",sum);
        return sum;
    }

    /*
     * Sets a reference to the requested DataPacketService See
     * Activator.configureInstance(...):
     * c.add(createContainerServiceDependency(containerName).setService(
     * IDataPacketService.class).setCallbacks( "setDataPacketService",
     * "unsetDataPacketService") .setRequired(true));
     */
    void setDataPacketService(IDataPacketService s) {
        logger.trace("Set DataPacketService.");

        dataPacketService = s;
    }

    /*
     * Unsets DataPacketService See Activator.configureInstance(...):
     * c.add(createContainerServiceDependency(containerName).setService(
     * IDataPacketService.class).setCallbacks( "setDataPacketService",
     * "unsetDataPacketService") .setRequired(true));
     */
    void unsetDataPacketService(IDataPacketService s) {
        logger.trace("Removed DataPacketService.");

        if (dataPacketService == s) {
            dataPacketService = null;
        }
    }

    /**
     * Sets a reference to the requested FlowProgrammerService
     */
    void setFlowProgrammerService(IFlowProgrammerService s) {
        logger.trace("Set FlowProgrammerService.");

        flowProgrammerService = s;
    }

    /**
     * Unsets FlowProgrammerService
     */
    void unsetFlowProgrammerService(IFlowProgrammerService s) {
        logger.trace("Removed FlowProgrammerService.");

        if (flowProgrammerService == s) {
            flowProgrammerService = null;
        }
    }

    /**
     * Sets a reference to the requested SwitchManagerService
     */
    void setSwitchManagerService(ISwitchManager s) {
        logger.trace("Set SwitchManagerService.");

        switchManager = s;
    }

    /**
     * Unsets SwitchManagerService
     */
    void unsetSwitchManagerService(ISwitchManager s) {
        logger.trace("Removed SwitchManagerService.");

        if (switchManager == s) {
            switchManager = null;
        }
    }

    public void setIPluginOutConnectionService(IPluginOutConnectionService s) {
        logger.trace("Set IPluginOutConnectionService.");

        connectionService = s;
    }

    public void unsetIPluginOutConnectionService(IPluginOutConnectionService s) {
        logger.trace("unSet IPluginOutConnectionService.");

        if (connectionService == s) {
            connectionService = null;
        }
    }

    public void setClusterServices(IClusterGlobalServices i) {
        logger.debug("Got cluster service set request {}", i);
        this.clusterServices = i;
    }

    public void unsetClusterServices(IClusterGlobalServices i) {
        logger.debug("Got cluster service unset request {}", i);
        if (this.clusterServices == i) {
            this.clusterServices = null;
        }
    }

    @Override
    public PacketResult receiveDataPacket(RawPacket inPkt) {
        logger.trace("Received a data packet");
        //executorService.submit(new PacketInTask(this, inPkt));
        processIt(inPkt);
        return PacketResult.CONSUME;
    }

    void processIt(RawPacket inPkt) {
        // The connector, the packet came from ("port")
        NodeConnector ingressConnector = inPkt.getIncomingNodeConnector();
        // The node that received the packet ("switch")
        Node node = ingressConnector.getNode();

        // Use DataPacketService to decode the packet.
        Packet l2pkt = dataPacketService.decodeDataPacket(inPkt);

        if (l2pkt instanceof Ethernet) {
            Ethernet ethPkt = (Ethernet) l2pkt;
            Object l3Pkt = l2pkt.getPayload();
            byte[] srcMAC = ethPkt.getSourceMACAddress();
            byte[] dstMAC = ethPkt.getDestinationMACAddress();
            if (l3Pkt instanceof ARP) {
                ARP arpPkt = (ARP) l3Pkt;
                InetAddress targetIP = null, sourceIP = null;
                try {
                    targetIP = InetAddress.getByAddress(arpPkt
                            .getTargetProtocolAddress());

                    sourceIP = InetAddress.getByAddress(arpPkt
                            .getSenderProtocolAddress());
                } catch (UnknownHostException e1) {
                    logger.debug("Invalid host in ARP packet: {}",
                            e1.getMessage());
                }
                logger.trace(
                        "Received ARP pkt. from nodeID={} on connector={}  "
                                + "srcMAC={} dstMAC={} *****sourceIP={} targetIP={}******",
                        node.getNodeIDString(), ingressConnector,
                        bytesToMACAddress(srcMAC), bytesToMACAddress(dstMAC),
                        sourceIP, targetIP);
                // log.warn("Received ARP pkt. to \n>>>>>>>>>>>>{}\t\tisLocal={}",targetIP,connectionService.isLocal(inPkt.getIncomingNodeConnector().getNode()));
                // if
                // (!connectionService.isLocal(inPkt.getIncomingNodeConnector().getNode()))
                // return PacketResult.KEEP_PROCESSING;
                if (targetIP != null
                        && !targetIP.getHostAddress().startsWith("10.")) {
                    if (service_delay != 0) {
                        busyWait(service_delay);
                    }
                    logger.trace("packets to {} will be dropped",
                            targetIP.getHostAddress());
                    Status status = createFlowAndInstall(node, sourceIP,
                            targetIP);
                    if (!status.isSuccess()) {
                        logger.info("Could not program the flow in sw={} due to: {}",
                                node,status.getDescription());
                        return;
                    }
                }
                return;
            }
            try {
                RawPacket outPkt = new RawPacket(inPkt);
                // outPkt.setOutgoingNodeConnector(p);
            } catch (ConstructionException e) {
                logger.warn("cannot create the output packet: {}", e);
            }

        }

    }

    private void busyWait(long interval) {
        long start = System.nanoTime();
        long end = 0;
        do {
            end = System.nanoTime();
        } while (start + interval >= end);
    }

    private Status createFlowAndInstall(Node node, InetAddress src,
            InetAddress dst) {
        // Create flow table entry for further incoming packets

        // (4 tuple source IP, source port, destination IP, destination port)
        Match match = new Match();
        match.setField(MatchType.DL_TYPE, EtherTypes.IPv4.shortValue()); // IP
                                                                         // packet
        match.setField(MatchType.NW_SRC, src);
        match.setField(MatchType.NW_DST, dst);

        // Create the flow
        Flow flow = new Flow(match, dropAction);
        flow.setHardTimeout((short) 60); // after 60 sec it will be removed

        // Use FlowProgrammerService to program flow.
        return flowProgrammerService.addFlowAsync(node, flow);
        /*
         */
    }

}
