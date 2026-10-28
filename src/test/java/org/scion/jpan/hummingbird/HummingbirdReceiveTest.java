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
    Path reply = receive(null);
    assertEquals(5, reply.getPathType());
    // The last hop field is AS 111's own, (41, 0), so the reply leaves through interface 41.
    assertEquals(ROUTER_111, reply.getFirstHopAddress());
  }

  /**
   * The packet carries Go's ReversePath option in an extension header. JPAN does not read the
   * option, but skips it: receive checks the payload and the UDP header behind it.
   */
  @Test
  void testReceiveWithExtension() throws IOException {
    for (int extHdr : new int[] {200, 201}) {
      receive(extHdr, HummingbirdResponsePathTest.REVERSE_RESERVATION);
    }
  }

  private static Path receive(byte[] reverse) throws IOException {
    return receive(201, reverse);
  }

  /**
   * Receives the Go packet in AS 111 and returns the reply path. With {@code reverse}, the packet
   * carries it as option 3 between the path and the UDP header, in an extension header of type
   * {@code extHdr}: 201 (end-to-end) as a Go client does, or 200 (hop-by-hop).
   */
  private static Path receive(int extHdr, byte[] reverse) throws IOException {
    // Next header, length in 4-byte lines minus one, option type and data length, padding to 4
    int pad = (4 - (4 + (reverse == null ? 0 : reverse.length)) % 4) % 4;
    int extLen = reverse == null ? 0 : 4 + reverse.length + pad;
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setReceiveCallback(
        buffer -> {
          int start = buffer.position();
          buffer.put(HummingbirdExamplePacket.PACKET_BYTES_HBIRD_112_111);
          if (reverse != null) {
            buffer.put(start + 4, (byte) extHdr); // next header: the extension header
            buffer.putShort(start + 6, (short) (1288 + extLen)); // payload length
            buffer.put((byte) 17).put((byte) (extLen / 4 - 1));
            buffer.put((byte) 3).put((byte) reverse.length).put(reverse);
            buffer.put(new byte[pad]); // Pad1 bytes
          }
          // The UDP header and payload of the 1288 bytes the captured header announces
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
      assertEquals(40000, reply.getRemotePort()); // from the UDP header, behind the extension
      return reply;
    }
  }
}
