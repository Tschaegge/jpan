// Copyright 2026 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.hummingbird;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionSocketOptions;
import org.scion.jpan.testutil.HummingbirdExamplePacket;
import org.scion.jpan.testutil.MockDatagramChannel;

/**
 * Receives a Hummingbird packet through a plain ScionDatagramChannel in AS 111: the packet the Go
 * prototype sent over 112 -> 110 -> 111, with flyovers at all three ASes.
 */
class HummingbirdReceiveTest {

  /** AS 111 from JPAN's tiny4 test topology. Its border router owns interface 41. */
  private static final String TOPO_111 = "topologies/tiny4/ASff00_0_111/topology.json";

  private static final InetSocketAddress ROUTER_111 = new InetSocketAddress("127.0.0.17", 31008);

  @Test
  void testReceive() throws IOException {
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setReceiveCallback(
        buffer -> {
          // The captured header, then the UDP header and payload of the 1288 bytes it announces
          buffer.put(HummingbirdExamplePacket.PACKET_BYTES_HBIRD_112_111);
          buffer.putShort((short) 40000).putShort((short) 32000).putShort((short) 1288);
          buffer.putShort((short) 0); // UDP checksum, not checked
          buffer.put(new byte[1280]);
          return ROUTER_111;
        });
    try (Scion.CloseableService service = Scion.newServiceWithTopologyFile(TOPO_111);
        ScionDatagramChannel channel = ScionDatagramChannel.open(service, mock)) {
      // Without this a rejected packet is skipped silently, and the mock would deliver it forever.
      channel.setOption(ScionSocketOptions.SCION_API_THROW_PARSER_FAILURE, true);
      channel.bind(new InetSocketAddress("127.0.0.1", 32000));
      ByteBuffer payload = ByteBuffer.allocate(2000);
      Path reply = channel.receive(payload).getPath();

      assertEquals(1280, payload.position());
      assertEquals(5, reply.getPathType());
      // The last hop field is AS 111's own, (41, 0), so the reply leaves through interface 41.
      assertEquals(ROUTER_111, reply.getFirstHopAddress());
    }
  }
}
