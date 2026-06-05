package at.test.forwarder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
/**
 * This program is an example from the book "Internet programming with Java" by Svetlin Nakov. It is freeware. For more information:
 * http://www.nakov.com/books/inetjava/
 */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * JavaForwarder is a simple {@code TCP} and {@code UDP} bridging software that allows a {@code TCP} or {@code UDP} port on some
 * host to be transparently forwarded to some other {@code TCP} or {@code UDPs} port on some other host. JavaForwarder
 * continuously accepts client connections on the listening {@code TCP} or {@code UDP} port (source port) and starts a thread
 * (ClientThread) that connects to the destination host and starts forwarding the data between the client socket and destination
 * sockets or datagram sockets.
 * 
 * Run a {@code NodeJS} HTTP server to test chunked messages (request to http://localhost:3000/ retrieves 1. / and 2.
 * /favicon.ico):
 * 
 * <pre>
 * node -e "require('http').createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/plain'});res.end('Hello World!\n');}).listen(3000)"
 * </pre>
 * 
 * To test {@code UDP} e.g. {@code IPerf3} can be used, though quite some setup is required:
 * 
 * <pre>
 * 1. Run the IPerf3 server on localhost: IPerf3 -V -s
 * 2. Forward the TCP control connection: java -jar -DDUMP=true -DDUMP_WIDTH=32 JavaForwarder.jar localhost 5201 5202
 * 3. Forward the UDP connection: java -jar -DMODE=UDP -DDUMP=true -DDUMP_WIDTH=32 d:\JavaForwarder.jar localhost 5201 5202
 * 4. Run the IPerf3 client on localhost: IPerf3.exe -p 5202 -c 127.0.0.1 -u
 * </pre>
 * 
 * This will execute for both TCP and UDP communication: IPerf3 client <-> JavaForwarder <-> IPerf3 server
 */
public class JavaForwarder {

    private static enum Protocol {
        /** Forward {@code TCP} data over {@link Socket}s. */
        TCP,
        /** Forward {@code UDP} data over {@link DatagramSocket}. */
        UDP
    };

    /** Direction of data flow for {@link ForwardThread}. */
    private static enum Direction {
        /** Data flowing from client to server. */
        CLIENT_TO_SERVER,
        /** Data flowing from server to client. */
        SERVER_TO_CLIENT
    }

    /** Detected format of the TCP traffic. */
    private static enum Format {
        /** Any non-HTTP TCP protocol. */
        OTHER,
        /** HTTP/1.x protocol detected. */
        HTTP
    }

    /** Mode of forwarding operation, {@code TCP} (default) or {@code UDP}. */
    private static final String ENVIRONMENT_VARIABLE_MODE = "MODE";
    /** Set to any value to activate recording of the forwarded data in a formatted data dump. */
    private static final String ENVIRONMENT_VARIABLE_DUMP = "DUMP";
    /** Set to a multiple of 16 to define non default width (number of bytes per rows) in formatted data dump. */
    private static final String ENVIRONMENT_VARIALBE_DUMP_WIDTH = "DUMP_WIDTH";
    /** Set to any value to activate recording of the forwarded HTTP data in a Postman/Bruno like data dump. */
    private static final String ENVIRONMENT_VARIABLE_DUMP_HTTP = "DUMP_HTTP";

    /** Flag checked by threads if they should terminate. */
    private static boolean doExit = false;

