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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 */
public class JavaForwarder {

	private static enum Protocol {
		/** Forward {@code TCP} data over {@link Socket}s. */
		TCP,
		/** Forward {@code UDP} data over {@link DatagramSocket}. */
		UDP
	};

	/** Mode of forwarding operation, {@code TCP} (default) or {@code UDP}. */
	private static final String ENVIRONMENT_VARIABLE_MODE = "MODE";
	/** Set to any value to activate recording of the forwarded data in a formatted data dump. */
	private static final String ENVIRONMENT_VARIABLE_DUMP = "DUMP";
	/** Set to a multiple of 16 to define non default width (number of bytes per rows) in formatted data dump. */
	private static final String ENVIRONMENT_VARIALBE_DUMP_WIDTH = "DUMP_WIDTH";

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
				// Start forwarding data between server and client
				forwardingActive = true;
				ForwardThread clientForward = new ForwardThread(this, protocol, clientSocket, serverSocket, clientInputStream,
						serverOutputStream);
				clientForward.start();
				ForwardThread serverForward = new ForwardThread(this, protocol, serverSocket, clientSocket, serverInputStream,
						clientOutputStream);
				serverForward.start();
				System.out.println("JavaForwarder " + protocol + " connection: "
						+ clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort() + " <--> "
						+ serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getPort() + " started");
			} else {
				try {
					System.out.println("JavaForwarder connecting to server ...");
					// Connect to the destination server
					serverDatagramSocket = new DatagramSocket();
					System.out.println("JavaForwarder connected to server");
				} catch (Exception e) {
					e.printStackTrace();
//					System.err.println("JavaForwarder failed to connect to initiate " + protocol + " connection: " + remoteHost
//							+ ":" + remotePort);
					connectionBroken();
					JavaForwarder.doExit = true;
					System.out.println("JavaForwarder failed to start, press Enter to terminate JavaForwarder ...");
					return;
				}
				// Start forwarding data between server and client
				forwardingActive = true;
				ForwardThread clientForward = new ForwardThread(this, protocol, clientDatagramSocket, serverDatagramSocket);
				clientForward.start();
//				ForwardThread serverForward = new ForwardThread(this, protocol, serverDatagramSocket, clientDatagramSocket);
//				serverForward.start();
//				System.out.println("JavaForwarder " + protocol + " connection: "
//						+ clientDatagramSocket.getInetAddress().getHostAddress() + ":" + clientDatagramSocket.getPort()
//						+ " <--> " + serverDatagramSocket.getInetAddress().getHostAddress() + ":"
//						+ serverDatagramSocket.getPort() + " started");
			}
		}

		/**
		 * Called by some of the forwarding threads to indicate that its socket connection is broken and both client and server
		 * sockets should be closed. Closing the client and server sockets causes all threads blocked on reading or writing to
		 * these sockets to get an exception and to finish their execution.
		 */
		public synchronized void connectionBroken() {
			if (serverSocket != null) {
				try {
					serverSocket.close();
				} catch (Exception e) {
				}
			}
			if (clientSocket != null) {
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
		final private Socket inputSocket;
		final private Socket outputSocket;
		final private DatagramSocket inputDatagramSocket;
		final private DatagramSocket outputDatagramSocket;
		final private InputStream inputStream;
		final private OutputStream outputStream;

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
		 */
		public ForwardThread(final ClientThread clientThread, final Protocol protocol, final Socket inputSocket,
				final Socket outputSocket, final InputStream inputStream, final OutputStream outputStream) {
			super();
			this.clientThread = clientThread;
			this.protocol = protocol;
			this.inputSocket = inputSocket;
			this.outputSocket = outputSocket;
			this.inputDatagramSocket = null;
			this.outputDatagramSocket = null;
			this.inputStream = inputStream;
			this.outputStream = outputStream;
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
				try {
					while (!JavaForwarder.doExit) {
						int bytesRead = inputStream.read(buffer);
						// Record data read
						if (localDateTimeForward == null) {
							localDateTimeForward = LocalDateTime.now();
						}
						dataDumpManager.record(localDateTimeForward, buffer, bytesRead);
						// If end of stream is reached --> exit
						if (bytesRead == -1) {
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

		/** Map of timestamps and formatted bytes forwarded from {@code inputSocket} to {@code outputSocket}. */
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
				Integer dumpWidth = Integer.valueOf(System.getProperty(JavaForwarder.ENVIRONMENT_VARIALBE_DUMP_WIDTH));
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
			if (System.getProperty(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP) == null) {
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
			// Dump data in hex and ascii in DUMP_WIDTH bytes blocks
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

	}

	/**
	 * Main entry point.
	 * 
	 * @param args to use and validate
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		System.out.println("JavaForwarder v1.10 (C) by Roman.Stangl@gmx.net");
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
				System.out.println("    DUMP ... any value to record data forwarded a formatted data dump");
				System.out.println("    DUMP_WIDTH ... multiple of 16 defining number of bytes per row of formatted data dump");
				System.out.println("");
				return;
			}
			// Check IP we want to forward
			Protocol protocol = Protocol.TCP;
			if (Protocol.UDP.name().equalsIgnoreCase(System.getProperty(JavaForwarder.ENVIRONMENT_VARIABLE_MODE))) {
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
//				while (true) {
				ClientThread clientThread = new ClientThread(protocol, clientDatagramSocket, remoteHost, remotePort);
				System.out.println("JavaForwarder accepted client thread ...");
				clientThreads.add(clientThread);
				clientThread.start();
//				}
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

}
