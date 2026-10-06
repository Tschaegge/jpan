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

package org.scion.jpan.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDatagramChannel;

/**
 * A path decides its own path type and writes its own bytes for every packet. A Hummingbird path
 * needs both: type 5, and MACs that cover the length of the packet.
 */
class PathHookTest {

  private static final String TOPO_112 = "topologies/tiny4/ASff00_0_112/topology.json";

  /** The path of the recorded example packet: one info field and two hop fields, 36 bytes. */
  private static final byte[] SCION_PATH =
      Arrays.copyOfRange(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING, 48, 84);

  @Test
  void testPathWritesItself() throws IOException {
    int[] packetLengthSeen = new int[1];
    Path path =
        new Path(
            SCION_PATH,
            new InetSocketAddress("127.0.0.1", 31000),
            ScionUtil.parseIA("1-ff00:0:112"),
            ScionUtil.parseIA("1-ff00:0:111"),
            InetAddress.getLoopbackAddress(),
            8080) {
          @Override
          public int getPathType() {
            return 5; // Hummingbird
          }

          @Override
          public void writePath(ByteBuffer buffer, int packetLength) {
            packetLengthSeen[0] = packetLength;
            byte[] stamped = getRawPath().clone();
            stamped[stamped.length - 1] ^= 0x01; // stands in for a per-packet change, e.g. a MAC
            buffer.put(stamped);
          }

          @Override
          public Path copy(InetAddress dstIP, int dstPort) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PathMetadata getMetadata() {
            return null;
          }
        };

    List<byte[]> sent = new ArrayList<>();
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setSendCallback(
        (buffer, address) -> {
          byte[] packet = new byte[buffer.remaining()];
          buffer.get(packet);
          sent.add(packet);
          return packet.length;
        });
    try (Scion.CloseableService service = Scion.newServiceWithTopologyFile(TOPO_112);
        ScionDatagramChannel channel = ScionDatagramChannel.open(service, mock)) {
      channel.bind(new InetSocketAddress("127.0.0.1", 44444));
      assertEquals(5, channel.send(ByteBuffer.wrap("hello".getBytes()), path));
    }

    byte[] packet = sent.get(0);
    assertEquals(5, packet[8]); // PathType, from getPathType()
    assertEquals(packet.length, packetLengthSeen[0]); // writePath knew the final packet length
    // The path starts at byte 36 (12 common header, 16 ISD-AS, 4 + 4 IPv4 addresses) and holds
    // what writePath wrote, not the raw path.
    assertEquals((byte) (SCION_PATH[35] ^ 0x01), packet[36 + 35]);
  }
}
