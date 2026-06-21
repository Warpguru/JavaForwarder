package edu.java;

/**
 * This program is an example from the book "Internet programming with Java" by Svetlin Nakov. It is freeware. For more information:
 * http://www.nakov.com/books/inetjava/
 */
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import edu.java.helper.Protocol;
import edu.java.thread.ClientThread;
import edu.java.thread.ProxyThread;

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

    /** Logger to sysout without formatting and logfile with formatting. */
    public static final String LOGGER_SYSOUT = "edu.java.Sysout";
    /** Mode of forwarding operation, {@code TCP} (default) or {@code UDP}. */
    public static final String ENVIRONMENT_VARIABLE_MODE = "MODE";
    /** Set to any value to activate recording of the forwarded data in a formatted data dump. */
    public static final String ENVIRONMENT_VARIABLE_DUMP = "DUMP";
    /** Set to a multiple of 16 to define non default width (number of bytes per rows) in formatted data dump. */
    public static final String ENVIRONMENT_VARIALBE_DUMP_WIDTH = "DUMP_WIDTH";
    /** Set to any value to activate recording of the forwarded HTTP data in a Postman/Bruno like data dump. */
    public static final String ENVIRONMENT_VARIABLE_DUMP_HTTP = "DUMP_HTTP";

    /** Flag checked by threads if they should terminate. */
    private boolean doExit = false;

    /** Default logger (using appender that includes e.g. timestamp, ...). */
    private static final Logger logger = LogManager.getLogger(JavaForwarder.class);
    /** Sysout logger (logging to sysout without formatting and logfile with formatting). */
    private static final Logger loggerSysout = LogManager.getLogger(LOGGER_SYSOUT);

    /**
     * Main entry point.
     * 
     * @param args to use and validate
     * @throws IOException
     */
    public static void main(final String[] args) throws IOException {
        JavaForwarder javaForwarder = new JavaForwarder();
        javaForwarder.run(args);
    }

    /**
     * Main entry point.
     * 
     * @param args to use and validate
     * @throws IOException
     */
    private void run(final String[] args) throws IOException {
        loggerSysout.info("JavaForwarder v1.25 (C) by Roman.Stangl@gmx.net");
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
                loggerSysout.info("");
                loggerSysout.info("Usage: JavaForwarder remoteHost remotePort localPort");
                loggerSysout.info("");
                loggerSysout.info("  Supported optional environment variables:");
                loggerSysout.info("    MODE ... forward TCP (default) or UDP data");
                loggerSysout.info("    DUMP ... any value to record data forwarded as formatted data dump");
                loggerSysout.info("    DUMP_HTTP ... any value to record data forwarded as formatted HTTP dump");
                loggerSysout.info("    DUMP_WIDTH ... multiple of 16 defining number of bytes per row of formatted data dump");
                loggerSysout.info("");
                return;
            }
            // Check IP we want to forward
            Protocol protocol = Protocol.TCP;
            if (Protocol.UDP.name()
                    .equalsIgnoreCase(getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_MODE))) {
                protocol = Protocol.UDP;
            }
            // Printing a start-up message
            loggerSysout.info("Starting proxy thread, forwarding " + protocol + " connection: " + remoteHost + ":" + remotePort
                    + " on local port " + localPort);
            // And start running the server
            ProxyThread proxyThread = new ProxyThread(this, protocol, remoteHost, remotePort, localPort);
            proxyThread.start();
            Thread.sleep(500);
            // Check if proxy thread is still alive after startup
            if (!proxyThread.isAlive()) {
                loggerSysout.info("Startup failed, exiting ...");
                return;
            }
            // Wait for quitting
            loggerSysout.info("Waiting for client connection(s), press Enter to terminate JavaForwarder ...");
            try {
                System.in.read();
            } catch (Exception e) {
                // Ignore
            }
            if (proxyThread.isAlive()) {
                loggerSysout.info("Termination requested, waiting for proxy thread ...");
            }
            setDoExit(true);
            proxyThread.join();
            loggerSysout.info("Exiting ...");
        } catch (Exception e) {
            logger.error(e);
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
    public void runServer(final Protocol protocol, final String remoteHost, final int remotePort, final int localPort)
            throws IOException {
        loggerSysout.info("Proxy thread waiting for client connection(s) ...");
        List<ClientThread> clientThreads = new ArrayList<>();
        if (Protocol.TCP == protocol) {
            // Creating a ServerSocket to listen for connections
            ServerSocket serverSocket = null;
            try {
                serverSocket = new ServerSocket(localPort);
                serverSocket.setSoTimeout(1000);
                while (!isDoExit()) {
                    try {
                        while (true) {
                            Socket clientSocket = serverSocket.accept();
                            ClientThread clientThread = new ClientThread(this, protocol, clientSocket, remoteHost, remotePort);
                            loggerSysout.info("Accepted client thread ...");
                            clientThreads.add(clientThread);
                            clientThread.start();
                        }
                    } catch (SocketTimeoutException e) {
                        // Ignore so we can check for termination request
                    }
                }
            } catch (BindException e) {
                loggerSysout.error("Port " + localPort + " is already in use.");
                loggerSysout
                        .error("Cannot start server. Please check if another instance is running or choose a different port.");
                setDoExit(true);
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
                ClientThread clientThread = new ClientThread(this, protocol, clientDatagramSocket, remoteHost, remotePort);
                loggerSysout.info("Accepted client thread ...");
                clientThreads.add(clientThread);
                clientThread.start();
                while (!isDoExit()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
            } catch (BindException e) {
                loggerSysout.error("Port " + localPort + " is already in use.");
                loggerSysout
                        .error("Cannot start server. Please check if another instance is running or choose a different port.");
                setDoExit(true);
            } catch (SocketException e) {
                // SocketTimeoutException ?
                // Ignore so we can check for termination request
                if (!isDoExit()) {
                    e.printStackTrace();
                }
            } finally {
            }
            while (!isDoExit()) {
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
        loggerSysout.info("Proxy thread terminating ...");
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
    public String getPropertyOrEnvironmentVariable(final String key) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            value = System.getenv(key);
        }
        return value;
    }

    public void setDoExit(boolean doExit) {
        this.doExit = doExit;
    }

    public boolean isDoExit() {
        return doExit;
    }

}
