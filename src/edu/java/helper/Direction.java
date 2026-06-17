package edu.java.helper;

import edu.java.thread.ForwardThread;

/**
 * Direction of data flow for {@link ForwardThread}.
 */
public enum Direction {
    /** Data flowing from client to server. */
    CLIENT_TO_SERVER,
    /** Data flowing from server to client. */
    SERVER_TO_CLIENT
}
