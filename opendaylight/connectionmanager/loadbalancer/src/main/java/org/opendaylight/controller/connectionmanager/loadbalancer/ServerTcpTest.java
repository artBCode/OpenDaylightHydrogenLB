package org.opendaylight.controller.connectionmanager.loadbalancer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.opendaylight.controller.protocol_plugin.openflow.migration.NodeBridge;
import org.opendaylight.controller.protocol_plugin.openflow.migration.Util;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.NodeCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerTcpTest implements Runnable {
    private static final Logger logger = LoggerFactory
            .getLogger(ServerTcpTest.class);
    private ManagerLB mlb;
    private int port;

    public ServerTcpTest(ManagerLB mlb) {
        this.mlb = mlb;
        try {
            this.port = Integer.parseInt(System.getProperty(
                    "managerLB.portServerTest", "45668"));
        } catch (Exception e) {
            logger.warn(Util.exceptionToString(e));
            this.port = 45668;
        }
    }

    private class SocketServer {

        private ServerSocket serverSocket;
        private int port;

        public SocketServer(int port) {
            this.port = port;
        }

        public void start() throws IOException {
            logger.debug("Starting the socket server on port:" + port);
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));
            } catch (IOException e1) {

                logger.error(
                        "The tcp testing server has failed to start up on port {} Error:",
                        port, Util.exceptionToString(e1));
            }

            String request = null;

            // Listen for clients. Block till one connects

            logger.info("Tcp Testing Server: Waiting for clients...");

            while (true) {
                Socket clientSocket = null;
                try {
                    clientSocket = serverSocket.accept();

                    logger.info("New tcp client for testing commands");
                    // A client has connected to this server. Send welcome
                    // message
                    sendWelcomeMessage(clientSocket);

                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(clientSocket.getInputStream()));
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(
                                    clientSocket.getOutputStream()));
                    while (true) {
                        if (in == null)
                            continue;
                        request = in.readLine();
                        logger.debug("The tcp test server received req="
                                + request);

                        writer.write(handleRequest(request));
                        writer.flush();
                    }
                } catch (Exception e) {
                    logger.warn("exeption in the tcp testing server: e={}",Util.exceptionToString(e));
                    if (clientSocket != null)
                        clientSocket.close();
                    if (e instanceof InterruptedException
                            || e instanceof InterruptedIOException) {
                        stopServer();
                        break;
                    }
                }
            }

        }

        public void stopServer() {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error(Util.exceptionToString(e));
            }
        }

        private String handleRequest(String req) {
            String response = "Invalid request";
            if (req.startsWith("getMapContrNodes")) {
                response = mlb.mapControllerNodesToString();
            } else if (req.startsWith("migrateSw")) {
                response = migrateReq(req);
            } else if (req.startsWith("getMapNodeContr")) {
                response = mlb.mapNodeControllersToString();
            } else if (req.startsWith("swDistribution")) {
                response = swDistribution();
            } else if (req.startsWith("getAvgMigrTimeAndReset")) {
                response = mlb.getAvgMigrationTimingAndReset();
            } else if (req.startsWith("getStatistics")) {
                response = getStats(req);
            } else if (req.startsWith("getListMsgsStartMigrAndReset")) {
                response = mlb.getListMsgsStartMigrAndReset();
            }

            return response + "\n";

        }
        private String getStats(String req){
            boolean ignoreNextStat=false;
            StringTokenizer st=new StringTokenizer(req);
            st.nextToken();
            if (st.hasMoreTokens()){
                try{
                    ignoreNextStat=Boolean.parseBoolean(st.nextToken());
                }finally{}
            
            }
            return mlb.getStatsAndRequestOtherStats(ignoreNextStat);
        }

        private String swDistribution() {
            Map<InetAddress, Set<Node>> cn = mlb
                    .getControllerToNodesMap();
            StringBuilder sb = new StringBuilder();
            for (InetAddress ia : cn.keySet()) {
                sb.append(cn.get(ia).size() + ",");
            }
            return sb.toString();
        }
        /** The request has this format 'migrateSw switchId controller [ignoreMigrationTime]' */
        private String migrateReq(String req) {

            String[] tokens = req.trim().split(" ");
            InetAddress dst;
            Node node;
            int id;
            if (tokens.length < 3) {
                logger.warn("The request has an incorrect format");
                return "request_incorrect_format";
            }
            try {
                id = Integer.parseInt(tokens[1]);
                node = NodeCreator.createOFNode((long) id);
                dst = InetAddress.getByName(tokens[2]);
            } catch (Exception e) {
                logger.error("req={} is incorrect e={}", req,
                        Util.exceptionToString(e));
                return "request_incorrect_format";
            }
            if (tokens.length == 4 && tokens[3].toLowerCase().equals("true"))
                mlb.ignoreMigrationTime.add(node);
            if (mlb.getConnectedToOvsNodes().isEmpty())
                return "internal_error";
            Node container = mlb.getConnectedToOvsNodes().iterator().next();
            NodeBridge nodeBridge = mlb.getNBFromContainerNode(container, node);

            ExitStatus es = mlb.startMigrationProto(nodeBridge, dst);
            String returningVal="";
            if (es.isOk()) {
                String additionalInfo = mlb.messageStartMigr.get(node);
                if (additionalInfo != null)
                    returningVal= "Done " + additionalInfo;
                else
                    returningVal= "Done";
            }else{
                returningVal =es.toString();
            }
            returningVal +=" "+ mlb.numberMasterAndTotalConnection();
            return returningVal;

        }

        private void sendWelcomeMessage(Socket client) throws IOException {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    client.getOutputStream()));
            writer.write("Hello. What can I do for you?\n");
            writer.flush();

        }

    }

    @Override
    public void run() {
        SocketServer socketServer = null;
        try {

            // initializing the Socket Server
            socketServer = new SocketServer(port);
            socketServer.start();
            logger.warn("The testing server has been started");

        } catch (IOException e) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e1) {
                logger.warn(Util.exceptionToString(e));
            }
            logger.warn("Restarting the tcp test server due to:{}",Util.exceptionToString(e));
            if (socketServer != null)
                socketServer.stopServer();
            // run();
        }

    }
}