    /**
     * ClientThread is responsible for starting forwarding between the client and the server. It keeps track of the client and
     * servers sockets that are both closed on input/output error during the forwarding. The forwarding is bidirectional and is
     * performed by two ForwardThread instances.
     */
    private static class ClientThread extends Thread {

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
        /** Address of ?. */
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
         * @param protocol     of {@code IP} data to forward
         * @param clientSocket to read data from to forward it to {@code serverSocket}
         * @param remoteHost   to connect to
         * @param remotePort   to connect to
         */
        public ClientThread(final Protocol protocol, final Socket clientSocket, final String remoteHost, final int remotePort) {
            super();
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
         * @param protocol             of {@code IP} data to forward
         * @param clientDatagramSocket to read data from to forward it to {@code serverSocket}
         * @param remoteHost           to connect to
         * @param remotePort           to connect to
         */
        public ClientThread(final Protocol protocol, final DatagramSocket clientDatagramSocket, final String remoteHost,
                final int remotePort) {
            super();
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
         * Establishes connection to the destination server and starts bidirectional forwarding of data between the client and
         * the server.
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
                    JavaForwarder.doExit = true;
                    System.out.println("JavaForwarder failed to start, press Enter to terminate JavaForwarder ...");
                    return;
                }
                // Start forwarding data between client and server
                forwardingActive = true;
                ForwardThread clientForward = new ForwardThread(this, protocol, clientSocket, serverSocket, clientInputStream,
                        serverOutputStream, Direction.CLIENT_TO_SERVER);
                clientForward.start();
                ForwardThread serverForward = new ForwardThread(this, protocol, serverSocket, clientSocket, serverInputStream,
                        clientOutputStream, Direction.SERVER_TO_CLIENT);
                serverForward.start();
                System.out.println("JavaForwarder " + protocol + " connection: "
                        + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort() + " <--> "
                        + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getPort() + " started");
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
                    JavaForwarder.doExit = true;
                    System.out.println("JavaForwarder failed to start, press Enter to terminate JavaForwarder ...");
                    return;
                }
                // Start forwarding data between server and client
                forwardingActive = true;
                ForwardThread clientForward = new ForwardThread(this, protocol, clientDatagramSocket, serverDatagramSocket,
                        Direction.CLIENT_TO_SERVER);
                clientForward.start();
                ForwardThread serverForward = new ForwardThread(this, protocol, serverDatagramSocket, clientDatagramSocket,
                        Direction.SERVER_TO_CLIENT);
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
         * <b>Rationale:</b> Both CLIENT_TO_SERVER and SERVER_TO_CLIENT threads need to know the detected format to choose
         * between DUMP and DUMP_HTTP output. Detection happens on first data chunk (either direction can see it first), then
         * result is shared.
         * </p>
         * 
         * <p>
         * <b>Thread Safety:</b> Uses synchronized(formatDetectionLock) to ensure only one thread performs detection even if
         * both threads receive data simultaneously. Cached result in volatile field for fast subsequent access.
         * </p>
         * 
         * <p>
         * <b>Detection Strategy:</b> Checks first bytes for HTTP signatures (GET, POST, HTTP/). See {@link #isHttpTraffic()}
         * for details.
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
        private Format detectFormat(final byte[] buffer, final int length) {
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
         * Called by some of the forwarding threads to indicate that its socket connection is broken. Only closes sockets when
         * BOTH directions have completed to support HTTP Keep-Alive. Closing the client and server sockets causes all threads
         * blocked on reading or writing to these sockets to get an exception and to finish their execution.
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
         * <b>Synchronization:</b> Entire method is synchronized to prevent race conditions when both threads finish
         * simultaneously.
         * </p>
         * 
         * <p>
         * <b>Socket Cleanup:</b> Closes both client and server sockets to unblock any threads still waiting on read() calls.
         * Ignores exceptions since sockets may already be closed.
         * </p>
         */
        private synchronized void connectionBroken() {
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
         * <b>Rationale:</b> Not all TCP traffic is HTTP. We need to detect the protocol to choose appropriate dump format. HTTP
         * has clear signatures in first bytes: request methods (GET, POST, etc.) or response status line (HTTP/1.x).
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
            if (prefix.startsWith("GET ") || prefix.startsWith("POST ") || prefix.startsWith("PUT ")
                    || prefix.startsWith("DELETE ") || prefix.startsWith("PATCH ") || prefix.startsWith("HEAD ")
                    || prefix.startsWith("OPTIONS ") || prefix.startsWith("CONNECT ") || prefix.startsWith("TRACE ")) {
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

    /**
     * Worker {@link Thread}, so main {@link Thread} can wait for user input to terminate forwarder. A server will be created
     * listening on {@code localhost:localPort} to forward and optionally dump the {@code IP} data forwarded to the reverse
     * proxy {@code remoteHost:remotePort}.
     */
    private static class ProxyThread extends Thread {

        private Protocol protocol;
        private String remoteHost;
        private int remotePort;
        private int localPort;

        /**
         * Create a {@link Thread} that runs a reverse proxy to forward {@code IP} data.
         * 
         * @param protocol   of {@code IP} data to forward
         * @param remoteHost hostname or IP-address of server data will be forwarded to
         * @param remotePort port of server data will be forwarded to
         * @param localPort  port to start a server on {@code localhost} that will receive the data and forward it to
         *                   {@code remoteHost:remotePort}
         */
        public ProxyThread(final Protocol protocol, final String remoteHost, final int remotePort, final int localPort) {
            super();
            this.protocol = protocol;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.localPort = localPort;
        }

        @Override
        public void run() {
            try {
                JavaForwarder.runServer(protocol, remoteHost, remotePort, localPort);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * ForwardThread handles the TCP forwarding between a socket input stream (source) and a socket output stream (destination).
     * It reads the input stream and forwards everything to the output stream. If some of the streams fails, the forwarding
     * stops and the parent is notified to close all its sockets.
     */
    private static class ForwardThread extends Thread {

        /** Data buffer, for TCP you retrieve in chunks, for UDP only 1 chunk with max. size of 65507 is possible. */
        private static final int BUFFER_SIZE = 65536;

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
         * Creates a new {@code TCP} traffic forwarding (copy) thread specifying its parent, input and output {@link Socket}s
         * and {@link Stream}s.
         * 
         * @param clientThread parent {@link Thread}
         * @param protocol     of {@code IP} data to forward
         * @param inputSocket  where {@code inputStream} reads data from
         * @param outputSocket where {@code outputStream} writes data to
         * @param inputStream  to read data from
         * @param outputStream to forward data from {@code inputStream} to
         * @param direction    of data flow (client to server or server to client)
         */
        public ForwardThread(final ClientThread clientThread, final Protocol protocol, final Socket inputSocket,
                final Socket outputSocket, final InputStream inputStream, final OutputStream outputStream,
                final Direction direction) {
            super();
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
         * @param clientThread         parent {@link Thread}
         * @param protocol             of {@code IP} data to forward
         * @param inputDatagramSocket  to read data from
         * @param outputDatagramSocket to write data to
         * @param direction            of data flow (client to server or server to client)
         */
        public ForwardThread(final ClientThread clientThread, final Protocol protocol, final DatagramSocket inputDatagramSocket,
                final DatagramSocket outputDatagramSocket, final Direction direction) {
            super();
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
         * Runs the thread. Continuously reads the input stream and writes the read data to the output stream. If reading or
         * writing fail, exits the thread and notifies the parent about the failure.
         */
        public void run() {
            final byte[] buffer = new byte[BUFFER_SIZE];
            LocalDateTime localDateTimeForward = null;
            if (Protocol.TCP == protocol) {
                final DataDumpManager dataDumpManager = new DataDumpManager(Thread.currentThread().getId(), protocol,
                        inputSocket.getInetAddress().getHostAddress(), String.valueOf(inputSocket.getPort()),
                        outputSocket.getInetAddress().getHostAddress(), String.valueOf(outputSocket.getPort()));
                // Check if DUMP_HTTP is enabled
                final boolean dumpHttpEnabled = JavaForwarder
                        .getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP_HTTP) != null;
                try {
                    while (!JavaForwarder.doExit) {
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
                final DataDumpManager dataDumpManager = new DataDumpManager(Thread.currentThread().getId(), protocol);
                try {
                    while (!JavaForwarder.doExit) {
                        DatagramPacket inputDatagramPacket = new DatagramPacket(buffer, buffer.length);
                        try {
                            inputDatagramSocket.receive(inputDatagramPacket);
                            final String inputAddress = inputDatagramPacket.getAddress().getHostAddress();
                            final String inputPort = String.valueOf(inputDatagramPacket.getPort());
                            SocketAddress serverAddress = null;
                            if (Direction.CLIENT_TO_SERVER == direction) {
                                clientThread.setClientAddress(inputDatagramPacket.getSocketAddress());
                                serverAddress = new InetSocketAddress(clientThread.getRemoteHost(),
                                        clientThread.getRemotePort());
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

    /**
     * Data dump manager to store data forwarded by the {@link ForwardThread}.
     */
    private static class DataDumpManager {

        /**
         * Map (shared between threads) of chronological timestamps and formatted traffic between {@code inputSocket} and
         * {@code outputSocket}.
         */
        private static Map<Long, StringBuffer> mapTimestampDataDump = new TreeMap<Long, StringBuffer>();

        /** ID of thread executing {@link ForwardThread} instance. */
        private Long threadId = null;
        /** {@link Protocol} used. */
        private Protocol protocol = null;
        /** IP address data is read from. */
        private String inputAddress = null;
        /** IP port data is read from. */
        private String inputPort = null;
        /** IP address data is written to. */
        private String outputAddress = null;
        /** IP port data is written to. */
        private String outputPort = null;

        /** Default number of bytes dumped from data dump in a single line. */
        private int DUMP_WIDTH = 16;

        /** Index in row to record formatted next byte of data dump. */
        private int bytesIndex = 0;
        /** Offset of next byte of data dump. */
        private int bytesOffset = 0;
        /** Buffer to record all records of data dump. */
        private StringBuffer sbBufferFormatted = null;
        /** Buffer to record up to {@code DUMP_WIDTH} bytes of a single record of data dump bytes in {@code Hex}. */
        private StringBuffer sbDataHex = null;
        /** Buffer to record up to {@code DUMP_WIDTH} bytes of a single record of data dump bytes in {@code ASCII}. */
        private StringBuffer sbDataChar = null;
        /** Buffer to accumulate HTTP headers for current request/response. */
        private StringBuilder httpHeaderBuffer = new StringBuilder();
        /** Stored Content-Encoding header value for decompression. */
        private String httpContentEncoding = null;
        /** Raw body bytes accumulated for decompression. */
        private ByteArrayOutputStream httpRawBodyStream = new ByteArrayOutputStream();
        /** Flag indicating if we're currently in headers or body. */
        private boolean inHttpBody = false;
        /** Flag indicating if HTTP uses chunked transfer encoding. */
        private boolean isChunkedEncoding = false;
        /** Complete raw byte stream (headers + body) for hex dump output in DUMP_HTTP mode. */
        private ByteArrayOutputStream httpRawFullStream = new ByteArrayOutputStream();

        /**
         * {@link DataDumpManager} initialization for {@code UDP}.
         * 
         * @param threadId of thread forwarding data from {@code inputSocket} to {@code outputSocket}
         * @param protocol {@link Protocol} of underlying communication
         */
        public DataDumpManager(final Long threadId, final Protocol protocol) {
            this(threadId, protocol, null, null, null, null);
        }

        /**
         * {@link DataDumpManager} initialization.
         * 
         * @param threadId      of thread forwarding data from {@code inputSocket} to {@code outputSocket}
         * @param protocol      {@link Protocol} of underlying communication
         * @param inputAddress  to record input host
         * @param inputPort     to record input port
         * @param outputAddress to record output host
         * @param outputPort    to record output port
         */
        public DataDumpManager(final Long threadId, final Protocol protocol, final String inputAddress, final String inputPort,
                final String outputAddress, final String outputPort) {
            super();
            this.threadId = threadId;
            this.protocol = protocol;
            this.inputAddress = inputAddress;
            this.inputPort = inputPort;
            this.outputAddress = outputAddress;
            this.outputPort = outputPort;
            try {
                Integer dumpWidth = Integer
                        .valueOf(JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIALBE_DUMP_WIDTH));
                DUMP_WIDTH = (dumpWidth / 16) * 16;
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        public void record(final LocalDateTime localDateTimeForward, final DatagramPacket datagramPacket) {
            if (Protocol.UDP != protocol) {
                return;
            }
            if (JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP) == null) {
                return;
            }
            record(localDateTimeForward, datagramPacket.getData(), datagramPacket.getLength());
        }

        /**
         * Record the bytes in {@code buffer} forwarded from {@code inputSocket} to {@code outputSocket} as a formatted data
         * dump for {@code TCP} traffic.
         * 
         * <p>
         * <b>Rationale:</b> When DUMP mode is enabled (not DUMP_HTTP), users need to see the raw TCP traffic in hex dump format
         * for protocol debugging. This method accumulates bytes incrementally as they arrive and formats them into readable
         * hex+ASCII rows.
         * </p>
         * 
         * <p>
         * <b>Design Decisions:</b>
         * <ul>
         * <li>Uses instance variables (bytesIndex, bytesOffset) to maintain state across calls</li>
         * <li>Prints thread header and column headers only once (when bytesOffset == 0)</li>
         * <li>Groups data in DUMP_WIDTH byte rows for readability</li>
         * <li>Shows both hex values and ASCII representation side-by-side</li>
         * <li>Thread-safe: uses synchronized mapTimestampDataDump for multi-direction forwarding</li>
         * </ul>
         * </p>
         * 
         * <p>
         * <b>Difference from DUMP_HTTP:</b> This shows pure raw bytes without any parsing. Use this for non-HTTP protocols or
         * when you need to see exact wire format.
         * </p>
         * 
         * @param localDateTimeForwarding timestamp of forwarding
         * @param buffer                  referencing buffer to read from {@code inputSocket} to record data dump from
         * @param bytesRead               containing the number of bytes actually read from {@code inputSocket}
         */
        public void record(final LocalDateTime localDateTimeForwarding, final byte[] buffer, final int bytesRead) {
            if (JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP) == null) {
                return;
            }
            if (Protocol.TCP == protocol) {
                // Retrieve buffer to record data dump from buffer into
                final long timeForwardingMilliSeconds = localDateTimeForwarding.atZone(ZoneId.systemDefault()).toInstant()
                        .toEpochMilli();
                synchronized (mapTimestampDataDump) {
                    sbBufferFormatted = mapTimestampDataDump.get(timeForwardingMilliSeconds);
                    if (sbBufferFormatted == null) {
                        this.bytesIndex = 0;
                        this.bytesOffset = 0;
                        this.sbDataHex = new StringBuffer();
                        this.sbDataChar = new StringBuffer();
                        sbBufferFormatted = new StringBuffer();
                        mapTimestampDataDump.put(timeForwardingMilliSeconds, sbBufferFormatted);
                    }
                }
            } else if (Protocol.UDP == protocol) {
                // For UDP, we use a fresh buffer for every packet to avoid thread interleaving
                // and ensure every packet starts at Offset 000000 in the log.
                this.bytesIndex = 0;
                this.bytesOffset = 0;
                this.sbDataHex = new StringBuffer();
                this.sbDataChar = new StringBuffer();
                this.sbBufferFormatted = new StringBuffer();
            }
            // Dump data in Hex and Ascii in DUMP_WIDTH bytes blocks
            for (int bufferOffset = 0; bufferOffset < bytesRead; bufferOffset++) {
                byte dataByte = buffer[bufferOffset];
                if (bytesOffset == 0) {
                    // Header row with record details
                    sbBufferFormatted.append(String.format("Thread %06x: %s: %s:%s -> %s:%s", threadId,
                            localDateTimeForwarding.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                            inputAddress, inputPort, outputAddress, outputPort)).append(System.lineSeparator());
                    // Header row with hex offsets of bytes in formatted data dump
                    sbBufferFormatted.append("  Offset ");
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append(String.format("%02X ", i));
                    }
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append(String.format("%1X", (i & 0xF)));
                    }
                    sbBufferFormatted.append(System.lineSeparator());
                    // Header row to separate headers with formatted dump data
                    sbBufferFormatted.append("  -------");
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append("----");
                    }
                    sbBufferFormatted.append(System.lineSeparator());
                    sbDataHex = new StringBuffer();
                    sbDataChar = new StringBuffer();
                }
                // Record a row of bytes as formatted data dump
                if (bytesIndex == 0) {
                    sbDataHex.append(String.format("  %06X ", bytesOffset));
                }
                sbDataHex.append(String.format("%02X ", dataByte));
                Character dataByteChar = new Character((char) dataByte);
                int type = Character.getType(dataByteChar);
                if ((Character.CONTROL == type) || (Character.FORMAT == type) || (Character.PRIVATE_USE == type)
                        || (Character.SURROGATE == type) || (Character.UNASSIGNED == type)) {
                    // Ignore non printable characters
                    sbDataChar.append(" ");
                } else {
                    sbDataChar.append(dataByteChar);
                }
                bytesOffset++;
                bytesIndex++;
                // Check for advancing to next line
                if (bytesIndex >= DUMP_WIDTH) {
                    sbBufferFormatted.append(sbDataHex).append(sbDataChar).append(System.lineSeparator());
                    sbDataHex = new StringBuffer();
                    sbDataChar = new StringBuffer();
                    bytesIndex = 0;
                }
            }
            if (bytesRead < buffer.length) {
                // Pad not yet complete DATA_WIDTH bytes line
                for (int bytesIndexNoData = bytesIndex; bytesIndexNoData < DUMP_WIDTH; bytesIndexNoData++) {
                    sbDataHex.append("   ");
                }
                sbBufferFormatted.append(sbDataHex).append(sbDataChar).append(System.lineSeparator());
                bytesIndex = 0;
                bytesOffset = 0;
                sbDataHex = new StringBuffer();
                sbDataChar = new StringBuffer();
            }
        }

        /**
         * Record HTTP traffic in DUMP_HTTP format with raw hex dump and parsed content.
         * 
         * <p>
         * <b>Rationale:</b> When DUMP_HTTP is enabled, users want to see both the raw wire format (for debugging protocol
         * issues) and the parsed, decoded HTTP content (for understanding the actual data). This method accumulates raw bytes
         * and parses headers on-the-fly.
         * </p>
         * 
         * <p>
         * <b>Design Decisions:</b>
         * <ul>
         * <li>Captures ALL raw bytes in httpRawFullStream for later hex dump</li>
         * <li>Parses headers incrementally to detect Content-Encoding and Transfer-Encoding</li>
         * <li>Separates header and body streams for different processing</li>
         * <li>Thread header and hex column headers printed on first call only</li>
         * <li>Actual formatting deferred to {@link #resetHttpState()} when message complete</li>
         * </ul>
         * </p>
         * 
         * <p>
         * <b>State Machine:</b>
         * <ul>
         * <li>HEADERS: Accumulating header lines, looking for "\r\n\r\n" separator</li>
         * <li>BODY: Headers complete, accumulating body bytes</li>
         * <li>COMPLETE: Message done, trigger output via {@link #resetHttpState()}</li>
         * </ul>
         * </p>
         * 
         * <p>
         * <b>Called by:</b> {@link ForwardThread#run()} for each data chunk received when HTTP format detected and DUMP_HTTP
         * enabled.
         * </p>
         * 
         * @param localDateTimeForwarding timestamp of this data chunk
         * @param buffer                  the raw bytes received from network
         * @param bytesRead               number of valid bytes in buffer
         * @param direction               CLIENT_TO_SERVER (request) or SERVER_TO_CLIENT (response)
         */
        public void recordHttp(final LocalDateTime localDateTimeForwarding, final byte[] buffer, final int bytesRead,
                final Direction direction) {
            if (Protocol.TCP != protocol) {
                return;
            }
            if (JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP_HTTP) == null) {
                return;
            }

            final long timeForwardingMilliSeconds = localDateTimeForwarding.atZone(ZoneId.systemDefault()).toInstant()
                    .toEpochMilli();
            synchronized (mapTimestampDataDump) {
                httpRawFullStream.write(buffer, 0, bytesRead);

                sbBufferFormatted = mapTimestampDataDump.get(timeForwardingMilliSeconds);
                if (sbBufferFormatted == null) {
                    sbBufferFormatted = new StringBuffer();
                    mapTimestampDataDump.put(timeForwardingMilliSeconds, sbBufferFormatted);

                    sbBufferFormatted
                            .append(String.format("Thread %06x: %s: %s:%s -> %s:%s [HTTP %s]", threadId,
                                    localDateTimeForwarding.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                                    inputAddress, inputPort, outputAddress, outputPort,
                                    direction == Direction.CLIENT_TO_SERVER ? "REQUEST" : "RESPONSE"))
                            .append(System.lineSeparator());
                    sbBufferFormatted.append("  Offset ");
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append(String.format("%02X ", i));
                    }
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append(String.format("%1X", (i & 0xF)));
                    }
                    sbBufferFormatted.append(System.lineSeparator());
                    sbBufferFormatted.append("  -------");
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append("----");
                    }
                    sbBufferFormatted.append(System.lineSeparator());
                }

                String data = new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.ISO_8859_1);

                if (!inHttpBody) {
                    int headerEnd = data.indexOf("\r\n\r\n");
                    if (headerEnd != -1) {
                        httpHeaderBuffer.append(data.substring(0, headerEnd));
                        String headers = httpHeaderBuffer.toString();

                        httpContentEncoding = extractHeader(headers, "Content-Encoding");
                        String transferEncoding = extractHeader(headers, "Transfer-Encoding");
                        isChunkedEncoding = "chunked".equalsIgnoreCase(transferEncoding);

                        inHttpBody = true;
                        byte[] bodyPart = extractBodyBytes(buffer, bytesRead, headerEnd + 4);
                        if (bodyPart.length > 0) {
                            httpRawBodyStream.write(bodyPart, 0, bodyPart.length);
                        }
                        httpHeaderBuffer = new StringBuilder();
                        httpHeaderBuffer.append(headers);
                    } else {
                        httpHeaderBuffer.append(data);
                    }
                } else {
                    httpRawBodyStream.write(buffer, 0, bytesRead);
                }
            }
        }

        /**
         * Finalize and output accumulated HTTP message, then reset state for next message.
         * 
         * <p>
         * <b>Rationale:</b> HTTP messages are accumulated across multiple {@link #recordHttp} calls as data arrives in chunks.
         * When a message is complete (detected by buffer not full), this method outputs the complete formatted HTTP dump and
         * resets all state variables.
         * </p>
         * 
         * <p>
         * <b>Output Format (DUMP_HTTP mode):</b>
         * <ol>
         * <li>Raw hex dump of complete message (headers + body as received on wire)</li>
         * <li>Separator line (matches hex dump width)</li>
         * <li>Parsed HTTP headers (indented 2 spaces)</li>
         * <li>Separator line between headers and body</li>
         * <li>Decoded body (decompressed, de-chunked, indented 2 spaces)</li>
         * </ol>
         * </p>
         * 
         * <p>
         * <b>Processing Order:</b> Remove chunked encoding BEFORE decompression, since Transfer-Encoding and Content-Encoding
         * are applied in layers (chunk, then compress).
         * </p>
         * 
         * <p>
         * <b>Called by:</b> {@link ForwardThread#run()} when detecting end of HTTP message (bytesRead < BUFFER_SIZE).
         * </p>
         */
        private void resetHttpState() {
            if (httpRawFullStream.size() > 0 && sbBufferFormatted != null) {
                synchronized (mapTimestampDataDump) {
                    byte[] allRawBytes = httpRawFullStream.toByteArray();

                    appendRawHexDump(allRawBytes);

                    sbBufferFormatted.append("  -------");
                    for (int i = 0; i < DUMP_WIDTH; i++) {
                        sbBufferFormatted.append("----");
                    }
                    sbBufferFormatted.append(System.lineSeparator());

                    String headers = httpHeaderBuffer.toString();
                    if (!headers.isEmpty()) {
                        String[] headerLines = headers.split("\r\n");
                        for (String line : headerLines) {
                            sbBufferFormatted.append("  ").append(line).append(System.lineSeparator());
                        }

                        sbBufferFormatted.append("  -------");
                        for (int i = 0; i < DUMP_WIDTH; i++) {
                            sbBufferFormatted.append("----");
                        }
                        sbBufferFormatted.append(System.lineSeparator());
                    }

                    byte[] rawBody = httpRawBodyStream.toByteArray();
                    byte[] cleanBody = isChunkedEncoding ? removeChunkEncoding(rawBody) : rawBody;
                    byte[] decodedBody = decompressBody(cleanBody, httpContentEncoding);
                    appendIndentedBodyWithWidth(decodedBody);
                }
            }
            httpRawFullStream = new ByteArrayOutputStream();
            httpHeaderBuffer = new StringBuilder();
            httpRawBodyStream = new ByteArrayOutputStream();
            httpContentEncoding = null;
            isChunkedEncoding = false;
            inHttpBody = false;
        }

        /**
         * Log the recorded data dump by increasing timestamp and clear the buffer.
         * 
         * <p>
         * <b>Rationale:</b> Data dumps are accumulated in memory (mapTimestampDataDump) during forwarding to avoid interleaved
         * output from multiple threads. When a ForwardThread completes (connection closed or error), this method outputs all
         * accumulated dumps in chronological order.
         * </p>
         * 
         * <p>
         * <b>Thread Safety:</b> Uses synchronized block because both CLIENT_TO_SERVER and SERVER_TO_CLIENT threads share the
         * same mapTimestampDataDump static map.
         * </p>
         * 
         * <p>
         * <b>Memory Management:</b> Clears the map after printing to free memory, especially important for long-running proxy
         * instances handling many connections.
         * </p>
         * 
         * <p>
         * <b>Called by:</b> {@link ForwardThread#run()} in the finally block, ensuring output even if an exception occurs.
         * </p>
         */
        private void logDataDump() {
            if (Protocol.TCP == protocol) {
                // For TCP, maintain the synchronized global sorting logic
                synchronized (mapTimestampDataDump) {
                    for (Entry<Long, StringBuffer> mapEntry : mapTimestampDataDump.entrySet()) {
                        System.out.print(mapEntry.getValue());
                    }
                    mapTimestampDataDump.clear();
                }
            } else if (Protocol.UDP == protocol) {
                // For UDP, print the thread-local buffer immediately
                if (sbBufferFormatted != null) {
                    System.out.print(sbBufferFormatted.toString());
                    // Clear the reference to free memory and prevent double-printing
                    sbBufferFormatted = null;
                }
            }
        }

        /**
         * Extract a header value from HTTP headers string.
         * 
         * <p>
         * <b>Rationale:</b> HTTP headers are case-insensitive by RFC specification. We need to extract specific headers
         * (Content-Encoding, Transfer-Encoding) to properly decode the body. Simple string split wouldn't handle case
         * variations like "content-encoding".
         * </p>
         * 
         * <p>
         * <b>Format:</b> HTTP headers are "Name: Value" pairs separated by CRLF:
         * 
         * <pre>
         * Content-Type: application/json\r\n
         * Content-Encoding: gzip\r\n
         * </pre>
         * </p>
         * 
         * @param headers    the complete headers string (all lines)
         * @param headerName the header name to find (case-insensitive)
         * @return the header value (trimmed), or null if header not found
         */
        private String extractHeader(final String headers, final String headerName) {
            String[] lines = headers.split("\r\n");
            for (String line : lines) {
                int colonPos = line.indexOf(':');
                if (colonPos != -1) {
                    String name = line.substring(0, colonPos).trim();
                    if (name.equalsIgnoreCase(headerName)) {
                        return line.substring(colonPos + 1).trim();
                    }
                }
            }
            return null;
        }

        /**
         * Extract body bytes from buffer after the header section ends.
         * 
         * <p>
         * <b>Rationale:</b> HTTP messages consist of headers and body separated by "\r\n\r\n". When we detect this separator in
         * {@link #recordHttp}, we need to extract any body bytes that arrived in the same buffer as the headers.
         * </p>
         * 
         * <p>
         * <b>Example:</b> If buffer contains "Content-Type: text\r\n\r\nHello", and headerEnd points to the first '\r',
         * bodyStart would be headerEnd+4, pointing to 'H'.
         * </p>
         * 
         * @param buffer    the complete buffer containing headers and possibly body
         * @param bytesRead total bytes in the buffer
         * @param bodyStart index where body begins (after "\r\n\r\n")
         * @return byte array containing only the body portion, or empty array if none
         */
        private byte[] extractBodyBytes(final byte[] buffer, final int bytesRead, final int bodyStart) {
            int bodyLength = bytesRead - bodyStart;
            if (bodyLength <= 0)
                return new byte[0];
            byte[] bodyBytes = new byte[bodyLength];
            System.arraycopy(buffer, bodyStart, bodyBytes, 0, bodyLength);
            return bodyBytes;
        }

        /**
         * Decompress body if Content-Encoding indicates compression.
         * 
         * <p>
         * <b>Rationale:</b> HTTP servers often compress responses with gzip or deflate to reduce bandwidth. Without
         * decompression, DUMP_HTTP would show binary garbage instead of readable content. This method transparently
         * decompresses based on Content-Encoding header.
         * </p>
         * 
         * <p>
         * <b>Supported Encodings:</b>
         * <ul>
         * <li>gzip - Most common, uses {@link java.util.zip.GZIPInputStream}</li>
         * <li>deflate - Less common, uses {@link java.util.zip.InflaterInputStream}</li>
         * </ul>
         * </p>
         * 
         * <p>
         * <b>Error Handling:</b> If decompression fails (corrupt data, wrong encoding), returns original bytes and logs error.
         * This allows partial debugging even with corrupted responses.
         * </p>
         * 
         * <p>
         * <b>Processing Order:</b> Must be called AFTER {@link #removeChunkEncoding()} since HTTP applies chunking as outer
         * layer, compression as inner layer.
         * </p>
         * 
         * @param rawBody         the raw body bytes (possibly compressed, already de-chunked)
         * @param contentEncoding the Content-Encoding header value ("gzip", "deflate", etc.)
         * @return decompressed bytes, or original if not compressed or on error
         */
        private byte[] decompressBody(final byte[] rawBody, final String contentEncoding) {
            if (contentEncoding == null || rawBody.length == 0) {
                return rawBody;
            }
            try {
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    return readAllBytes(new GZIPInputStream(new ByteArrayInputStream(rawBody)));
                } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
                    return readAllBytes(new InflaterInputStream(new ByteArrayInputStream(rawBody)));
                }
            } catch (IOException e) {
                // Decompression failed, return original with error note
                System.err.println("JavaForwarder decompression failed (" + contentEncoding + "): " + e.getMessage());
            }
            return rawBody;
        }

        /**
         * Read all bytes from an InputStream into a byte array.
         * 
         * <p>
         * <b>Rationale:</b> Decompression streams (GZIP, Deflate) don't provide length information upfront. We need to read
         * until EOF to get the complete decompressed content. This helper method handles the buffering and accumulation.
         * </p>
         * 
         * <p>
         * <b>Note:</b> Java 9+ has {@code InputStream.readAllBytes()}, but this implementation maintains compatibility with
         * Java 8.
         * </p>
         * 
         * @param is the input stream to read from (will be closed)
         * @return byte array containing all data from stream
         * @throws IOException if reading fails
         */
        private byte[] readAllBytes(final InputStream is) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            is.close();
            return baos.toByteArray();
        }

        /**
         * Parse and remove HTTP chunked transfer encoding from body bytes.
         * 
         * <p>
         * <b>Rationale:</b> HTTP/1.1 chunked encoding wraps body content with chunk size metadata:
         * 
         * <pre>
         * d\r\n              ← chunk size in hex (13 bytes)
         * Hello World!\r\n   ← actual data
         * 0\r\n              ← last chunk (size 0)
         * \r\n               ← trailing CRLF
         * </pre>
         * 
         * For DUMP_HTTP output, users want to see the clean body content without these control characters. The raw hex dump
         * already shows the complete wire format including chunks.
         * </p>
         * 
         * <p>
         * <b>Process:</b>
         * <ol>
         * <li>Read chunk size line (hex number ending with CRLF)</li>
         * <li>Extract that many bytes of data</li>
         * <li>Skip trailing CRLF after data</li>
         * <li>Repeat until chunk size is 0</li>
         * </ol>
         * </p>
         * 
         * @param chunkedBody the raw body bytes with chunked encoding
         * @return clean body bytes with chunk metadata removed
         */
        private byte[] removeChunkEncoding(final byte[] chunkedBody) {
            ByteArrayOutputStream clean = new ByteArrayOutputStream();
            int pos = 0;
            while (pos < chunkedBody.length) {
                // Find chunk size line (ends with \r\n)
                int crlfPos = findCRLF(chunkedBody, pos);
                if (crlfPos == -1) {
                    break;
                }
                String sizeLine = new String(chunkedBody, pos, crlfPos - pos, StandardCharsets.US_ASCII);
                int chunkSize = Integer.parseInt(sizeLine.trim().split(";")[0], 16);

                if (chunkSize == 0) {
                    // Last chunk
                    break;
                }
                pos = crlfPos + 2; // Skip \r\n
                clean.write(chunkedBody, pos, chunkSize);
                pos += chunkSize + 2; // Skip chunk data + trailing \r\n
            }
            return clean.toByteArray();
        }

        /**
         * Append raw hex dump of bytes to sbBufferFormatted without thread header.
         * 
         * <p>
         * <b>Rationale:</b> Both DUMP and DUMP_HTTP modes need hex dump formatting, but with different context. This method
         * extracts the core hex formatting logic for reuse:
         * <ul>
         * <li>DUMP mode: {@link #record()} calls this implicitly (inline code)</li>
         * <li>DUMP_HTTP mode: {@link #resetHttpState()} calls this explicitly</li>
         * </ul>
         * </p>
         * 
         * <p>
         * <b>Design:</b> Uses local variables (not instance variables) to be self-contained. Assumes caller has already printed
         * thread header and column headers. Formats complete byte array in one pass (vs incremental in {@link #record()}).
         * </p>
         * 
         * <p>
         * <b>Output Format:</b> Each row shows:
         * <ul>
         * <li>Offset in hex (6 digits)</li>
         * <li>DUMP_WIDTH bytes in hex (2 digits each + space)</li>
         * <li>ASCII representation (printable chars or space)</li>
         * </ul>
         * Last row is padded with spaces if incomplete.
         * </p>
         * 
         * @param rawBytes the complete byte array to dump
         */
        private void appendRawHexDump(final byte[] rawBytes) {
            int localBytesIndex = 0;
            int localBytesOffset = 0;
            StringBuffer localDataHex = new StringBuffer();
            StringBuffer localDataChar = new StringBuffer();
            for (int bufferOffset = 0; bufferOffset < rawBytes.length; bufferOffset++) {
                byte dataByte = rawBytes[bufferOffset];
                if (localBytesIndex == 0) {
                    localDataHex.append(String.format("  %06X ", localBytesOffset));
                }
                localDataHex.append(String.format("%02X ", dataByte));
                Character dataByteChar = new Character((char) dataByte);
                int type = Character.getType(dataByteChar);
                if ((Character.CONTROL == type) || (Character.FORMAT == type) || (Character.PRIVATE_USE == type)
                        || (Character.SURROGATE == type) || (Character.UNASSIGNED == type)) {
                    localDataChar.append(" ");
                } else {
                    localDataChar.append(dataByteChar);
                }
                localBytesOffset++;
                localBytesIndex++;
                if (localBytesIndex >= DUMP_WIDTH) {
                    sbBufferFormatted.append(localDataHex).append(localDataChar).append(System.lineSeparator());
                    localDataHex = new StringBuffer();
                    localDataChar = new StringBuffer();
                    localBytesIndex = 0;
                }
            }
            if (localBytesIndex > 0) {
                for (int bytesIndexNoData = localBytesIndex; bytesIndexNoData < DUMP_WIDTH; bytesIndexNoData++) {
                    localDataHex.append("   ");
                }
                sbBufferFormatted.append(localDataHex).append(localDataChar).append(System.lineSeparator());
            }
        }

        /**
         * Append HTTP body content with 2-space indentation for DUMP_HTTP format.
         * 
         * <p>
         * <b>Rationale:</b> In DUMP_HTTP mode, the body content should be indented to visually align with the HTTP headers
         * (which are also indented 2 spaces). This creates a clean, readable format similar to Postman/Bruno HTTP clients.
         * </p>
         * 
         * <p>
         * <b>Behavior:</b>
         * <ul>
         * <li>Text bodies: Each line prefixed with 2 spaces, preserves line breaks</li>
         * <li>Binary bodies: Hex dump with 4-space indent (2 for alignment + 2 for offset column)</li>
         * <li>Respects DUMP_WIDTH for binary hex dump formatting</li>
         * <li>No trailing newline added (caller controls spacing)</li>
         * </ul>
         * </p>
         * 
         * @param bodyBytes the body bytes (already decompressed and de-chunked)
         */
        private void appendIndentedBodyWithWidth(final byte[] bodyBytes) {
            if (bodyBytes.length == 0)
                return;
            boolean isText = true;
            for (byte b : bodyBytes) {
                if (b < 32 && b != '\r' && b != '\n' && b != '\t') {
                    isText = false;
                    break;
                }
            }
            if (isText) {
                String bodyText = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
                String[] lines = bodyText.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (i == lines.length - 1 && lines[i].isEmpty()) {
                        break;
                    }
                    sbBufferFormatted.append("  ").append(lines[i]);
                    if (i < lines.length - 1) {
                        sbBufferFormatted.append(System.lineSeparator());
                    }
                }
            } else {
                sbBufferFormatted.append("  [Binary body - hex dump]").append(System.lineSeparator());
                for (int i = 0; i < bodyBytes.length; i += DUMP_WIDTH) {
                    sbBufferFormatted.append("    ").append(String.format("%06X ", i));
                    for (int j = 0; j < DUMP_WIDTH; j++) {
                        if (i + j < bodyBytes.length) {
                            sbBufferFormatted.append(String.format("%02X ", bodyBytes[i + j]));
                        } else {
                            sbBufferFormatted.append("   ");
                        }
                    }
                    for (int j = 0; j < DUMP_WIDTH && i + j < bodyBytes.length; j++) {
                        char c = (char) bodyBytes[i + j];
                        sbBufferFormatted.append(c >= 32 && c < 127 ? c : '.');
                    }
                    if (i + DUMP_WIDTH < bodyBytes.length) {
                        sbBufferFormatted.append(System.lineSeparator());
                    }
                }
            }
        }

        /**
         * Find the next CRLF (Carriage Return + Line Feed) sequence in a byte array.
         * 
         * <p>
         * <b>Rationale:</b> HTTP uses CRLF (\r\n) as line terminators. When parsing chunked transfer encoding, we need to find
         * chunk size lines which are terminated by CRLF. This helper method encapsulates the scanning logic.
         * </p>
         * 
         * <p>
         * <b>Example:</b> In chunked body "d\r\nHello World!\r\n0\r\n\r\n", this finds the CRLF positions to separate chunk
         * size lines from chunk data.
         * </p>
         * 
         * @param data  the byte array to search
         * @param start the starting position in the array
         * @return the index of '\r' in the CRLF sequence, or -1 if not found
         */
        private int findCRLF(final byte[] data, final int start) {
            for (int i = start; i < data.length - 1; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n')
                    return i;
            }
            return -1;
        }

        public void setInputAddress(String inputAddress) {
            this.inputAddress = inputAddress;
        }

        public void setInputPort(String inputPort) {
            this.inputPort = inputPort;
        }

        public void setOutputAddress(String outputAddress) {
            this.outputAddress = outputAddress;
        }

        public void setOutputPort(String outputPort) {
            this.outputPort = outputPort;
        }

    }

    /**
     * Main entry point.
     * 
     * @param args to use and validate
     * @throws IOException
     */
    public static void main(final String[] args) throws IOException {
        System.out.println("JavaForwarder v1.21 (C) by Roman.Stangl@gmx.net");
        try {
            String remoteHost = "localhost";
            int remotePort = 9080;
            int localPort = 8888;
            // Process commandline
            if (args.length == 3) {
                remoteHost = args[0];
                remotePort = Integer.valueOf(args[1]);
                localPort = Integer.valueOf(args[2]);
            } else {
                System.out.println("");
                System.out.println("Usage: JavaForwarder remoteHost remotePort localPort");
                System.out.println("");
                System.out.println("  Supported optional environment variables:");
                System.out.println("    MODE ... forward TCP (default) or UDP data");
                System.out.println("    DUMP ... any value to record data forwarded as formatted data dump");
                System.out.println("    DUMP_HTTP ... any value to record data forwarded as formatted HTTP dump");
                System.out.println("    DUMP_WIDTH ... multiple of 16 defining number of bytes per row of formatted data dump");
                System.out.println("");
                return;
            }
            // Check IP we want to forward
            Protocol protocol = Protocol.TCP;
            if (Protocol.UDP.name().equalsIgnoreCase(
                    JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_MODE))) {
                protocol = Protocol.UDP;
            }
            // Printing a start-up message
            System.out.println("JavaForwarder starting proxy thread, forwarding " + protocol + " connection: " + remoteHost
                    + ":" + remotePort + " on local port " + localPort);
            // And start running the server
            ProxyThread proxyThread = new ProxyThread(protocol, remoteHost, remotePort, localPort);
            proxyThread.start();
            Thread.sleep(500);
            // Check if proxy thread is still alive after startup
            if (!proxyThread.isAlive()) {
                System.out.println("JavaForwarder startup failed, exiting ...");
                return;
            }
            // Wait for quitting
            System.out.println("JavaForwarder waiting for client connection(s), press Enter to terminate JavaForwarder ...");
            try {
                System.in.read();
            } catch (Exception e) {
                // Ignore
            }
            if (proxyThread.isAlive()) {
                System.out.println("JavaForwarder termination requested, waiting for proxy thread ...");
            }
            JavaForwarder.doExit = true;
            proxyThread.join();
            System.out.println("JavaForwarder exiting ...");
        } catch (Exception e) {
            System.err.println(e); // Prints the standard errors
        }
    }

    /**
     * It will run a single-threaded proxy server on the provided local port to forward {@code IP} data between
     * {@code localhost:localPort} and {@code remoteHost:remotePort}.
     * 
     * @param protocol   of {@code IP} data to forward
     * @param remoteHost hostname or IP-address of server data will be forwarded to
     * @param remotePort port of server data will be forwarded to
     * @param localPort  port to start a server on {@code localhost} that will receive the data and forward it to
     *                   {@code remoteHost:remotePort}
     * @throws IOException
     */
    public static void runServer(final Protocol protocol, final String remoteHost, final int remotePort, final int localPort)
            throws IOException {
        System.out.println("JavaForwarder proxy thread waiting for client connection(s) ...");
        List<ClientThread> clientThreads = new ArrayList<>();
        if (Protocol.TCP == protocol) {
            // Creating a ServerSocket to listen for connections
            ServerSocket serverSocket = null;
            try {
                serverSocket = new ServerSocket(localPort);
                serverSocket.setSoTimeout(1000);
                while (!JavaForwarder.doExit) {
                    try {
                        while (true) {
                            Socket clientSocket = serverSocket.accept();
                            ClientThread clientThread = new ClientThread(protocol, clientSocket, remoteHost, remotePort);
                            System.out.println("JavaForwarder accepted client thread ...");
                            clientThreads.add(clientThread);
                            clientThread.start();
                        }
                    } catch (SocketTimeoutException e) {
                        // Ignore so we can check for termination request
                    }
                }
            } catch (BindException e) {
                System.err.println("JavaForwarder port " + localPort + " is already in use.");
                System.err.println(
                        "JavaForwarder cannot start server. Please check if another instance is running or choose a different port.");
                JavaForwarder.doExit = true;
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        } else if (Protocol.UDP == protocol) {
            // Creating a ServerSocket to listen for connections
            try (DatagramSocket clientDatagramSocket = new DatagramSocket(localPort)) {
                clientDatagramSocket.setSoTimeout(1000);
                ClientThread clientThread = new ClientThread(protocol, clientDatagramSocket, remoteHost, remotePort);
                System.out.println("JavaForwarder accepted client thread ...");
                clientThreads.add(clientThread);
                clientThread.start();
                while (!JavaForwarder.doExit) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
            } catch (BindException e) {
                System.err.println("JavaForwarder port " + localPort + " is already in use.");
                System.err.println(
                        "JavaForwarder cannot start server. Please check if another instance is running or choose a different port.");
                JavaForwarder.doExit = true;
            } catch (SocketException e) {
                // SocketTimeoutException ?
                // Ignore so we can check for termination request
                if (!JavaForwarder.doExit) {
                    e.printStackTrace();
                }
            } finally {
            }
            while (!JavaForwarder.doExit) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
            }
        }
        for (ClientThread clientThread : clientThreads) {
            try {
                clientThread.connectionBroken();
                clientThread.join();
                System.out.flush();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("JavaForwarder proxy thread terminating ...");
    }

    /**
     * Retrieve a configuration value from JVM system properties or OS environment variables.
     * 
     * <p>
     * <b>Rationale:</b> JavaForwarder can be configured either via:
     * <ul>
     * <li>JVM system properties: {@code java -DDUMP=true -jar JavaForwarder.jar ...}</li>
     * <li>OS environment variables: {@code export DUMP=true; java -jar JavaForwarder.jar ...}</li>
     * </ul>
     * 
     * This dual approach provides flexibility:
     * <ul>
     * <li>System properties: Per-invocation configuration, useful in scripts</li>
     * <li>Environment variables: Session/user-wide configuration, easier for interactive use</li>
     * </ul>
     * </p>
     * 
     * <p>
     * <b>Precedence:</b> System properties take priority over environment variables. This allows command-line overrides of
     * environment defaults:
     * 
     * <pre>
     * export DUMP_WIDTH=32              # Default for session
     * java -DDUMP_WIDTH=64 ...          # Override for this run only
     * </pre>
     * </p>
     * 
     * <p>
     * <b>Empty String Handling:</b> Empty strings are treated as null (not set). This prevents confusion when environment
     * variable is defined but empty: {@code export DUMP=}
     * </p>
     * 
     * <p>
     * <b>Usage Pattern:</b>
     * 
     * <pre>
     * // Check if feature enabled (any non-null value)
     * boolean dumpEnabled = getPropertyOrEnvironmentVariable("DUMP") != null;
     * 
     * // Get specific value with parsing
     * String widthStr = getPropertyOrEnvironmentVariable("DUMP_WIDTH");
     * int width = widthStr != null ? Integer.parseInt(widthStr) : 16;
     * </pre>
     * </p>
     * 
     * <p>
     * <b>Supported Configuration Keys:</b>
     * <ul>
     * <li>{@link #ENVIRONMENT_VARIABLE_MODE} - Protocol: TCP or UDP</li>
     * <li>{@link #ENVIRONMENT_VARIABLE_DUMP} - Enable hex dump output</li>
     * <li>{@link #ENVIRONMENT_VARIABLE_DUMP_HTTP} - Enable HTTP-aware dump output</li>
     * <li>{@link #ENVIRONMENT_VARIALBE_DUMP_WIDTH} - Bytes per row in hex dump (multiple of 16)</li>
     * </ul>
     * </p>
     * 
     * @param key the name of the property or environment variable
     * @return the value found, or null if not found or empty
     */
    private static String getPropertyOrEnvironmentVariable(final String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv(key);
        }
        return value;
    }

}
