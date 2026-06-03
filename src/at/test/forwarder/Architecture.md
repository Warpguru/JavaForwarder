# JavaForwarder Architecture Document

## Overview

JavaForwarder is a TCP reverse proxy that transparently forwards traffic between a client and a server while logging the data exchanged. It supports HTTP Keep-Alive connections and properly handles half-closed TCP sockets.

## Table of Contents

1. [High-Level Architecture](#high-level-architecture)
2. [Class Structure](#class-structure)
3. [Thread Model](#thread-model)
4. [Connection Lifecycle](#connection-lifecycle)
5. [Half-Close Socket Handling](#half-close-socket-handling)
6. [Data Flow and Chunking](#data-flow-and-chunking)
7. [Data Dump Management](#data-dump-management)
8. [Configuration](#configuration)

---

## High-Level Architecture

```mermaid
graph TB
    subgraph "Client Side"
        Browser[Browser/Client]
    end
    
    subgraph "JavaForwarder Proxy"
        PS[ProxyThread<br/>ServerSocket on localPort]
        CT[ClientThread<br/>Connection Manager]
        FT1[ForwardThread<br/>CLIENT_TO_SERVER]
        FT2[ForwardThread<br/>SERVER_TO_CLIENT]
        DDM[DataDumpManager<br/>Traffic Logger]
    end
    
    subgraph "Server Side"
        Server[Remote Server]
    end
    
    Browser -->|"Connect to<br/>localhost:localPort"| PS
    PS -->|"Accept & Create"| CT
    CT -->|"Create"| FT1
    CT -->|"Create"| FT2
    CT -->|"Connect to<br/>remoteHost:remotePort"| Server
    
    FT1 -->|"Read from Client<br/>Write to Server"| Server
    FT2 -->|"Read from Server<br/>Write to Client"| Browser
    
    FT1 -.->|"Log Traffic"| DDM
    FT2 -.->|"Log Traffic"| DDM
```

---

## Class Structure

```mermaid
classDiagram
    class JavaForwarder {
        -static boolean doExit
        +static void main(String[] args)
        +static void runServer(Protocol, String, int, int)
        -static String getPropertyOrEnvironmentVariable(String)
    }
    
    class Protocol {
        <<enumeration>>
        TCP
        UDP
    }
    
    class Direction {
        <<enumeration>>
        CLIENT_TO_SERVER
        SERVER_TO_CLIENT
    }
    
    class ClientThread {
        -Protocol protocol
        -Socket clientSocket
        -Socket serverSocket
        -String remoteHost
        -int remotePort
        -boolean forwardingActive
        -Map~Direction,Boolean~ directionActive
        +void run()
        +synchronized void forwardingDirectionComplete(Direction)
        +synchronized void connectionBroken()
    }
    
    class ForwardThread {
        -static int BUFFER_SIZE
        -ClientThread clientThread
        -Protocol protocol
        -Socket inputSocket
        -Socket outputSocket
        -InputStream inputStream
        -OutputStream outputStream
        -Direction direction
        +void run()
    }
    
    class DataDumpManager {
        -static Map~Long,StringBuffer~ mapTimestampDataDump
        -Long threadId
        -Socket inputSocket
        -Socket outputSocket
        -int DUMP_WIDTH
        +void record(LocalDateTime, byte[], int)
        +void logDataDump()
    }
    
    class ProxyThread {
        -Protocol protocol
        -String remoteHost
        -int remotePort
        -int localPort
        +void run()
    }
    
    JavaForwarder ..> Protocol
    JavaForwarder ..> Direction
    JavaForwarder *-- ProxyThread
    ProxyThread ..> ClientThread
    ClientThread *-- ForwardThread
    ForwardThread ..> DataDumpManager
    ClientThread ..> Direction
    ForwardThread ..> Direction
```

---

## Thread Model

JavaForwarder uses a multi-threaded architecture to handle bidirectional data forwarding:

```mermaid
graph TD
    subgraph "Main Thread"
        M[main<br/>Wait for Enter key]
    end
    
    subgraph "ProxyThread"
        P[ProxyThread<br/>Accept connections on localPort]
    end
    
    subgraph "Per Connection"
        CT[ClientThread<br/>Connection Manager]
        FT1[ForwardThread<br/>CLIENT_TO_SERVER<br/>Thread 0x00000d]
        FT2[ForwardThread<br/>SERVER_TO_CLIENT<br/>Thread 0x00000e]
    end
    
    M -->|"Start"| P
    P -->|"Accept & Create"| CT
    CT -->|"Start"| FT1
    CT -->|"Start"| FT2
    
    FT1 -.->|"forwardingDirectionComplete<br/>connectionBroken"| CT
    FT2 -.->|"forwardingDirectionComplete<br/>connectionBroken"| CT
```

### Thread Responsibilities

| Thread | Responsibility |
|--------|---------------|
| **Main Thread** | Starts ProxyThread, waits for user input to terminate |
| **ProxyThread** | Listens on `localPort`, accepts connections, creates ClientThreads |
| **ClientThread** | Establishes server connection, manages ForwardThreads, handles cleanup |
| **ForwardThread (C→S)** | Reads from client, writes to server, logs traffic |
| **ForwardThread (S→C)** | Reads from server, writes to client, logs traffic |

---

## Connection Lifecycle

```mermaid
sequenceDiagram
    participant B as Browser
    participant P as ProxyThread
    participant CT as ClientThread
    participant FTC as ForwardThread<br/>CLIENT_TO_SERVER
    participant FTS as ForwardThread<br/>SERVER_TO_CLIENT
    participant S as Server

    Note over P: Listening on localPort
    
    B->>P: TCP Connect
    P->>CT: Create & Start
    CT->>S: TCP Connect
    CT->>FTC: Create & Start
    CT->>FTS: Create & Start
    
    Note over CT: forwardingActive = true<br/>directionActive[C→S] = true<br/>directionActive[S→C] = true
    
    rect rgb(200, 230, 200)
        Note over B,S: Normal Operation
        B->>FTC: HTTP Request
        FTC->>S: Forward Request
        S->>FTS: HTTP Response
        FTS->>B: Forward Response
    end
    
    rect rgb(255, 200, 200)
        Note over B,S: Connection Teardown
        B->>FTC: EOF (close)
        FTC->>CT: forwardingDirectionComplete(C→S)
        CT->>CT: directionActive[C→S] = false
        CT->>S: Close server socket
        Note over FTS: read() throws IOException
        FTS->>CT: forwardingDirectionComplete(S→C)
        CT->>CT: directionActive[S→C] = false
        CT->>CT: connectionBroken()
        CT->>CT: Close all sockets
    end
```

---

## Half-Close Socket Handling

TCP supports "half-close" where one direction of the connection can be closed while the other remains open. This is crucial for HTTP Keep-Alive where:

1. The client sends a request, then waits
2. The server sends a response, may close its side
3. The client may send another request

### The Challenge

```mermaid
graph TB
    subgraph "Problem: Without Half-Close Handling"
        A1[Server sends response] --> A2[Server closes socket]
        A2 --> A3[SERVER_TO_CLIENT detects EOF]
        A3 --> A4[Immediately close ALL sockets]
        A4 --> A5[CLIENT_TO_SERVER blocked<br/>on read - never logs data!]
        style A5 fill:#ff6666
    end
```

### The Solution

```mermaid
graph TB
    subgraph "Solution: With Half-Close Handling"
        B1[Server sends response] --> B2[Server closes socket]
        B2 --> B3[SERVER_TO_CLIENT detects EOF]
        B3 --> B4[forwardingDirectionComplete<br/>S→C]
        B4 --> B5{Other direction<br/>still active?}
        B5 -->|Yes| B6[Close client socket<br/>to unblock C→S]
        B6 --> B7[CLIENT_TO_SERVER<br/>unblocks and logs data]
        B7 --> B8[forwardingDirectionComplete<br/>C→S]
        B8 --> B9[connectionBroken<br/>Both done - cleanup]
        style B7 fill:#66ff66
    end
```

### Direction State Machine

```mermaid
stateDiagram-v2
    [*] --> BothActive: Connection Established
    
    BothActive: directionActive[C→S] = true<br/>directionActive[S→C] = true
    
    BothActive --> ClientClosed: CLIENT_TO_SERVER EOF
    BothActive --> ServerClosed: SERVER_TO_CLIENT EOF
    
    ClientClosed: directionActive[C→S] = false<br/>directionActive[S→C] = true<br/>Close server socket
    
    ServerClosed: directionActive[C→S] = true<br/>directionActive[S→C] = false<br/>Close client socket
    
    ClientClosed --> BothClosed: SERVER_TO_CLIENT EOF
    ServerClosed --> BothClosed: CLIENT_TO_SERVER EOF
    
    BothClosed: directionActive[C→S] = false<br/>directionActive[S→C] = false
    
    BothClosed --> [*]: Cleanup & Log
```

### forwardingDirectionComplete() Logic

```mermaid
flowchart TD
    A[forwardingDirectionComplete<br/>called with direction] --> B[Set directionActive<br/>direction = false]
    
    B --> C{direction ==<br/>SERVER_TO_CLIENT?}
    
    C -->|Yes| D{clientSocket<br/>not closed?}
    D -->|Yes| E[Close clientSocket]
    D -->|No| G
    E --> G
    
    C -->|No| F{serverSocket<br/>not closed?}
    F -->|Yes| H[Close serverSocket]
    F -->|No| G
    H --> G
    
    G[Return - let connectionBroken<br/>handle final cleanup]
```

### connectionBroken() Logic

```mermaid
flowchart TD
    A[connectionBroken<br/>called] --> B{Any direction<br/>still active?}
    
    B -->|Yes| C[Log: One direction closed,<br/>waiting for other]
    C --> D[Return early]
    
    B -->|No| E[Log: Both directions closed]
    E --> F[Close serverSocket<br/>if not closed]
    F --> G[Close clientSocket<br/>if not closed]
    G --> H[Set forwardingActive = false]
    H --> I[Log connection stopped]
```

---

## Data Flow and Chunking

### Buffer-Based Data Forwarding

Each ForwardThread reads data in chunks of up to `BUFFER_SIZE` (8192 bytes):

```mermaid
sequenceDiagram
    participant IS as InputStream
    participant FT as ForwardThread
    participant DDM as DataDumpManager
    participant OS as OutputStream

    loop while not EOF and not doExit
        FT->>IS: read(buffer)
        IS-->>FT: bytesRead
        
        alt bytesRead == -1
            Note over FT: EOF detected, exit loop
        else bytesRead > 0
            FT->>DDM: record(timestamp, buffer, bytesRead)
            FT->>OS: write(buffer, 0, bytesRead)
            FT->>OS: flush()
        end
        
        alt bytesRead < BUFFER_SIZE
            Note over FT: Chunk complete,<br/>reset timestamp
        else bytesRead == BUFFER_SIZE
            Note over FT: More data expected,<br/>keep same timestamp
        end
    end
```

### Timestamp Logic for Chunking

The timestamp logic groups consecutive buffer reads under the same timestamp when they likely belong to the same logical data unit:

```mermaid
graph TD
    A[localDateTimeForward<br/>is null?] -->|Yes| B[Set to LocalDateTime.now]
    A -->|No| C[Keep existing timestamp]
    B --> D[Record data with timestamp]
    C --> D
    D --> E{bytesRead < BUFFER_SIZE?}
    E -->|Yes| F[Reset timestamp to null<br/>Next read gets new timestamp]
    E -->|No| G[Keep timestamp<br/>Continuation of same data]
```

**Example:**
- Large HTTP response (10KB):
  - Read 1: 8192 bytes @ timestamp T1 → keep timestamp
  - Read 2: 1808 bytes @ timestamp T1 → reset timestamp
- Small HTTP request (500 bytes):
  - Read: 500 bytes @ timestamp T2 → reset timestamp

---

## Data Dump Management

### Static Shared Map

The `DataDumpManager` uses a static map shared across all ForwardThreads to ensure chronological ordering:

```mermaid
graph LR
    subgraph "Thread 0x00000d<br/>CLIENT_TO_SERVER"
        DDM1[DataDumpManager]
    end
    
    subgraph "Thread 0x00000e<br/>SERVER_TO_CLIENT"
        DDM2[DataDumpManager]
    end
    
    subgraph "Static Shared Storage"
        MAP[(TreeMap<br/>timestamp → StringBuffer)]
    end
    
    DDM1 -->|"record()"| MAP
    DDM2 -->|"record()"| MAP
    DDM1 -->|"logDataDump()"| MAP
    DDM2 -->|"logDataDump()"| MAP
```

### Synchronized Access

```mermaid
sequenceDiagram
    participant FT1 as ForwardThread 1
    participant FT2 as ForwardThread 2
    participant MAP as mapTimestampDataDump

    FT1->>MAP: synchronized(map)<br/>record data @ T1
    Note over MAP: Thread 1 holds lock
    FT2--xMAP: Blocked
    FT1->>MAP: Release lock
    
    FT2->>MAP: synchronized(map)<br/>record data @ T2
    Note over MAP: Thread 2 holds lock
    FT2->>MAP: Release lock
    
    Note over MAP: Map now contains:<br/>T1: Request data<br/>T2: Response data
    
    FT1->>MAP: synchronized(map)<br/>logDataDump()
    Note over MAP: Iterate in timestamp order<br/>Print all entries<br/>Clear map
```

### Output Format

```
Thread 00000d: 2026-06-03 18:20:30.409: 127.0.0.1:54247 -> 127.0.0.1:3000
  Offset 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 0123456789ABCDEF
  -----------------------------------------------------------------------
  000000 47 45 54 20 2F 20 48 54 54 50 2F 31 2E 31 0D 0A GET / HTTP/1.1  
```

---

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `MODE` | Protocol: `TCP` or `UDP` | `TCP` |
| `DUMP` | Enable traffic logging (any value) | disabled |
| `DUMP_WIDTH` | Bytes per row in hex dump (multiple of 16) | 16 |
| `DUMP_HTTP` | HTTP-specific logging (future) | disabled |

### Command Line

```bash
java JavaForwarder <remoteHost> <remotePort> <localPort>
```

**Example:**
```bash
# Forward traffic from localhost:80 to localhost:3000
# Set DUMP environment variable to enable logging
export DUMP=1
java JavaForwarder localhost 3000 80
```

---

## HTTP Keep-Alive Scenario

The following diagram shows a complete HTTP Keep-Alive scenario with two requests:

```mermaid
sequenceDiagram
    participant B as Browser
    participant C2S as CLIENT_TO_SERVER
    participant S2C as SERVER_TO_CLIENT
    participant S as Server

    Note over B,S: Connection established

    rect rgb(200, 230, 200)
        Note over B,S: Request 1: GET /
        B->>C2S: GET / HTTP/1.1<br/>Connection: keep-alive
        C2S->>S: Forward request
        S->>S2C: HTTP/1.1 200 OK<br/>Transfer-Encoding: chunked<br/>d\r\nHello World!\n\r\n0\r\n\r\n
        S2C->>B: Forward response
    end

    rect rgb(200, 200, 230)
        Note over B,S: Request 2: GET /favicon.ico
        B->>C2S: GET /favicon.ico HTTP/1.1<br/>Connection: keep-alive
        C2S->>S: Forward request
        S->>S2C: HTTP/1.1 200 OK<br/>Transfer-Encoding: chunked<br/>d\r\nHello World!\n\r\n0\r\n\r\n
        S2C->>B: Forward response
    end

    rect rgb(255, 200, 200)
        Note over B,S: Connection Teardown
        B->>C2S: EOF (close)
        Note over C2S: Logs both requests
        C2S->>C2S: forwardingDirectionComplete(C→S)
        Note over C2S: Closes server socket
        S2C->>S2C: IOException (socket closed)
        Note over S2C: Logs both responses
        S2C->>S2C: forwardingDirectionComplete(S→C)
        Note over S2C: connectionBroken()<br/>Both done, cleanup
    end
```

---

## Typical Log Output

```
JavaForwarder v1.13 (C) by Roman.Stangl@gmx.net
JavaForwarder starting proxy thread, forwarding TCP connection: localhost:3000 on local port 80
JavaForwarder proxy thread waiting for client connection(s) ...
JavaForwarder waiting for client connection(s), press Enter to terminate JavaForwarder ...
JavaForwarder accepted client thread ...
JavaForwarder connecting to server ...
JavaForwarder connected to server
JavaForwarder TCP connection: 127.0.0.1:54247 <--> 127.0.0.1:3000 started

Thread 00000d: 2026-06-03 18:20:30.409: 127.0.0.1:54247 -> 127.0.0.1:3000
  [Request 1: GET / HTTP/1.1 ...]

Thread 00000e: 2026-06-03 18:20:30.443: 127.0.0.1:3000 -> 127.0.0.1:54247
  [Response 1: HTTP/1.1 200 OK ...]

Thread 00000d: 2026-06-03 18:20:30.489: 127.0.0.1:54247 -> 127.0.0.1:3000
  [Request 2: GET /favicon.ico HTTP/1.1 ...]

Thread 00000e: 2026-06-03 18:20:30.493: 127.0.0.1:3000 -> 127.0.0.1:54247
  [Response 2: HTTP/1.1 200 OK ...]

JavaForwarder: CLIENT_TO_SERVER forwarding completed
JavaForwarder: Closed server socket to unblock SERVER_TO_CLIENT
JavaForwarder: SERVER_TO_CLIENT forwarding completed
JavaForwarder: Both directions closed, terminating connection
JavaForwarder TCP connection: 127.0.0.1:54247 <--> 127.0.0.1:3000 stopped
```

---

## Future Enhancements

1. **HTTP-Aware Logging** (`DUMP_HTTP` environment variable)
   - Parse HTTP headers and body separately
   - Display in Postman/Bruno-like format
   - Handle chunked transfer encoding display

2. **UDP Support**
   - Currently partially implemented
   - Needs bidirectional forwarding completion

3. **Connection Pooling**
   - Reuse server connections for multiple client connections
   - Reduce connection overhead

4. **TLS/SSL Support**
   - Intercept HTTPS traffic (with proper certificates)
   - Display decrypted content

---

## License

This program is freeware based on an example from the book "Internet programming with Java" by Svetlin Nakov.

For more information: http://www.nakov.com/books/inetjava/
