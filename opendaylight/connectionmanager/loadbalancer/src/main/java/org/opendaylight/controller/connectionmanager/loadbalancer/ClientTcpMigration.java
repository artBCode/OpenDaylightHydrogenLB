package org.opendaylight.controller.connectionmanager.loadbalancer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;

import org.eclipse.osgi.framework.internal.core.Tokenizer;
import org.opendaylight.controller.protocol_plugin.openflow.migration.MultiThreadedServer;
import org.opendaylight.controller.protocol_plugin.openflow.migration.Util;
import org.opendaylight.controller.sal.core.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientTcpMigration implements Runnable {
    private InetAddress contrConn;
    private int port = 8088;
    private Socket clientSocket = null;
    private ManagerLB mlb;
    private InetAddress src;
    private InetAddress dst;
    private Node node;
    private boolean initiallyMaster;
    private static final Logger logger = LoggerFactory
            .getLogger(ClientTcpMigration.class);
    private CountDownLatch latch4clients;
    private CountDownLatch latch4lb;
    private CountDownLatch latchReadyEndMigr;
    private BufferedReader inFromServer;
    private BufferedWriter out;
    private String migrationIdString;

    public ClientTcpMigration(int port, ManagerLB mlb, Node node,
            InetAddress src, InetAddress dst, boolean initiallyMaster,
            CountDownLatch latch4clients, CountDownLatch latch4lb) {
        /** controller that it should connect to */
        this.contrConn = initiallyMaster ? src : dst;
        /** the initial master */
        this.src = src;
        /** the new master */
        this.dst = dst;
        this.initiallyMaster = initiallyMaster;
        /** the tcp port of the TCP testing server */
        this.port = port;
        /** the switch that should migrate */
        this.node = node;
        /** reference to the managerLB component */
        this.mlb = mlb;
        /**
         * synchronization start migration. the src waits that also
         * dst starts migraion
         */
        this.latch4clients = latch4clients;
        /**
         * becomes 0 when both clients have stated migration for
         * switch node
         */
        this.latch4lb = latch4lb;
        /**
         * becomes 0 to indicate that the the migration can be
         * ended(send and remove dummy-flow)
         */
        this.latchReadyEndMigr = new CountDownLatch(1);
        migrationIdString = "migration:(src=" + src + " dst=" + dst + " node="
                + node + ")";
    }

    private boolean writeAndFlush(BufferedWriter out, String request) {
        try {
            out.write(request + "\n");
            out.flush();
        } catch (IOException e) {
            if (request != null)
                logger.error("{}: cannot write the request={} for {} err={}",
                        migrationIdString, request, contrConn,
                        Util.exceptionToString(e));
            return false;
        }
        logger.debug("{}:the request={} has been successfully sent to {}",
                migrationIdString, request, contrConn);
        return true;

    }

    // called from ManagerLB
    boolean sendReadyEndMigration(Node node) {
        latchReadyEndMigr.countDown();
        return true;
    }

    @Override
    public void run() {
        logger.debug("{}: {}ms till the beginnig of run", contrConn,
                System.currentTimeMillis()
                        - mlb.migrationTiming.get(node).longValue());
        // opening the connection with the migration server
        try {
            clientSocket = new Socket(contrConn, port);
            logger.info(
                    "{}: the migration client has successfully established the connection with {}",
                    migrationIdString, contrConn);
        } catch (UnknownHostException e) {
            logger.error(
                    "{} cannot establish the connection with {} due to e={}",
                    migrationIdString, contrConn, Util.exceptionToString(e));
            return;
        } catch (IOException e) {
            logger.error(
                    "{}: cannot establish the connection with {} due to e={}",
                    migrationIdString, contrConn, Util.exceptionToString(e));
            return;
        }

        try {
            inFromServer = new BufferedReader(new InputStreamReader(
                    clientSocket.getInputStream()));
            out = new BufferedWriter(new OutputStreamWriter(
                    clientSocket.getOutputStream()));
        } catch (IOException e) {
            logger.warn(Util.exceptionToString(e));
            return;
        }
        // receiving the greeting msg
        try {
            logger.debug("{}:greeting msg: {}", migrationIdString,
                    inFromServer.readLine());
        } catch (IOException e1) {
            logger.warn(Util.exceptionToString(e1));
        }
        boolean ok;

        // start the work
        ok = sendReceiveStartMigr();
        if (!ok)
            return;

        logger.info("{}: the contr {} has started the migration",
                migrationIdString, contrConn);
        ok = synchronizeAndSendInstallAndRemoveDummyFlow();
        if (!ok)
            return;

        ok = waitEndMigrationMsg();
        if (!ok)
            return;

        // closing the connection with the migration server
        try {
            inFromServer.close();
            out.close();
        } catch (IOException e) {
            logger.warn(Util.exceptionToString(e));
            return;
        }

    }

    private boolean sendReceiveStartMigr() {
        String request = "startMigr "
                + String.format("%d %s %s %b",
                        ((Long) node.getID()).longValue(),
                        src.getHostAddress(), dst.getHostAddress(),
                        initiallyMaster);
        logger.debug("{}:Sendig the start migration request", migrationIdString);
        if (!writeAndFlush(out, request))
            return false;

        try {
            String answer = inFromServer.readLine();
            logger.debug("{}: the server said: '{}'", migrationIdString, answer);
            if (!answer.startsWith("Done"))
                logger.error("{} controller cannot start the migration:{}",
                        contrConn, answer);
            // marking the first pkt-in after start migration
            if (initiallyMaster) {
                StringTokenizer tok = new StringTokenizer(answer.trim());
                tok.nextToken();
                String srcTarget = "";
                while (tok.hasMoreTokens()) {
                    srcTarget += tok.nextToken() + " ";
                }
                if (!srcTarget.isEmpty())
                    mlb.messageStartMigr.put(node, srcTarget);
            }

        } catch (IOException e) {
            logger.error("{}: error during the wait for answer to {}. e={}",
                    migrationIdString, request, Util.exceptionToString(e));
            return false;
        }
        return true;
    }

    private boolean waitAndSendInstallAndRemoveDummy() {
        try {
            latchReadyEndMigr.await();
            logger.debug("{}: the mapControllers map has been changed",
                    migrationIdString);
        } catch (InterruptedException e) {
            logger.warn(
                    "{}:The wait for the map nodeControllers change has been interrupted.e={}",
                    migrationIdString, Util.exceptionToString(e));
            return false;
        }
        String request = "installAndRemoveDummyFlow " + (Long) node.getID();
        if (!writeAndFlush(out, request)) {
            return false;
        }
        return true;
    }

    private boolean synchronizeAndSendInstallAndRemoveDummyFlow() {
        latch4lb.countDown(); // it will signal the beginning of
                              // migration on
                              // both controllers
        if (initiallyMaster) {
            latch4clients.countDown();
        } else { // the dst will install and remove the dummy flow

            try {
                // wait until the other controller starts the
                // migration
                latch4clients.await();
            } catch (InterruptedException e) {
                logger.warn(
                        "Interrupted during the waiting period for the start of the other controller. e={}",
                        Util.exceptionToString(e));
                return false;
            }
            boolean ok = waitAndSendInstallAndRemoveDummy();
            return ok;

        }
        return true;
    }

    private boolean waitEndMigrationMsg() {
        try {
            String answer = inFromServer.readLine();
            logger.debug("{},the server said: '{}'", migrationIdString, answer);
            if (!answer.equals("migrationEnded"))
                logger.error("{}:Expecting migrationEnded but got {}",
                        migrationIdString, answer);
        } catch (IOException e) {
            logger.error(
                    "{}:error during the wait for end migration msg {}. e={}",
                    migrationIdString, Util.exceptionToString(e));
            return false;
        }
        mlb.endMigrationCallBack(contrConn, node);
        logger.debug("{}: {}ms till the ack for end migration", contrConn,
                System.currentTimeMillis()
                        - mlb.migrationTiming.get(node).longValue());

        return true;

    }
}
