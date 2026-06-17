package edu.java.thread;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

import edu.java.JavaForwarder;
import edu.java.helper.Direction;
import edu.java.helper.Format;
import edu.java.helper.Protocol;

/**
 * ClientThread is responsible for starting forwarding between the client and the server. It keeps track of the client and
 * servers sockets that are both closed on input/output error during the forwarding. The forwarding is bidirectional and is
 * performed by two ForwardThread instances.
 */
public class ClientThread extends Thread {

    /** {@link JavaForwarder} instance running. */
    private JavaForwarder javaForwarder;
    /** Type of {@code IP} data to forward. */
    private Protocol protocol;
    /** {@link Socket} to read {@code TCP} data from to forward it to {@code serverSocket}. */
    private Socket clientSocket;
    /** {@link Socket} to read {@code UDP} data from to forward it to {@code serverSocket}. */
    private DatagramSocket clientDatagramSocket;
    /** Remote host name or IP address. */
    private String remoteHost;
    /** Remote port. */
    private int remotePort;
    /** Address of client. */
    private volatile SocketAddress clientAddress = null;

    /** {@link Socket} connected to {@code remoteHost:remotePort}. */
    private Socket serverSocket;
    /** {@link DatagramSocket} connected to {@code remoteHost:remotePort}. */
    private DatagramSocket serverDatagramSocket;
    /** Flag set while forwarding is active. */
    private boolean forwardingActive = false;
    /** Detected format of the TCP traffic, shared between both ForwardThreads. */
    private volatile Format detectedFormat = null;
    /** Lock for thread-safe format detection. */
    private final Object formatDetectionLock = new Object();
    /** Tracks which forwarding directions are still active (for half-close support). */
    private volatile Map<Direction, Boolean> directionActive = new HashMap<>();

    /**
     * Client thread constructor to process {@code TCP} data.
     * 
     * @param javaForwarder instance running
     * @param protocol      of {@code IP} data to forward
     * @param clientSocket  to read data from to forward it to {@code serverSocket}
     * @param remoteHost    to connect to
     * @param remotePort    to connect to
     */
    public ClientThread(final JavaForwarder javaForwarder, final Protocol protocol, final Socket clientSocket,
            final String remoteHost, final int remotePort) {
        super();
        this.javaForwarder = javaForwarder;
        this.protocol = protocol;
        this.clientSocket = clientSocket;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.serverSocket = null;
        directionActive.put(Direction.CLIENT_TO_SERVER, true);
        directionActive.put(Direction.SERVER_TO_CLIENT, true);
    }

    /**
     * Client thread constructor to process {@code UDP} data.
     * 
     * @param javaForwarder        instance running
     * @param protocol             of {@code IP} data to forward
     * @param clientDatagramSocket to read data from to forward it to {@code serverSocket}
     * @param remoteHost           to connect to
     * @param remotePort           to connect to
     */
    public ClientThread(final JavaForwarder javaForwarder, final Protocol protocol, final DatagramSocket clientDatagramSocket,
            final String remoteHost, final int remotePort) {
        super();
        this.javaForwarder = javaForwarder;
        this.protocol = protocol;
        this.clientSocket = null;
        this.clientDatagramSocket = clientDatagramSocket;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.serverSocket = null;
        directionActive.put(Direction.CLIENT_TO_SERVER, true);
        directionActive.put(Direction.SERVER_TO_CLIENT, true);
    }

    /**
     * Get the detected traffic format.
     * 
     * @return the detected Format, or null if not yet detected
     */
    public Format getDetectedFormat() {
        return detectedFormat;
    }

