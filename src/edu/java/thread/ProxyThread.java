package edu.java.thread;

import java.io.IOException;

import edu.java.JavaForwarder;
import edu.java.helper.Protocol;

/**
 * Worker {@link Thread}, so main {@link Thread} can wait for user input to terminate forwarder. A server will be created
 * listening on {@code localhost:localPort} to forward and optionally dump the {@code IP} data forwarded to the reverse proxy
 * {@code remoteHost:remotePort}.
 */
public class ProxyThread extends Thread {

    /** {@link JavaForwarder} instance running. */
    private JavaForwarder javaForwarder;
    /** Type of {@code IP} data to forward. */
    private Protocol protocol;
    /** Remote host name or IP address. */
    private String remoteHost;
    /** Remote port. */
    private int remotePort;
    /** Local port. */
    private int localPort;

    /**
     * Create a {@link Thread} that runs a reverse proxy to forward {@code IP} data.
     * 
     * @param javaForwarder instance running
     * @param protocol      of {@code IP} data to forward
     * @param remoteHost    hostname or IP-address of server data will be forwarded to
     * @param remotePort    port of server data will be forwarded to
     * @param localPort     port to start a server on {@code localhost} that will receive the data and forward it to
     *                      {@code remoteHost:remotePort}
     */
    public ProxyThread(final JavaForwarder javaForwarder,
            final Protocol protocol, final String remoteHost,
            final int remotePort, final int localPort) {
        super();
        this.javaForwarder = javaForwarder;
        this.protocol = protocol;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
    }

    @Override
    public void run() {
        try {
            javaForwarder.runServer(protocol, remoteHost, remotePort, localPort);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
