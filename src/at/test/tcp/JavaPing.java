package at.test.tcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class JavaPing {

	// Create a trust manager that does not validate certificate chains
	static TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
		public java.security.cert.X509Certificate[] getAcceptedIssuers() {
			return null;
		}

		public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}

		public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}
	} };

	private static void print_https_cert(HttpsURLConnection con) {
		if (con != null) {
			try {
				System.out.println("Response Code : " + con.getResponseCode());
				System.out.println("Cipher Suite : " + con.getCipherSuite());
				System.out.println("\n");
				Certificate[] certs = con.getServerCertificates();
				for (Certificate cert : certs) {
					System.out.println("Cert Type : " + cert.getType());
					System.out.println("Cert Hash Code : " + cert.hashCode());
					System.out.println("Cert Public Key Algorithm : " + cert.getPublicKey().getAlgorithm());
					System.out.println("Cert Public Key Format : " + cert.getPublicKey().getFormat());
					System.out.println("\n");
				}
			} catch (SSLPeerUnverifiedException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private static void print_content(HttpsURLConnection con) {
		if (con != null) {
			try {
				System.out.println("****** Content of the URL ********");
				BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()));
				String input;
				while ((input = br.readLine()) != null) {
					System.out.println(input);
				}
				br.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public static void main(String[] args) throws Exception {
		// URL url = new URL("http://LSE3S22D:19080");
		// URL url = new URL("https://stportal.bmi.intra.gv.at:443/");
		URL url = new URL(args[0]);
		int port = url.getPort();
		int timeout = 5000;
		try (Socket socket = new Socket()) {
			InetAddress host = InetAddress.getByName(url.getHost());
			System.out.println("Connecting via Socket: " + host.getCanonicalHostName() + ":" + port + " (" + host.getHostAddress()
					+ ":" + port + ")");
			socket.connect(new InetSocketAddress(host, port), timeout);
			System.out.println("  Socket connection successful");
		} catch (IOException e) {
			System.out.println("  Failed to connect: " + e.getMessage());
		}

		// Install the all-trusting trust manager
		try {
			SSLContext sc = SSLContext.getInstance("SSL");
			sc.init(null, JavaPing.trustAllCerts, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		} catch (Exception e) {
		}

		try {
			URLConnection connection = url.openConnection();
			connection.setConnectTimeout(timeout);
			connection.setReadTimeout(timeout);
			System.out
					.println("Connecting via URLConnection: " + connection.getURL().getHost() + ":" + connection.getURL().getPort()
							+ " (" + connection.getURL().getHost() + ":" + connection.getURL().getPort() + ")");
			connection.connect();
			if (connection instanceof HttpsURLConnection) {
				HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
				System.out.println("  Got HTTPS connection");
				// dumpl all cert info
				print_https_cert(httpsConnection);
				// // dump all the content
				// print_content(httpsConnection);
			}
			System.out.println("  URLConnection successful");
		} catch (final MalformedURLException e) {
			System.out.println("  Failed to connect: " + e.getMessage());
		} catch (final IOException e) {
			System.out.println("  Service unavailable: " + e.getMessage());
		}
	}

}