    /**
     * Establishes connection to the destination server and starts bidirectional forwarding of data between the client and the
     * server.
     */
    public void run() {
        if (Protocol.TCP == protocol) {
            /** {@link InputStream} to read data from {@code localhost:localPort}. */
            InputStream clientInputStream;
            /** {@link InputStream} to write data to {@code remoteHost:remotePort}. */
            OutputStream clientOutputStream;
            /** {@link InputStream} to read data from {@code remoteHost:remotePort}. */
            InputStream serverInputStream;
            /** {@link InputStream} to write data to {@code localhost:localPort}. */
            OutputStream serverOutputStream;
            try {
                System.out.println("JavaForwarder connecting to server ...");
                // Connect to the destination server
                serverSocket = new Socket(remoteHost, remotePort);
                // Turn on keep-alive for both the sockets
                serverSocket.setKeepAlive(true);
                clientSocket.setKeepAlive(true);
                // Obtain client & server input & output streams
                clientInputStream = clientSocket.getInputStream();
                clientOutputStream = clientSocket.getOutputStream();
                serverInputStream = serverSocket.getInputStream();
                serverOutputStream = serverSocket.getOutputStream();
                System.out.println("JavaForwarder connected to server");
            } catch (IOException ioe) {
                System.err.println("JavaForwarder failed to connect to initiate " + protocol + " connection: " + remoteHost
                        + ":" + remotePort);
                connectionBroken();
                javaForwarder.setDoExit(true);
                System.out.println("JavaForwarder failed to start, press Enter to terminate JavaForwarder ...");
                return;
            }
            // Start forwarding data between client and server
            forwardingActive = true;
            ForwardThread clientForward = new ForwardThread(javaForwarder, this, protocol, clientSocket, serverSocket,
                    clientInputStream, serverOutputStream, Direction.CLIENT_TO_SERVER);
            clientForward.start();
            ForwardThread serverForward = new ForwardThread(javaForwarder, this, protocol, serverSocket, clientSocket,
                    serverInputStream, clientOutputStream, Direction.SERVER_TO_CLIENT);
            serverForward.start();
            System.out.println("JavaForwarder " + protocol + " connection: " + clientSocket.getInetAddress().getHostAddress()
                    + ":" + clientSocket.getPort() + " <--> " + serverSocket.getInetAddress().getHostAddress() + ":"
                    + serverSocket.getPort() + " started");
        } else if (Protocol.UDP == protocol) {
            try {
                System.out.println("JavaForwarder connecting to server ...");
                // Connect to the destination server
                serverDatagramSocket = new DatagramSocket();
                // TODO: Remove once it is working
                // serverDatagramSocket.connect(InetAddress.getByName(remoteHost), remotePort);
                System.out.println("JavaForwarder connected to server (UDP)");
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("JavaForwarder failed to connect to initiate " + protocol + " connection: " + remoteHost
                        + ":" + remotePort);
                connectionBroken();
                javaForwarder.setDoExit(true);
                System.out.println("JavaForwarder failed to start, press Enter to terminate JavaForwarder ...");
                return;
            }
            // Start forwarding data between server and client
            forwardingActive = true;
            ForwardThread clientForward = new ForwardThread(javaForwarder, this, protocol, clientDatagramSocket,
                    serverDatagramSocket, Direction.CLIENT_TO_SERVER);
            clientForward.start();
            ForwardThread serverForward = new ForwardThread(javaForwarder, this, protocol, serverDatagramSocket,
                    clientDatagramSocket, Direction.SERVER_TO_CLIENT);
            serverForward.start();
            // Can't log connection details, as client has not connected yet
        }
    }

