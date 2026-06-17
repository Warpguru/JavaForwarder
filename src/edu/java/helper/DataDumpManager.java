package edu.java.helper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import edu.java.JavaForwarder;
import edu.java.thread.ForwardThread;

/**
 * Data dump manager to store data forwarded by the {@link ForwardThread}.
 */
public class DataDumpManager {

    /**
     * Map (shared between threads) of chronological timestamps and formatted traffic between {@code inputSocket} and
     * {@code outputSocket}.
     */
    private static Map<Long, StringBuffer> mapTimestampDataDump = new TreeMap<Long, StringBuffer>();

    /** {@link JavaForwarder} instance running. */
    private JavaForwarder javaForwarder;
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
    /** Stored Content-Type header value for body formatting decision. */
    private String httpContentType = null;
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
     * @param javaForwarder instance running
     * @param threadId      of thread forwarding data from {@code inputSocket} to {@code outputSocket}
     * @param protocol      {@link Protocol} of underlying communication
     */
    public DataDumpManager(final JavaForwarder javaForwarder, final Long threadId, final Protocol protocol) {
        this(javaForwarder, threadId, protocol, null, null, null, null);
    }

    /**
     * {@link DataDumpManager} initialization.
     * 
     * @param javaForwarder instance running
     * @param threadId      of thread forwarding data from {@code inputSocket} to {@code outputSocket}
     * @param protocol      {@link Protocol} of underlying communication
     * @param inputAddress  to record input host
     * @param inputPort     to record input port
     * @param outputAddress to record output host
     * @param outputPort    to record output port
     */
    public DataDumpManager(final JavaForwarder javaForwarder, final Long threadId, final Protocol protocol,
            final String inputAddress, final String inputPort, final String outputAddress, final String outputPort) {
        super();
        this.javaForwarder = javaForwarder;
        this.threadId = threadId;
        this.protocol = protocol;
        this.inputAddress = inputAddress;
        this.inputPort = inputPort;
        this.outputAddress = outputAddress;
        this.outputPort = outputPort;
        try {
            Integer dumpWidth = Integer
                    .valueOf(javaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIALBE_DUMP_WIDTH));
            DUMP_WIDTH = (dumpWidth / 16) * 16;
        } catch (NumberFormatException e) {
            // Ignore
        }
    }

    public void record(final LocalDateTime localDateTimeForward, final DatagramPacket datagramPacket) {
        if (Protocol.UDP != protocol) {
            return;
        }
        if (javaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP) == null) {
            return;
        }
        record(localDateTimeForward, datagramPacket.getData(), datagramPacket.getLength());
    }

    /**
     * Record the bytes in {@code buffer} forwarded from {@code inputSocket} to {@code outputSocket} as a formatted data dump
     * for {@code TCP} traffic.
     * 
     * <p>
     * <b>Rationale:</b> When DUMP mode is enabled (not DUMP_HTTP), users need to see the raw TCP traffic in hex dump format for
     * protocol debugging. This method accumulates bytes incrementally as they arrive and formats them into readable hex+ASCII
     * rows.
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
     * <b>Difference from DUMP_HTTP:</b> This shows pure raw bytes without any parsing. Use this for non-HTTP protocols or when
     * you need to see exact wire format.
     * </p>
     * 
     * @param localDateTimeForwarding timestamp of forwarding
     * @param buffer                  referencing buffer to read from {@code inputSocket} to record data dump from
     * @param bytesRead               containing the number of bytes actually read from {@code inputSocket}
     */
    public void record(final LocalDateTime localDateTimeForwarding, final byte[] buffer, final int bytesRead) {
        if (javaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP) == null) {
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
                        localDateTimeForwarding.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")), inputAddress,
                        inputPort, outputAddress, outputPort)).append(System.lineSeparator());
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
     * <b>Rationale:</b> When DUMP_HTTP is enabled, users want to see both the raw wire format (for debugging protocol issues)
     * and the parsed, decoded HTTP content (for understanding the actual data). This method accumulates raw bytes and parses
     * headers on-the-fly.
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
        if (javaForwarder.getPropertyOrEnvironmentVariable(JavaForwarder.ENVIRONMENT_VARIABLE_DUMP_HTTP) == null) {
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

                sbBufferFormatted.append(String.format("Thread %06x: %s: %s:%s -> %s:%s [HTTP %s]", threadId,
                        localDateTimeForwarding.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")), inputAddress,
                        inputPort, outputAddress, outputPort, direction == Direction.CLIENT_TO_SERVER ? "REQUEST" : "RESPONSE"))
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
                    httpContentType = extractHeader(headers, "Content-Type");
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
     * <b>Rationale:</b> HTTP messages are accumulated across multiple {@link #recordHttp} calls as data arrives in chunks. When
     * a message is complete (detected by buffer not full), this method outputs the complete formatted HTTP dump and resets all
     * state variables.
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
     * <b>Processing Order:</b> Remove chunked encoding BEFORE decompression, since Transfer-Encoding and Content-Encoding are
     * applied in layers (chunk, then compress).
     * </p>
     * 
     * <p>
     * <b>Called by:</b> {@link ForwardThread#run()} when detecting end of HTTP message (bytesRead < BUFFER_SIZE).
     * </p>
     */
    public void resetHttpState() {
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
                appendIndentedBodyWithWidth(decodedBody, httpContentType);
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
     * <b>Thread Safety:</b> Uses synchronized block because both CLIENT_TO_SERVER and SERVER_TO_CLIENT threads share the same
     * mapTimestampDataDump static map.
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
    public void logDataDump() {
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
     * (Content-Encoding, Transfer-Encoding) to properly decode the body. Simple string split wouldn't handle case variations
     * like "content-encoding".
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
     * <b>Example:</b> If buffer contains "Content-Type: text\r\n\r\nHello", and headerEnd points to the first '\r', bodyStart
     * would be headerEnd+4, pointing to 'H'.
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
     * <b>Rationale:</b> HTTP servers often compress responses with gzip or deflate to reduce bandwidth. Without decompression,
     * DUMP_HTTP would show binary garbage instead of readable content. This method transparently decompresses based on
     * Content-Encoding header.
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
     * <b>Error Handling:</b> If decompression fails (corrupt data, wrong encoding), returns original bytes and logs error. This
     * allows partial debugging even with corrupted responses.
     * </p>
     * 
     * <p>
     * <b>Processing Order:</b> Must be called AFTER {@link #removeChunkEncoding()} since HTTP applies chunking as outer layer,
     * compression as inner layer.
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
     * <b>Rationale:</b> Decompression streams (GZIP, Deflate) don't provide length information upfront. We need to read until
     * EOF to get the complete decompressed content. This helper method handles the buffering and accumulation.
     * </p>
     * 
     * <p>
     * <b>Note:</b> Java 9+ has {@code InputStream.readAllBytes()}, but this implementation maintains compatibility with Java 8.
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
     * For DUMP_HTTP output, users want to see the clean body content without these control characters. The raw hex dump already
     * shows the complete wire format including chunks.
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
     * <b>Rationale:</b> In DUMP_HTTP mode, the body content should be indented to visually align with the HTTP headers (which
     * are also indented 2 spaces). This creates a clean, readable format similar to Postman/Bruno HTTP clients.
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
     * @param bodyBytes   the body bytes (already decompressed and de-chunked)
     * @param contentType optional {@code Mime} type
     */
    private void appendIndentedBodyWithWidth(final byte[] bodyBytes, final String contentType) {
        if (bodyBytes.length == 0) {
            return;
        }
        // Determine display format with highest precedence from Content-Type MIME type
        boolean isJson = isJsonContentType(contentType);
        boolean isText = isJson || isTextContentType(contentType);
        // If no known text MIME type, fall back to UTF-8 decode attempt
        if (!isText) {
            try {
                java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
                decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
                decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                decoder.decode(java.nio.ByteBuffer.wrap(bodyBytes));
                isText = true;
            } catch (Exception e) {
                isText = false;
            }
        }
        if (isJson) {
            // Pretty-print JSON with 2-space base indent
            String rawJson = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            String formattedJson = formatJson(rawJson, "  ");
            String[] lines = formattedJson.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i == lines.length - 1 && lines[i].isEmpty()) {
                    break;
                }
                sbBufferFormatted.append("  ").append(lines[i]);
                if (i < lines.length - 1) {
                    sbBufferFormatted.append(System.lineSeparator());
                }
            }
        } else if (isText) {
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
     * Pretty-print JSON string with indentation. Self-contained implementation using no external libraries. Handles strings
     * (including escaped quotes), arrays, objects. Falls back to original string if input is not valid JSON structure.
     *
     * @param json   the raw JSON string (may be compact or already formatted)
     * @param indent the base indent prefix to apply to each level
     * @return pretty-printed JSON string
     */
    private String formatJson(final String json, final String indent) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        char[] chars = json.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (escape) {
                sb.append(c);
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                sb.append(c);
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) {
                sb.append(c);
                continue;
            }

            // Structural characters - skip existing whitespace between tokens
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                continue;
            }

            switch (c) {
            case '{':
            case '[':
                sb.append(c);
                // Peek ahead: if next non-whitespace is closing bracket, keep on same line
                int next = i + 1;
                while (next < chars.length
                        && (chars[next] == ' ' || chars[next] == '\t' || chars[next] == '\r' || chars[next] == '\n'))
                    next++;
                if (next < chars.length && ((c == '{' && chars[next] == '}') || (c == '[' && chars[next] == ']'))) {
                    // Empty object/array - skip depth change
                } else {
                    depth++;
                    sb.append('\n');
                    for (int d = 0; d < depth; d++)
                        sb.append(indent);
                }
                break;
            case '}':
            case ']':
                // Check if previous non-whitespace output ends in { or [
                depth = Math.max(0, depth - 1);
                sb.append('\n');
                for (int d = 0; d < depth; d++)
                    sb.append(indent);
                sb.append(c);
                break;
            case ',':
                sb.append(c);
                sb.append('\n');
                for (int d = 0; d < depth; d++)
                    sb.append(indent);
                break;
            case ':':
                sb.append(": ");
                break;
            default:
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Check if {@code Content-Type} indicates {@code Json} format.
     * 
     * @param contentType the Content-Type header value, may be null
     * @return true if the content type is Json format
     */
    private boolean isJsonContentType(final String contentType) {
        if (contentType == null)
            return false;
        String ct = contentType.toLowerCase();
        return ct.contains("application/json") || ct.contains("application/ld+json") || ct.contains("application/graphql")
                || ct.contains("+json");
    }

    /**
     * Check if {@code Content-Type} indicates text content that can be displayed as a {@link String}.
     * 
     * @param contentType the Content-Type header value, may be null
     * @return true if the content type is a known text format
     */
    private boolean isTextContentType(final String contentType) {
        if (contentType == null)
            return false;
        String ct = contentType.toLowerCase();
        return ct.startsWith("text/") || ct.contains("application/json") || ct.contains("application/xml")
                || ct.contains("application/xhtml") || ct.contains("application/x-www-form-urlencoded")
                || ct.contains("application/javascript") || ct.contains("application/ld+json")
                || ct.contains("application/graphql") || ct.contains("+json") || ct.contains("+xml");
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
     * <b>Example:</b> In chunked body "d\r\nHello World!\r\n0\r\n\r\n", this finds the CRLF positions to separate chunk size
     * lines from chunk data.
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
