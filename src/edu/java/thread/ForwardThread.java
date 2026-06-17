package edu.java.thread;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import edu.java.JavaForwarder;
import edu.java.helper.DataDumpManager;
import edu.java.helper.Direction;
import edu.java.helper.Format;
import edu.java.helper.Protocol;

/**
 * ForwardThread handles the TCP forwarding between a socket input stream (source) and a socket output stream (destination). It
 * reads the input stream and forwards everything to the output stream. If some of the streams fails, the forwarding stops and
 * the parent is notified to close all its sockets.
 */
public class ForwardThread extends Thread {

    /** Data buffer, for TCP you retrieve in chunks, for UDP only 1 chunk with max. size of 65507 is possible. */
    private static final int BUFFER_SIZE = 65536;

    /** {@link JavaForwarder} instance running. */
    private JavaForwarder javaForwarder;
    /** Parent {@link Thread}. */
    final private ClientThread clientThread;
    /** Type of {@code IP} data to forward. */
    final private Protocol protocol;
    /** {@link Socket} {@code inputStream} reads data from. */
    final private Socket inputSocket;
    /** {@link Socket} {@code outputStream} writes data to. */
    final private Socket outputSocket;
    final private DatagramSocket inputDatagramSocket;
    final private DatagramSocket outputDatagramSocket;
    /** {@code InputStream} to read data from. */
    final private InputStream inputStream;
    /** {@link OutputStream} to forward data to. */
    final private OutputStream outputStream;
    /** Direction of data flow (client to server or server to client). */
    final private Direction direction;

    /**
     * Creates a new {@code TCP} traffic forwarding (copy) thread specifying its parent, input and output {@link Socket}s and
     * {@link Stream}s.
     * 
     * @param javaForwarder instance running
     * @param clientThread  parent {@link Thread}
     * @param protocol      of {@code IP} data to forward
     * @param inputSocket   where {@code inputStream} reads data from
     * @param outputSocket  where {@code outputStream} writes data to
     * @param inputStream   to read data from
     * @param outputStream  to forward data from {@code inputStream} to
     * @param direction     of data flow (client to server or server to client)
     */
    public ForwardThread(final JavaForwarder javaForwarder, final ClientThread clientThread, final Protocol protocol,
            final Socket inputSocket, final Socket outputSocket, final InputStream inputStream, final OutputStream outputStream,
            final Direction direction) {
        super();
        this.javaForwarder = javaForwarder;
        this.clientThread = clientThread;
        this.protocol = protocol;
        this.inputSocket = inputSocket;
        this.outputSocket = outputSocket;
        this.inputDatagramSocket = null;
        this.outputDatagramSocket = null;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.direction = direction;
    }

    /**
     * Creates a new {@code TCP} traffic forwarding (copy) thread specifying its parent, input and output
     * {@link DatagramSocket}s.
     * 
     * @param javaForwarder        instance running
     * @param clientThread         parent {@link Thread}
     * @param protocol             of {@code IP} data to forward
     * @param inputDatagramSocket  to read data from
     * @param outputDatagramSocket to write data to
     * @param direction            of data flow (client to server or server to client)
     */
    public ForwardThread(final JavaForwarder javaForwarder, final ClientThread clientThread, final Protocol protocol,
            final DatagramSocket inputDatagramSocket, final DatagramSocket outputDatagramSocket, final Direction direction) {
        super();
        this.javaForwarder = javaForwarder;
        this.clientThread = clientThread;
        this.protocol = protocol;
        this.inputSocket = null;
        this.outputSocket = null;
        this.inputDatagramSocket = inputDatagramSocket;
        this.outputDatagramSocket = outputDatagramSocket;
        this.inputStream = null;
        this.outputStream = null;
        this.direction = direction;
    }

