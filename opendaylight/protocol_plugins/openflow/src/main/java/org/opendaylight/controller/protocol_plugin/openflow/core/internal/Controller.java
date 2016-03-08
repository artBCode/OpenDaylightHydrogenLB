/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.protocol_plugin.openflow.core.internal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.osgi.framework.console.CommandInterpreter;
import org.eclipse.osgi.framework.console.CommandProvider;
import org.opendaylight.controller.clustering.services.CacheConfigException;
import org.opendaylight.controller.clustering.services.CacheExistException;
import org.opendaylight.controller.clustering.services.ICacheUpdateAware;
import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.clustering.services.IClusterServices;
import org.opendaylight.controller.protocol_plugin.openflow.core.IController;
import org.opendaylight.controller.protocol_plugin.openflow.core.IMessageListener;
import org.opendaylight.controller.protocol_plugin.openflow.core.ISwitch;
import org.opendaylight.controller.protocol_plugin.openflow.core.ISwitchStateListener;
import org.opendaylight.controller.protocol_plugin.openflow.core.internal.SwitchEvent.SwitchEventType;
import org.opendaylight.controller.protocol_plugin.openflow.internal.FlowConverter;
import org.opendaylight.controller.protocol_plugin.openflow.migration.IControllerMigrationService;
import org.opendaylight.controller.protocol_plugin.openflow.migration.ILocalityMigrationCore;
import org.opendaylight.controller.protocol_plugin.openflow.migration.LoadStatistics;
import org.opendaylight.controller.protocol_plugin.openflow.migration.MultiThreadedServer;
import org.opendaylight.controller.protocol_plugin.openflow.migration.NodeBridge;
import org.opendaylight.controller.protocol_plugin.openflow.migration.SwMigrInfo;
import org.opendaylight.controller.protocol_plugin.openflow.migration.Util;
import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.connection.IPluginInConnectionService;
import org.opendaylight.controller.sal.core.ConstructionException;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.flowprogrammer.Flow;
import org.opendaylight.controller.sal.flowprogrammer.IPluginInFlowProgrammerService;
import org.opendaylight.controller.sal.packet.ARP;
import org.opendaylight.controller.sal.packet.Ethernet;
import org.opendaylight.controller.sal.packet.IDataPacketService;
import org.opendaylight.controller.sal.packet.Packet;
import org.opendaylight.controller.sal.packet.RawPacket;
import org.opendaylight.controller.sal.utils.NodeCreator;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.util.HexString;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.management.OperatingSystemMXBean;

