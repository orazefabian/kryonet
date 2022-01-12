/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryonet;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.IntMap;
import com.esotericsoftware.kryonet.FrameworkMessage.DiscoverHost;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterTCP;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterUDP;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.esotericsoftware.minlog.Log.*;

/**
 * Manages TCP and optionally UDP connections from many {@link Client Clients}.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public class Server implements EndPoint {
    private final Serialization serialization;
    private final int writeBufferSize, objectBufferSize;
    private final Selector selector;
    private final IntMap<Connection> pendingConnections = new IntMap<>();
    private final Object updateLock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private int emptySelects;
    private ServerSocketChannel serverChannel;
    private UdpConnection udp;
    private ArrayList<Connection> connections;

    private final Listener dispatchListener = new Listener() {
        public void connected(Connection connection) {
            for (Listener listener : listeners) {
                listener.connected(connection);
            }
        }

        public void disconnected(Connection connection) {
            removeConnection(connection);

            for (Listener listener : listeners) {
                listener.disconnected(connection);
            }
        }

        public void received(Connection connection, Object object) {
            for (Listener listener : listeners) {
                listener.received(connection, object);
            }
        }

        public void idle(Connection connection) {
            for (Listener listener : listeners) {
                listener.idle(connection);
            }
        }
    };
    private int nextConnectionID = 1;
    private volatile boolean shutdown;
    private Thread updateThread;
    private ServerDiscoveryHandler discoveryHandler;

    /**
     * Creates a Server with a write buffer size of 16384 and an object buffer size of 2048.
     */
    public Server() {
        this(16384, 2048);
    }

    /**
     * @param writeBufferSize  One buffer of this size is allocated for each connected client. Objects are serialized to the write
     *                         buffer where the bytes are queued until they can be written to the TCP socket.
     *                         <p>
     *                         Normally the socket is writable and the bytes are written immediately. If the socket cannot be written to and
     *                         enough serialized objects are queued to overflow the buffer, then the connection will be closed.
     *                         <p>
     *                         The write buffer should be sized at least as large as the largest object that will be sent, plus some head room to
     *                         allow for some serialized objects to be queued in case the buffer is temporarily not writable. The amount of head
     *                         room needed is dependent upon the size of objects being sent and how often they are sent.
     * @param objectBufferSize One (using only TCP) or three (using both TCP and UDP) buffers of this size are allocated. These
     *                         buffers are used to hold the bytes for a single object graph until it can be sent over the network or
     *                         deserialized.
     *                         <p>
     *                         The object buffers should be sized at least as large as the largest object that will be sent or received.
     */
    public Server(int writeBufferSize, int objectBufferSize) {
        this(writeBufferSize, objectBufferSize, new KryoSerialization());
    }

    public Server(int writeBufferSize, int objectBufferSize, Serialization serialization) {
        this.writeBufferSize = writeBufferSize;
        this.objectBufferSize = objectBufferSize;
        this.serialization = serialization;
        this.discoveryHandler = ServerDiscoveryHandler.DEFAULT;
        this.connections = new ArrayList<>();
        try {
            selector = Selector.open();
        } catch (IOException ex) {
            throw new RuntimeException("Error opening selector.", ex);
        }
    }

    public void setDiscoveryHandler(ServerDiscoveryHandler newDiscoveryHandler) {
        discoveryHandler = newDiscoveryHandler;
    }

    public Serialization getSerialization() {
        return serialization;
    }

    public Kryo getKryo() {
        return serialization instanceof KryoSerialization ? ((KryoSerialization) serialization).getKryo() : null;
    }

    /**
     * Opens a TCP only server.
     *
     * @throws IOException if the server could not be opened.
     */
    public void bind(int tcpPort) throws IOException {
        bind(new InetSocketAddress(tcpPort), null);
    }

    /**
     * Opens a TCP and UDP server.
     *
     * @throws IOException if the server could not be opened.
     */
    public void bind(int tcpPort, int udpPort) throws IOException {
        bind(new InetSocketAddress(tcpPort), new InetSocketAddress(udpPort));
    }

    /**
     * @param udpPort May be null.
     */
    public void bind(InetSocketAddress tcpPort, InetSocketAddress udpPort) throws IOException {
        close();
        synchronized (updateLock) {
            selector.wakeup();
            try {
                serverChannel = selector.provider().openServerSocketChannel();
                serverChannel.socket().bind(tcpPort);
                serverChannel.configureBlocking(false);
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);
                if (DEBUG) debug("kryonet", "Accepting connections on port: " + tcpPort + "/TCP");

                if (udpPort != null) {
                    udp = new UdpConnection(serialization, objectBufferSize);
                    udp.bind(selector, udpPort);
                    if (DEBUG) debug("kryonet", "Accepting connections on port: " + udpPort + "/UDP");
                }
            } catch (IOException ex) {
                close();
                throw ex;
            }
        }
        if (INFO) info("kryonet", "Server opened.");
    }

    public int getTcpPort() {
        return serverChannel.socket().getLocalPort();
    }

    public int getUdpPort() {
        return udp.datagramChannel.socket().getLocalPort();
    }

    /**
     * Accepts any new connections and reads or writes any pending data for the current connections.
     *
     * @param timeout Wait for up to the specified milliseconds for a connection to be ready to process. May be zero to return
     *                immediately if there are no connections to process.
     */
    public void update(int timeout) throws IOException {
        updateThread = Thread.currentThread();
        synchronized (updateLock) { // Blocks to avoid a select while the selector is used to bind the server connection.
        }
        long startTime = System.currentTimeMillis();
        int select = getNumberOfKeysFromSelector(timeout);
        if (select == 0) {
            emptySelects++;
            checkEmptySelects(startTime);
        } else {
            performDataUpdateOnSelector();
        }
        long endTime = System.currentTimeMillis();
        for (Connection connection : connections) {
            checkConnectionState(endTime, connection);
        }
    }

    private int getNumberOfKeysFromSelector(int timeout) throws IOException {
        int select;
        if (timeout > 0) {
            select = selector.select(timeout);
        } else {
            select = selector.selectNow();
        }
        return select;
    }

    private void checkConnectionState(long time, Connection connection) {
        if (connection.tcp.isTimedOut(time)) {
            if (DEBUG) debug("kryonet", connection + " timed out.");
            connection.close();
        } else {
            if (connection.tcp.needsKeepAlive(time)) connection.sendTCP(FrameworkMessage.keepAlive);
        }
        if (connection.isIdle()) connection.notifyIdle();
    }


    private void performDataUpdateOnSelector() throws IOException {
        emptySelects = 0;
        Set<SelectionKey> keys = selector.selectedKeys();
        synchronized (keys) {
            for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext(); ) {
                processUpdateOnSelectionKey(iter);
            }
        }
    }

    private void processUpdateOnSelectionKey(Iterator<SelectionKey> iter) throws IOException {
        keepAlive();
        SelectionKey selectionKey = iter.next();
        iter.remove();
        Connection fromConnection = (Connection) selectionKey.attachment();
        processOperationFromSelectionKeyOntoConnection(selectionKey, fromConnection);
    }

    private void processOperationFromSelectionKeyOntoConnection(SelectionKey selectionKey, Connection fromConnection) throws IOException {
        try {
            int operationsSet = selectionKey.readyOps();
            if (fromConnection != null) { // Must be a TCP read or write operation.
                handleConnectionWithOpsSet(fromConnection, operationsSet);
            } else if (checkIfServerChannelIsInAcceptOperation(operationsSet)) {
                checkServerChannelAndAcceptOps();
            } else if (udp == null) { // Must be a UDP read operation.
                selectionKey.channel().close();
            } else {
                fromConnection = updateConnectionBasedOnUDPAddress(fromConnection);
            }
        } catch (CancelledKeyException ex) {
            handleCancelledKeyExceptionFromConnection(selectionKey, fromConnection);
        }
    }

    private Connection updateConnectionBasedOnUDPAddress(Connection fromConnection) {
        InetSocketAddress fromAddress = null;
        Object object;
        try {
            fromAddress = udp.readFromAddress();
            if (fromAddress != null) {
                fromConnection = getConnectionCorrespondingToAddress(fromConnection, fromAddress);
                object = udp.readObject(fromConnection);
                checkForObjectType(fromConnection, fromAddress, object);
            }
        } catch (IOException ex) {
            if (WARN) warn("kryonet", "Error reading UDP data.", ex);
        } catch (KryoNetException ex) {
            handleKryoNetExceptionFromConnection(fromConnection, fromAddress, ex);
        }
        if (DEBUG) debug("kryonet", "Ignoring UDP from unregistered address: " + fromAddress);
        return fromConnection;
    }

    private void checkServerChannelAndAcceptOps() {
        if (serverChannel != null) {
            acceptSocketChannelOperation();
        }
    }

    private void handleConnectionWithOpsSet(Connection fromConnection, int operationsSet) {
        if (udp != null && fromConnection.udpRemoteAddress == null) {
            fromConnection.close();
        } else {
            checkIfConnectionIsInReadOperation(fromConnection, operationsSet);
            checkIfConnectionIsInWriteOperation(fromConnection, operationsSet);
        }
    }

    private boolean checkIfServerChannelIsInAcceptOperation(int operationsSet) {
        return (operationsSet & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT;
    }

    private void acceptSocketChannelOperation() {
        try {
            SocketChannel socketChannel = serverChannel.accept();
            if (socketChannel != null) acceptOperation(socketChannel);
        } catch (IOException ex) {
            if (DEBUG) debug("kryonet", "Unable to accept new connection.", ex);
        }
    }

    private Connection getConnectionCorrespondingToAddress(Connection fromConnection, InetSocketAddress fromAddress) {
        for (Connection connection : connections) {
            if (fromAddress.equals(connection.udpRemoteAddress)) {
                fromConnection = connection;
                break;
            }
        }
        return fromConnection;
    }

    private void handleCancelledKeyExceptionFromConnection(SelectionKey selectionKey, Connection fromConnection) throws IOException {
        if (fromConnection != null)
            fromConnection.close();
        else
            selectionKey.channel().close();
    }

    private void handleKryoNetExceptionFromConnection(Connection fromConnection, InetSocketAddress fromAddress, KryoNetException ex) {
        if (WARN) {
            if (fromConnection != null) {
                if (ERROR)
                    error("kryonet", "Error reading UDP from connection: " + fromConnection, ex);
            } else
                warn("kryonet", "Error reading UDP from unregistered address: " + fromAddress, ex);
        }
    }

    private void checkForObjectType(Connection fromConnection, InetSocketAddress fromAddress, Object object) {
        if (object instanceof FrameworkMessage) {
            if (object instanceof RegisterUDP) {
                // Store the fromAddress on the connection and reply over TCP with a RegisterUDP to indicate success.
                int fromConnectionID = ((RegisterUDP) object).connectionID;
                Connection connection = pendingConnections.remove(fromConnectionID);
                if (connection != null) {
                    if (connection.udpRemoteAddress != null) return;
                    connection.udpRemoteAddress = fromAddress;
                    addConnection(connection);
                    connection.sendTCP(new RegisterUDP());
                    if (DEBUG) debug("kryonet",
                            "Port " + udp.datagramChannel.socket().getLocalPort() + "/UDP connected to: " + fromAddress);
                    connection.notifyConnected();
                    return;
                }
                if (DEBUG)
                    debug("kryonet", "Ignoring incoming RegisterUDP with invalid connection ID: " + fromConnectionID);
                return;
            }
            if (object instanceof DiscoverHost) {
                try {
                    boolean responseSent = discoveryHandler.onDiscoverHost(udp.datagramChannel, fromAddress,
                            serialization);
                    if (DEBUG && responseSent)
                        debug("kryonet", "Responded to host discovery from: " + fromAddress);
                } catch (IOException ex) {
                    if (WARN)
                        warn("kryonet", "Error replying to host discovery from: " + fromAddress, ex);
                }
                return;
            }
        }

        if (fromConnection != null) {
            if (DEBUG) {
                String objectString = object == null ? "null" : object.getClass().getSimpleName();
                if (object instanceof FrameworkMessage) {
                    if (TRACE) trace("kryonet", fromConnection + " received UDP: " + objectString);
                } else
                    debug("kryonet", fromConnection + " received UDP: " + objectString);
            }
            fromConnection.notifyReceived(object);
        }
    }

    private void checkIfConnectionIsInWriteOperation(Connection fromConnection, int operationsSet) {
        if ((operationsSet & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
            try {
                fromConnection.tcp.writeOperation();
            } catch (IOException ex) {
                handleIOExceptionFromConnection(fromConnection, ex, "Unable to write TCP to connection: ");
            }
        }
    }

    private void checkIfConnectionIsInReadOperation(Connection fromConnection, int operationsSet) {
        if ((operationsSet & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
            try {
                readObjectsFromTcpConnection(fromConnection);
            } catch (IOException ex) {
                handleIOExceptionFromConnection(fromConnection, ex, "Unable to read TCP from: ");
            } catch (KryoNetException ex) {
                if (ERROR)
                    error("kryonet", "Error reading TCP from connection: " + fromConnection, ex);
                fromConnection.close();
            }
        }
    }

    private void handleIOExceptionFromConnection(Connection fromConnection, IOException ex, String s) {
        if (TRACE) {
            trace("kryonet", s + fromConnection, ex);
        } else if (DEBUG) {
            debug("kryonet", fromConnection + " update: " + ex.getMessage());
        }
        fromConnection.close();
    }

    private void readObjectsFromTcpConnection(Connection fromConnection) throws IOException {
        Object object;
        while ((object = fromConnection.tcp.readObject(fromConnection)) != null) {
            if (DEBUG) {
                String objectString = object == null ? "null" : object.getClass().getSimpleName();
                if (!(object instanceof FrameworkMessage)) {
                    debug("kryonet", fromConnection + " received TCP: " + objectString);
                } else if (TRACE) {
                    trace("kryonet", fromConnection + " received TCP: " + objectString);
                }
            }
            fromConnection.notifyReceived(object);
        }
    }

    private void checkEmptySelects(long startTime) {
        if (emptySelects == 100) {
            emptySelects = 0;
            // NIO freaks and returns immediately with 0 sometimes, so try to keep from hogging the CPU.
            long elapsedTime = System.currentTimeMillis() - startTime;
            try {
                if (elapsedTime < 25) Thread.sleep(25 - elapsedTime);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void keepAlive() {
        long time = System.currentTimeMillis();
        for (Connection connection : connections) {
            if (connection.tcp.needsKeepAlive(time)) connection.sendTCP(FrameworkMessage.keepAlive);
        }
    }

    public void run() {
        if (TRACE) trace("kryonet", "Server thread started.");
        shutdown = false;
        while (!shutdown) {
            try {
                update(250);
            } catch (IOException ex) {
                if (ERROR) error("kryonet", "Error updating server connections.", ex);
                close();
            }
        }
        if (TRACE) trace("kryonet", "Server thread stopped.");
    }

    public void start() {
        new Thread(this, "Server").start();
    }

    public void stop() {
        if (shutdown) return;
        close();
        if (TRACE) trace("kryonet", "Server thread stopping.");
        shutdown = true;
    }

    private void acceptOperation(SocketChannel socketChannel) {
        Connection connection = newConnection();
        connection.initialize(serialization, writeBufferSize, objectBufferSize);
        connection.endPoint = this;
        if (udp != null) connection.udp = udp;
        try {
            SelectionKey selectionKey = connection.tcp.accept(selector, socketChannel);
            selectionKey.attach(connection);

            int id = nextConnectionID++;
            if (nextConnectionID == -1) nextConnectionID = 1;
            connection.id = id;
            connection.setConnected(true);
            connection.addListener(dispatchListener);

            if (udp == null)
                addConnection(connection);
            else
                pendingConnections.put(id, connection);

            RegisterTCP registerConnection = new RegisterTCP();
            registerConnection.connectionID = id;
            connection.sendTCP(registerConnection);

            if (udp == null) connection.notifyConnected();
        } catch (IOException ex) {
            connection.close();
            if (DEBUG) debug("kryonet", "Unable to accept TCP connection.", ex);
        }
    }

    /**
     * Allows the connections used by the server to be subclassed. This can be useful for storage per connection without an
     * additional lookup.
     */
    protected Connection newConnection() {
        return new Connection();
    }

    private void addConnection(Connection connection) {
        connections.add(connection);
    }

    void removeConnection(Connection connection) {
        ArrayList<Connection> temp = new ArrayList<>(connections);
        temp.remove(connection);
        connections = temp;

        pendingConnections.remove(connection.id);
    }

    // BOZO - Provide mechanism for sending to multiple clients without serializing multiple times.

    public void sendToAllTCP(Object object) {
        for (Connection connection : connections) {
            connection.sendTCP(object);
        }
    }

    public void sendToAllExceptTCP(int connectionID, Object object) {
        for (Connection connection : connections) {
            if (connection.id != connectionID) connection.sendTCP(object);
        }
    }

    public void sendToTCP(int connectionID, Object object) {
        for (Connection connection : connections) {
            if (connection.id == connectionID) {
                connection.sendTCP(object);
                break;
            }
        }
    }

    public void sendToAllUDP(Object object) {
        for (Connection connection : connections) {
            connection.sendUDP(object);
        }
    }

    public void sendToAllExceptUDP(int connectionID, Object object) {
        for (Connection connection : connections) {
            if (connection.id != connectionID) connection.sendUDP(object);
        }
    }

    public void sendToUDP(int connectionID, Object object) {
        for (Connection connection : connections) {
            if (connection.id == connectionID) {
                connection.sendUDP(object);
                break;
            }
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        listeners.add(listener);

        if (TRACE) trace("kryonet", "Server listener added: " + listener.getClass().getName());
    }

    public void removeListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        listeners.remove(listener);

        if (TRACE) {
            trace("kryonet", "Server listener removed: " + listener.getClass().getName());
        }
    }

    /**
     * Closes all open connections and the server port(s).
     */
    public void close() {
        if (INFO && connections.size() > 0) info("kryonet", "Closing server connections...");
        for (Connection connection : connections) connection.close();
        connections = new ArrayList<>();

        ServerSocketChannel serverChannel = this.serverChannel;
        if (serverChannel != null) {
            try {
                serverChannel.close();
                if (INFO) info("kryonet", "Server closed.");
            } catch (IOException ex) {
                if (DEBUG) debug("kryonet", "Unable to close server.", ex);
            }
            this.serverChannel = null;
        }

        if (udp != null) {
            udp.close();
            this.udp = null;
        }

        synchronized (updateLock) { // Blocks to avoid a select while the selector is used to bind the server connection.
        }
        // Select one last time to complete closing the socket.
        selector.wakeup();
        try {
            selector.selectNow();
        } catch (IOException ignored) {
        }
    }

    /**
     * Releases the resources used by this server, which may no longer be used.
     */
    public void dispose() throws IOException {
        close();
        selector.close();
    }

    public Thread getUpdateThread() {
        return updateThread;
    }

    /**
     * Returns the current connections. The array returned should not be modified.
     */
    public ArrayList<Connection> getConnections() {
        return connections;
    }
}
