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
import com.esotericsoftware.kryo.io.Input;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static com.esotericsoftware.minlog.Log.info;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(KryonetExtension.class)
class DiscoverHostTest {

	@Test
	void testBroadcast(KryonetExtension.Kryonet extension) throws IOException {
		// This server exists solely to reply to Client#discoverHost.
		// It wouldn't be needed if the real server was using UDP.
		final Server broadcastServer = new Server();
		extension.startEndPoint(broadcastServer);
		broadcastServer.bind(extension.tcpPort, extension.udpPort);

		final Server server = new Server();
		extension.startEndPoint(server);
		server.bind(0);
		server.addListener(new Listener() {
			public void disconnected(Connection connection) {
				broadcastServer.stop();
				server.stop();
			}
		});

		// ----

		Client client = new Client();
		InetAddress host = client.discoverHost(broadcastServer.getUdpPort(), 2000);
		if (host == null) {
			extension.stopEndPoints();
			fail("No servers found.");
			return;
		}

		extension.startEndPoint(client);
		client.connect(2000, host, server.getTcpPort());
		client.stop();

		extension.waitForThreads();
	}

	@Test
	void testCustomBroadcast(KryonetExtension.Kryonet extension) throws IOException {
		final AtomicInteger port = new AtomicInteger();

		ServerDiscoveryHandler serverDiscoveryHandler = new ServerDiscoveryHandler() {
			@Override
			public boolean onDiscoverHost(DatagramChannel datagramChannel, InetSocketAddress fromAddress,
										  Serialization serialization) throws IOException {

				DiscoveryResponsePacket packet = new DiscoveryResponsePacket();
				packet.id = 42;
				packet.gameName = "gameName";
				packet.playerName = "playerName";

				ByteBuffer buffer = ByteBuffer.allocate(256);
				serialization.write(null, buffer, packet);
				buffer.flip();

				datagramChannel.send(buffer, fromAddress);

				return true;
			}
		};

		ClientDiscoveryHandler clientDiscoveryHandler = new ClientDiscoveryHandler() {
			private Input input = null;

			@Override
			public DatagramPacket onRequestNewDatagramPacket() {
				byte[] buffer = new byte[1024];
				input = new Input(buffer);
				return new DatagramPacket(buffer, buffer.length);
			}

			@Override
			public void onDiscoveredHost(DatagramPacket datagramPacket, Kryo kryo) {
				if (input != null) {
					DiscoveryResponsePacket packet;
					packet = (DiscoveryResponsePacket) kryo.readClassAndObject(input);
					info("test", "packet.id = " + packet.id);
					info("test", "packet.gameName = " + packet.gameName);
					info("test", "packet.playerName = " + packet.playerName);
					info("test", "datagramPacket.getAddress() = " + datagramPacket.getAddress());
					info("test", "datagramPacket.getPort() = " + datagramPacket.getPort());
					assertEquals(42, packet.id);
					assertEquals("gameName", packet.gameName);
					assertEquals("playerName", packet.playerName);
					assertEquals(port.get(), datagramPacket.getPort());
				}
			}

			@Override
			public void onFinally() {
				if (input != null) {
					input.close();
				}
			}
		};

		// This server exists solely to reply to Client#discoverHost.
		// It wouldn't be needed if the real server was using UDP.
		final Server broadcastServer = new Server();

		broadcastServer.getKryo().register(DiscoveryResponsePacket.class);
		broadcastServer.setDiscoveryHandler(serverDiscoveryHandler);

		extension.startEndPoint(broadcastServer);
		broadcastServer.bind(0, extension.udpPort);
		port.set(broadcastServer.getUdpPort());

		final Server server = new Server();
		extension.startEndPoint(server);
		server.bind(0);
		server.addListener(new Listener() {
			public void disconnected(Connection connection) {
				broadcastServer.stop();
				server.stop();
			}
		});

		// ----

		Client client = new Client();

		client.getKryo().register(DiscoveryResponsePacket.class);
		client.setDiscoveryHandler(clientDiscoveryHandler);

		InetAddress host = client.discoverHost(broadcastServer.getUdpPort(), 2000);
		if (host == null) {
			extension.stopEndPoints();
			fail("No servers found.");
			return;
		}

		extension.startEndPoint(client);
		client.connect(2000, host, server.getTcpPort());
		client.stop();

		extension.waitForThreads();
	}

	public static class DiscoveryResponsePacket {

		public int id;
		public String gameName;
		public String playerName;

		public DiscoveryResponsePacket() {
			//
		}
	}

}
