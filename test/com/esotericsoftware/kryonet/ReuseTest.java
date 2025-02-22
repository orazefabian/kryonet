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

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(KryonetExtension.class)
class ReuseTest {
	@Test
	void testPingPong(KryonetExtension.Kryonet extension) throws IOException, InterruptedException {
		final AtomicInteger stringCount = new AtomicInteger(0);

		final Server server = new Server();
		extension.startEndPoint(server);
		server.addListener(new Listener() {
			public void connected(Connection connection) {
				connection.sendTCP("TCP from server");
				connection.sendUDP("UDP from server");
			}

			public void received(Connection connection, Object object) {
				if (object instanceof String) {
					stringCount.incrementAndGet();
					System.out.println(object);
				}
			}
		});

		// ----

		final Client client = new Client();
		extension.startEndPoint(client);
		client.addListener(new Listener() {
			public void connected(Connection connection) {
				connection.sendTCP("TCP from client");
				connection.sendUDP("UDP from client");
			}

			public void received(Connection connection, Object object) {
				if (object instanceof String) {
					stringCount.incrementAndGet();
					System.out.println(object);
				}
			}
		});

		final int count = 5;

		try {
			for (int i = 0; i < count; i++) {
				server.bind(extension.tcpPort, extension.udpPort);
				client.connect(5000, extension.host, server.getTcpPort(), server.getUdpPort());
				Thread.sleep(250);
				server.close();
			}
			assertEquals(count * 2 * 2, stringCount.get());
		} finally {
			extension.stopEndPoints();
			extension.waitForThreads(10000);
		}
	}
}
