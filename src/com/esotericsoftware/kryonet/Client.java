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
import com.esotericsoftware.kryonet.FrameworkMessage.DiscoverHost;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterTCP;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterUDP;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.AccessControlException;
import java.util.*;

import static java.util.Objects.requireNonNull;
import static com.esotericsoftware.minlog.Log.*;

/**
 * Represents a TCP and optionally a UDP connection to a {@link Server}.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public class Client implements EndPoint {
	static {
		try {
			// Needed for NIO selectors on Android 2.2.
			System.setProperty("java.net.preferIPv6Addresses", "false");
		} catch (AccessControlException ignored) {
		}
	}

	@Nonnull final Connection connection;

	@Nonnull private final Serialization serialization;
	@Nonnull private ClientDiscoveryHandler discoveryHandler;

	@Nonnull private final Object updateLock = new Object();

	private int emptySelects;
	private volatile boolean shutdown;

	@CheckForNull private Thread updateThread;

	@CheckForNull private InetAddress connectHost;
	@Nonnull private final Selector selector;
	private volatile boolean tcpRegistered;
	private int connectTcpPort;
	private boolean isClosed;
	private int connectTimeout;
	@Nonnull private final Object tcpRegistrationLock = new Object();

	private volatile boolean udpRegistered;
	private int connectUdpPort;
	@Nonnull private final Object udpRegistrationLock = new Object();

	/**
	 * Creates a Client with a write buffer size of 8192 and an object buffer size of 2048.
	 */
	public Client() {
		this(8192, 2048);
	}

	/**
	 * @param writeBufferSize  One buffer of this size is allocated. Objects are serialized to the write buffer where the bytes are
	 *                         queued until they can be written to the TCP socket.
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
	public Client(int writeBufferSize, int objectBufferSize) {
		this(writeBufferSize, objectBufferSize, new KryoSerialization());
	}

	public Client(int writeBufferSize, int objectBufferSize, @Nonnull Serialization serialization) {
		requireNonNull(serialization);
		connection = new Connection(serialization, writeBufferSize, objectBufferSize, this);

		this.serialization = serialization;

		this.discoveryHandler = ClientDiscoveryHandler.DEFAULT;

		try {
			selector = Selector.open();
		} catch (IOException ex) {
			throw new RuntimeException("Error opening selector.", ex);
		}
	}

	public void setDiscoveryHandler(@Nonnull ClientDiscoveryHandler newDiscoveryHandler) {
		discoveryHandler = requireNonNull(newDiscoveryHandler);
	}

	@Nonnull
	public Serialization getSerialization() {
		return serialization;
	}

	@Nullable
	public Kryo getKryo() {
		return serialization instanceof KryoSerialization ? ((KryoSerialization) serialization).getKryo() : null;
	}

	/**
	 * Opens a TCP only client.
	 *
	 * @see #connect(int, InetAddress, int, int)
	 */
	public void connect(int timeout, @Nullable String host, int tcpPort) throws IOException {
		connect(timeout, InetAddress.getByName(host), tcpPort, -1);
	}

	/**
	 * Opens a TCP and UDP client.
	 *
	 * @see #connect(int, InetAddress, int, int)
	 */
	public void connect(int timeout, String host, int tcpPort, int udpPort) throws IOException {
		connect(timeout, InetAddress.getByName(host), tcpPort, udpPort);
	}

	/**
	 * Opens a TCP only client.
	 *
	 * @see #connect(int, InetAddress, int, int)
	 */
	public void connect(int timeout, InetAddress host, int tcpPort) throws IOException {
		connect(timeout, host, tcpPort, -1);
	}

	/**
	 * Opens a TCP and UDP client. Blocks until the connection is complete or the timeout is reached.
	 * <p>
	 * Because the framework must perform some minimal communication before the connection is considered successful,
	 * {@link #update(int)} must be called on a separate thread during the connection process.
	 *
	 * @throws IllegalStateException if called from the connection's update thread.
	 * @throws IOException           if the client could not be opened or connecting times out.
	 */
	public void connect(int timeout, @Nonnull InetAddress host, int tcpPort, int udpPort) throws IOException {
		if (host == null) {
			throw new IllegalArgumentException("host cannot be null.");
		}
		if (Thread.currentThread() == getUpdateThread()) {
			throw new IllegalStateException("Cannot connect on the connection's update thread.");
		}

		this.connectTimeout = timeout;
		this.connectHost = host;
		this.connectTcpPort = tcpPort;
		this.connectUdpPort = udpPort;

		close();
		if (INFO) {
			if (udpPort != -1) {
				info("kryonet", "Connecting: " + host + ":" + tcpPort + "/" + udpPort);
			} else {
				info("kryonet", "Connecting: " + host + ":" + tcpPort);
			}
		}

		connection.id = -1;
		try {
			connectTcpAndUdp(timeout, host, tcpPort, udpPort);
		} catch (IOException ex) {
			close();
			throw ex;
		}
	}

	private void connectTcpAndUdp(int timeout, @Nonnull InetAddress host, int tcpPort, int udpPort) throws IOException {
		if (udpPort != -1) {
			connection.udp = new UdpConnection(serialization, connection.tcp.readBuffer.capacity());
		}

		final long endTime;
		synchronized (updateLock) {
			tcpRegistered = false;
			selector.wakeup();
			endTime = System.currentTimeMillis() + timeout;
			connection.tcp.connect(selector, new InetSocketAddress(host, tcpPort), 5000);
		}

		// Wait for RegisterTCP.
		synchronized (tcpRegistrationLock) {
			connectTcp(endTime);
		}

		if (udpPort != -1) {
			connectUdp(host, udpPort, endTime);
		}
	}

	private void connectTcp(long endTime) throws SocketTimeoutException {
		while (!tcpRegistered && System.currentTimeMillis() < endTime) {
			try {
				tcpRegistrationLock.wait(100);
			} catch (InterruptedException ignored) {
			}
		}
		if (!tcpRegistered) {
			throw new SocketTimeoutException("Connected, but timed out during TCP registration.\n"
					+ "Note: Client#update must be called in a separate thread during connect.");
		}
	}

	private void connectUdp(@Nonnull InetAddress host, int udpPort, long endTime) throws IOException {
		final InetSocketAddress udpAddress = new InetSocketAddress(host, udpPort);
		synchronized (updateLock) {
			udpRegistered = false;
			selector.wakeup();
			connection.udp.connect(selector, udpAddress);
		}

		// Wait for RegisterUDP reply.
		synchronized (udpRegistrationLock) {
			while (!udpRegistered && System.currentTimeMillis() < endTime) {
				final RegisterUDP registerUDP = new RegisterUDP();
				registerUDP.connectionID = connection.id;
				connection.udp.send(connection, registerUDP, udpAddress);
				try {
					udpRegistrationLock.wait(100);
				} catch (InterruptedException ignored) {
				}
			}
			if (!udpRegistered) {
				throw new SocketTimeoutException("Connected, but timed out during UDP registration: " + host + ":" + udpPort);
			}
		}
	}

	/**
	 * Calls {@link #connect(int, InetAddress, int, int) connect} with the values last passed to connect.
	 *
	 * @throws IllegalStateException if connect has never been called.
	 */
	public void reconnect() throws IOException {
		reconnect(connectTimeout);
	}

	/**
	 * Calls {@link #connect(int, InetAddress, int, int) connect} with the specified timeout and the other values last passed to
	 * connect.
	 *
	 * @throws IllegalStateException if connect has never been called.
	 */
	public void reconnect(int timeout) throws IOException {
		if (connectHost == null) {
			throw new IllegalStateException("This client has never been connected.");
		}
		connect(timeout, connectHost, connectTcpPort, connectUdpPort);
	}

	/**
	 * Reads or writes any pending data for this client. Multiple threads should not call this method at the same time.
	 *
	 * @param timeout Wait for up to the specified milliseconds for data to be ready to process. May be zero to return immediately
	 *                if there is no data to process.
	 */
	public void update(int timeout) throws IOException {
		updateThread = Thread.currentThread();
		synchronized (updateLock) { // Blocks to avoid a select while the selector is used to bind the server connection.
		}
		final long startTime = System.currentTimeMillis();
		final int readySelects = timeout > 0 ? selector.select(timeout) :  selector.selectNow();

		if (readySelects == 0) {
			incEmptySelectsAndSleepIfThresholdIsMet(startTime);
		} else {
			updateForSelect();
		}

		if (connection.isConnected) {
			long time = System.currentTimeMillis();
			if (connection.tcp.isTimedOut(time)) {
				if (DEBUG) debug("kryonet", this + " timed out.");
				close();
			} else {
				keepAlive();
			}
			if (connection.isIdle()) connection.notifyIdle();
		}
	}

	private void updateForSelect() throws IOException {
		emptySelects = 0;
		isClosed = false;

		synchronized (selector.selectedKeys()) {
			final Set<SelectionKey> selectionKeys = selector.selectedKeys();

			updateForSelectionKeys(selectionKeys);
		}
	}

	private void updateForSelectionKeys(Set<SelectionKey> selectionKeys) throws IOException {
		for (Iterator<SelectionKey> iter = selectionKeys.iterator(); iter.hasNext(); ) {
			keepAlive();
			final SelectionKey selectionKey = iter.next();
			iter.remove();
			updateForSelectionKey(selectionKey);
		}
	}

	private void updateForSelectionKey(SelectionKey selectionKey) throws IOException {
		try {
			int ops = selectionKey.readyOps();

			if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
				if (selectionKey.attachment() == connection.tcp) {
					updateForUdp();
				} else {
					if (connection.udp.readFromAddress() == null) {
						return;
					}
					Object object = connection.udp.readObject(connection);
					if (object == null) {
						return;
					}
					if (DEBUG) {
						String objectString = object == null ? "null" : object.getClass().getSimpleName();
						debug("kryonet", this + " received UDP: " + objectString);
					}
					connection.notifyReceived(object);
				}
			}
			if ((ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
				connection.tcp.writeOperation();
			}
		} catch (CancelledKeyException ignored) {
			// Connection is closed.
		}
	}

	private void updateForUdp() throws IOException {
		while (true) {
			final Object object = connection.tcp.readObject(connection);
			if (object == null) {
				break;
			}
			if (!tcpRegistered) {
				registerTcp(object);
				continue;
			}

			if (connection.udp != null && !udpRegistered) {
				registerUdp(object);
				continue;
			}

			if (!connection.isConnected) {
				continue;
			}

			if (DEBUG) {
				String objectString = object == null ? "null" : object.getClass().getSimpleName();
				if (!(object instanceof FrameworkMessage)) {
					debug("kryonet", this + " received TCP: " + objectString);
				} else if (TRACE) {
					trace("kryonet", this + " received TCP: " + objectString);
				}
			}
			connection.notifyReceived(object);
		}
	}

	private void registerUdp(Object object) {
		if (object instanceof RegisterUDP) {
			synchronized (udpRegistrationLock) {
				udpRegistered = true;
				udpRegistrationLock.notifyAll();
				if (TRACE) {
					trace("kryonet", this + " received UDP: RegisterUDP");
				}
				if (DEBUG) {
					debug("kryonet", "Port " + connection.udp.datagramChannel.socket().getLocalPort()
							+ "/UDP connected to: " + connection.udp.connectedAddress);
				}
				connection.setConnected(true);
			}
			connection.notifyConnected();
		}
	}

	private void registerTcp(Object object) {
		if (object instanceof RegisterTCP) {
			connection.id = ((RegisterTCP) object).connectionID;
			synchronized (tcpRegistrationLock) {
				tcpRegistered = true;
				tcpRegistrationLock.notifyAll();
				if (TRACE) {
					trace("kryonet", this + " received TCP: RegisterTCP");
				}
				if (connection.udp == null) {
					connection.setConnected(true);
				}
			}
			if (connection.udp == null) {
				connection.notifyConnected();
			}
		}
		return;
	}

	private void incEmptySelectsAndSleepIfThresholdIsMet(long startTime) {
		emptySelects++;
		if (emptySelects == 100) {
			emptySelects = 0;
			// NIO freaks and returns immediately with 0 sometimes, so try to keep from hogging the CPU.
			final long elapsedTime = System.currentTimeMillis() - startTime;
			try {
				if (elapsedTime < 25) {
					Thread.sleep(25 - elapsedTime);
				}
			} catch (InterruptedException ignored) {
			}
		}
	}

	void keepAlive() {
		if (!connection.isConnected) {
			return;
		}

		final long time = System.currentTimeMillis();
		if (connection.tcp.needsKeepAlive(time)) {
			connection.sendTCP(FrameworkMessage.keepAlive);
		}

		if (connection.udp != null && udpRegistered && connection.udp.needsKeepAlive(time)) {
			connection.sendUDP(FrameworkMessage.keepAlive);
		}
	}

	public void run() {
		if (TRACE) trace("kryonet", "Client thread started.");
		shutdown = false;
		while (!shutdown) {
			try {
				update(250);
			} catch (IOException ex) {
				if (TRACE) {
					if (connection.isConnected) {
						trace("kryonet", "Unable to update connection: " + this, ex);
					} else {
						trace("kryonet", "Unable to update connection.", ex);
					}
				} else if (DEBUG) {
					if (connection.isConnected) {
						debug("kryonet", this + " update: " + ex.getMessage());
					} else {
						debug("kryonet", "Unable to update connection: " + ex.getMessage());
					}
				}
				close();
			} catch (KryoNetException ex) {
				connection.lastProtocolError = ex;
				if (ERROR) {
					if (connection.isConnected) {
						error("kryonet", "Error updating connection: " + this, ex);
					} else {
						error("kryonet", "Error updating connection.", ex);
					}
				}
				close();
				throw ex;
			}
		}
		if (TRACE) {
			trace("kryonet", "Client thread stopped.");
		}
	}

	public void start() {
		// Try to let any previous update thread stop.
		if (updateThread != null) {
			shutdown = true;
			try {
				updateThread.join(5000);
			} catch (InterruptedException ignored) {
			}
		}
		updateThread = new Thread(this, "Client");
		updateThread.setDaemon(true);
		updateThread.start();
	}

	public void stop() {
		if (shutdown) return;
		close();
		if (TRACE) {
			trace("kryonet", "Client thread stopping.");
		}

		shutdown = true;
		selector.wakeup();
	}

	public void close() {
		connection.close();
		synchronized (updateLock) { // Blocks to avoid a select while the selector is used to bind the server connection.
		}
		// Select one last time to complete closing the socket.
		if (!isClosed) {
			isClosed = true;
			selector.wakeup();
			try {
				selector.selectNow();
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Releases the resources used by this client, which may no longer be used.
	 */
	public void dispose() throws IOException {
		close();
		selector.close();
	}

	public void addListener(@Nonnull Listener listener) {
		connection.addListener(listener);
		if (TRACE) {
			trace("kryonet", "Client listener added.");
		}
	}

	public void removeListener(@Nonnull Listener listener) {
		connection.removeListener(listener);
		if (TRACE) {
			trace("kryonet", "Client listener removed.");
		}
	}

	/**
	 * An empty object will be sent if the UDP connection is inactive more than the specified milliseconds. Network hardware may
	 * keep a translation table of inside to outside IP addresses and a UDP keep alive keeps this table entry from expiring. Set to
	 * zero to disable. Defaults to 19000.
	 */
	public void setKeepAliveUDP(int keepAliveMillis) {
		if (connection.udp == null) {
			throw new IllegalStateException("Not connected via UDP.");
		}
		connection.udp.keepAliveMillis = keepAliveMillis;
	}

	@CheckForNull
	public Thread getUpdateThread() {
		return updateThread;
	}

	private void broadcast(int udpPort, DatagramSocket socket) throws IOException {
		final ByteBuffer dataBuffer = ByteBuffer.allocate(64);
		serialization.write(null, dataBuffer, new DiscoverHost());
		dataBuffer.flip();
		final byte[] data = new byte[dataBuffer.limit()];
		dataBuffer.get(data);
		for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			for (InetAddress address : Collections.list(iface.getInetAddresses())) {
				// Java 1.5 doesn't support getting the subnet mask, so try the two most common.
				byte[] ip = address.getAddress();
				ip[3] = -1; // 255.255.255.0
				try {
					socket.send(new DatagramPacket(data, data.length, InetAddress.getByAddress(ip), udpPort));
				} catch (Exception ignored) {
				}
				ip[2] = -1; // 255.255.0.0
				try {
					socket.send(new DatagramPacket(data, data.length, InetAddress.getByAddress(ip), udpPort));
				} catch (Exception ignored) {
				}
			}
		}
		if (DEBUG) {
			debug("kryonet", "Broadcasted host discovery on port: " + udpPort);
		}
	}

	/**
	 * Broadcasts a UDP message on the LAN to discover any running servers. The address of the first server to respond is
	 * returned.
	 *
	 * @param udpPort       The UDP port of the server.
	 * @param timeoutMillis The number of milliseconds to wait for a response.
	 * @return the first server found, or null if no server responded.
	 */
	@CheckForNull
	public InetAddress discoverHost(int udpPort, int timeoutMillis) {
		try (DatagramSocket socket = new DatagramSocket()) {
			broadcast(udpPort, socket);
			socket.setSoTimeout(timeoutMillis);
			final DatagramPacket packet = discoveryHandler.onRequestNewDatagramPacket();
			try {
				socket.receive(packet);
			} catch (SocketTimeoutException ex) {
				if (INFO) {
					info("kryonet", "Host discovery timed out.");
				}
				return null;
			}
			if (INFO) {
				info("kryonet", "Discovered server: " + packet.getAddress());
			}
			discoveryHandler.onDiscoveredHost(packet, getKryo());
			return packet.getAddress();
		} catch (IOException ex) {
			if (ERROR) {
				error("kryonet", "Host discovery failed.", ex);
			}
			return null;
		} finally {
			discoveryHandler.onFinally();
		}
	}

	/**
	 * Broadcasts a UDP message on the LAN to discover any running servers.
	 *
	 * @param udpPort       The UDP port of the server.
	 * @param timeoutMillis The number of milliseconds to wait for a response.
	 */
	public List<InetAddress> discoverHosts(int udpPort, int timeoutMillis) {
		final List<InetAddress> hosts = new ArrayList<>();
		try (DatagramSocket socket = new DatagramSocket()) {
			broadcast(udpPort, socket);
			socket.setSoTimeout(timeoutMillis);
			while (true) {
				final DatagramPacket packet = discoveryHandler.onRequestNewDatagramPacket();
				try {
					socket.receive(packet);
				} catch (SocketTimeoutException ex) {
					if (INFO) info("kryonet", "Host discovery timed out.");
					return hosts;
				}
				if (INFO) {
					info("kryonet", "Discovered server: " + packet.getAddress());
				}
				discoveryHandler.onDiscoveredHost(packet, getKryo());
				hosts.add(packet.getAddress());
			}
		} catch (IOException ex) {
			if (ERROR) {
				error("kryonet", "Host discovery failed.", ex);
			}
			return hosts;
		} finally {
			discoveryHandler.onFinally();
		}
	}
}
