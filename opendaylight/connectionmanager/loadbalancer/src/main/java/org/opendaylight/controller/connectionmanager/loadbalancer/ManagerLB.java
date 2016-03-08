package org.opendaylight.controller.connectionmanager.loadbalancer;

/** Notes: In OpenDaylight, at upper layers(due to abstraction), all network nodes are 
 * instances of the class Node. They can be distinguished by checking their type. In 
 * the following code, in general, variables called node are the OVS bridges. When I 
 * have to deal with OVS I call it container. 
 * In the current OpenDaylight architecture it is not possible to have 2
 * bridges having the same id(hardware address-MAC) even when they are in 2
 * different OVS containers. 
 */
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.osgi.framework.console.CommandInterpreter;
import org.eclipse.osgi.framework.console.CommandProvider;
import org.opendaylight.controller.clustering.services.CacheConfigException;
import org.opendaylight.controller.clustering.services.CacheExistException;
import org.opendaylight.controller.clustering.services.ICacheUpdateAware;
import org.opendaylight.controller.clustering.services.IClusterGlobalServices;
import org.opendaylight.controller.clustering.services.IClusterServices;
import org.opendaylight.controller.clustering.services.ICoordinatorChangeAware;
import org.opendaylight.controller.protocol_plugin.openflow.core.internal.Controller;
import org.opendaylight.controller.protocol_plugin.openflow.migration.IControllerMigrationService;
import org.opendaylight.controller.protocol_plugin.openflow.migration.LoadStatistics;
import org.opendaylight.controller.protocol_plugin.openflow.migration.NodeBridge;
import org.opendaylight.controller.protocol_plugin.openflow.migration.Util;
import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.ConstructionException;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.core.NodeConnector;
import org.opendaylight.controller.sal.flowprogrammer.IFlowProgrammerService;
import org.opendaylight.controller.sal.utils.NodeCreator;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.table.Bridge;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.plugin.IConnectionServiceInternal;
import org.opendaylight.ovsdb.plugin.InventoryServiceInternal;
import org.opendaylight.ovsdb.plugin.OVSDBConfigService;
import org.opendaylight.ovsdb.plugin.OVSDBInventoryListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManagerLB implements OVSDBInventoryListener,
        CommandProvider, ICacheUpdateAware<Object, Object>,
        INodeControllerAssociations, ICoordinatorChangeAware {

    private static final Logger logger = LoggerFactory
            .getLogger(ManagerLB.class);
    private IConnectionServiceInternal ovsdbConnectionService;
    private InventoryServiceInternal ovsdbInventoryService;
    private IClusterGlobalServices clusterServices;
    private OVSDBInventoryListener ovsdbInventoryListener;
    /**
     * Contains the logical map node --> MasterControllers. A node can
     * be connected also to other controllers that are not
     * 'masters'(master= controller that can receive asynchronous
     * events from the switch)
     */
    private ConcurrentMap<Node, Set<InetAddress>> nodeMasterControllers;
    private Set<Node> connectedToOVS;
    private IFlowProgrammerService flowProgrammerService;
    private final String KEY_OVSs = "connectionmanager.loadbalancer.OVSs";
    private final String nodeControllersCacheName = "connectionmanager.LOAD_BALANCED.nodeconnections";
    private final String migrationCacheName = "connectionmanager.loadbalancer.contrNodeContr";
    /** pending migrations map entry=(switchID:(src,dst,moreswInfo)) */
    private ConcurrentMap<Node, TripleContrContrNode> pendingMigrations;
    /**
     * the map contains the current statistics.
     * entry=(controller,loadStatistics)
     */
    private ConcurrentMap<InetAddress, LoadStatistics> contrLoadStatistics;
    /**
     * map that contains statistic requests.
     * entry=(controller,requestId)
     */
    private ConcurrentMap<InetAddress, Long> contrLoadStatisticsRequest;
    /**
     * map that stores the physical connections(bridge
     * -->controllers). The word 'physical' means that the bridge is
     * really connected to that set of controllers.
     */
    private ConcurrentMap<NodeBridge, Set<InetAddress>> bridgeContrPhysicalConn;
    /**
     * keeps track of the first controller that ends the migration of
     * Node
     */
    private ConcurrentMap<Node, InetAddress> endMigration;

    ConcurrentHashMap<Node, Long> migrationTiming;
    /** Sum of migration time */
    private AtomicLong sumMigrTime = new AtomicLong(0L);
    /**
     * Sum of partial migration time. The partial time indicated the
     * time from the beginning of migration algorithm till the
     * destination controller received the dummy-flow removal message
     */
    private AtomicLong sumPartMigrTime = new AtomicLong(0L);
    /** used with the above sums to compute the average */
    private AtomicLong nrMigrations = new AtomicLong(0L);
    /**
     * used to test only part of migrations. The migration time of a
     * node in this set is not considered in the average.
     */
    Set<Node> ignoreMigrationTime = new HashSet<Node>();
    private final int portMigrationServer = 8088;
    private InetAddress myAddress;
    private Node nodeovs = null;
    private Thread threadTcpTestingServer;
    private IControllerMigrationService contrMigrServ;
    /**
     * It saves the srcARPAddress, targetARP address(in a string) of
     * the first packet-in before the start migration. It also adds
     * the queue length at time of the request. Then it is used to
     * track the exact moment when a migration starts. Testing
     * purposes.
     */
    ConcurrentHashMap<Node, String> messageStartMigr = new ConcurrentHashMap<Node, String>();
    /**
     * It can store more than one message per node. For the testing
     * environment the node is not important, so the list may contain
     * msgs from migrations of different nodes
     */
    private List<String> listMsgsStartMigr = new LinkedList<String>();

    private enum Operation {
        PUT, REMOVE;
    }

    private enum Order {
        ASCENDING, DESCENDING;
    }

    /**
     * if set to false every controller will connect to all bridges,
     * otherwise every switch will connect to exactly one
     * controller(that will be also the master
     */
    private boolean selectiveInitConnection = true;
    private InetAddress REQ_STATISTICS_ADDRESS;
    private static long request_number;
    private static AtomicInteger waiting_number_additionalCtrls;
    /**
     * keeps track of the responses. Used to wait until the statistics
     * are received from all controllers
     */
    private Set<InetAddress> statisticsResponses;
    private ExecutorService executorService;
    private SynchronousQueue<Runnable> migrationsTasks;
    /** all thresholds should have values within 1-100 */
    private final int THRESHOLD_BALANCE = 10;
    private final int THRESHOLD_SHRINK = 0;
    private final int THRESHOLD_EXPAND = 100;
    /**
     * it should always be > 0. Be aware that it blocks the migration
     * of all switches having a load impact smaller than this value.
     * Big value may block "lock" most of the switches in a
     * controller.
     */
    private final int THRESHOLD_MIGR_SINGLE_SW = 1;
    private final boolean AUTO_BALANCE = false;
    private final int CHECK_LOAD_EVERY = 2; // seconds
    private boolean balancing_in_progress = false;
    private boolean ignoreNextStat = true;

    void setIFlowProgrammerService(IFlowProgrammerService s) {
        logger.trace("Set FlowProgrammerService.");
        flowProgrammerService = s;
    }

    void unsetIFlowProgrammerService(IFlowProgrammerService s) {
        logger.trace("Removed FlowProgrammerService.");

        if (flowProgrammerService == s) {
            flowProgrammerService = null;
        }
    }

    public void setIControllerMigrationService(
            IControllerMigrationService s) {
        logger.debug("IControllerMigrationService is being set");
        contrMigrServ = s;
    }

    public void unsetIControllerMigrationService(
            IControllerMigrationService s) {
        logger.debug("IControllerMigrationService is being unset");
        if (contrMigrServ == s)
            contrMigrServ = null;
    }

    public void setOVSDBInventoryListener(OVSDBInventoryListener il) {
        logger.debug("Got ovsdb inventory service "
                + "set request {}", il);
        ovsdbInventoryListener = il;
    }

    public void unsetOVSDBInventoryListener(OVSDBInventoryListener il) {
        logger.debug("Got ovsdb inventory service unset request {}",
                il);
        if (ovsdbInventoryListener == il)
            ovsdbInventoryListener = null;
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

    public void setOvsdbConnectionService(
            IConnectionServiceInternal cs) {
        logger.debug("Got ovsdb connection service set request {}",
                cs);
        ovsdbConnectionService = cs;
    }

    public void unsetOvsdbConnectionService(
            IConnectionServiceInternal cs) {
        logger.debug("Got ovsdb connection service unset request {}",
                cs);
        if (this.ovsdbConnectionService == cs) {
            ovsdbConnectionService = null;
        }
    }

    public void setOvsdbInventoryService(InventoryServiceInternal is) {
        logger.debug("Got ovsdb inventory service set request {}", is);
        ovsdbInventoryService = is;
    }

    public void unsetOvsdbInventoryService(InventoryServiceInternal is) {
        logger.debug("Got ovsdb inventory service unset request {}",
                is);
        if (this.ovsdbInventoryService == is) {
            ovsdbInventoryService = null;
        }
    }

    public void init() {
        logger.debug("LoadBalancing component has been initiated");
        connectedToOVS = new HashSet<Node>();
        allocateCaches();
        retrieveCacheNodeContr();
        retrieveCacheContrNodeContr();
        retrievePhysicalConnectionsCache();
        retrieveCacheLoadStatisticsRequest();
        retrieveCacheLoadStatistics();
        migrationTiming = new ConcurrentHashMap<Node, Long>();
        endMigration = new ConcurrentHashMap<Node, InetAddress>();
        myAddress = clusterServices.getMyAddress();
        String str = System
                .getProperty("managerLB.selectiveInitConnection");
        try {
            selectiveInitConnection = Boolean.parseBoolean(str);
        } catch (Exception e) {
            logger.warn(
                    "your value {} for managerLB.selectiveInitConnection is not boolean.err={}",
                    str, Util.exceptionToString(e));
        }
        try {
            REQ_STATISTICS_ADDRESS = InetAddress.getByName("0.0.0.0");
        } catch (UnknownHostException e) {
            logger.warn(
                    "cannot define the ip address of a statistic request. err={}",
                    Util.exceptionToString(e));
        }
        statisticsResponses = new HashSet<InetAddress>();
        request_number = 0;
        waiting_number_additionalCtrls = new AtomicInteger(0);
        migrationsTasks = new SynchronousQueue<Runnable>();
        executorService = new ThreadPoolExecutor(2, 200, 60,
                TimeUnit.SECONDS, migrationsTasks);
        if (AUTO_BALANCE) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                logger.warn("err={}", Util.exceptionToString(e));
            }
            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (balancing_in_progress)
                        return;
                    statisticsResponses = new HashSet<InetAddress>();
                    request_number++;
                    contrLoadStatisticsRequest.put(
                            REQ_STATISTICS_ADDRESS, new Long(
                                    request_number));
                }
            }, CHECK_LOAD_EVERY, CHECK_LOAD_EVERY);
        }
    }

    public void started() {
        logger.debug("LoadBalancing component has been started");
        registerWithOSGIConsole();
        try {
            connectToOVSAsManager();
        } catch (Exception e) {
            logger.error("cannot connect as manager due to: {}",
                    Util.exceptionToString(e));
        }

        try {
            this.nodeovs = new Node("OVS", "MININET");
        } catch (ConstructionException e) {
            logger.error("cannot build the container node. err={}",
                    Util.exceptionToString(e));
        }
        threadTcpTestingServer = new Thread(new ServerTcpTest(this));
        threadTcpTestingServer.start();
    }

    public void stopping() {
        logger.debug("LoadBalancing component has been stopped");
        try {
            threadTcpTestingServer.interrupt();
        } finally {
        }

    }

    public List<String> connectToOVSAsManager() {
        List<String> problematicSwitches = new LinkedList<String>();
        logger.debug("MY_METHOD");
        String myAddr = clusterServices.getMyAddress()
                .getHostAddress();
        Node.NodeIDType.registerIDType("OVS", String.class);
        NodeConnector.NodeConnectorIDType.registerIDType("OVS",
                String.class, "OVS");

        // Properties props = loadProperties();

        List<InetAddress> controllers = clusterServices
                .getClusteredControllers();
        logger.debug("controllers={} myAddr={}", controllers, myAddr);
        String switches = System.getProperty(KEY_OVSs,
                "192.168.1.2:6634");
        for (String sw : switches.split(",")) {
            String[] tokens = sw.split(":");
            if (tokens.length != 2) {
                logger.error(
                        "The the format of %s is not correct. It is expected to "
                                + "receive ip:port", sw);
                problematicSwitches.add(sw);
            } else {
                Map<ConnectionConstants, String> params = new HashMap<ConnectionConstants, String>();
                params.put(ConnectionConstants.ADDRESS,
                        tokens[0].trim());
                params.put(ConnectionConstants.PORT, tokens[1].trim());
                Node node = ovsdbConnectionService.connect("MININET",
                        params);
                if (node == null) {
                    logger.warn(
                            "Failed to connecto to ovsdb server %s",
                            sw);
                    problematicSwitches.add(sw);
                } else
                    logger.debug(
                            "The connection has been established between contr {}  node={}",
                            myAddr, node);
            }
        }

        return problematicSwitches;

    }

    public boolean isThisBrAllowedToConnect(Node node) {
        Set<InetAddress> controllersConnectedToNode = nodeMasterControllers
                .get(node);
        List<InetAddress> contrCluster = clusterServices
                .getClusteredControllers();
        logger.debug("These are the controllers in the cluster: {}",
                contrCluster);
        Map<InetAddress, Set<Node>> contrSetNodes = getControllerToNodesMap();
        InetAddress myIP = clusterServices.getMyAddress();
        // this assures that there is AT MOST one "active"(master)
        // controller
        if (controllersConnectedToNode != null
                && controllersConnectedToNode.size() == 1)
            if (!controllersConnectedToNode.contains(myIP)) {
                return false;
            } else {
                return true;
            }

        for (InetAddress ia : contrCluster) {
            if (!contrSetNodes.containsKey(ia))
                contrSetNodes.put(ia, new HashSet<Node>());
        }

        long minNrNodes = Long.MAX_VALUE;
        for (InetAddress ia : contrSetNodes.keySet()) {
            long setSize = contrSetNodes.get(ia).size();
            if (setSize < minNrNodes)
                minNrNodes = setSize;
        }
        // if I have less or the same nr of switches I accept to
        // connect to the
        // node
        if (contrSetNodes.size() == 0
                || contrSetNodes.get(myIP).size() <= minNrNodes) {
            logger.debug("isConnectionAllowedInternal returns true.");
            return true;
        }
        logger.debug("isConnectionAllowedInternal returns true.");
        return false;
    }

    private void connectAllBridgesToAllOFControllers(Node node) {

        logger.debug("connectAllOFControllers node={}", node);
        Map<String, Table<?>> bridges = ovsdbInventoryService
                .getTableCache(node, Bridge.NAME.getName());
        logger.debug("ConfiguredNotConnectedNodes={}",
                ovsdbInventoryService
                        .getConfiguredNotConnectedNodes());
        logger.debug("getNodeConnectorProps={}",
                ovsdbInventoryService.getNodeConnectorProps(true));
        logger.debug("getNodeProps={}",
                ovsdbInventoryService.getNodeProps());

        if (bridges == null) {
            logger.warn("connectAllOFControllers bridges=null ---> exiting");
            return;
        }
        logger.debug("AllBridges = {}", bridges.keySet());
        logger.debug("AllBridgesTable = {}", bridges);

        for (String bridgeUUID : bridges.keySet()) {
            try {
                ovsdbConnectionService.setOFController(node,
                        bridgeUUID);

            } catch (InterruptedException e) {
                logger.debug(
                        "cannot connect node={} to bridge={} due to {}",
                        node, bridgeUUID, Util.exceptionToString(e));
            } catch (ExecutionException e) {
                logger.debug(
                        "cannot connect node={} to bridge={} due to:",
                        node, bridgeUUID, Util.exceptionToString(e));
            }
        }

    }

    /**
     * returns the map controller --> (set of nodes that have it as
     * master)
     */
    protected Map<InetAddress, Set<Node>> getControllerToNodesMap() {
        ConcurrentMap<InetAddress, Set<Node>> controllerNodesMap = new ConcurrentHashMap<InetAddress, Set<Node>>();
        for (Node node : nodeMasterControllers.keySet()) {
            Set<InetAddress> controllers = nodeMasterControllers
                    .get(node);
            if (controllers == null)
                continue;
            for (InetAddress controller : controllers) {
                Set<Node> nodes = controllerNodesMap.get(controller);
                // if there is no set of nodes associated with this
                // controller,
                // create an empty one
                if (nodes == null) {
                    nodes = new HashSet<Node>();
                }
                nodes.add(node);
                controllerNodesMap.put(controller, nodes);
            }
        }
        return controllerNodesMap;
    }

    /** Returns the map controller --> all switches connected to it. */
    protected Map<InetAddress, Set<NodeBridge>> getContrBridgesPhisicalConnMap() {
        List<InetAddress> listContrs = clusterServices
                .getClusteredControllers();
        HashMap<InetAddress, Set<NodeBridge>> mapContrBridges = new HashMap<InetAddress, Set<NodeBridge>>();
        for (InetAddress contrClust : listContrs) {
            Set<NodeBridge> bridgesConnToThisContr = new HashSet<NodeBridge>();
            for (NodeBridge nb : bridgeContrPhysicalConn.keySet()) {
                Set<InetAddress> sia = bridgeContrPhysicalConn
                        .get(nb);
                if (sia.contains(contrClust))
                    bridgesConnToThisContr.add(nb);
            }
            mapContrBridges.put(contrClust, bridgesConnToThisContr);
        }
        return mapContrBridges;
    }

    /** copies nr nodes from the set nodes to the list toBemoved */
    private void addNodesToList(Set<Node> nodes,
            List<Node> toBeMoved, int nr) {
        if (nr == 0)
            return;
        int added = 0;
        for (Node n : nodes) {
            toBeMoved.add(n);
            if (++added == nr)
                return;
        }
    }

    /**
     * migrates a node from listToBeMoved from its controller to
     * contrDest
     */
    private synchronized void migrateNodeAtomicMap(
            List<Node> listToBeMoved, InetAddress contrDst) {
        if (listToBeMoved.size() == 0)
            return;
        Node node = listToBeMoved.remove(0);
        Set<InetAddress> newSet = new HashSet<InetAddress>();
        newSet.add(contrDst);
        while (!nodeMasterControllers.containsKey(node)
                && listToBeMoved.size() > 0) {
            node = listToBeMoved.remove(0);
        }
        if (!nodeMasterControllers.containsKey(node)) { // there was
                                                        // no valid
                                                        // node
            logger.warn("Something happened meanwhile. There is no node that needs to change its controller");
            return;
        }
        logger.debug("the node {} is being migrated from {} to {}",
                node, nodeMasterControllers.get(node), contrDst);

        Set<InetAddress> prevNodes = nodeMasterControllers.get(node);
        if (prevNodes != null) {
            if (prevNodes.size() > 1) {
                logger.error("There were more that one master contr. Nothing will be changed...");
                return;
            } else {
                try {
                    clusterServices.tbegin();
                    nodeMasterControllers.replace(node, newSet);
                    clusterServices.tcommit();

                    logger.info(
                            "the node {} has been migrated successfully to {}",
                            node, contrDst);

                } catch (Exception e) {
                    try {
                        clusterServices.trollback();
                    } catch (Exception e1) {
                        logger.error(
                                "Error Rolling back the nodeControllers map changes err={}",
                                Util.exceptionToString(e1));
                    }
                    logger.warn(
                            "there was a problem during the migration of {} to {} \n err={}",
                            node, contrDst, Util.exceptionToString(e));

                }
            }
        } else if (prevNodes == null || prevNodes.size() == 0) {
            try {
                clusterServices.tbegin();
                if (nodeMasterControllers.putIfAbsent(node, newSet) != null) {
                    logger.warn(
                            "There is already one master. At the begining of the transaction there "
                                    + "was no controller associate with this {}... No migration has been done",
                            node);
                    clusterServices.trollback();
                    return;
                }
                clusterServices.tcommit();
                logger.info(
                        "the node {} has been migrated successfully to {}",
                        node, contrDst);

            } catch (Exception e) {
                logger.warn(
                        "there was a problem during the migration of {} to {} \n err={}",
                        node, contrDst, Util.exceptionToString(e));
                try {
                    clusterServices.trollback();
                } catch (Exception e1) {
                    logger.error(
                            "Error Rolling back the nodeControllers map changes. err={}",
                            Util.exceptionToString(e1));
                }
            }
        }

    }

    void migrateAllTo(InetAddress dst) {
        List<Node> listSwitches = new LinkedList<Node>(
                nodeMasterControllers.keySet());
        migrateNodeAtomicMap(listSwitches, dst);
    }

    void balanceNumberMasters() {
        logger.trace("Balance based on the number of nodes currently being cared of by each controller");
        Map<InetAddress, Set<Node>> controllerNodes = getControllerToNodesMap();
        List<InetAddress> allControllers = clusterServices
                .getClusteredControllers();
        int mean = nodeMasterControllers.size()
                / allControllers.size();
        int remainder = nodeMasterControllers.size()
                % allControllers.size();
        logger.trace("mean={} remainder={}", mean, remainder);
        int overflow_tokens = remainder; // number of controllers that
                                         // should
                                         // handle one more sw
        List<Node> toBeMoved = new LinkedList<Node>(); // list of
                                                       // candidate
                                                       // nodes to be
                                                       // "moved"

        for (InetAddress contr : controllerNodes.keySet()) {
            Set<Node> currNodes = controllerNodes.get(contr);
            int masterForNr = currNodes.size(); // this controller is
                                                // the master
                                                // for
            // masterForNr nodes
            if (masterForNr > mean) {
                if (overflow_tokens > 0) {
                    addNodesToList(currNodes, toBeMoved, masterForNr
                            - mean - 1);
                    overflow_tokens--;
                } else {
                    addNodesToList(currNodes, toBeMoved, masterForNr
                            - mean);
                }
            }

        }
        logger.trace("toBeMoved={}", toBeMoved);
        if (toBeMoved.size() == 0) // everything is balanced
            return;
        for (InetAddress contr : clusterServices
                .getClusteredControllers()) {
            Set<Node> currNodes = controllerNodes.get(contr);
            int masterForNr = (currNodes != null) ? currNodes.size()
                    : 0;
            int under = mean - masterForNr;
            for (int i = 0; i < under; i++) { // if it has less than
                                              // the mean
                                              // add more
                migrateNodeAtomicMap(toBeMoved, contr);
            }

        }

    }

    private Status putOrRemoveControllerToNode(Operation op,
            InetAddress controller, NodeBridge nb) {
        if (clusterServices == null) {
            return new Status(StatusCode.INTERNALERROR,
                    "Cluster service unavailable!");
        }
        if (controller == null) {
            return new Status(StatusCode.INTERNALERROR,
                    "The given controller is null");
        }
        if (nb == null) {
            return new Status(StatusCode.INTERNALERROR,
                    "The given NodeBridge object is null");
        }

        try {
            clusterServices.tbegin();
        } catch (Exception e) {
            String msg = "cannot start the transaction due to : "
                    + Util.exceptionToString(e);
            logger.warn(msg);
            e.printStackTrace();

            return new Status(StatusCode.INTERNALERROR, msg);
        }
        if (op == Operation.PUT) {
            Set<InetAddress> prevContr = bridgeContrPhysicalConn
                    .get(nb);
            Set<InetAddress> setNewContr;
            if (prevContr == null) {
                setNewContr = new HashSet<InetAddress>();
                setNewContr.add(controller);
            } else if (!prevContr.contains(controller)) {
                setNewContr = new HashSet<InetAddress>(prevContr);
                setNewContr.add(controller);
            } else {
                logger.debug("The controller is already connected");
                return new Status(StatusCode.SUCCESS);
            }
            bridgeContrPhysicalConn.put(nb, setNewContr);
        } else {
            Set<InetAddress> prevContr = bridgeContrPhysicalConn
                    .get(nb);
            if (prevContr == null) {
                logger.debug(
                        "OK.Nothing to do. The controller={} is not connected to {}",
                        controller, nb);
                return new Status(StatusCode.SUCCESS);
            } else {
                Set<InetAddress> setNewContr = new HashSet<InetAddress>(
                        prevContr);
                setNewContr.remove(controller);
                if (setNewContr.size() > 0)
                    bridgeContrPhysicalConn.put(nb, setNewContr);
            }

        }

        try {
            clusterServices.tcommit();
        } catch (Exception e) {
            String msg = "cannot commit the transaction be started due to:"
                    + Util.exceptionToString(e);
            logger.warn(msg);
            try {
                clusterServices.trollback();
            } catch (Exception e1) {
                logger.warn(Util.exceptionToString(e1));
            }
            return new Status(StatusCode.INTERNALERROR, msg);
        }

        return new Status(StatusCode.SUCCESS);
    }

    private Status putMasterControllerToNode(InetAddress controller,
            Node node) {
        if (clusterServices == null) {
            return new Status(StatusCode.INTERNALERROR,
                    "Cluster service unavailable!");
        }
        if (nodeMasterControllers == null) {
            return new Status(StatusCode.INTERNALERROR,
                    "Node connections info missing.");
        }

        logger.debug("Trying to Put {} to {}",
                controller.getHostAddress(), node.toString());

        Set<InetAddress> oldControllers = nodeMasterControllers
                .get(node);

        Set<InetAddress> newControllers = null;
        if (oldControllers == null) {
            newControllers = new HashSet<InetAddress>();
        } else {
            if (oldControllers.contains(controller))
                return new Status(StatusCode.SUCCESS);
            if (oldControllers.size() > 0) {

                logger.warn(
                        "States Exists for {} : {}. There is already one or more masters. "
                                + "This controller({}) it is not allowed to connect to the node.",
                        node, oldControllers, controller);
                return new Status(StatusCode.CONFLICT);
            } else {
                newControllers = new HashSet<InetAddress>(
                        oldControllers);
            }
        }
        newControllers.add(controller);

        try {
            clusterServices.tbegin();
            if (nodeMasterControllers.putIfAbsent(node,
                    newControllers) != null) {
                logger.warn(
                        "PutIfAbsent failed {} to {}. There is/are already another controllers associated with the node",
                        controller.getHostAddress(), node.toString());

                clusterServices.trollback();
                return new Status(StatusCode.CONFLICT);

            } else {
                logger.debug("Added contr={} to node={}",
                        controller.getHostAddress(), node);
            }
            clusterServices.tcommit();
        } catch (Exception e) {
            logger.error(
                    "Exception during the put of Controller to a Node err={}",
                    Util.exceptionToString(e));
            try {
                clusterServices.trollback();
            } catch (Exception e1) {
                logger.error(
                        "Error Rolling back the node Connections Changes err={}",
                        Util.exceptionToString(e));
            }
            return new Status(StatusCode.INTERNALERROR);
        }
        return new Status(StatusCode.SUCCESS);
    }

    private Status removeMasterControllerFromNode(
            InetAddress controller, Node node) {
        if (node == null || controller == null) {
            return new Status(StatusCode.BADREQUEST,
                    "Invalid Node or Controller Address Specified.");
        }

        if (clusterServices == null || nodeMasterControllers == null) {
            return new Status(StatusCode.SUCCESS);
        }
        logger.debug(
                "Trying to remove the controller {} from the list of controllers associated with {}",
                controller.toString(), node.toString());
        Set<InetAddress> oldControllers = nodeMasterControllers
                .get(node);

        if (oldControllers != null
                && oldControllers.contains(controller)) {
            Set<InetAddress> newControllers = new HashSet<InetAddress>(
                    oldControllers);
            if (newControllers.remove(controller)) {
                try {
                    clusterServices.tbegin();
                    if (newControllers.size() > 0) {
                        // modifies the map in the distributed cluster
                        if (!nodeMasterControllers.replace(node,
                                oldControllers, newControllers)) {
                            clusterServices.trollback();
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                logger.warn(Util.exceptionToString(e));
                            }
                            return removeMasterControllerFromNode(
                                    controller, node);
                        }
                    } else {
                        nodeMasterControllers.remove(node);
                    }
                    clusterServices.tcommit();
                    logger.debug(
                            "The controller {} has been removed from the list of controllers associated with {}",
                            controller.toString(), node.toString());
                } catch (Exception e) {
                    logger.error(
                            "Exception in removing Controller from a Node err={}",
                            Util.exceptionToString(e));
                    try {
                        clusterServices.trollback();
                    } catch (Exception e1) {
                        logger.error(
                                "Error Rolling back the node Connections Changes err={}",
                                Util.exceptionToString(e1));
                    }
                    return new Status(StatusCode.INTERNALERROR);
                }

            }
        }
        return new Status(StatusCode.SUCCESS);

    }

    /**
     * notification from ovsdb inventory service. Here, the node is an
     * Open Virtual Switch. This OVS could contain multiple bridges
     * each identified by a string at low level and Node(again) at
     * higher level of abstraction.
     */
    @Override
    public void nodeAdded(Node node) {
        logger.debug(
                "the node {} has just been added. Got notification from ovsdb inventory",
                node);
        if (connectedToOVS.contains(node))
            return;
        connectedToOVS.add(node);

        // without the following line, the property contains all
        // clustered
        // controllers
        System.setProperty("ovsdb.controller.address",
                myAddress.getHostAddress());
        if (clusterServices.amICoordinator())
            decideOptimalConnectionAndMasterMapping(node);

    }

    /** notification from ovsdb inventory service */
    @Override
    public void nodeRemoved(Node node) {

    }

    /** notification from ovsdb inventory service */
    @Override
    public void rowAdded(Node arg0, String arg1, String arg2,
            Table<?> arg3) {

    }

    /** notification from ovsdb inventory service */
    @Override
    public void rowRemoved(Node arg0, String arg1, String arg2,
            Table<?> arg3, Object arg4) {
        // TODO Auto-generated method stub

    }

    /** notification from ovsdb inventory service */
    @Override
    public void rowUpdated(Node arg0, String tableName, String uuid,
            Table<?> tableBefore, Table<?> tableAfter) {

    }

    private void registerWithOSGIConsole() {
        BundleContext bundleContext = FrameworkUtil.getBundle(
                this.getClass()).getBundleContext();
        bundleContext.registerService(
                CommandProvider.class.getName(), this, null);
    }

    /**
     * it should print the list of available commands in the osgi
     * console
     */
    @Override
    public String getHelp() {
        // TODO Auto-generated method stub
        return null;
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
    private void retrieveCacheContrNodeContr() {
        logger.info("retrieving the migration cache");
        if (this.clusterServices == null) {
            logger.error("Un-initialized Cluster Services, can't retrieve migration cache");
            return;
        }

        pendingMigrations = (ConcurrentMap<Node, TripleContrContrNode>) clusterServices
                .getCache(migrationCacheName);
        if (pendingMigrations == null) {

            logger.error("\nFailed to get cache: {}.",
                    migrationCacheName);

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
    private void retrieveCacheLoadStatistics() {
        logger.info("retrieving the cache containing load statistics");
        if (this.clusterServices == null) {
            logger.error("Un-initialized Cluster Services, can't retrieve the statistics cache");
            return;
        }
        contrLoadStatistics = (ConcurrentMap<InetAddress, LoadStatistics>) clusterServices
                .getCache(Controller.loadStatisticsCacheName);
        if (contrLoadStatistics == null) {
            logger.error("\nFailed to get cache: {}.",
                    Controller.loadStatisticsCacheName);
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

    private void allocateCaches() {
        if (this.clusterServices == null) {
            logger.error("Un-initialized clusterServices, can't create cache");
            return;
        }
        logger.debug("allocationg the migration cache");

        try {
            clusterServices.createCache(migrationCacheName, EnumSet
                    .of(IClusterServices.cacheMode.NON_TRANSACTIONAL,
                            IClusterServices.cacheMode.ASYNC));
            logger.debug("A new cache has been created with name {}",
                    migrationCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}. No problem",
                    migrationCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured err={}",
                    Util.exceptionToString(e));
        }
        logger.debug("allocationg the cache nodeMasterController");
        try {
            clusterServices
                    .createCache(
                            nodeControllersCacheName,
                            EnumSet.of(IClusterServices.cacheMode.TRANSACTIONAL));
            logger.debug("A new cache has been created with name {}",
                    nodeControllersCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}. No problem",
                    nodeControllersCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured err={}",
                    Util.exceptionToString(e));
        }

        logger.debug("allocationg the physical connection cache");

        try {
            clusterServices
                    .createCache(
                            Controller.physicalConnectionsCacheName,
                            EnumSet.of(IClusterServices.cacheMode.TRANSACTIONAL));
            logger.debug("A new cache has been created with name {}",
                    Controller.physicalConnectionsCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache={} already exists - no problem",
                    Controller.physicalConnectionsCacheName);
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

        try {
            clusterServices
                    .createCache(
                            Controller.loadStatisticsCacheName,
                            EnumSet.of(
                                    IClusterServices.cacheMode.NON_TRANSACTIONAL,
                                    IClusterServices.cacheMode.SYNC));
            logger.debug("A new cache has been created with name {}",
                    Controller.loadStatisticsCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}. No problem",
                    Controller.loadStatisticsCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured err={}",
                    Util.exceptionToString(e));
        }

        try {
            clusterServices
                    .createCache(
                            Controller.loadStatisticsRequestCacheName,
                            EnumSet.of(
                                    IClusterServices.cacheMode.SYNC,
                                    IClusterServices.cacheMode.NON_TRANSACTIONAL));
            logger.debug("A new cache has been created with name {}",
                    Controller.loadStatisticsRequestCacheName);
        } catch (CacheExistException cee) {
            logger.debug("\nCache already exists: {}. No problem",
                    Controller.loadStatisticsRequestCacheName);
        } catch (CacheConfigException cce) {
            logger.error("\nCache configuration invalid - check cache mode");
        } catch (Exception e) {
            logger.error("An error occured err={}",
                    Util.exceptionToString(e));
        }
    }

    public void _connectAllAll(CommandInterpreter ci) {
        try {
            String nodeName = ci.nextArgument();
            Node node = new Node("OVS", "MININET");
            if (nodeName == null) {
                ci.println("You have entered no node name. The default name is 'OVS|MININET'");

            } else {
                String[] tokens = nodeName.split("\\|");
                if (tokens.length != 2) {
                    ci.println("the node name shoud have this format: 'nodetype|nodeID'");
                    return;
                }
                node = new Node(tokens[0], tokens[1]);
            }

            connectAllBridgesToAllOFControllers(node);
        } catch (Exception e) {
            logger.warn(Util.exceptionToString(e));
        }

    }

    public void _balance(CommandInterpreter ci) {
        balanceNumberMasters();
        ci.print("The load has been blanced");
    }

    public void _removeOneController(CommandInterpreter ci) {

        for (NodeBridge nb : bridgeContrPhysicalConn.keySet()) {
            Set<InetAddress> setContr = bridgeContrPhysicalConn
                    .get(nb);
            if (setContr.size() == 0)
                continue;
            InetAddress contr = setContr.iterator().next();
            Node containetNode = nb.getContainerNode();
            boolean removalStatus = false;
            try {
                removalStatus = ovsdbConnectionService
                        .removeOFController(containetNode,
                                nb.getBridgeUUID(), contr);
            } catch (Exception e) {
                logger.debug("cannot remove contr={} from nodeBr={}",
                        contr, nb.getBridgeVirtualNode());
                logger.warn(Util.exceptionToString(e));
                continue;
            }
            logger.warn(
                    "contr={} removal from node={} with status={}",
                    contr, nb.getBridgeVirtualNode(), removalStatus);
            break;
        }

    }

    public void _printBridges(CommandInterpreter ci) {
        try {
            Map<String, Table<?>> bridges = ovsdbInventoryService
                    .getTableCache(new Node("OVS", "MININET"),
                            Bridge.NAME.getName());
            ci.println(bridges.keySet());
        } catch (ConstructionException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void _removeControllerFromNode(CommandInterpreter ci) {
        try {

            String IP = ci.nextArgument();
            InetAddress controller = null;
            if (IP == null) {
                ci.println("You have entered no controller. It should be the its IPv4 address");
                return;
            }
            controller = InetAddress.getByName(IP);

            String nodeName = ci.nextArgument();
            Node node = null;
            if (nodeName == null) {
                ci.println("You have entered no node name. The node format is 'index' ");
                _printNodeIds(ci);
                return;

            } else {

                List<Node> nodes = new LinkedList<Node>();
                // for Node node:
                node = nodes.get(Integer.parseInt(nodeName));
            }
            Status s = removeMasterControllerFromNode(controller,
                    node);
            ci.println("The controller " + controller.toString()
                    + " has been removed from node "
                    + node.toString() + " with status "
                    + s.toString());

        } catch (Exception e) {
            logger.debug("exeption: {}", Util.exceptionToString(e));
        }
    }

    public void _printNodeIds(CommandInterpreter ci) {
        List<Node> nodes = Util.nodesListSorted(nodeMasterControllers
                .keySet());
        for (int i = 0; i < nodes.size(); i++)
            ci.println(i + " " + nodes.get(i));

    }

    public void _putControllerToNode(CommandInterpreter ci) {
        try {
            String IP = ci.nextArgument();
            InetAddress controller = null;
            if (IP == null) {
                ci.println("You have entered no controller. It should be the its IPv4 address");
                return;
            }
            controller = InetAddress.getByName(IP);

            String nodeName = ci.nextArgument();
            Node node = null;
            if (nodeName == null) {
                ci.println("You have entered no node name. The node format is 'index' ");
                _printNodeIds(ci);
                return;

            } else {
                List<Node> nodes = Util
                        .nodesListSorted(nodeMasterControllers
                                .keySet());
                node = nodes
                        .get(Integer.valueOf(nodeName).intValue());
            }
            Status s = putMasterControllerToNode(controller, node);
            ci.println("The controller " + controller.toString()
                    + " has been put to node " + node.toString()
                    + " with status " + s.toString());

        } catch (Exception e) {
            logger.debug("exeption: {}", Util.exceptionToString(e));
        }
    }

    public void _printMigrationCache(CommandInterpreter ci) {
        try {
            for (Node node : pendingMigrations.keySet()) {
                TripleContrContrNode tcc = pendingMigrations
                        .get(node);
                System.out.println(tcc.getSrcContr() + " "
                        + tcc.getDstContr() + " "
                        + tcc.getNodeBridge());
            }

        } catch (Exception e) {
            logger.warn(Util.exceptionToString(e));
        }
    }

    public void _mapPhysicalConnections(CommandInterpreter ci) {
        System.out.println("The map of physical connections: \n");
        for (NodeBridge nb : bridgeContrPhysicalConn.keySet())
            System.out.println(nb + " --> "
                    + bridgeContrPhysicalConn.get(nb));
    }

    /**
     * Returns the number of master connections and total connection
     * to every controller
     */
    String numberMasterAndTotalConnection() {
        StringBuilder sb = new StringBuilder();
        Map<InetAddress, Set<Node>> contrMasters = getControllerToNodesMap();
        Map<InetAddress, Set<NodeBridge>> contrPhysicalConn = getContrBridgesPhisicalConnMap();
        for (InetAddress contr : clusterServices
                .getClusteredControllers()) {
            sb.append(contr.getHostName());
            sb.append(" : ");
            int number_sw_master = 0;
            Set<Node> set = contrMasters.get(contr);
            if (set != null)
                number_sw_master = set.size();
            sb.append(number_sw_master);
            sb.append("(");
            int number_sw_total = 0;
            Set<NodeBridge> set_total = contrPhysicalConn.get(contr);
            if (set_total != null)
                number_sw_total = set_total.size();
            sb.append(number_sw_total);
            sb.append(") , ");
        }
        return sb.toString();
    }

    /**
     * For every controller it writes the following: contr -->
     * [cpuload queueLenght avgNumberMsgsReceivedFromTheLastRequest
     * elapsedTime numberSwitces_as_master total_no_sw_connected]
     */
    String contrLoadStatsToString() {
        StringBuilder sb = new StringBuilder();
        Map<InetAddress, Set<Node>> contrMasters = getControllerToNodesMap();
        // logger.warn("{}",contrMasters);
        Map<InetAddress, Set<NodeBridge>> contrPhysicalConn = getContrBridgesPhisicalConnMap();
        for (InetAddress contr : contrLoadStatistics.keySet()) {
            LoadStatistics ls = contrLoadStatistics.get(contr);
            sb.append(contr.getHostName());
            sb.append(" : [");
            sb.append(ls.getCpuLoad());
            sb.append(" ");
            sb.append(ls.getIncomingQueueLength());
            sb.append(" ");
            float elapsed = ls.getTime();
            long avgNrMsgs = 0;
            if (elapsed != 0)
                avgNrMsgs = (long) (ls.nrTotalMsgs() / elapsed * 1000);
            sb.append(avgNrMsgs);
            sb.append(" ");
            sb.append(elapsed);
            sb.append(" ");
            int number_sw_master = 0;
            Set<Node> set = contrMasters.get(contr);
            if (set != null)
                number_sw_master = set.size();
            sb.append(number_sw_master);
            sb.append("(");
            int number_sw_total = 0;
            Set<NodeBridge> set_total = contrPhysicalConn.get(contr);
            if (set_total != null)
                number_sw_total = set_total.size();
            sb.append(number_sw_total);
            sb.append(")");
            // sb.append(ls.getNodeNrReqSortedByNrMsgs().values().toString());
            sb.append("], ");
        }
        return sb.toString();
    }

    /**
     * Method used to get the current statistics and request new ones
     * that, when arrive, could trigger the balance or resize
     * operation. It is called from the testing client. No local use.
     */
    String getStatsAndRequestOtherStats(boolean ignoreNextSt) {
        logger.trace(
                "getStatsAndRequestOtherStats ignoreNextStat={}",
                ignoreNextSt);
        String resp = contrLoadStatsToString();
        if ((balancing_in_progress == false) && (!AUTO_BALANCE)) {
            balancing_in_progress = true;
            this.ignoreNextStat = ignoreNextSt;
            statisticsResponses = new HashSet<InetAddress>();
            request_number++;
            Long nextReqId = new Long(request_number);
            // logger.warn("_____request("+ nextReqId+
            // ")at "+System.currentTimeMillis());
            contrLoadStatisticsRequest.put(REQ_STATISTICS_ADDRESS,
                    nextReqId);
        }

        logger.trace("stats={} ", resp, nodeMasterControllers);
        return resp;
    }

    public void _requestStats(CommandInterpreter ci) {
        getStatsAndRequestOtherStats(false);

        for (InetAddress ia : contrLoadStatisticsRequest.keySet()) {
            System.out.println(ia + " --> "
                    + contrLoadStatisticsRequest.get(ia));
        }
    }

    /**
     * Starts the migration protocol. The swToBeMoved will migrate
     * from controller src to controller dst
     */
    synchronized ExitStatus startMigrationProto(
            NodeBridge nodeBridge, InetAddress src, InetAddress dst) {

        Node swToBeMoved = nodeBridge.getBridgeVirtualNode();

        try {
            Long currTime = new Long(System.currentTimeMillis());
            if (src.equals(dst)) {
                String msg = String
                        .format("The src and destination are the same:%s. Ignoring this request",
                                src);
                return new ExitStatus(false, msg);
            }

            TripleContrContrNode oldTriple = pendingMigrations
                    .putIfAbsent(swToBeMoved,
                            new TripleContrContrNode(src, dst,
                                    nodeBridge));
            logger.debug(
                    "The migration entry (src={} dst={} sw={}) has been written in the cache",
                    src, dst, swToBeMoved);
            if (oldTriple != null) {
                String msg = String
                        .format("there is another pending migration for node= %s. Ignoring this request.",
                                swToBeMoved);
                return new ExitStatus(false, msg);
            }
            logger.debug(
                    "+++++++++++++++++++++++++++++++startMigrationProto node={} src={} dst={}",
                    nodeBridge.getBridgeVirtualNode(), src, dst);

            migrationTiming.put(swToBeMoved, currTime);
            if (selectiveInitConnection) {
                try {
                    clusterServices.tbegin();
                } catch (Exception e) {
                    String msg = "clusteService problem with tbegin. ";
                    logger.warn(msg + Util.exceptionToString(e));
                    pendingMigrations.remove(swToBeMoved);
                    return new ExitStatus(false, msg);
                }
                Set<InetAddress> setContrs = bridgeContrPhysicalConn
                        .get(nodeBridge);
                if (setContrs == null)
                    setContrs = new HashSet<InetAddress>();
                setContrs.add(dst);
                bridgeContrPhysicalConn.put(nodeBridge, setContrs);
                try {
                    clusterServices.tcommit();
                } catch (Exception e) {
                    String msg = String
                            .format("clusteService problem with tcommit. Cannot add the mapping %s-->%s",
                                    nodeBridge, setContrs);
                    logger.warn(msg + Util.exceptionToString(e));
                    try {
                        clusterServices.trollback();
                    } catch (Exception e1) {
                        logger.warn(
                                "cannot rollback the {}-->{} e={}",
                                nodeBridge, setContrs,
                                Util.exceptionToString(e));
                    }
                    pendingMigrations.remove(swToBeMoved);
                    return new ExitStatus(false, msg);

                }
                boolean ok = ovsdbConnectionService.setOFController(
                        nodeBridge.getContainerNode(),
                        nodeBridge.getBridgeUUID(), dst);
                if (!ok) {
                    logger.warn(
                            "ovsdb:cannot connect the sw={} to contr={}",
                            nodeBridge.getBridgeVirtualNode(), dst);
                    pendingMigrations.remove(swToBeMoved);
                    return new ExitStatus(ok,
                            "ovsdb:cannot connect the sw="
                                    + nodeBridge
                                            .getBridgeVirtualNode()
                                    + " contr=" + dst);

                }

            }
            // monitors that help the synchronization among the 3
            // threads
            CountDownLatch latch4clients = new CountDownLatch(1);
            CountDownLatch latch4lb = new CountDownLatch(2);
            ClientTcpMigration client_contr1 = new ClientTcpMigration(
                    portMigrationServer, this, swToBeMoved, src, dst,
                    true, latch4clients, latch4lb);
            logger.debug(
                    "There are {} clients waiting to send migration commmands",
                    migrationsTasks.size());
            executorService.submit(client_contr1);
            ClientTcpMigration client_contr2 = new ClientTcpMigration(
                    portMigrationServer, this, swToBeMoved, src, dst,
                    false, latch4clients, latch4lb);
            executorService.submit(client_contr2);
            /*
             * wait until both controller core modules are aware of
             * this pending migration
             */
            try {
                latch4lb.await();
            } catch (InterruptedException e) {
                logger.error(
                        "migration exeption while waiting both contr to start "
                                + "the migration protocol e={}",
                        Util.exceptionToString(e));
                pendingMigrations.remove(swToBeMoved);

            }
            /*
             * changing the map(that will be used only at the end of
             * the migration)
             */
            List<Node> l = new LinkedList<Node>();
            l.add(swToBeMoved);
            migrateNodeAtomicMap(l, dst);
            client_contr1.sendReadyEndMigration(swToBeMoved);
            client_contr2.sendReadyEndMigration(swToBeMoved);

        } catch (Exception e) {
            String msg = String
                    .format("Cannot put the migration-signaler entry (src=%s dst=%s sw=%s) in the cache. \nRolling back",
                            src, dst, swToBeMoved);
            logger.warn(msg + Util.exceptionToString(e));
            pendingMigrations.remove(swToBeMoved);
            return new ExitStatus(false, msg);
        }
        return new ExitStatus(true);

    }

    /**
     * This method starts the migration of node node from its
     * controller to dst
     */
    ExitStatus startMigrationProto(NodeBridge nodeBridge,
            InetAddress dst) {
        Node node = nodeBridge.getBridgeVirtualNode();
        Set<InetAddress> controllers = nodeMasterControllers
                .get(node);
        if (controllers == null) {
            String msg = String
                    .format("cannnot start migration. The node=%s is not connected to any controller ",
                            node);
            logger.error(msg);
            return new ExitStatus(false, msg);
        }
        if (controllers.size() != 1) {
            String msg = String
                    .format("cannnot start migration. The node=%s is connected to %s controllers. Only one is allowed.",
                            node, controllers.size());
            return new ExitStatus(false, msg);
        }

        return startMigrationProto(nodeBridge, controllers.iterator()
                .next(), dst);

    }

    Set<Node> getConnectedToOvsNodes() {
        return connectedToOVS;
    }

    /**
     * method called from each TCP client that connects to one of the
     * 2 controllers that should exchange their role. It says that the
     * controller contr has finished the migration of node node.
     */
    synchronized void endMigrationCallBack(InetAddress contr,
            Node node) {
        logger.debug(
                "-----------------endMigrationCallBack contr={} node={}",
                contr, node);
        InetAddress prevAck = endMigration.get(node);
        TripleContrContrNode t = pendingMigrations.get(node);
        // the dst controller should finish before because it is less
        // loaded
        if (t != null && t.getDstContr().equals(contr)
                && !ignoreMigrationTime.contains(node)) {
            long diff = System.currentTimeMillis()
                    - migrationTiming.get(node).longValue();
            sumPartMigrTime.addAndGet(diff);
        }
        if (prevAck == null) {
            endMigration.put(node, contr);
        } else if (!prevAck.getHostAddress().equals(
                contr.getHostAddress())) {
            endMigration.remove(node);
            TripleContrContrNode triple = pendingMigrations.get(node);
            if (triple == null) // cannot do anything without it
                return;
            NodeBridge nodeBridge = triple.getNodeBridge();
            /** if true disconnects the sw from its initial controller */
            if (selectiveInitConnection) {

                try {
                    ovsdbConnectionService.removeOFController(
                            nodeBridge.getContainerNode(),
                            nodeBridge.getBridgeUUID(),
                            triple.getSrcContr());
                } catch (Exception e2) {
                    logger.warn("{}", Util.exceptionToString(e2));
                }
            }
            try {
                clusterServices.tbegin();
            } catch (Exception e) {
                String msg = "clusteService problem with tbegin. ";
                logger.warn("{} err={}", msg,
                        Util.exceptionToString(e));
                return;
            }
            Set<InetAddress> setContrsPhysic = bridgeContrPhysicalConn
                    .get(nodeBridge);
            if (selectiveInitConnection && setContrsPhysic != null) {
                setContrsPhysic.remove(triple.getSrcContr());
                bridgeContrPhysicalConn.put(nodeBridge,
                        setContrsPhysic);
            }
            ignoreMigrationTime.remove(node);
            pendingMigrations.remove(node);
            try {
                clusterServices.tcommit();
            } catch (Exception e) {
                String msg = String
                        .format("clusteService problem with tcommit. Cannot add the mapping %s-->%s",
                                nodeBridge, setContrsPhysic);
                logger.warn("{} err={}", msg,
                        Util.exceptionToString(e));
                try {
                    clusterServices.trollback();
                } catch (Exception e1) {
                    logger.warn(
                            "cannot rollback the {}-->{} due to:{}",
                            nodeBridge, setContrsPhysic,
                            Util.exceptionToString(e1));
                }
                pendingMigrations.remove(node);
            }

            if (!ignoreMigrationTime.contains(node)) {
                long diff = System.currentTimeMillis()
                        - migrationTiming.get(node).longValue();
                sumMigrTime.addAndGet(diff);
                nrMigrations.incrementAndGet();
                logger.debug(
                        "THE MIGRATION {} COMPLETED in <<<<{}ms>>>>.The avg is {}",
                        triple, diff, sumMigrTime.get()
                                / (double) nrMigrations.get());
                /* the message received before start migration */
                String srcDstInfo = messageStartMigr.remove(node);
                if (srcDstInfo != null)
                    listMsgsStartMigr.add(srcDstInfo);

            }

        }

    }

    /**
     * Method called from the TCP testing server. It gets the average
     * total/partial migration time of all the migrations that
     * happened since the last call of this function. The first call
     * returns the average since the controller's start.
     */
    synchronized String getAvgMigrationTimingAndReset() {
        long sum = sumMigrTime.getAndSet(0);
        long partialSum = sumPartMigrTime.getAndSet(0);
        long nrMigr = nrMigrations.getAndSet(0);
        if (nrMigr == 0)
            return "0\t0";
        return (sum / (float) nrMigr) + "\t"
                + (partialSum / (float) nrMigr);
    }

    /**
     * Auxiliary method. Used only for debugging and testing purposes.
     */
    public String mapControllerNodesToString() {
        Map<InetAddress, Set<Node>> cn = getControllerToNodesMap();
        StringBuffer sb = new StringBuffer();
        for (InetAddress ia : cn.keySet()) {
            sb.append(ia.getHostAddress() + " : [ ");
            for (Node n : cn.get(ia)) {
                sb.append(n + ", ");
            }
            sb.append("]\n");
        }
        return sb.toString() + "\n";
    }

    /**
     * Auxiliary method. Used only for debugging and testing purposes.
     */
    public String mapNodeControllersToString() {
        ArrayList<Node> arr = new ArrayList<Node>(
                nodeMasterControllers.keySet());
        Collections.sort(arr, new Comparator<Node>() {

            @Override
            public int compare(Node o1, Node o2) {
                return o1.toString().compareTo(o2.toString());
            }

        });
        StringBuffer sb = new StringBuffer();

        for (Node node : arr) {
            Set<InetAddress> controllers = nodeMasterControllers
                    .get(node);
            sb.append(node + " : [ ");
            for (InetAddress ia : controllers) {
                sb.append(ia.getHostAddress() + ", ");
            }
            sb.append("]\n");
        }
        double avg = 0;
        if (nrMigrations.get() != 0)
            avg = sumMigrTime.get() / (double) nrMigrations.get();

        return sb.toString() + "\nAvg migration time = " + avg + "\n";
    }

    /** callback methods for events related to the distributed cache */
    @Override
    public void entryCreated(Object key, String cacheName,
            boolean originLocal) {
    }

    @Override
    public void entryUpdated(Object key, Object new_value,
            String cacheName, boolean originLocal) {
        logger.trace(
                "entryUpdated(Object key={}, Object new_value={}, String cacheName={}",
                key, new_value, cacheName);
        if (cacheName.equals(Controller.physicalConnectionsCacheName))
            entryUpdatedOFConn((NodeBridge) key,
                    (Set<InetAddress>) new_value);
        else if (cacheName.equals(Controller.loadStatisticsCacheName))
            handleStatisticEntryUpdated((InetAddress) key,
                    (LoadStatistics) new_value);
    }

    @Override
    public void entryDeleted(Object key, String cacheName,
            boolean originLocal) {
    }

    private void entryUpdatedOFConn(NodeBridge nodeBridge,
            Set<InetAddress> setContr) {
        logger.trace("entryUpdatedOFConn nodeBridge={} setContr={}",
                nodeBridge, setContr);

    }

    /**
     * This considers only the number of switches connected to every
     * controllers. So the switch with the smaller number of connected
     * switches will be returned
     */
    private InetAddress getLeastLoadedContr() {
        long minNrBridges = 999999999;
        InetAddress ia = null;
        Map<InetAddress, Set<NodeBridge>> map = getContrBridgesPhisicalConnMap();
        for (InetAddress contr : map.keySet()) {
            Set<NodeBridge> set = map.get(contr);
            if (set.size() < minNrBridges) {
                minNrBridges = set.size();
                ia = contr;
            }
        }
        return ia;
    }

    NodeBridge getNBFromContainerNode(Node containerNode, Node bridge) {
        for (NodeBridge nb : bridgeContrPhysicalConn.keySet()) {
            if (nb.getBridgeVirtualNode().equals(bridge)
                    && nb.getContainerNode().equals(containerNode))
                return nb;
        }
        return null;
    }

    Node getBridgeAsNodeFromUuid(String uuid,
            Map<String, Table<?>> bridges) {
        Bridge bridgeTable = (Bridge) bridges.get(uuid);
        Node bridgeAsNode = null;
        try {
            String dataPathId = bridgeTable.getOther_config().get(
                    "datapath-id");
            bridgeAsNode = NodeCreator.createOFNode(Long.parseLong(
                    dataPathId, 16));
        } catch (Exception e) {
            logger.warn(
                    "cannot create the SAL Node for bridgeUUID={} due to: {}",
                    uuid, Util.exceptionToString(e));
        }
        return bridgeAsNode;
    }

    /**
     * decides the initial mapping bridge --> set controllers for
     * every bridge in node(that could be the OVS) and bridge --> set
     * masters
     */
    private void decideOptimalConnectionAndMasterMapping(Node node) {
        Map<String, Table<?>> bridges = ovsdbInventoryService
                .getTableCache(node, Bridge.NAME.getName());
        if (bridges == null) {
            logger.warn("decideOptimalMapping bridges=null ---> nothing to do");
            return;
        }
        List<String> listUuids = new LinkedList<String>(
                bridges.keySet());
        for (String uuid : listUuids) {
            Set<InetAddress> setContr = null;
            InetAddress leastLoadedContr = getLeastLoadedContr();
            if (selectiveInitConnection) {
                setContr = new HashSet<InetAddress>();
                setContr.add(leastLoadedContr);
            } else {
                setContr = new HashSet<InetAddress>(
                        clusterServices.getClusteredControllers());
            }

            Node bridgeAsNode = getBridgeAsNodeFromUuid(uuid, bridges);
            // setting the master
            Status st = putMasterControllerToNode(leastLoadedContr,
                    bridgeAsNode);
            if (st.equals(StatusCode.SUCCESS))
                logger.warn(
                        "cannot add the contr={} to node={} due to {}",
                        leastLoadedContr, bridgeAsNode, st);

            NodeBridge nb = new NodeBridge(node, uuid, bridgeAsNode);
            try {
                clusterServices.tbegin();
            } catch (Exception e) {
                logger.warn(
                        "clusteService problem with tbegin. Cannot add the mapping {}-->{} due to {}",
                        nb, setContr, Util.exceptionToString(e));
                continue;
            }
            bridgeContrPhysicalConn.put(nb, setContr);
            try {
                clusterServices.tcommit();
            } catch (Exception e) {
                logger.warn(
                        "clusteService problem with tcommit. Cannot add the mapping {}-->{} due to: {}",
                        nb, setContr, Util.exceptionToString(e));
                try {
                    clusterServices.trollback();
                } catch (Exception e1) {
                    logger.warn(
                            "cannot rollback the {}-->{} due to {}",
                            nb, setContr, Util.exceptionToString(e1));
                }

            }
            // connecting switches to the controllers that have been
            // chosen
            for (InetAddress contr_tc : setContr)
                try {
                    ovsdbConnectionService.setOFController(node,
                            uuid, contr_tc);
                } catch (Exception e) {
                    logger.error(
                            "ovsdb: canot set the contr={} for bridge={} within node={} due to{}",
                            contr_tc, uuid, node,
                            Util.exceptionToString(e));
                }
            logger.debug(
                    "The physical bridge controllers mapping {}-->{} has been successfully "
                            + "added in the cache", nb, setContr);
        }
    }

   
    @Override
    public boolean isConnectionAllowedLB() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void coordinatorChanged() {
        logger.warn("the coordinator has been changed");

    }

    String getListMsgsStartMigrAndReset() {
        String returnVal = "";
        StringBuilder sb = new StringBuilder();
        for (String s : listMsgsStartMigr) {
            sb.append(s);
            sb.append(",");
        }
        if (listMsgsStartMigr.size() > 0)
            returnVal = sb.substring(0, sb.length() - 1);
        listMsgsStartMigr = new LinkedList<String>();
        return returnVal;
    }

    private void shrinkClusterAndBalance(int avgLoad,
            Map<InetAddress, LoadStatistics> localMapContrStat) {
        int curr_nr_contr = clusterServices.getClusteredControllers()
                .size();
        Set<InetAddress> shutting_down_ctrls = new HashSet<InetAddress>();
        if (curr_nr_contr >= 2) {
            int cummulative_underload = (THRESHOLD_SHRINK - avgLoad)
                    * curr_nr_contr;
            int nr_extra_contr = cummulative_underload
                    / THRESHOLD_SHRINK;
            if (curr_nr_contr <= nr_extra_contr)
                /* it should remain at least one */
                nr_extra_contr = Math.max(1, curr_nr_contr - 1);
            Map<InetAddress, LoadStatistics> sortedMap = sortByNrSwitches(
                    localMapContrStat, Order.ASCENDING);
            for (InetAddress shut_ctr : sortedMap.keySet()) {
                shutting_down_ctrls.add(shut_ctr);
                if (shutting_down_ctrls.size() >= nr_extra_contr)
                    break;
            }
        }

        Map<InetAddress, LoadStatistics> overLoaded = new HashMap<InetAddress, LoadStatistics>();
        Map<InetAddress, LoadStatistics> nonOverLoaded = new HashMap<InetAddress, LoadStatistics>();

        /** I divide the controllers in overloaded and non overloaded */
        for (InetAddress contr : localMapContrStat.keySet()) {
            if (localMapContrStat.get(contr).getCpuLoad() > avgLoad
                    + THRESHOLD_BALANCE) {
                overLoaded.put(contr, localMapContrStat.get(contr));
            } else {
                nonOverLoaded
                        .put(contr, localMapContrStat.get(contr));
            }
        }
        Set<TripleContrContrNode> migrations = balanceGetMigr_internal(
                overLoaded, nonOverLoaded, avgLoad,
                shutting_down_ctrls);
        executeMigrations(migrations);

        for (InetAddress contr : shutting_down_ctrls)
            stopController(contr, localMapContrStat);

    }

    /**
     * method that should call the computing platform asking for
     * shutdown of the machine contr
     */
    private void stopController(InetAddress contr,
            Map<InetAddress, LoadStatistics> localMapContrStat) {

    }

    private void expandCluster(int avgLoad,
            Map<InetAddress, LoadStatistics> localMapContrStat) {
        int curr_nr_contr = clusterServices.getClusteredControllers()
                .size();
        int cummulative_overload = (avgLoad - THRESHOLD_EXPAND)
                * curr_nr_contr;
        int nr_additional_contr_needed = cummulative_overload
                / THRESHOLD_EXPAND;
        if (cummulative_overload % THRESHOLD_EXPAND > 0)
            nr_additional_contr_needed++;

        for (int i = 0; i < nr_additional_contr_needed
                - waiting_number_additionalCtrls.get(); i++) {
            startNewController(localMapContrStat);
        }
    }

    /**
     * method that should call the computing platform asking for a new
     * machine and as soon as it becomes available it should configure
     * it.
     */
    private void startNewController(
            Map<InetAddress, LoadStatistics> localMapContrStat) {

    }

    private int clusterAvgCpuLoad(Map<InetAddress, LoadStatistics> map) {
        float sum = 0;
        int nr_contr = 0;
        for (InetAddress contr : clusterServices
                .getClusteredControllers()) {
            if (!map.containsKey(contr)) {
                logger.error(
                        "There is no load statistics from contr {}",
                        contr);
                return 0;
            }
            nr_contr++;
            sum += map.get(contr).getCpuLoad();
        }
        if (nr_contr == 0) {
            logger.warn("got statistics only from {} out of {}",
                    nr_contr, clusterServices
                            .getClusteredControllers().size());
            return 0;
        }
        return (int) (sum / nr_contr);
    }

    public static Map<InetAddress, LoadStatistics> sortByLoad(
            Map<InetAddress, LoadStatistics> map, Order order) {
        List<Map.Entry<InetAddress, LoadStatistics>> list = new LinkedList<>(
                map.entrySet());
        Comparator comparator;
        if (order == order.ASCENDING) {
            comparator = new Comparator<Map.Entry<InetAddress, LoadStatistics>>() {

                @Override
                public int compare(
                        Entry<InetAddress, LoadStatistics> o1,
                        Entry<InetAddress, LoadStatistics> o2) {
                    return (int) (o2.getValue().getCpuLoad() - o1
                            .getValue().getCpuLoad());
                }
            };
        } else {
            comparator = new Comparator<Map.Entry<InetAddress, LoadStatistics>>() {

                @Override
                public int compare(
                        Entry<InetAddress, LoadStatistics> o1,
                        Entry<InetAddress, LoadStatistics> o2) {
                    return (int) (o1.getValue().getCpuLoad() - o2
                            .getValue().getCpuLoad());
                }
            };
        }
        Collections.sort(list, comparator);

        Map<InetAddress, LoadStatistics> result = new LinkedHashMap<>();
        for (Map.Entry<InetAddress, LoadStatistics> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static Map<InetAddress, LoadStatistics> sortByNrSwitches(
            Map<InetAddress, LoadStatistics> map, Order order) {
        List<Map.Entry<InetAddress, LoadStatistics>> list = new LinkedList<>(
                map.entrySet());
        Comparator comparator;
        if (order == order.ASCENDING) {
            comparator = new Comparator<Map.Entry<InetAddress, LoadStatistics>>() {

                @Override
                public int compare(
                        Entry<InetAddress, LoadStatistics> o1,
                        Entry<InetAddress, LoadStatistics> o2) {
                    return (int) (o1.getValue().getNodeNrReq().size() - o2
                            .getValue().getNodeNrReq().size());
                }
            };
        } else {
            comparator = new Comparator<Map.Entry<InetAddress, LoadStatistics>>() {

                @Override
                public int compare(
                        Entry<InetAddress, LoadStatistics> o1,
                        Entry<InetAddress, LoadStatistics> o2) {
                    return (int) (o2.getValue().getNodeNrReq().size() - o1
                            .getValue().getNodeNrReq().size());
                }
            };
        }
        Collections.sort(list, comparator);

        Map<InetAddress, LoadStatistics> result = new LinkedHashMap<>();
        for (Map.Entry<InetAddress, LoadStatistics> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Map<InetAddress, LoadStatistics> getClonedMap(
            Map<InetAddress, LoadStatistics> map) {
        Map<InetAddress, LoadStatistics> result = new HashMap<InetAddress, LoadStatistics>();
        for (InetAddress contr : map.keySet()) {
            result.put(contr, map.get(contr).getClone());
        }
        return result;
    }

    private void executeMigrations(
            Set<TripleContrContrNode> setMigrations) {
        for (TripleContrContrNode triple : setMigrations) {
            if (checkBeforeMigrReqOK(triple))
                startMigrationProto(triple.getNodeBridge(),
                        triple.getSrcContr(), triple.getDstContr());
        }
    }

    private Set<TripleContrContrNode> balance_get_migrations(
            Map<InetAddress, LoadStatistics> localMapContrStat,
            int avgLoad) {
        logger.trace(
                "balance_get_migrations localMapStat={} avgLoad={}",
                localMapContrStat, avgLoad);
        Map<InetAddress, LoadStatistics> overLoaded = new HashMap<InetAddress, LoadStatistics>();
        Map<InetAddress, LoadStatistics> nonOverLoaded = new HashMap<InetAddress, LoadStatistics>();

        /** I divide the controllers in overloaded and non overloaded */
        for (InetAddress contr : localMapContrStat.keySet()) {
            if (localMapContrStat.get(contr).getCpuLoad() > avgLoad
                    + THRESHOLD_BALANCE) {
                overLoaded.put(contr, localMapContrStat.get(contr));
            } else {
                nonOverLoaded
                        .put(contr, localMapContrStat.get(contr));
            }
        }
        return balanceGetMigr_internal(overLoaded, nonOverLoaded,
                avgLoad, null);

    }

    private Set<TripleContrContrNode> balanceGetMigr_internal(
            Map<InetAddress, LoadStatistics> overLoaded,
            Map<InetAddress, LoadStatistics> nonOverLoaded,
            int avgLoad, Set<InetAddress> shutting_down_ctrls) {
        logger.trace(
                "balanceGetMigr_internal oveloaded={} nonoverloaded={} "
                        + "avgload={} shutting_down_ctrls={}",
                overLoaded.keySet(), nonOverLoaded.keySet(), avgLoad,
                shutting_down_ctrls);
        Set<TripleContrContrNode> migrationSet = new HashSet<TripleContrContrNode>();
        Map<Integer, Set<TupleNodeContr>> loadClsSwitchContr = new HashMap<Integer, Set<TupleNodeContr>>();
        int nr_ctrls_shuttingDown = (shutting_down_ctrls == null) ? 0
                : shutting_down_ctrls.size();
        // the avg should be higher if some controllers are being shut
        // down
        if (nr_ctrls_shuttingDown > 0) {
            int nr_ctrl_cluster = clusterServices
                    .getClusteredControllers().size();
            avgLoad = avgLoad * nr_ctrl_cluster
                    / (nr_ctrl_cluster - nr_ctrls_shuttingDown);
            // moving artificially the shutting down contrs in the
            // overloaded map
            for (InetAddress shutting_ctr : shutting_down_ctrls) {
                LoadStatistics ls = nonOverLoaded
                        .remove(shutting_ctr);
                if (ls != null)
                    overLoaded.put(shutting_ctr, ls);
            }
        }
        /**
         * I divide the switches connected to overloaded controllers
         * in classes of load impact. A class is given by the amount
         * of CPU that is being utilized by a switch. Optimization
         * driven choice.
         */
        for (InetAddress ov_ctrl : overLoaded.keySet()) {
            int cpuload = overLoaded.get(ov_ctrl).getCpuLoad();
            long nr_total_msgs = overLoaded.get(ov_ctrl)
                    .nrTotalMsgs();
            int cpuImpact;
            Map<Node, Long> nodeNrReq = overLoaded.get(ov_ctrl)
                    .getNodeNrReq();
            for (Node node : nodeNrReq.keySet()) {
                if (nr_total_msgs != 0) {
                    cpuImpact = (int) (nodeNrReq.get(node)
                            .longValue() / (float) (nr_total_msgs) * cpuload);
                } else {
                    cpuImpact = 0;
                }
                if (cpuImpact < THRESHOLD_MIGR_SINGLE_SW)
                    continue; // this switch does non have a
                              // significant impact
                int loadClass = (int) (cpuImpact / THRESHOLD_MIGR_SINGLE_SW);
                Set<TupleNodeContr> setNC = loadClsSwitchContr
                        .get(loadClass);
                if (setNC == null) {
                    setNC = new HashSet<TupleNodeContr>();
                    setNC.add(new TupleNodeContr(node, ov_ctrl,
                            cpuImpact));
                    loadClsSwitchContr.put(loadClass, setNC);
                } else {
                    setNC.add(new TupleNodeContr(node, ov_ctrl,
                            cpuImpact));
                }
            }
        }

        /**
         * Iterating over all non overloaded controllers trying to
         * transfer to them an amount of load up to the cluster
         * average load
         **/
        for (InetAddress dst_contr : nonOverLoaded.keySet()) {
            LoadStatistics ls_dst = nonOverLoaded.get(dst_contr);
            int cpuload = ls_dst.getCpuLoad();
            int availableLoad_dst = avgLoad - cpuload
                    + THRESHOLD_BALANCE / 2;
            if (availableLoad_dst < THRESHOLD_MIGR_SINGLE_SW)
                continue;
            boolean improving;
            // filling the current controller
            do {
                improving = false;
                // searching the best switch to fill the current
                // available space
                for (int load_class = availableLoad_dst
                        / THRESHOLD_MIGR_SINGLE_SW; load_class >= 0; load_class--) {
                    Set<TupleNodeContr> list_sw_ctrl = loadClsSwitchContr
                            .get(load_class);
                    if (list_sw_ctrl == null)
                        continue;
                    TupleNodeContr nodeCtrl = null;

                    nodeCtrl = findMostLoadingSwUnder(
                            availableLoad_dst, list_sw_ctrl,
                            overLoaded, avgLoad, shutting_down_ctrls);
                    // there is no sw that can be moved to this
                    // controller
                    if (nodeCtrl == null)
                        continue;
                    Node node = nodeCtrl.getNode();
                    InetAddress src_ctrl = nodeCtrl.getContr();
                    int transferedLoad = nodeCtrl.getLoadImpact();

                    LoadStatistics ls_src = overLoaded.get(src_ctrl);
                    ls_src.getNodeNrReq().remove(node);
                    int load_src = ls_src.getCpuLoad();
                    load_src -= transferedLoad;
                    ls_src.setCpuLoad(load_src);
                    NodeBridge nodeBr = Util.getNodeBridgeFromNode(
                            node, bridgeContrPhysicalConn);
                    if (nodeBr == null) {
                        logger.warn(
                                "cannot retrieve more detailed info about node={}",
                                node);
                        continue;
                    }
                    TripleContrContrNode migrTriple = new TripleContrContrNode(
                            src_ctrl, dst_contr, nodeBr);
                    if (!checkBeforeMigrReqOK(migrTriple)) {
                        logger.warn(
                                "migr={} is anomalous: nodeMaster={} nodePhysConn={}",
                                migrTriple,
                                Util.nodeMastersToStr(nodeMasterControllers),
                                Util.nodeConnectionsToStr(bridgeContrPhysicalConn));
                        break;
                    }
                    migrationSet.add(migrTriple);
                    availableLoad_dst -= transferedLoad;
                    if (availableLoad_dst < THRESHOLD_MIGR_SINGLE_SW)
                        break;
                    improving = true;
                }
            } while (improving);
        }
        if (nr_ctrls_shuttingDown > 0) {
            Set<TripleContrContrNode> moreMigrations = getLeftMigrShuttingDownCtrls(
                    overLoaded, nonOverLoaded, shutting_down_ctrls);
            if (moreMigrations != null && moreMigrations.size() > 0)
                migrationSet.addAll(moreMigrations);
        }
        if (migrationSet.size() > 0)
            logger.warn(
                    "\n--------------------------------------------------------\nsetMigrations=\n{}\n",
                    migrationSet);
        return migrationSet;
    }

    /*
     * checks if this migration can be fulfilled. It may happen that
     * the previous requested migrations have not been completed. A
     * too frequent rebalancing may lead to this.
     */
    private boolean checkBeforeMigrReqOK(
            TripleContrContrNode migrTriple) {
        if (migrTriple == null)
            return false;
        InetAddress src = migrTriple.getSrcContr();
        if (src == null)
            return false;
        NodeBridge nodeBridge = migrTriple.getNodeBridge();
        Node bridge = nodeBridge.getBridgeVirtualNode();
        Set<InetAddress> setMasterCtr = nodeMasterControllers
                .get(bridge);
        if (setMasterCtr == null)
            return false;
        if (!setMasterCtr.contains(src))
            return false;
        Set<InetAddress> setConn = bridgeContrPhysicalConn
                .get(nodeBridge);
        if (setConn == null)
            return false;
        if (!setConn.contains(src))
            return false;
        return true;
    }

    /**
     * it returns the migration needed to free completely the
     * controllers that are being shutted down
     */
    private Set<TripleContrContrNode> getLeftMigrShuttingDownCtrls(
            Map<InetAddress, LoadStatistics> overLoaded,
            Map<InetAddress, LoadStatistics> nonOverLoaded,
            Set<InetAddress> shutting_down_ctrls) {
        Set<TripleContrContrNode> setMigr = new HashSet<TripleContrContrNode>();
        if (shutting_down_ctrls == null)
            return setMigr;
        for (InetAddress contr : shutting_down_ctrls) {
            Map<Node, Long> nodeNrmsgs = overLoaded.get(contr)
                    .getNodeNrReqSortedByNrMsgs(); // descending order
            // repeat until all switches connected to this controller
            // are put in
            // the migration set
            while (!nodeNrmsgs.isEmpty()) {
                Iterator<Node> iter = nodeNrmsgs.keySet().iterator();
                Map<InetAddress, LoadStatistics> sortedContrStat = sortByLoad(
                        nonOverLoaded, Order.ASCENDING);
                // the switches are distributed among all controllers,
                // the sw
                // generating the highest load to the controller with
                // the
                // smaller load
                for (InetAddress dst_contr : sortedContrStat.keySet()) {
                    if (!iter.hasNext())
                        break;
                    Node node = iter.next();
                    iter.remove();
                }
            }
        }
        return null;
    }

    /**
     * Find the switch that generates the biggest load smaller than
     * availableLoad. It also take into account the donor's load. It
     * is not a good to generate imbalance by subtracting too much
     * load.
     */
    private TupleNodeContr findMostLoadingSwUnder(int availableLoad,
            Set<TupleNodeContr> list_sw_ctrl,
            Map<InetAddress, LoadStatistics> overLoaded, int avgLoad,
            Set<InetAddress> shutting_down_ctrls) {
        TupleNodeContr tuple_result = null;
        for (TupleNodeContr tuple : list_sw_ctrl) {
            int cpuImpact = tuple.getLoadImpact();
            if (cpuImpact < availableLoad) {
                Node node = tuple.getNode();
                InetAddress src_ctrl = tuple.getContr();
                // if the contr is being shutted down all its switches
                // should be
                // moved
                // so the underload is normal
                if (shutting_down_ctrls != null
                        && shutting_down_ctrls.contains(src_ctrl)) {
                    tuple_result = tuple;
                    break;
                }
                // I have to not underload the controller that is
                // connected to
                // this sw
                if (overLoaded.get(src_ctrl).getCpuLoad() - cpuImpact > avgLoad
                        - THRESHOLD_BALANCE / 2) {
                    tuple_result = tuple;
                    break;
                }
            }
        }
        // removing the switch from the list, s.t. it will not be
        // picked again
        if (tuple_result != null)
            list_sw_ctrl.remove(tuple_result);
        return tuple_result;
    }

    private void handleStatisticEntryUpdated(InetAddress ia,
            LoadStatistics ls) {
        logger.trace("handleStatisticEntryUpdated ia={} ls={}", ia,
                ls);
        if (!clusterServices.amICoordinator() || ia == null) {
            balancing_in_progress = false;
            return;
        }

        statisticsResponses.add(ia);
        if (statisticsResponses.size() == clusterServices
                .getClusteredControllers().size()) {
            if (ignoreNextStat) { // the first statistic request
                                  // should not
                                  // be used
                ignoreNextStat = false;
            } else {
                Map<InetAddress, LoadStatistics> localMapContrStat = getClonedMap(contrLoadStatistics);
                int avgLoad = clusterAvgCpuLoad(localMapContrStat);
                if (avgLoad > THRESHOLD_EXPAND) {

                    expandCluster(avgLoad, localMapContrStat);
                } else if (avgLoad < THRESHOLD_SHRINK) {
                    shrinkClusterAndBalance(avgLoad,
                            localMapContrStat);
                } else {
                    Set<TripleContrContrNode> setMigrations = balance_get_migrations(
                            localMapContrStat, avgLoad);
                    executeMigrations(setMigrations);
                }
            }
            balancing_in_progress = false;
        }

    }

}
