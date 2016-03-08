package org.opendaylight.controller.protocol_plugin.openflow.migration;
/** adapted from http://tutorials.jenkov.com/java-multithreaded-servers/multithreaded-server.html */
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

import org.opendaylight.controller.protocol_plugin.openflow.core.internal.Controller;
import org.opendaylight.controller.sal.core.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiThreadedServer implements Runnable{

    protected int           serverPort   = 8088;
    protected ServerSocket  serverSocket = null;
    protected boolean       isStopped    = false;
    protected Thread        runningThread= null;
    private static final    Logger logger = LoggerFactory
            .getLogger(MultiThreadedServer.class);
    private Controller      controller;
    private ExecutorService executorService;
    private SynchronousQueue<Runnable> migrationsReqTask;
    private final int MAX_NUMBER_SIMULANEOUS_MIGR=100;

    public MultiThreadedServer(int port,Controller contr){
        this.serverPort = port;
        this.controller=contr;
        this.migrationsReqTask = new SynchronousQueue<Runnable>();
        this.executorService = new ThreadPoolExecutor(1, MAX_NUMBER_SIMULANEOUS_MIGR, 60, TimeUnit.SECONDS,
                migrationsReqTask);
    }
    

    public void run(){
        synchronized(this){
            this.runningThread = Thread.currentThread();
        }
        openServerSocket();
        while(! isStopped()){
            Socket clientSocket = null;
            try {
                clientSocket = this.serverSocket.accept();
            } catch (IOException e) {
                if(isStopped()) {
                    logger.info("the migration server is stopped.") ;
                    return;
                }
                throw new RuntimeException(
                    "Error accepting client connection", e);
            }
            InetAddress ia=clientSocket.getInetAddress();
            WorkerRunnable wr=new WorkerRunnable(
                    clientSocket,controller, "migration_server:"+ia);
            executorService.submit(wr);
        }
        logger.info("the migration server has been stopped.") ;
    }
    

    private synchronized boolean isStopped() {
        return this.isStopped;
    }

    public synchronized void stop(){
        this.isStopped = true;
        try {
            this.serverSocket.close();
        } catch (IOException e) {
            throw new RuntimeException("Error closing server", e);
        }
    }

    private void openServerSocket() {
        try {
            this.serverSocket = new ServerSocket(this.serverPort);
            logger.info("the migrations server has been started");
        } catch (IOException e) {
            throw new RuntimeException("Cannot open port "+serverPort, e);
        }
    }

}
