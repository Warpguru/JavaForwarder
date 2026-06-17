package edu.java.helper;

import java.net.DatagramSocket;
import java.net.Socket;

/**
 * Supported {@code TCP/IP} protocols.
 */
public enum Protocol {
    /** Forward {@code TCP} data over {@link Socket}s. */
    TCP,
    /** Forward {@code UDP} data over {@link DatagramSocket}. */
    UDP
};
