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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(KryonetExtension.class)
class MultipleServerTest {
	AtomicInteger received = new AtomicInteger();

	@Test
	void testMultipleThreads(KryonetExtension.Kryonet extension) throws IOException {
		final Server server1 = new Server(16384, 8192);
		server1.getKryo().register(String[].class);
		extension.startEndPoint(server1);
		server1.bind(extension.tcpPort, extension.udpPort);
		server1.addListener(new Listener() {
			public void received(Connection connection, Object object) {
				if (object instanceof String) {
					if (!object.equals("client1")) fail();
					if (received.incrementAndGet() == 2) extension.stopEndPoints();
				}
			}
		});

		final Server server2 = new Server(16384, 8192);
		server2.getKryo().register(String[].class);
		extension.startEndPoint(server2);
		server2.bind(extension.tcpPort, extension.udpPort);
		server2.addListener(new Listener() {
			public void received(Connection connection, Object object) {
				if (object instanceof String) {
					if (!object.equals("client2")) fail();
					if (received.incrementAndGet() == 2) extension.stopEndPoints();
				}
			}
		});

		// ----

		Client client1 = new Client(16384, 8192);
		client1.getKryo().register(String[].class);
		extension.startEndPoint(client1);
		client1.addListener(new Listener() {
			public void connected(Connection connection) {
				connection.sendTCP("client1");
			}
		});
		client1.connect(5000, extension.host, server1.getTcpPort(), server1.getUdpPort());

		Client client2 = new Client(16384, 8192);
		client2.getKryo().register(String[].class);
		extension.startEndPoint(client2);
		client2.addListener(new Listener() {
			public void connected(Connection connection) {
				connection.sendTCP("client2");
			}
		});
		client2.connect(5000, extension.host, server2.getTcpPort(), server2.getUdpPort());

		extension.waitForThreads(5000);
	}
}
