package at.test.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class JavaProxy {

	private static boolean doExit = false;

	private static class ProxyThread extends Thread {

		private String remotehost;
		private int remoteport;
		private int localport;

		public ProxyThread(final String remotehost, final int remoteport, final int localport) {
			super();
			this.remotehost = remotehost;
			this.remoteport = remoteport;
			this.localport = localport;
		}

		@Override
		public void run() {
			try {
				JavaProxy.runServer(remotehost, remoteport, localport);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	public static void main(String[] args) throws IOException {
		try {
			String remotehost = "localhost";
			int remoteport = 80;
			int localport = 8888;
			// Process commandline
			if (args.length == 3) {
				remotehost = args[0];
				remoteport = Integer.valueOf(args[1]);
				localport = Integer.valueOf(args[2]);
			} else {
				System.out.println("Usage: JavaProxy remotehost remoteport localport");
				return;
			}
			// Printing a start-up message
			System.out.println("Starting JavaProxy for " + remotehost + ":" + remoteport + " on local port " + localport);
			// And start running the server
			ProxyThread proxyThread = new ProxyThread(remotehost, remoteport, localport);
			proxyThread.start();
			Thread.sleep(100);
			// Wait for quitting
			System.out.println("Press Enter to terminate JavaProxy ...");
			try {
				System.in.read();
			} catch (Exception e) {
			}
			System.out.println("JavaProxy waiting for proxy thread termination ...");
			JavaProxy.doExit = true;
			proxyThread.join();
			System.out.println("JavaProxy exiting...");
		} catch (Exception e) {
			System.err.println(e); // Prints the standard errors
		}
	}

	/**
	 * It will run a single-threaded proxy server on the provided local port.
	 */
	public static void runServer(final String remotehost, final int remoteport, final int localport) throws IOException {
		// Creating a ServerSocket to listen for connections
		try (ServerSocket serverSocket = new ServerSocket(localport);) {
			serverSocket.setSoTimeout(1000);
			final byte[] request = new byte[1024 * 64];
			byte[] reply = new byte[1024 * 64];
			System.out.println("JavaProxy waiting for connection ...");
			while (true) {
				Socket client = null, server = null;
				try {
					// It will wait for a connection on the local port
					client = serverSocket.accept();
					System.out.println("Accepted connection from host: " + client.getInetAddress().getHostName() + ", local port: "
							+ client.getLocalPort());
					final InputStream streamFromClient = client.getInputStream();
					final OutputStream streamToClient = client.getOutputStream();

					// Create a connection to the real server.
					// If we cannot connect to the server, send an error to the
					// client, disconnect, and continue waiting for connections.
					try {
						server = new Socket(remotehost, remoteport);
					} catch (IOException e) {
						e.printStackTrace();
						PrintWriter out = new PrintWriter(streamToClient);
						out.print("Proxy server cannot connect to " + remotehost + ":" + remoteport + ":\n" + e + "\n");
						out.flush();
						client.close();
						break;
					}

					// Get server streams.
					final InputStream streamFromServer = server.getInputStream();
					final OutputStream streamToServer = server.getOutputStream();

					// a thread to read the client's requests and pass them
					// to the server. A separate thread for asynchronous.
					Thread t = new Thread() {
						public void run() {
							int bytesRead;
							try {
								while ((bytesRead = streamFromClient.read(request)) != -1) {
									streamToServer.write(request, 0, bytesRead);
									streamToServer.flush();
								}
							} catch (IOException e) {
							}

							// the client closed the connection to us, so close our
							// connection to the server.
							try {
								streamToServer.close();
							} catch (IOException e) {
							}
						}
					};

					// Start the client-to-server request thread running
					t.start();
					// Read the server's responses
					// and pass them back to the client.
					int bytesRead;
					try {
						while ((bytesRead = streamFromServer.read(reply)) != -1) {
							streamToClient.write(reply, 0, bytesRead);
							streamToClient.flush();
						}
					} catch (IOException e) {
					}
					// The server closed its connection to us, so we close our
					// connection to our client.
					System.out.println("Closing connection to host: " + client.getInetAddress().getHostName() + ", local port: "
							+ client.getLocalPort());
					streamToClient.close();
					t.interrupt();
				} catch (SocketTimeoutException e) {
					// Socket timeout
					if (JavaProxy.doExit == true) {
						break;
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					try {
						if (server != null)
							server.close();
						if (client != null)
							client.close();
					} catch (IOException e) {
					}
				}
			}
			System.out.println("Proxy terminating ...");
		}
	}

}