    /**
     * Called by ForwardThread when one direction of forwarding completes. Marks the direction as inactive and handles
     * half-close scenarios.
     * 
     * @param direction which direction completed
     */
    public synchronized void forwardingDirectionComplete(final Direction direction) {
        directionActive.put(direction, false);
        System.out.println("JavaForwarder " + direction + " forwarding completed");
        // For UDP, if one direction finishes, close both sockets to unblock the other thread
        if (protocol == Protocol.UDP) {
            if (serverDatagramSocket != null)
                serverDatagramSocket.close();
            if (clientDatagramSocket != null)
                clientDatagramSocket.close();
        }
        // When server closes (SERVER_TO_CLIENT completes), close the client socket
        // to unblock CLIENT_TO_SERVER thread which is waiting on clientInputStream.read()
        if (direction == Direction.SERVER_TO_CLIENT && clientSocket != null && !clientSocket.isClosed()) {
            try {
                // Close client socket to make CLIENT_TO_SERVER thread exit
                clientSocket.close();
                System.out.println("JavaForwarder closed client socket to unblock CLIENT_TO_SERVER");
            } catch (Exception e) {
                // Ignore - socket may already be closed
            }
        }
        // When client closes (CLIENT_TO_SERVER completes), close the server socket
        // to unblock SERVER_TO_CLIENT thread which is waiting on serverInputStream.read()
        if (direction == Direction.CLIENT_TO_SERVER && serverSocket != null && !serverSocket.isClosed()) {
            try {
                // Close server socket to make SERVER_TO_CLIENT thread exit
                serverSocket.close();
                System.out.println("JavaForwarder closed server socket to unblock SERVER_TO_CLIENT");
            } catch (Exception e) {
                // Ignore - socket may already be closed
            }
        }
    }

    /**
     * Detect and set the traffic format based on initial data. Thread-safe - only the first caller performs detection,
     * subsequent calls return cached result.
     * 
     * <p>
     * <b>Rationale:</b> Both CLIENT_TO_SERVER and SERVER_TO_CLIENT threads need to know the detected format to choose between
     * DUMP and DUMP_HTTP output. Detection happens on first data chunk (either direction can see it first), then result is
     * shared.
     * </p>
     * 
     * <p>
     * <b>Thread Safety:</b> Uses synchronized(formatDetectionLock) to ensure only one thread performs detection even if both
     * threads receive data simultaneously. Cached result in volatile field for fast subsequent access.
     * </p>
     * 
     * <p>
     * <b>Detection Strategy:</b> Checks first bytes for HTTP signatures (GET, POST, HTTP/). See {@link #isHttpTraffic()} for
     * details.
     * </p>
     * 
     * <p>
     * <b>Called by:</b> {@link ForwardThread#run()} on first data chunk before recording.
     * </p>
     * 
     * @param buffer the data buffer to analyze
     * @param length number of valid bytes in buffer
     * @return the detected Format (HTTP or OTHER)
     */
    Format detectFormat(final byte[] buffer, final int length) {
        synchronized (formatDetectionLock) {
            if (detectedFormat != null) {
                return detectedFormat;
            }
            detectedFormat = isHttpTraffic(buffer, length) ? Format.HTTP : Format.OTHER;
            System.out.println("JavaForwarder detected traffic format: " + detectedFormat);
            return detectedFormat;
        }
    }

