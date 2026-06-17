package edu.java.helper;

/**
 * Detected format of the TCP traffic.
 */
public enum Format {
    /** Any non-HTTP TCP protocol. */
    OTHER,
    /** HTTP/1.x protocol detected. */
    HTTP
}