public class Controller implements IController, CommandProvider,
        IPluginInConnectionService, IControllerMigrationService,
        ILocalityMigrationCore, ICacheUpdateAware<InetAddress, Long>,
        ISwitchStateListener {
    private static final Logger logger = LoggerFactory
            .getLogger(Controller.class);
    private ControllerIO controllerIO;
    private Thread switchEventThread;
    private volatile boolean shutdownSwitchEventThread;// default to
                                                       // false
    private ConcurrentHashMap<Long, ISwitch> switches;
    private PriorityBlockingQueue<SwitchEvent> switchEvents;
    // only 1 message listener per OFType
    private ConcurrentMap<OFType, IMessageListener> messageListeners;
    // only 1 switch state listener
    private ISwitchStateListener switchStateListener;
    private AtomicInteger switchInstanceNumber;
    private int MAXQUEUESIZE = 50000;
    private IClusterGlobalServices clusterServices;
    public final static String endMigrationCacheName = "connectionmanager.loadbalancer.endMigration";
    public final static String loadStatisticsCacheName = "connectionmanager.loadbalancer.loadStatistics";
    public final static String loadStatisticsRequestCacheName = "connectionmanager.loadbalancer.loadStatisticsRequest";
    private ConcurrentMap<InetAddress, LoadStatistics> contrLoadStatistics;
    private ConcurrentMap<InetAddress, Long> contrLoadStatisticsRequest;
    /** map number msgs entry=(swID,no_msgs) */
    private ConcurrentHashMap<Long, Long> swCounterReq;
    private IPluginInFlowProgrammerService flowProgramServ;
    private InetAddress myAddr;
    private final Short matchDummyFlowPortNr = new Short(
            (short) 12508);
    // next 2 fields: for better performance & no duplicated fmod
    // answers in
    // case of failure
    private final String nodeControllersCacheName = "connectionmanager.LOAD_BALANCED.nodeconnections";
    private ConcurrentMap<Node, Set<InetAddress>> nodeMasterControllers;
    private InetAddress REQ_STATISTICS_ADDRESS;
    private InetAddress MY_ADDRESS;
    @SuppressWarnings("restriction")
    private OperatingSystemMXBean osBean = ManagementFactory
            .getPlatformMXBean(OperatingSystemMXBean.class);
    private static final int CPU_SAMPLING_INTERVAL = 10; // ms
    private AtomicLong nrSamples;
    private AtomicLong sumCpuLoad; // the cpuLoad is between 0 and 100
    // the management impact of a slave switch wrt the management of a
    // master
    private final float PERC_LOAD_NON_MASTER = 0.4f;
    private IDataPacketService dataPacketService;
    private OFPacketIn lastPinMsg = null;
    private long timeLastStatistics = 0;
    public final static String physicalConnectionsCacheName = "connectionmanager.loadbalancer.physicalConn";
    private ConcurrentMap<NodeBridge, Set<InetAddress>> bridgeContrPhysicalConn;

    private static enum SwitchEventPriority {
        LOW, NORMAL, HIGH
    }

    /**
     * contains informations about pending migrations of switches
     * indicated by the keys
     */
    private HashMap<Node, SwMigrInfo> nodeMigrInfo;

    /*
     * this thread monitors the switchEvents queue for new incoming
     * events from switch
     */
    private class EventHandler implements Runnable {
        @Override
        public void run() {

            while (true) {
                try {
                    if (shutdownSwitchEventThread) {
                        // break out of the infinite loop
                        // if you are shutting down
                        logger.info("Switch Event Thread is shutting down");
                        break;
                    }
                    SwitchEvent ev = switchEvents.take();
                    SwitchEvent.SwitchEventType eType = ev
                            .getEventType();
                    ISwitch sw = ev.getSwitch();

                    if (switchEvents.size() > 100000
                            && switchEvents.size() % 100000 == 0)
                        logger.warn(
                                "There are still other {} events in the incomming queue.",
                                switchEvents.size());
                    switch (eType) {
                    case SWITCH_ADD:
                        Long sid = sw.getId();
                        ISwitch existingSwitch = switches.get(sid);
                        if (existingSwitch != null) {
                            logger.warn(
                                    "Replacing existing {} with New {}",
                                    existingSwitch, sw);
                            disconnectSwitch(existingSwitch);
                        }
                        switches.put(sid, sw);
                        notifySwitchAdded(sw);
                        break;
                    case SWITCH_DELETE:
                        disconnectSwitch(sw);
                        break;
                    case SWITCH_ERROR:
                        disconnectSwitch(sw);
                        break;
                    case SWITCH_MESSAGE:
                        OFMessage msg = ev.getMsg();
                        if (msg instanceof OFFlowRemoved) {
                            handleAPotentialDummyFlowRemoval(sw,
                                    (OFFlowRemoved) msg);
                        } else if (msg instanceof OFPacketIn) {
                            if (lastPinMsg != null) {
                                synchronized (lastPinMsg) {
                                    lastPinMsg = (OFPacketIn) msg;
                                }
                            } else {
                                lastPinMsg = (OFPacketIn) msg;
                            }

                        }
                        if (msg != null) {
                            IMessageListener listener = messageListeners
                                    .get(msg.getType());
                            if (listener != null) {
                                listener.receive(sw, msg);
                            }
                        }
                        break;
                    default:
                        logger.error("Unknown switch event {}",
                                eType.ordinal());
                    }
                } catch (InterruptedException e) {
                    // nothing to do except retry
                } catch (Exception e) {
                    // log the exception and retry
                    logger.warn(
                            "Exception in Switch Event Thread is {}",
                            Util.exceptionToString(e));
                }
            }
            switchEvents.clear();
        }
    }

    /**
     * Function called by the dependency manager when all the required
     * dependencies are satisfied
     * 
     */
    public void init() {
        logger.debug("Initializing!");
        this.nodeMigrInfo = new HashMap<Node, SwMigrInfo>();
        this.switches = new ConcurrentHashMap<Long, ISwitch>();
        this.switchEvents = new PriorityBlockingQueue<SwitchEvent>(
                MAXQUEUESIZE, new Comparator<SwitchEvent>() {
                    @Override
                    public int compare(SwitchEvent p1, SwitchEvent p2) {
                        int i;
                        i = p2.getPriority() - p1.getPriority();
                        if (i != 0)
                            return i;
                        if (p2.getArrivalID() == p1.getArrivalID())
                            return 0;
                        if (p2.getArrivalID() < p1.getArrivalID())
                            return 1;
                        return -1;

                    }
                });
        this.messageListeners = new ConcurrentHashMap<OFType, IMessageListener>();
        this.switchStateListener = null;
        this.switchInstanceNumber = new AtomicInteger(0);
        this.swCounterReq = new ConcurrentHashMap<Long, Long>(100,
                0.9f, 100);
        allocateCaches();
        new Thread(new MultiThreadedServer(8088, this),
                "TCP_migration_server").start();
        ;
        registerWithOSGIConsole();
        retrieveCacheNodeContr();
        retrieveCacheLoadStatistics();
        retrieveCacheLoadStatisticsRequest();
        retrievePhysicalConnectionsCache();
        try {
            REQ_STATISTICS_ADDRESS = InetAddress.getByName("0.0.0.0");
        } catch (UnknownHostException e) {
            logger.warn(
                    "cannot define the ip address of a statistic request e={}",
                    Util.exceptionToString(e));
        }
        MY_ADDRESS = clusterServices.getMyAddress();
        nrSamples = new AtomicLong(0);
        sumCpuLoad = new AtomicLong(0);
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @SuppressWarnings("restriction")
            @Override
            public void run() {
                double loadNow = osBean.getSystemCpuLoad() * 100;
                nrSamples.incrementAndGet();
                sumCpuLoad.addAndGet((long) loadNow);
            }
        }, CPU_SAMPLING_INTERVAL, CPU_SAMPLING_INTERVAL);
    }

    /**
     * Function called by dependency manager after "init ()" is called
     * and after the services provided by the class are registered in
     * the service registry
     * 
     */
    public void start() {
        logger.debug("Starting!");
        /*
         * start a thread to handle event coming from the switch
         */
        switchEventThread = new Thread(new EventHandler(),
                "SwitchEvent Thread");
        switchEventThread.start();

        // spawn a thread to start to listen on the open flow port
        controllerIO = new ControllerIO(this);
        try {
            controllerIO.start();
        } catch (IOException ex) {
            logger.error("Caught exception while starting: {}",
                    Util.exceptionToString(ex));
        }
    }

    /**
     * Function called by the dependency manager before the services
     * exported by the component are unregistered, this will be
     * followed by a "destroy ()" calls
     * 
     */
    public void stop() {
        for (Iterator<Entry<Long, ISwitch>> it = switches.entrySet()
                .iterator(); it.hasNext();) {
            Entry<Long, ISwitch> entry = it.next();
            ((SwitchHandler) entry.getValue()).stop();
            it.remove();
        }
        shutdownSwitchEventThread = true;
        switchEventThread.interrupt();
        try {
            controllerIO.shutDown();
        } catch (IOException ex) {
            logger.error("Caught exception while stopping: {}",
                    Util.exceptionToString(ex));
        }
    }

    /**
     * Function called by the dependency manager when at least one
     * dependency become unsatisfied or when the component is shutting
     * down because for example bundle is being stopped.
     * 
     */
    public void destroy() {
    }

    @Override
    public void addMessageListener(OFType type,
            IMessageListener listener) {
        IMessageListener currentListener = this.messageListeners
                .get(type);
        if (currentListener != null) {
            logger.warn("{} is already listened by {}", type,
                    currentListener);
        }
        this.messageListeners.put(type, listener);
        logger.debug("{} is now listened by {}", type, listener);
    }

    @Override
    public void removeMessageListener(OFType type,
            IMessageListener listener) {
        IMessageListener currentListener = this.messageListeners
                .get(type);
        if ((currentListener != null)
                && (currentListener == listener)) {
            logger.debug("{} listener {} is Removed", type, listener);
            this.messageListeners.remove(type);
        }
    }

    @Override
    public void addSwitchStateListener(ISwitchStateListener listener) {
        if (this.switchStateListener != null) {
            logger.warn("Switch events are already listened by {}",
                    this.switchStateListener);
        }
        this.switchStateListener = listener;
        logger.debug("Switch events are now listened by {}", listener);
    }

    @Override
    public void removeSwitchStateListener(
            ISwitchStateListener listener) {
        if ((this.switchStateListener != null)
                && (this.switchStateListener == listener)) {
            logger.debug("SwitchStateListener {} is Removed",
                    listener);
            this.switchStateListener = null;
        }
    }

    public void handleNewConnection(Selector selector,
            SelectionKey serverSelectionKey) {
        logger.trace("handleNewConnection from a switch. A thread new thread will be started");
        ServerSocketChannel ssc = (ServerSocketChannel) serverSelectionKey
                .channel();
        SocketChannel sc = null;
        try {
            sc = ssc.accept();
            // create new switch
            int i = this.switchInstanceNumber.addAndGet(1);
            String instanceName = "SwitchHandler-" + i;
            SwitchHandler switchHandler = new SwitchHandler(this, sc,
                    instanceName);
            switchHandler.start();
            if (sc.isConnected()) {
                logger.info(
                        "Switch:{} is connected to the Controller",
                        sc.socket().getRemoteSocketAddress()
                                .toString().split("/")[1]);
            }

        } catch (IOException e) {
            logger.warn(Util.exceptionToString(e));
            return;
        }
    }

    private void disconnectSwitch(ISwitch sw) {
        if (((SwitchHandler) sw).isOperational()) {
            Long sid = sw.getId();
            if (this.switches.remove(sid, sw)) {
                logger.info("{} is removed", sw);
                notifySwitchDeleted(sw);
            }
        }
        ((SwitchHandler) sw).stop();
        sw = null;
    }

    private void notifySwitchAdded(ISwitch sw) {
        logger.debug(
                "The sw having id={} has been added to the local controller",
                sw.getId());
        if (switchStateListener != null) {
            switchStateListener.switchAdded(sw);
        }
    }

    private void notifySwitchDeleted(ISwitch sw) {
        logger.debug(
                "The sw having id={} has been deleted from the local controller",
                sw.getId());
        if (switchStateListener != null) {
            switchStateListener.switchDeleted(sw);
        }
    }

    private synchronized void addSwitchEvent(SwitchEvent event,
            Long swid) {
        this.switchEvents.put(event);
        logger.debug("The event={} has just been queued", event);

    }

    public void takeSwitchEventAdd(ISwitch sw) {
        SwitchEvent ev = new SwitchEvent(
                SwitchEvent.SwitchEventType.SWITCH_ADD, sw, null,
                SwitchEventPriority.HIGH.ordinal());
        addSwitchEvent(ev, sw.getId());
    }

    public void takeSwitchEventDelete(ISwitch sw) {
        SwitchEvent ev = new SwitchEvent(
                SwitchEvent.SwitchEventType.SWITCH_DELETE, sw, null,
                SwitchEventPriority.HIGH.ordinal());
        addSwitchEvent(ev, sw.getId());
    }

    public void takeSwitchEventError(ISwitch sw) {
        SwitchEvent ev = new SwitchEvent(
                SwitchEvent.SwitchEventType.SWITCH_ERROR, sw, null,
                SwitchEventPriority.NORMAL.ordinal());
        addSwitchEvent(ev, sw.getId());
    }

    /**
     * Function called when a new message arrives. It queues the
     * message if the controller is the master for sw.
     */
    public void takeSwitchEventMsg(ISwitch sw, OFMessage msg) {
        Node node = NodeCreator.createOFNode(sw.getId());
        long swid = sw.getId().longValue();
        Long cntReq = this.swCounterReq.get(swid);
        if (cntReq == null)
            swCounterReq.put(swid, new Long(1));
        else
            swCounterReq.put(swid, cntReq + 1);
        if (!nodeMigrInfo.containsKey(node)
                && !nodeMasterControllers.containsKey(node)) {
            return;
        }
        if (messageListeners.get(msg.getType()) != null) {
            SwitchEvent ev = new SwitchEvent(
                    SwitchEvent.SwitchEventType.SWITCH_MESSAGE, sw,
                    msg, SwitchEventPriority.LOW.ordinal());

            addSwitchEvent(ev, swid);
        }
    }

    @Override
    public Map<Long, ISwitch> getSwitches() {
        return this.switches;
    }

    @Override
    public ISwitch getSwitch(Long switchId) {
        return this.switches.get(switchId);
    }

    public void _controllerShowQueueSize(CommandInterpreter ci) {
        ci.print("switchEvents queue size: " + switchEvents.size()
                + "\n");
    }

    public void _controllerShowSwitches(CommandInterpreter ci) {
        Set<Long> sids = switches.keySet();
        StringBuffer s = new StringBuffer();
        int size = sids.size();
        if (size == 0) {
            ci.print("switches: empty");
            return;
        }
        Iterator<Long> iter = sids.iterator();
        s.append("Total: " + size + " switches\n");
        while (iter.hasNext()) {
            Long sid = iter.next();
            Date date = switches.get(sid).getConnectedDate();
            String switchInstanceName = ((SwitchHandler) switches
                    .get(sid)).getInstanceName();
            s.append(switchInstanceName + "/"
                    + HexString.toHexString(sid)
                    + " connected since " + date.toString() + "\n");
        }
        ci.print(s.toString());
        return;
    }

    public void _controllerReset(CommandInterpreter ci) {
        ci.print("...Disconnecting the communication to all switches...\n");
        stop();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
        } finally {
            ci.print("...start to accept connections from switches...\n");
            start();
        }
    }

    public void _controllerShowConnConfig(CommandInterpreter ci) {
        String str = System.getProperty("secureChannelEnabled");
        if ((str != null) && (str.trim().equalsIgnoreCase("true"))) {
            ci.print("The Controller and Switch should communicate through TLS connetion.\n");

            String keyStoreFile = System
                    .getProperty("controllerKeyStore");
            String trustStoreFile = System
                    .getProperty("controllerTrustStore");
            if ((keyStoreFile == null)
                    || keyStoreFile.trim().isEmpty()) {
                ci.print("controllerKeyStore not specified\n");
            } else {
                ci.print("controllerKeyStore=" + keyStoreFile + "\n");
            }
            if ((trustStoreFile == null)
                    || trustStoreFile.trim().isEmpty()) {
                ci.print("controllerTrustStore not specified\n");
            } else {
                ci.print("controllerTrustStore=" + trustStoreFile
                        + "\n");
            }
        } else {
            ci.print("The Controller and Switch should communicate through TCP connetion.\n");
        }
    }

    private void registerWithOSGIConsole() {
        BundleContext bundleContext = FrameworkUtil.getBundle(
                this.getClass()).getBundleContext();
        bundleContext.registerService(
                CommandProvider.class.getName(), this, null);
    }

    @Override
    public String getHelp() {
        StringBuffer help = new StringBuffer();
        help.append("---Open Flow Controller---\n");
        help.append("\t controllerShowSwitches\n");
        help.append("\t controllerReset\n");
        help.append("\t controllerShowConnConfig\n");
        help.append("\t controllerShowQueueSize\n");
        return help.toString();
    }

    @Override
    public Status disconnect(Node node) {
        ISwitch sw = getSwitch((Long) node.getID());
        if (sw != null)
            disconnectSwitch(sw);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Node connect(String connectionIdentifier,
            Map<ConnectionConstants, String> params) {
        return null;
    }

    /**
     * View Change notification
     */
    public void notifyClusterViewChanged() {
        for (ISwitch sw : switches.values()) {
            notifySwitchAdded(sw);
        }
    }

    /**
     * Node Disconnected from the node's master controller.
     */
    @Override
    public void notifyNodeDisconnectFromMaster(Node node) {
        ISwitch sw = switches.get((Long) node.getID());
        if (sw != null)
            notifySwitchAdded(sw);
    }

    @Override
    public synchronized boolean migrationInProgressON(Node node,
            InetAddress srcAddr, InetAddress finalContr,
            boolean initiallyMaster, BufferedWriter out) {
        logger.debug(
                "Migration in progress for node={} from {} to {} initiallyMaster={} nodemigrInfo={}",
                node, srcAddr, finalContr, initiallyMaster,
                nodeMigrInfo);
        if (myAddr == null) {
            if (initiallyMaster)
                myAddr = srcAddr;
            else
                myAddr = finalContr;
        }

        if (nodeMigrInfo.containsKey(node)) {
            logger.warn(
                    "there is another pending migration for node {}",
                    node);
            return false;
        }
        SwMigrInfo smi = new SwMigrInfo(node, srcAddr, finalContr,
                initiallyMaster, out);

        nodeMigrInfo.put(node, smi);

        return true;
    }

    public String getLastProcessedPacketIn() {
        OFPacketIn lastPin = null;
        if (lastPinMsg != null)
            synchronized (lastPinMsg) {
                lastPin = lastPinMsg;
            }
        String returnVal = "";
        if (lastPin == null)
            return returnVal;
        RawPacket inPkt = null;
        InetAddress targetIP = null, sourceIP = null;
        try {
            inPkt = new RawPacket(lastPin.getPacketData());
        } catch (ConstructionException e) {
            logger.warn(Util.exceptionToString(e));
        }
        if (inPkt == null) {
            return returnVal;
        }
        Packet l2pkt = dataPacketService.decodeDataPacket(inPkt);

        if (l2pkt instanceof Ethernet) {
            Ethernet ethPkt = (Ethernet) l2pkt;
            Object l3Pkt = l2pkt.getPayload();
            logger.trace("getFirstQueuedPacketIn checking packet={}",
                    l3Pkt);
            if (l3Pkt instanceof ARP) {
                ARP arpPkt = (ARP) l3Pkt;

                try {
                    targetIP = InetAddress.getByAddress(arpPkt
                            .getTargetProtocolAddress());

                    sourceIP = InetAddress.getByAddress(arpPkt
                            .getSenderProtocolAddress());
                } catch (UnknownHostException e1) {
                    logger.debug("Invalid host in ARP packet: {}",
                            Util.exceptionToString(e1));
                }
                logger.trace("Found packetIn + ARP pkt.   "
                        + " *****sourceIP={} targetIP={}******",
                        sourceIP, targetIP);

                if (targetIP != null
                        && sourceIP != null
                        && (targetIP.getHostAddress().startsWith("1") || targetIP
                                .getHostAddress().startsWith("2"))) {

                    returnVal = sourceIP.getHostAddress() + " "
                            + targetIP.getHostAddress() + " "
                            + switchEvents.size();

                }

            }
        }

        return returnVal;
    }

    @Override
    public synchronized boolean migrationInProgressOFF(Node node) {
        nodeMigrInfo.remove(node);
        return true;
    }

    @Override
    public boolean installAndRemoveDummyFlow(Node node) {
        /**
         * the setOFController method from the ovsdb connection
         * service is asynchronous and non blocking so the switch may
         * non be connected when this method is called. For this
         * reason I retry to install the dummyflow for 2 sec. In the
         * install and removal operation timeout may happen if the
         * controller is high loaded
         */
        Flow dummy = nodeMigrInfo.get(node).getDummyFlow();
        int retrialLeft = 400;
        Status s = flowProgramServ.addFlow(node, dummy);
        boolean success = s.isSuccess()
                || s.getCode() == StatusCode.TIMEOUT;
        while (!success && retrialLeft > 0) {
            s = flowProgramServ.addFlow(node, dummy);
            success = s.isSuccess()
                    || s.getCode() == StatusCode.TIMEOUT;
            if (!success)
                logger.debug(
                        "tried to install the dummy flow in node={} but failed:{}",
                        node, s.getDescription());
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
            }
            retrialLeft--;
        }
        if (!success) {
            logger.warn(
                    "Cannot add the dummy flow for node={} -> {}",
                    node, s.getDescription());
            return false;
        } else
            logger.debug(
                    "the exit status of addFlow for node={} is {}",
                    node, s.getDescription());

        s = flowProgramServ.removeFlow(node, dummy);
        success = s.isSuccess() || s.getCode() == StatusCode.TIMEOUT;

        if (!success)
            logger.warn(
                    "Cannot remove the dummy flow for node={} -> {}",
                    node, s.getDescription());
        else
            logger.debug(
                    "the exit status of removeFlow for node={} is {}",
                    node, s.getDescription());
        return success;

    }

    /**
     * used by isLocal method in the connection manager bundle. If
     * there is a migration in progress this method ignores the
     * locality received in localityScheme that reflects the
     * connection manager view.
     */
    @Override
    public boolean localityMigrationCore(Node node,
            boolean localityScheme) {
        SwMigrInfo swinfo;
        swinfo = nodeMigrInfo.get(node);
        if (swinfo != null && swinfo.isMigrInProgress()) {
            return swinfo.isAmIAllowedToProcess();
        }
        return localityScheme;
    }

    private void handleAPotentialDummyFlowRemoval(ISwitch sw,
            OFFlowRemoved msg) {
        long ini = System.currentTimeMillis();
        logger.trace("handleAPotentialDummyFlowRemoval");
        Node node = NodeCreator.createOFNode(sw.getId());

        SwMigrInfo si = nodeMigrInfo.get(node);
        if (si == null) {
            logger.debug("missing entry in the nodeMigration data structure");
            return;
        }
        if (!si.isMigrInProgress()) {
            logger.debug("Migration NON in progress. Ignoring the flow remove event.");
            return;
        }
        Flow dummyFlow = si.getDummyFlow();
        Flow flow = new FlowConverter(msg.getMatch(),
                new ArrayList<OFAction>(0)).getFlow(node);
        flow.setPriority(msg.getPriority());
        flow.setIdleTimeout(msg.getIdleTimeout());
        flow.setId(msg.getCookie());
        logger.debug("\nflow=\t{}\ndummy=\t{}", flow, dummyFlow);
        if (SwMigrInfo.checkFlowRemoveEqual(dummyFlow, flow)) {
            boolean allowed = si.isAmIAllowedToProcess();
            // inverting the permission
            // si.setAmIAllowedToProcess(!allowed); //no need to do
            // it. removing
            // the entry means the same thing
            logger.debug("changing permission from {} to {}",
                    allowed, !allowed);
            nodeMigrInfo.remove(node);

            if (allowed) { // I was master at the beginning of this
                           // migration
                /**
                 * the following step ensures that all previous state
                 * messages are completed before the barrier response
                 * is sent back to the controller.
                 */
                Status status = flowProgramServ
                        .syncSendBarrierMessage(node);
                if (!status.isSuccess())
                    logger.warn(
                            "problems with the barrier request:{}",
                            status);
            }
            try {
                si.getBufferForEndMigr().write("migrationEnded\n");
                si.getBufferForEndMigr().flush();
            } catch (IOException e) {
                logger.warn(Util.exceptionToString(e));
            }
            logger.trace("DummyFlowRemoved: The migration has been successfully completed in the core controller.");

        }
        logger.debug(
                "{}ms till(from its beginnning) the end of handleAPotentialDummyFlowRemoval",
                System.currentTimeMillis() - ini);

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

    public void setFlowProgrammingService(
            IPluginInFlowProgrammerService ps) {
        logger.debug("setFlowProgrammingService");
        this.flowProgramServ = ps;
    }

    public void unsetFlowProgrammingService(
            IPluginInFlowProgrammerService ps) {
        logger.debug("unsetFlowProgrammingService");
        if (this.flowProgramServ == ps) {
            this.flowProgramServ = null;
        }

    }

    @SuppressWarnings("unchecked")
    private void allocateCaches() {
        if (this.clusterServices == null) {
            logger.error("Un-initialized clusterServices, can't create cache");
            return;
        }
        logger.debug("allocationg the migration statistics-cache");
        try {
            clusterServices
                    .createCache(
                            nodeControllersCacheName,
                            EnumSet.of(IClusterServices.cacheMode.TRANSACTIONAL));
            logger.debug(
                    "A new cache has been created with nodeConnectionsCacheName={}",
                    nodeControllersCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}",
                    nodeControllersCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured e={}",
                    Util.exceptionToString(e));
        }

        try {
            clusterServices
                    .createCache(
                            loadStatisticsCacheName,
                            EnumSet.of(
                                    IClusterServices.cacheMode.NON_TRANSACTIONAL,
                                    IClusterServices.cacheMode.ASYNC));
            logger.debug("A new cache has been created with name {}",
                    loadStatisticsCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}. No problem",
                    loadStatisticsCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured e={}",
                    Util.exceptionToString(e));
        }

        try {
            clusterServices
                    .createCache(
                            Controller.loadStatisticsRequestCacheName,
                            EnumSet.of(
                                    IClusterServices.cacheMode.ASYNC,
                                    IClusterServices.cacheMode.NON_TRANSACTIONAL));
            logger.debug("A new cache has been created with name {}",
                    Controller.loadStatisticsRequestCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}. No problem",
                    Controller.loadStatisticsRequestCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured e={}",
                    Util.exceptionToString(e));
        }
        logger.debug("allocationg the physical connection cache");

        try {
            clusterServices.createCache(physicalConnectionsCacheName,
                    EnumSet.of(
                            IClusterServices.cacheMode.TRANSACTIONAL,
                            IClusterServices.cacheMode.SYNC));
            logger.debug("A new cache has been created with name {}",
                    physicalConnectionsCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache={} already exists - no problem",
                    physicalConnectionsCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured err={}",
                    Util.exceptionToString(e));
        }
        logger.debug("allocationg the migration cache");
        if (this.clusterServices == null) {
            logger.error("Un-initialized clusterServices, can't create cache");
            return;
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void retrieveCacheNodeContr() {
        if (this.clusterServices == null) {

            logger.error("Un-initialized Cluster Services, can't retrieve caches for scheme: BALANCED");

            return;
        }

        nodeMasterControllers = (ConcurrentMap<Node, Set<InetAddress>>) clusterServices
                .getCache(nodeControllersCacheName);
        if (nodeMasterControllers == null) {
            logger.error("\nFailed to get cache: {}",
                    nodeControllersCacheName);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void retrieveCacheLoadStatistics() {
        logger.info("retrieving the cache containing load statistics");
        if (this.clusterServices == null) {
            logger.error("Un-initialized Cluster Services, can't retrieve statistics cache");
            return;
        }
        contrLoadStatistics = (ConcurrentMap<InetAddress, LoadStatistics>) clusterServices
                .getCache(loadStatisticsCacheName);
        if (contrLoadStatistics == null) {
            logger.error("\nFailed to get cache: {}.",
                    loadStatisticsCacheName);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void retrieveCacheLoadStatisticsRequest() {
        logger.info("retrieving the cache containing load statistics requests");
        if (this.clusterServices == null) {
            logger.error("Un-initialized Cluster Services, can't retrieve the statistics request cache");
            return;
        }

        contrLoadStatisticsRequest = (ConcurrentMap<InetAddress, Long>) clusterServices
                .getCache(Controller.loadStatisticsRequestCacheName);
        if (contrLoadStatisticsRequest == null) {

            logger.error("\nFailed to get cache: {}.",
                    Controller.loadStatisticsRequestCacheName);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private void retrievePhysicalConnectionsCache() {
        logger.info("retrieving the cache pysicalConnections");
        if (this.clusterServices == null) {
            logger.error("Un-initialized Cluster Services, can't retrieve end migration signaling-cache");
            return;
        }

        bridgeContrPhysicalConn = (ConcurrentMap<NodeBridge, Set<InetAddress>>) clusterServices
                .getCache(Controller.physicalConnectionsCacheName);
        if (bridgeContrPhysicalConn == null) {

            logger.error("\nFailed to get cache: {}.",
                    Controller.physicalConnectionsCacheName);
        }

    }

    @Override
    public void entryCreated(InetAddress key, String cacheName,
            boolean originLocal) {
        logger.trace("entryCreated ia={}", key);

    }

    @Override
    public void entryUpdated(InetAddress ia, Long req_nr,
            String cacheName, boolean originLocal) {
        logger.trace("entryUpdated ia={} req_nr={}", ia, req_nr);
        if (ia.equals(REQ_STATISTICS_ADDRESS) || ia.equals(myAddr))
            updateTheStatisticsCache(req_nr);

    }

    @Override
    public void entryDeleted(InetAddress key, String cacheName,
            boolean originLocal) {
        logger.warn("entryDeleted ia={}", key);
    }

    /** put in the cache my statistics info. req_nr is the request id. */
    private void updateTheStatisticsCache(Long req_nr) {
        // logger.warn("_____arrival of "+req_nr+" at "+System.currentTimeMillis());
        long currTime = System.currentTimeMillis();
        long timeLapse = currTime - timeLastStatistics;
        if (timeLapse < 200) // ignoring very frequent updates
            return;
        HashMap<Node, Long> nodeNrReq = new HashMap<Node, Long>();
        Map<Node, Set<InetAddress>> nodePhysicalConn = Util
                .getCompatibleMapNodeContr(bridgeContrPhysicalConn);
        int cpuLoad = 0;

        for (Long swId : swCounterReq.keySet()) {
            Node n = NodeCreator.createOFNode(swId);
            long nrMsgs = swCounterReq.get(swId);
            swCounterReq.put(swId, new Long(0)); // reset the old
                                                 // value

            Set<InetAddress> setMasterCntr = nodeMasterControllers
                    .get(n);
            Set<InetAddress> physicalConnContr = nodePhysicalConn
                    .get(n);
            // the bridge is connected to no contr or it is not
            // physically
            // connected to
            // this contr
            if (setMasterCntr == null || physicalConnContr == null) {
                swCounterReq.remove(n);
                continue;
            } else {
                // the load generated by non master controllers is
                // smaller
                if (!setMasterCntr.contains(myAddr))
                    nrMsgs = (long) (nrMsgs * PERC_LOAD_NON_MASTER);
                if (!physicalConnContr.contains(myAddr)) {
                    swCounterReq.remove(n);
                    continue;
                }

            }
            nodeNrReq.put(n, nrMsgs);
        }
        long nrsamp = nrSamples.getAndSet(0);
        long sum = sumCpuLoad.getAndSet(0);
        if (nrsamp != 0)
            cpuLoad = (int) (sum / nrsamp);

        timeLastStatistics = currTime;

        LoadStatistics ls = new LoadStatistics(timeLapse, cpuLoad,
                nodeNrReq, switchEvents.size(), req_nr);
        // logger.warn("_____served ("+req_nr+") at "+System.currentTimeMillis());
        contrLoadStatistics.put(MY_ADDRESS, ls);

    }

    @SuppressWarnings("restriction")
    public void _cpuLoad(CommandInterpreter ci) {
        // What % load the overall system is at, from 0.0-1.0
        System.out.println(osBean.getSystemCpuLoad());
    }

    void setDataPacketService(IDataPacketService s) {
        logger.trace("Set DataPacketService.");

        dataPacketService = s;
    }

    void unsetDataPacketService(IDataPacketService s) {
        logger.trace("Removed DataPacketService.");

        if (dataPacketService == s) {
            dataPacketService = null;
        }
    }

    @Override
    public void switchAdded(ISwitch sw) {
        // TODO Auto-generated method stub

    }

    @Override
    public void switchDeleted(ISwitch sw) {
        // TODO Auto-generated method stub

    }

}
