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

package com.esotericsoftware.kryonet.compress;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serializers.CollectionSerializer;
import com.esotericsoftware.kryo.serializers.DeflateSerializer;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryonet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.ArrayList;

@ExtendWith(KryonetExtension.class)
public class DeflateTest {
	static public void register(Kryo kryo) {
		kryo.register(short[].class);
		kryo.register(SomeData.class, new DeflateSerializer(new FieldSerializer(kryo, SomeData.class)));
		kryo.register(ArrayList.class, new CollectionSerializer());
	}

	@Test
	public void testDeflate(KryonetExtension.Kryonet extension) throws IOException {
		final Server server = new Server();
		register(server.getKryo());

		final SomeData data = new SomeData();
		data.text = "some text here aaaaaaaaaabbbbbbbbbbbcccccccccc";
		data.stuff = new short[]{1, 2, 3, 4, 5, 6, 7, 8};

		final ArrayList a = new ArrayList();
		a.add(12);
		a.add(null);
		a.add(34);

		extension.startEndPoint(server);
		server.bind(extension.tcpPort, extension.udpPort);
		server.addListener(new Listener() {
			public void connected(Connection connection) {
				server.sendToAllTCP(data);
				connection.sendTCP(data);
				connection.sendTCP(a);
			}
		});

		// ----

		final Client client = new Client();
		register(client.getKryo());
		extension.startEndPoint(client);
		client.addListener(new Listener() {
			public void received(Connection connection, Object object) {
				if (object instanceof SomeData) {
					SomeData data = (SomeData) object;
					System.out.println(data.stuff[3]);
				} else if (object instanceof ArrayList) {
					extension.stopEndPoints();
				}
			}
		});
		client.connect(5000, extension.host, server.getTcpPort(), server.getUdpPort());

		extension.waitForThreads();
	}

	static public class SomeData {
		public String text;
		public short[] stuff;
	}
}
