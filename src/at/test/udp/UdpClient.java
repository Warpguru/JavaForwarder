package at.test.udp;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class UdpClient {

	public static void main(String[] args) throws IOException {
		System.out.println("UDP Client Started. Sending user input to Server until \"QUIT\" is entered" + "...");
		DatagramPacket sendPacket;
		byte[] sendData;
		// Create a Datagram Socket
		DatagramSocket clientSocket = new DatagramSocket();
		// Set client timeout to be 1 second
		clientSocket.setSoTimeout(1000);
		Scanner input = new Scanner(System.in);
		while (true) {
			String cmd = input.nextLine();
			// If client types quit, close the socket and exit
			if (cmd.equals("QUIT")) {
				clientSocket.close();
				System.exit(1);
			}
			sendData = cmd.getBytes();
//			sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName("127.0.0.1"), 5001);
			sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName("127.0.0.1"), 80);
			clientSocket.send(sendPacket);
		}
	}
}
