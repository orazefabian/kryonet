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
import com.esotericsoftware.kryonet.FrameworkMessage.Ping;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.esotericsoftware.minlog.Log.*;
import static java.util.Objects.requireNonNull;

// BOZO - Layer to handle handshake state.

/**
 * Represents a TCP and optionally a UDP connection between a {@link Client} and a {@link Server}. If either underlying connection
 * is closed or errors, both connections are closed.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public class Connection {
	int id = -1;
	@Nonnull private final EndPoint endPoint;
	@Nullable final TcpConnection tcp;
	@Nullable UdpConnection udp;
	@Nullable InetSocketAddress udpRemoteAddress;
	volatile boolean isConnected;
	@CheckForNull volatile KryoNetException lastProtocolError;
	private String name;
	@Nonnull private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private int lastPingID;
	private long lastPingSendTime;
	private int returnTripTime;

	protected Connection(Serialization serialization, int writeBufferSize, int objectBufferSize, @Nonnull EndPoint endPoint) {
		requireNonNull(endPoint);

		tcp = new TcpConnection(serialization, writeBufferSize, objectBufferSize);
		this.endPoint = endPoint;
	}
	/**
	 * Returns the server assigned ID. Will return -1 if this connection has never been connected or the last assigned ID if this
	 * connection has been disconnected.
	 */
	public int getID() {
		return id;
	}

	/**
	 * Returns true if this connection is connected to the remote end. Note that a connection can become disconnected at any time.
	 */
	public boolean isConnected() {
		return isConnected;
	}

	void setConnected(boolean isConnected) {
		this.isConnected = isConnected;
		if (isConnected && name == null) name = "Connection " + id;
	}

	/**
	 * Returns the last protocol error that occured on the connection.
	 *
	 * @return The last protocol error or null if none error occured.
	 */
	@CheckForNull
	public KryoNetException getLastProtocolError() {
		return lastProtocolError;
	}

	/**
	 * Sends the object over the network using TCP.
	 *
	 * @return The number of bytes sent.
	 * @see Kryo#register(Class, com.esotericsoftware.kryo.Serializer)
	 */
	public int sendTCP(@Nonnull Object object) {
		if (object == null) {
			throw new IllegalArgumentException("object cannot be null.");
		}

		try {
			int length = tcp.send(this, object);
			if (length == 0) {
				if (TRACE) trace("kryonet", this + " TCP had nothing to send.");
			} else if (DEBUG) {
				String objectString = object == null ? "null" : object.getClass().getSimpleName();
				if (!(object instanceof FrameworkMessage)) {
					debug("kryonet", this + " sent TCP: " + objectString + " (" + length + ")");
				} else if (TRACE) {
					trace("kryonet", this + " sent TCP: " + objectString + " (" + length + ")");
				}
			}
			return length;
		} catch (IOException ex) {
			if (DEBUG) debug("kryonet", "Unable to send TCP with connection: " + this, ex);
			close();
			return 0;
		} catch (KryoNetException ex) {
			if (ERROR) error("kryonet", "Unable to send TCP with connection: " + this, ex);
			close();
			return 0;
		}
	}

	/**
	 * Sends the object over the network using UDP.
	 *
	 * @return The number of bytes sent.
	 * @throws IllegalStateException if this connection was not opened with both TCP and UDP.
	 * @see Kryo#register(Class, com.esotericsoftware.kryo.Serializer)
	 */
	public int sendUDP(@Nonnull Object object) {
		if (object == null) throw new IllegalArgumentException("object cannot be null.");
		SocketAddress address = udpRemoteAddress;
		if (address == null && udp != null) address = udp.connectedAddress;
		if (address == null && isConnected) throw new IllegalStateException("Connection is not connected via UDP.");

		try {
			if (address == null) throw new SocketException("Connection is closed.");

			int length = udp.send(this, object, address);
			if (length == 0) {
				if (TRACE) trace("kryonet", this + " UDP had nothing to send.");
			} else if (DEBUG) {
				if (length != -1) {
					String objectString = object == null ? "null" : object.getClass().getSimpleName();
					if (!(object instanceof FrameworkMessage)) {
						debug("kryonet", this + " sent UDP: " + objectString + " (" + length + ")");
					} else if (TRACE) {
						trace("kryonet", this + " sent UDP: " + objectString + " (" + length + ")");
					}
				} else
					debug("kryonet", this + " was unable to send, UDP socket buffer full.");
			}
			return length;
		} catch (IOException ex) {
			if (DEBUG) debug("kryonet", "Unable to send UDP with connection: " + this, ex);
			close();
			return 0;
		} catch (KryoNetException ex) {
			if (ERROR) error("kryonet", "Unable to send UDP with connection: " + this, ex);
			close();
			return 0;
		}
	}

	public void close() {
		boolean wasConnected = isConnected;
		isConnected = false;
		tcp.close();
		if (udp != null && udp.connectedAddress != null) {
			udp.close();
		}
		if (wasConnected) {
			notifyDisconnected();
			if (INFO) info("kryonet", this + " disconnected.");
		}
		setConnected(false);
	}

	/**
	 * Requests the connection to communicate with the remote computer to determine a new value for the
	 * {@link #getReturnTripTime() return trip time}. When the connection receives a {@link FrameworkMessage.Ping} object with
	 * {@link Ping#isReply isReply} set to true, the new return trip time is available.
	 */
	public void updateReturnTripTime() {
		Ping ping = new Ping();
		ping.id = lastPingID++;
		lastPingSendTime = System.currentTimeMillis();
		sendTCP(ping);
	}

	/**
	 * Returns the last calculated TCP return trip time, or -1 if {@link #updateReturnTripTime()} has never been called or the
	 * {@link FrameworkMessage.Ping} response has not yet been received.
	 */
	public int getReturnTripTime() {
		return returnTripTime;
	}

	/**
	 * An empty object will be sent if the TCP connection has not sent an object within the specified milliseconds. Periodically
	 * sending a keep alive ensures that an abnormal close is detected in a reasonable amount of time (see {@link #setTimeout(int)}
	 * ). Also, some network hardware will close a TCP connection that ceases to transmit for a period of time (typically 1+
	 * minutes). Set to zero to disable. Defaults to 8000.
	 */
	public void setKeepAliveTCP(int keepAliveMillis) {
		tcp.keepAliveMillis = keepAliveMillis;
	}

	/**
	 * If the specified amount of time passes without receiving an object over TCP, the connection is considered closed. When a TCP
	 * socket is closed normally, the remote end is notified immediately and this timeout is not needed. However, if a socket is
	 * closed abnormally (eg, power loss), KryoNet uses this timeout to detect the problem. The timeout should be set higher than
	 * the {@link #setKeepAliveTCP(int) TCP keep alive} for the remote end of the connection. The keep alive ensures that the remote
	 * end of the connection will be constantly sending objects, and setting the timeout higher than the keep alive allows for
	 * network latency. Set to zero to disable. Defaults to 12000.
	 */
	public void setTimeout(int timeoutMillis) {
		tcp.timeoutMillis = timeoutMillis;
	}

	/**
	 * If the listener already exists, it is not added again.
	 */
	public void addListener(@Nonnull Listener listener) {
		if (listener == null) {
			throw new IllegalArgumentException("listener cannot be null.");
		}

		listeners.add(listener);

		if (TRACE) trace("kryonet", "Connection listener added: " + listener.getClass().getName());
	}

	public void removeListener(@Nonnull Listener listener) {
		if (listener == null) {
			throw new IllegalArgumentException("listener cannot be null.");
		}

		listeners.remove(listener);

		if (TRACE) trace("kryonet", "Connection listener removed: " + listener.getClass().getName());
	}

	void notifyConnected() {
		if (INFO) {
			final SocketChannel socketChannel = tcp.socketChannel;
			if (socketChannel != null) {
				final Socket socket = tcp.socketChannel.socket();
				if (socket != null) {
					final InetSocketAddress remoteSocketAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
					if (remoteSocketAddress != null)
						info("kryonet", this + " connected: " + remoteSocketAddress.getAddress());
				}
			}
		}
		for (Listener listener : this.listeners) listener.connected(this);
	}

	void notifyDisconnected() {
		for (Listener listener : this.listeners) listener.disconnected(this);
	}

	void notifyIdle() {
		for (Listener listener : this.listeners) {
			listener.idle(this);

			if (!isIdle()) {
				break;
			}
		}
	}

	void notifyReceived(Object object) {
		if (object instanceof Ping) {
			Ping ping = (Ping) object;
			if (ping.isReply) {
				if (ping.id == lastPingID - 1) {
					returnTripTime = (int) (System.currentTimeMillis() - lastPingSendTime);
					if (TRACE) trace("kryonet", this + " return trip time: " + returnTripTime);
				}
			} else {
				ping.isReply = true;
				sendTCP(ping);
			}
		}
		for (Listener listener : this.listeners) {
			listener.received(this, object);
		}
	}

	/**
	 * Returns the local {@link Client} or {@link Server} to which this connection belongs.
	 */
	@Nonnull
	public EndPoint getEndPoint() {
		return endPoint;
	}

	/**
	 * Returns the IP address and port of the remote end of the TCP connection, or null if this connection is not connected.
	 */
	public InetSocketAddress getRemoteAddressTCP() {
		SocketChannel socketChannel = tcp.socketChannel;
		if (socketChannel != null) {
			Socket socket = tcp.socketChannel.socket();
			if (socket != null) {
				return (InetSocketAddress) socket.getRemoteSocketAddress();
			}
		}
		return null;
	}

	/**
	 * Returns the IP address and port of the remote end of the UDP connection, or null if this connection is not connected.
	 */
	public InetSocketAddress getRemoteAddressUDP() {
		InetSocketAddress connectedAddress = udp.connectedAddress;
		if (connectedAddress != null) return connectedAddress;
		return udpRemoteAddress;
	}

	/**
	 * Workaround for broken NIO networking on Android 1.6. If true, the underlying NIO buffer is always copied to the beginning of
	 * the buffer before being given to the SocketChannel for sending. The Harmony SocketChannel implementation in Android 1.6
	 * ignores the buffer position, always copying from the beginning of the buffer. This is fixed in Android 2.0+.
	 */
	public void setBufferPositionFix(boolean bufferPositionFix) {
		tcp.bufferPositionFix = bufferPositionFix;
	}

	/**
	 * Sets the friendly name of this connection. This is returned by {@link #toString()} and is useful for providing application
	 * specific identifying information in the logging. May be null for the default name of "Connection X", where X is the
	 * connection ID.
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Returns the number of bytes that are waiting to be written to the TCP socket, if any.
	 */
	public int getTcpWriteBufferSize() {
		return tcp.writeBuffer.position();
	}

	/**
	 * @see #setIdleThreshold(float)
	 */
	public boolean isIdle() {
		return tcp.writeBuffer.position() / (float) tcp.writeBuffer.capacity() < tcp.idleThreshold;
	}

	/**
	 * If the percent of the TCP write buffer that is filled is less than the specified threshold,
	 * {@link Listener#idle(Connection)} will be called for each network thread update. Default is 0.1.
	 */
	public void setIdleThreshold(float idleThreshold) {
		tcp.idleThreshold = idleThreshold;
	}

	public String toString() {
		if (name != null) {
			return name;
		}
		return "Connection " + id;
	}
}
