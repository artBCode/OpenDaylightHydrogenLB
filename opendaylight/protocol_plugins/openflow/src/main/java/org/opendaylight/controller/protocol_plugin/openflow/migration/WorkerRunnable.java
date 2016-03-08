package org.opendaylight.controller.protocol_plugin.openflow.migration;

/** adapted from http://tutorials.jenkov.com/java-multithreaded-servers/multithreaded-server.html */
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;

import javax.xml.crypto.dsig.keyinfo.RetrievalMethod;

import org.opendaylight.controller.protocol_plugin.openflow.core.internal.Controller;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.NodeCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**

 */
public class WorkerRunnable implements Runnable {

    protected Socket clientSocket = null;
    protected String serverText = null;
    private Controller controller = null;
    private static final Logger logger = LoggerFactory
            .getLogger(WorkerRunnable.class);

    public WorkerRunnable(Socket clientSocket, Controller contr,
            String serverText) {
        this.clientSocket = clientSocket;
        this.serverText = serverText;
        this.controller = contr;
    }

    public void run() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    clientSocket.getInputStream()));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    clientSocket.getOutputStream()));
            out.write("What can I do for you?" + "\n");
            out.flush();
            String request = null;
            try {
                while (true) {
                    request = in.readLine();
                    logger.info("got request={}", request);
                    boolean ok = handleRequest(request, out);
                    if (!ok)
                        return;
                }
            } catch (Exception e) {
                logger.warn("{}", Util.exceptionToString(e));
            }
            in.close();
            out.close();

        } catch (IOException e) {
            logger.warn("{}", Util.exceptionToString(e));
        }
    }

    private boolean writeAndFlush(BufferedWriter out, String req,
            String response) {
        logger.debug("req={} sending the response {}", req, response);
        try {
            out.write(response + "\n");
            out.flush();
        } catch (IOException e) {
            if (req != null)
                logger.warn("canot answer to the {} due to {}", req,
                        Util.exceptionToString(e));
            return false;
        }
        return true;
    }

    private boolean handleRequest(String req, BufferedWriter out) {
        String response = "Invalid Request";
        if (req != null) {
            if (req.startsWith("startMigr")) {
                response = startMigration(req, out);
                return writeAndFlush(out, req, response);
            } else if (req.startsWith("installAndRemoveDummyFlow")) {
                // this will not be notified to the coordinator it has
                // a
                // positive result
                response = installAndRemoveDummyFlow(req);
                if (!response.startsWith("Error")
                        && !response.equals("Invalid Request")) {
                    logger.info("{} has been successfully served", req);
                    return true;

                }
                // sends the response only in case of failure.
                // otherwise there
                // migration
                // ended will be sent
                return writeAndFlush(out, req, response);
            }
        }
        return false;
    }

    /*
     * The request has the following format: "startMigr" switchID
     * initialMaster finalMaster true/false. the last string is the
     * value that will be assigned to migrInProgress. Hence,True if
     * the controller that receives the message is the initial master
     * and false otherwise. The buffer is used to write to the client
     * "endMigration" when the controller receives the dummy-flow
     * removal message
     */
    private String startMigration(String req, BufferedWriter out) {
        String[] tokens = req.trim().split(" ");
        if (tokens.length != 5) {
            logger.warn("Request {} is not valid. Will not be processed.", req);
            return "Error";
        }
        try {
            String extraInfoLastPin = controller.getLastProcessedPacketIn();
            Node node = NodeCreator.createOFNode(Long.parseLong(tokens[1]));
            InetAddress srcContr = InetAddress.getByName(tokens[2]);
            InetAddress dstContr = InetAddress.getByName(tokens[3]);
            boolean initiallyMaster = Boolean.parseBoolean(tokens[4]);
            controller.migrationInProgressON(node, srcContr, dstContr,
                    initiallyMaster, out);
            return "Done " + extraInfoLastPin;

        } catch (Exception e) {
            logger.warn("Request [{}] will not be processed due to:{}", req,
                    Util.exceptionToString(e));
        }
        return "Error";

    }

    /**
     * The req has the following format: "installAndRemoveDummyFlow"
     * switchID
     */
    private String installAndRemoveDummyFlow(String req) {
        String[] tokens = req.trim().split(" ");
        if (tokens.length != 2) {
            logger.error("Request {} is not valid. Will not be processed.", req);
            return "Error";
        }
        try {
            Node node = NodeCreator.createOFNode(Long.parseLong(tokens[1]));
            boolean ok = controller.installAndRemoveDummyFlow(node);
            return ok ? "Done"
                    : "Error:cannot install and remove the dummyflow";
        } catch (Exception e) {
            logger.warn("Request {} has will not be processed due to:{}", req,
                    Util.exceptionToString(e));
        }
        return "Error";
    }

}