    /**
     * Runs the thread. Continuously reads the input stream and writes the read data to the output stream. If reading or writing
     * fail, exits the thread and notifies the parent about the failure.
     */
    public void run() {
        final byte[] buffer = new byte[BUFFER_SIZE];
        LocalDateTime localDateTimeForward = null;
        if (Protocol.TCP == protocol) {
            final DataDumpManager dataDumpManager = new DataDumpManager(javaForwarder, Thread.currentThread().getId(), protocol,
                    inputSocket.getInetAddress().getHostAddress(), String.valueOf(inputSocket.getPort()),
                    outputSocket.getInetAddress().getHostAddress(), String.valueOf(outputSocket.getPort()));
            // Check if DUMP_HTTP is enabled
            final boolean dumpHttpEnabled = javaForwarder
                    .getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP_HTTP) != null;
            try {
                while (!javaForwarder.isDoExit()) {
                    System.out.println("JavaForwarder " + direction + " waiting for data...");
                    int bytesRead = inputStream.read(buffer);
                    System.out.println("JavaForwarder " + direction + " read " + bytesRead + " bytes");
                    // If end of stream is reached --> exit
                    if (bytesRead == -1) {
                        System.out.println("JavaForwarder " + direction + " EOF detected, exiting");
                        break;
                    }
                    // Record timestamp for data read
                    if (localDateTimeForward == null) {
                        localDateTimeForward = LocalDateTime.now();
                    }
                    // Detect format on first data received (either direction can trigger this)
                    if (clientThread.getDetectedFormat() == null) {
                        clientThread.detectFormat(buffer, bytesRead);
                    }
                    // Record data based on detected format and DUMP_HTTP setting
                    Format format = clientThread.getDetectedFormat();
                    if (format == Format.HTTP && dumpHttpEnabled) {
                        dataDumpManager.recordHttp(localDateTimeForward, buffer, bytesRead, direction);
                    } else {
                        dataDumpManager.record(localDateTimeForward, buffer, bytesRead);
                    }
                    // Reset timestamp when buffer not full (end of logical message)
                    if (bytesRead < BUFFER_SIZE) {
                        localDateTimeForward = null;
                        // Reset HTTP state for next request/response
                        if (format == Format.HTTP && dumpHttpEnabled) {
                            dataDumpManager.resetHttpState();
                        }
                    }
                    // Forward data
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                }
            } catch (IOException e) {
                // Read/write failed --> connection is broken
            }
            // Display threads data dump
            dataDumpManager.logDataDump();
            // Signal that this direction has finished
            clientThread.forwardingDirectionComplete(direction);
            // Notify parent thread that the connection is broken
            clientThread.connectionBroken();
        } else if (Protocol.UDP == protocol) {
            final DataDumpManager dataDumpManager = new DataDumpManager(javaForwarder, Thread.currentThread().getId(),
                    protocol);
            try {
                while (!javaForwarder.isDoExit()) {
                    DatagramPacket inputDatagramPacket = new DatagramPacket(buffer, buffer.length);
                    try {
                        inputDatagramSocket.receive(inputDatagramPacket);
                        final String inputAddress = inputDatagramPacket.getAddress().getHostAddress();
                        final String inputPort = String.valueOf(inputDatagramPacket.getPort());
                        SocketAddress serverAddress = null;
                        if (Direction.CLIENT_TO_SERVER == direction) {
                            clientThread.setClientAddress(inputDatagramPacket.getSocketAddress());
                            serverAddress = new InetSocketAddress(clientThread.getRemoteHost(), clientThread.getRemotePort());
                        } else if (Direction.SERVER_TO_CLIENT == direction) {
                            serverAddress = clientThread.getClientAddress();
                            if (serverAddress == null) {
                                // If we haven't seen a client packet yet, we can't send anything back to the client
                                continue;
                            }
                        }
                        inputDatagramPacket.setSocketAddress(serverAddress);
                        outputDatagramSocket.send(inputDatagramPacket);
                        // Record data read
                        dataDumpManager.setInputAddress(inputAddress);
                        dataDumpManager.setInputPort(inputPort);
                        dataDumpManager.setOutputAddress(((InetSocketAddress) serverAddress).getAddress().getHostAddress());
                        dataDumpManager.setOutputPort(String.valueOf(((InetSocketAddress) serverAddress).getPort()));
                        dataDumpManager.record(LocalDateTime.now(), inputDatagramPacket);
                        dataDumpManager.logDataDump();
                    } catch (PortUnreachableException e) {
                        System.out.println("JavaForwarder ICMP Port Unreachable (Destination unreachable)");
                    } catch (SocketTimeoutException e) {
                        // Ignore timeout and continue waiting until we should exist
                    }
                }
            } catch (Exception e) {
                // Connection closed or forced closed, ignore
            } finally {
                // Signal that this direction has finished
                clientThread.forwardingDirectionComplete(direction);
                // Notify parent thread that the connection is broken
                clientThread.connectionBroken();
            }
        }
    }
}