    /**
     * Called by some of the forwarding threads to indicate that its socket connection is broken. Only closes sockets when BOTH
     * directions have completed to support HTTP Keep-Alive. Closing the client and server sockets causes all threads blocked on
     * reading or writing to these sockets to get an exception and to finish their execution.
     * 
     * <p>
     * <b>Rationale:</b> HTTP/1.1 Keep-Alive allows multiple request/response pairs on a single TCP connection. If we close
     * sockets immediately when one direction finishes, we'd break legitimate Keep-Alive connections. Must wait for both
     * directions to complete.
     * </p>
     * 
     * <p>
     * <b>Design:</b> Uses directionActive map to track CLIENT_TO_SERVER and SERVER_TO_CLIENT states independently. First
     * direction to finish just marks itself inactive and returns. Second direction to finish sees both inactive and closes
     * everything.
     * </p>
     * 
     * <p>
     * <b>Synchronization:</b> Entire method is synchronized to prevent race conditions when both threads finish simultaneously.
     * </p>
     * 
     * <p>
     * <b>Socket Cleanup:</b> Closes both client and server sockets to unblock any threads still waiting on read() calls.
     * Ignores exceptions since sockets may already be closed.
     * </p>
     */
    public synchronized void connectionBroken() {
        // Check if any direction is still active
        boolean anyDirectionActive = directionActive.values().stream().anyMatch(active -> active);
        if (anyDirectionActive) {
            System.out.println("JavaForwarder one direction closed, waiting for other direction");
            return; // Don't close yet - other direction still active
        }
        // Both directions finished - now close everything
        System.out.println("JavaForwarder both directions closed, terminating connection");
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception e) {
            }
        }
        if (clientSocket != null && !clientSocket.isClosed()) {
            try {
                clientSocket.close();
            } catch (Exception e) {
            }
        }
        if (serverDatagramSocket != null) {
            try {
                serverDatagramSocket.close();
            } catch (Exception e) {
            }
        }
        if (clientDatagramSocket != null) {
            try {
                clientDatagramSocket.close();
            } catch (Exception e) {
            }
        }
        if (forwardingActive) {
            if (Protocol.TCP == protocol) {
                System.out.println("JavaForwarder " + protocol + " connection: "
                        + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort() + " <--> "
                        + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getPort() + " stopped");
            }
            if (Protocol.UDP == protocol) {
                if (clientDatagramSocket.getInetAddress() != null) {
                    System.out.println("JavaForwarder " + protocol + " connection: "
                            + clientDatagramSocket.getInetAddress().getHostAddress() + ":" + clientDatagramSocket.getPort()
                            + " <--> " + serverDatagramSocket.getInetAddress().getHostAddress() + ":"
                            + serverDatagramSocket.getPort() + " stopped");
                }
            }
            forwardingActive = false;
        }
    }

    /**
     * Check if the data appears to be HTTP traffic.
     * 
     * <p>
     * <b>Rationale:</b> Not all TCP traffic is HTTP. We need to detect the protocol to choose appropriate dump format. HTTP has
     * clear signatures in first bytes: request methods (GET, POST, etc.) or response status line (HTTP/1.x).
     * </p>
     * 
     * <p>
     * <b>Detection Logic:</b>
     * <ul>
     * <li>Checks first 16 bytes (sufficient for method/version detection)</li>
     * <li>Request: Looks for "GET ", "POST ", "PUT ", etc. (space after method)</li>
     * <li>Response: Looks for "HTTP/1.0" or "HTTP/1.1" at start</li>
     * <li>Uses US_ASCII since HTTP start line must be ASCII per RFC</li>
     * </ul>
     * </p>
     * 
     * <p>
     * <b>Limitations:</b>
     * <ul>
     * <li>Doesn't detect HTTP/2 (uses binary protocol)</li>
     * <li>Could false-positive on binary data starting with these bytes (very rare)</li>
     * <li>Requires at least 4 bytes to decide</li>
     * </ul>
     * </p>
     * 
     * @param buffer the data buffer to check
     * @param length number of valid bytes in buffer
     * @return true if HTTP traffic detected, false otherwise
     */
    private boolean isHttpTraffic(final byte[] buffer, final int length) {
        if (length < 4)
            return false;
        String prefix = new String(buffer, 0, Math.min(length, 16), java.nio.charset.StandardCharsets.US_ASCII);
        // HTTP request methods
        if (prefix.startsWith("GET ") || prefix.startsWith("POST ") || prefix.startsWith("PUT ") || prefix.startsWith("DELETE ")
                || prefix.startsWith("PATCH ") || prefix.startsWith("HEAD ") || prefix.startsWith("OPTIONS ")
                || prefix.startsWith("CONNECT ") || prefix.startsWith("TRACE ")) {
            return true;
        }
        // HTTP response status line
        if (prefix.startsWith("HTTP/1.0") || prefix.startsWith("HTTP/1.1")) {
            return true;
        }
        return false;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public SocketAddress getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(SocketAddress clientAddress) {
        this.clientAddress = clientAddress;
    }

}
