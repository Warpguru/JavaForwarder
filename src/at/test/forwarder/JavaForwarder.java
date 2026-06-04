package at.test.forwarder;

/**
 * This program is an example from the book "Internet programming with Java" by Svetlin Nakov. It is freeware. For more information:
 * http://www.nakov.com/books/inetjava/
 */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
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

/**
 * JavaForwarder is a simple {@code TCP} bridging software that allows a {@code TCP} port on some host to be transparently
 * forwarded to some other {@code TCP} port on some other host. JavaForwarder continuously accepts client connections on the
 * listening {@code TCP} port (source port) and starts a thread (ClientThread) that connects to the destination host and starts
 * forwarding the data between the client socket and destination socket.
 * 
 * Run test Node HTTP server to test with chunked messages (request to http://localhost:3000/ retrieves 1. / and 2. /favicon.ico):
 * <xmp>
 * node -e "require('http').createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/plain'});res.end('Hello World!\n');}).listen(3000)"
 * </xmp>
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
                    System.out.println("JavaForwarder connected to server");
                } catch (Exception e) {
                    e.printStackTrace();
//                  System.err.println("JavaForwarder failed to connect to initiate " + protocol + " connection: " + remoteHost
//                          + ":" + remotePort);
                    connectionBroken();
                    JavaForwarder.doExit = true;
                    System.out.println("JavaForwarder failed to start, press Enter to terminate JavaForwarder ...");
                    return;
                }
                // Start forwarding data between server and client
                forwardingActive = true;
                ForwardThread clientForward = new ForwardThread(this, protocol, clientDatagramSocket, serverDatagramSocket);
                clientForward.start();
//              ForwardThread serverForward = new ForwardThread(this, protocol, serverDatagramSocket, clientDatagramSocket);
//              serverForward.start();
//              System.out.println("JavaForwarder " + protocol + " connection: "
//                      + clientDatagramSocket.getInetAddress().getHostAddress() + ":" + clientDatagramSocket.getPort()
//                      + " <--> " + serverDatagramSocket.getInetAddress().getHostAddress() + ":"
//                      + serverDatagramSocket.getPort() + " started");
            }
        }
           
        /**
         * Called by ForwardThread when one direction of forwarding completes.
         * Marks the direction as inactive and handles half-close scenarios.
         * 
         * @param direction which direction completed
         */
        public synchronized void forwardingDirectionComplete(Direction direction) {
            directionActive.put(direction, false);
            System.out.println("JavaForwarder: " + direction + " forwarding completed");
            // When server closes (SERVER_TO_CLIENT completes), close the client socket
            // to unblock CLIENT_TO_SERVER thread which is waiting on clientInputStream.read()
            if (direction == Direction.SERVER_TO_CLIENT && clientSocket != null && !clientSocket.isClosed()) {
                try {
                    // Close client socket to make CLIENT_TO_SERVER thread exit
                    clientSocket.close();
                    System.out.println("JavaForwarder: Closed client socket to unblock CLIENT_TO_SERVER");
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
                    System.out.println("JavaForwarder: Closed server socket to unblock SERVER_TO_CLIENT");
                } catch (Exception e) {
                    // Ignore - socket may already be closed
                }
            }
        }

        /**
         * Detect and set the traffic format based on initial data.
         * Thread-safe - only the first caller performs detection, subsequent calls return cached result.
         * 
         * @param buffer the data buffer to analyze
         * @param length number of valid bytes in buffer
         * @return the detected Format (HTTP or OTHER)
         */
        public Format detectFormat(byte[] buffer, int length) {
            synchronized (formatDetectionLock) {
                if (detectedFormat != null) {
                    return detectedFormat;
                }
                detectedFormat = isHttpTraffic(buffer, length) ? Format.HTTP : Format.OTHER;
                System.out.println("JavaForwarder: Detected traffic format: " + detectedFormat);
                return detectedFormat;
            }
        }
        
        /**
         * Called by some of the forwarding threads to indicate that its socket connection is broken.
         * Only closes sockets when BOTH directions have completed to support HTTP Keep-Alive.
         * Closing the client and server sockets causes all threads blocked on reading or writing to
         * these sockets to get an exception and to finish their execution.
         */
        public synchronized void connectionBroken() {
            // Check if any direction is still active
            boolean anyDirectionActive = directionActive.values().stream().anyMatch(active -> active);
            
            if (anyDirectionActive) {
                System.out.println("JavaForwarder: One direction closed, waiting for other direction");
                return; // Don't close yet - other direction still active
            }
            
            // Both directions finished - now close everything
            System.out.println("JavaForwarder: Both directions closed, terminating connection");
            
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
                            + clientDatagramSocket.getInetAddress().getHostAddress() 
                            + ":" 
                            + clientDatagramSocket.getPort()
                            + " <--> " 
                            + serverDatagramSocket.getInetAddress().getHostAddress() + ":"
                            + serverDatagramSocket.getPort() + " stopped");
                    }
                }
                forwardingActive = false;
            }
        }

        /**
         * Check if the data appears to be HTTP traffic.
         * 
         * @param buffer the data buffer to check
         * @param length number of valid bytes in buffer
         * @return true if HTTP traffic detected
         */
        private boolean isHttpTraffic(byte[] buffer, int length) {
            if (length < 4) return false;
            String prefix = new String(buffer, 0, Math.min(length, 16), java.nio.charset.StandardCharsets.US_ASCII);
            // HTTP request methods
            if (prefix.startsWith("GET ") || prefix.startsWith("POST ") || 
                prefix.startsWith("PUT ") || prefix.startsWith("DELETE ") ||
                prefix.startsWith("PATCH ") || prefix.startsWith("HEAD ") ||
                prefix.startsWith("OPTIONS ") || prefix.startsWith("CONNECT ") ||
                prefix.startsWith("TRACE ")) {
                return true;
            }
            // HTTP response status line
            if (prefix.startsWith("HTTP/1.0") || prefix.startsWith("HTTP/1.1")) {
                return true;
            }
            return false;
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

        private static final int BUFFER_SIZE = 8192;

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
                final Socket outputSocket, final InputStream inputStream, final OutputStream outputStream, final Direction direction) {
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
         */
        public ForwardThread(final ClientThread clientThread, final Protocol protocol, final DatagramSocket inputDatagramSocket,
                final DatagramSocket outputDatagramSocket) {
            super();
            this.clientThread = clientThread;
            this.protocol = protocol;
            this.inputSocket = null;
            this.outputSocket = null;
            this.inputDatagramSocket = inputDatagramSocket;
            this.outputDatagramSocket = outputDatagramSocket;
            this.inputStream = null;
            this.outputStream = null;
            this.direction = null;
        }

        /**
         * Runs the thread. Continuously reads the input stream and writes the read data to the output stream. If reading or
         * writing fail, exits the thread and notifies the parent about the failure.
         */
        public void run() {
            final byte[] buffer = new byte[BUFFER_SIZE];
            LocalDateTime localDateTimeForward = null;
            if (Protocol.TCP == protocol) {
                final DataDumpManager dataDumpManager = new DataDumpManager(Thread.currentThread().getId(), inputSocket,
                        outputSocket);
                // Check if DUMP_HTTP is enabled
                final boolean dumpHttpEnabled = JavaForwarder.getPropertyOrEnvironmentVariable(
                        JavaForwarder.ENVIRONMENT_VARIABLE_DUMP_HTTP) != null;
                try {
                    while (!JavaForwarder.doExit) {
                        System.out.println("JavaForwarder: " + direction + " waiting for data...");
                        int bytesRead = inputStream.read(buffer);
                        System.out.println("JavaForwarder: " + direction + " read " + bytesRead + " bytes");
                        // Record data read
                        if (localDateTimeForward == null) {
                            localDateTimeForward = LocalDateTime.now();
                        }
                        dataDumpManager.record(localDateTimeForward, buffer, bytesRead);
                        // If end of stream is reached --> exit
                        if (bytesRead == -1) {
                            System.out.println("JavaForwarder: " + direction + " EOF detected, exiting");
                            break;
                        }
                        if (bytesRead < BUFFER_SIZE) {
                            localDateTimeForward = null;
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
                try {
                    while (!JavaForwarder.doExit) {
                        DatagramPacket inputDatagramPacket = new DatagramPacket(buffer, buffer.length);
                        try {
                            inputDatagramSocket.receive(inputDatagramPacket);
                            // Record data read
                            if (localDateTimeForward == null) {
                                localDateTimeForward = LocalDateTime.now();
                            }
                            String clientMessage = new String(inputDatagramPacket.getData(), 0, inputDatagramPacket.getLength());
                            System.out.println(clientMessage);
                        } catch (SocketTimeoutException e) {
                            // Ignore timeout and continue waiting until we should exist
                        }
                    }
                } catch (Exception e) {
                    // ???
                    e.printStackTrace();
                }
                // Notify parent thread that the connection is broken
                clientThread.connectionBroken();
            }
        }
    }

    /**
     * Data dump manager to store data forwarded by the {@link ForwardThread}.
     */
    private static class DataDumpManager {

        /** Map (shared between threads) of chronological timestamps and formatted traffic between {@code inputSocket} and {@code outputSocket}. */
        private static Map<Long, StringBuffer> mapTimestampDataDump = new TreeMap<Long, StringBuffer>();

        /** ID of thread executing {@link ForwardThread} instance. */
        private Long threadId = null;
        /** {@link Socket} data is read from. */
        private Socket inputSocket = null;
        /** {@link Socket} data is forwarded (copied) to. */
        private Socket outputSocket = null;

        /** Number of bytes dumped from data dump in a single line. */
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
        /** Buffer to accumulate HTTP body for current request/response. */
        private StringBuilder httpBodyBuffer = new StringBuilder();
        /** Flag indicating if we're currently in headers or body. */
        private boolean inHttpBody = false;        

        /**
         * {@link DataDumpManager} initialization.
         * 
         * @param threadId     of thread forwarding data from {@code inputSocket} to {@code outputSocket}
         * @param inputSocket  to record host and port
         * @param outputSocket to record host and port
         */
        public DataDumpManager(final Long threadId, final Socket inputSocket, final Socket outputSocket) {
            super();
            this.threadId = threadId;
            this.inputSocket = inputSocket;
            this.outputSocket = outputSocket;
            try {
                Integer dumpWidth = Integer.valueOf(JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIALBE_DUMP_WIDTH));
                DUMP_WIDTH = (dumpWidth / 16) * 16;
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        /**
         * Record the bytes in {@code buffer} forwarded from {@code inputSocket} to {@code outputSocket} as a formatted data
         * dump.
         * 
         * @param localDateTimeForwarding timestamp of forwarding
         * @param buffer                  referencing buffer to read from {@code inputSocket} to record data dump from
         * @param bytesRead               containing the number of bytes actually read from {@code inputSocket}
         */
        public void record(final LocalDateTime localDateTimeForwarding, final byte[] buffer, final int bytesRead) {
            if (JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP) == null) {
                return;
            }
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
            // Dump data in Hex and Ascii in DUMP_WIDTH bytes blocks
            for (int bufferOffset = 0; bufferOffset < bytesRead; bufferOffset++) {
                byte dataByte = buffer[bufferOffset];
                if (bytesOffset == 0) {
                    // Header row with record details
                    sbBufferFormatted
                            .append(String.format("Thread %06x: %s: %s:%s -> %s:%s", threadId,
                                    localDateTimeForwarding.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")),
                                    inputSocket.getInetAddress().getHostAddress(), inputSocket.getPort(),
                                    outputSocket.getInetAddress().getHostAddress(), outputSocket.getPort()))
                            .append(System.lineSeparator());
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
         * Record HTTP traffic in Postman/Bruno-like format.
         * 
         * @param localDateTimeForwarding timestamp of forwarding
         * @param buffer data buffer
         * @param bytesRead number of bytes read
         * @param direction CLIENT_TO_SERVER (request) or SERVER_TO_CLIENT (response)
         */
        public void recordHttp(final LocalDateTime localDateTimeForwarding, final byte[] buffer, 
                               final int bytesRead, final Direction direction) {
            if (JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP_HTTP) == null) {
                return;
            }
            
            final long timeForwardingMilliSeconds = localDateTimeForwarding.atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            synchronized (mapTimestampDataDump) {
                sbBufferFormatted = mapTimestampDataDump.get(timeForwardingMilliSeconds);
                if (sbBufferFormatted == null) {
                    sbBufferFormatted = new StringBuffer();
                    mapTimestampDataDump.put(timeForwardingMilliSeconds, sbBufferFormatted);
                    
                    // Print header for new HTTP exchange
                    sbBufferFormatted.append(System.lineSeparator());
                    sbBufferFormatted.append("###############################################################################")
                            .append(System.lineSeparator());
                    sbBufferFormatted.append("### ").append(direction == Direction.CLIENT_TO_SERVER ? "REQUEST" : "RESPONSE")
                            .append(" - Thread ").append(String.format("%06x", threadId))
                            .append(" - ").append(localDateTimeForwarding.format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")))
                            .append(System.lineSeparator());
                    sbBufferFormatted.append("### ").append(inputSocket.getInetAddress().getHostAddress())
                            .append(":").append(inputSocket.getPort())
                            .append(" -> ").append(outputSocket.getInetAddress().getHostAddress())
                            .append(":").append(outputSocket.getPort())
                            .append(System.lineSeparator());
                    sbBufferFormatted.append(System.lineSeparator());
                }
                
                // Convert buffer to string and append
                String data = new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8);
                
                // Simple parsing: look for double CRLF to separate headers from body
                if (!inHttpBody) {
                    int headerEnd = data.indexOf("\r\n\r\n");
                    if (headerEnd != -1) {
                        // Found end of headers
                        httpHeaderBuffer.append(data.substring(0, headerEnd));
                        sbBufferFormatted.append(httpHeaderBuffer.toString()).append(System.lineSeparator());
                        sbBufferFormatted.append(System.lineSeparator());
                        
                        // Start body
                        inHttpBody = true;
                        String bodyPart = data.substring(headerEnd + 4);
                        if (!bodyPart.isEmpty()) {
                            appendBodyWithWidth(bodyPart);
                        }
                        httpHeaderBuffer = new StringBuilder();
                    } else {
                        httpHeaderBuffer.append(data);
                    }
                } else {
                    // Already in body, just append
                    appendBodyWithWidth(data);
                }
            }
        }
        
        /**
         * Reset HTTP state for next request/response.
         */
        public void resetHttpState() {
            httpHeaderBuffer = new StringBuilder();
            httpBodyBuffer = new StringBuilder();
            inHttpBody = false;
        }
        
        /**
         * Log the recorded data dump by increasing timestamp.
         */
        public void logDataDump() {
            synchronized (mapTimestampDataDump) {
                for (Entry<Long, StringBuffer> mapEntry : mapTimestampDataDump.entrySet()) {
                    System.out.print(mapEntry.getValue());
                }
                mapTimestampDataDump.clear();
            }
        }

        /**
         * Append body content respecting DUMP_WIDTH for binary content.
         */
        private void appendBodyWithWidth(String bodyData) {
            // For text content, preserve original formatting
            // For binary, could do hex dump respecting DUMP_WIDTH
            byte[] bodyBytes = bodyData.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            // Check if printable text
            boolean isText = true;
            for (byte b : bodyBytes) {
                if (b < 32 && b != '\r' && b != '\n' && b != '\t') {
                    isText = false;
                    break;
                }
            }
            
            if (isText) {
                sbBufferFormatted.append(bodyData);
            } else {
                // Hex dump respecting DUMP_WIDTH
                for (int i = 0; i < bodyBytes.length; i += DUMP_WIDTH) {
                    sbBufferFormatted.append(String.format("  %06X ", i));
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
                    sbBufferFormatted.append(System.lineSeparator());
                }
            }
        }
        
    }

    /**
     * Main entry point.
     * 
     * @param args to use and validate
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        System.out.println("JavaForwarder v1.14 (C) by Roman.Stangl@gmx.net");
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
            if (Protocol.UDP.name().equalsIgnoreCase(JavaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_MODE))) {
                protocol = Protocol.UDP;
            }
            // Printing a start-up message
            System.out.println("JavaForwarder starting proxy thread, forwarding " + protocol + " connection: " + remoteHost
                    + ":" + remotePort + " on local port " + localPort);
            // And start running the server
            ProxyThread proxyThread = new ProxyThread(protocol, remoteHost, remotePort, localPort);
            proxyThread.start();
            Thread.sleep(100);
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
            while (!JavaForwarder.doExit) {
                try (ServerSocket serverSocket = new ServerSocket(localPort)) {
                    serverSocket.setSoTimeout(1000);
                    while (true) {
                        Socket clientSocket = serverSocket.accept();
                        ClientThread clientThread = new ClientThread(protocol, clientSocket, remoteHost, remotePort);
                        System.out.println("JavaForwarder accepted client thread ...");
                        clientThreads.add(clientThread);
                        clientThread.start();
                    }
                } catch (SocketTimeoutException e) {
                    // Ignore so we can check for termination request
                } finally {
                }
            }
        } else if (Protocol.UDP == protocol) {
            // Creating a ServerSocket to listen for connections
            try (DatagramSocket clientDatagramSocket = new DatagramSocket(localPort)) {
                clientDatagramSocket.setSoTimeout(1000);
//              while (true) {
                ClientThread clientThread = new ClientThread(protocol, clientDatagramSocket, remoteHost, remotePort);
                System.out.println("JavaForwarder accepted client thread ...");
                clientThreads.add(clientThread);
                clientThread.start();
//              }
                while (!JavaForwarder.doExit) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                    }
                }
            } catch (SocketException e) {
                // SocketTimeoutException ?
                // Ignore so we can check for termination request
                e.printStackTrace();
            } finally {
            }
            while (!JavaForwarder.doExit) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                }
            }
        }
        for (ClientThread clientThread : clientThreads) {
            try {
                clientThread.connectionBroken();
                clientThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("JavaForwarder proxy thread terminating ...");
    }

    /**
     * Retrieve a configuration value from JVM system properties or OS environment variables.
     * 
     * @param key the name of the property or environment variable
     * @return the value found, or null if not found
     */
    private static String getPropertyOrEnvironmentVariable(final String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv(key);
        }
        return value;
    }
    
}
