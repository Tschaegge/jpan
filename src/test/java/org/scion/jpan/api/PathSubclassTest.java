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
 * A subclass of Path can override getPathType() and writePath(): the channel takes the path type
 * from the path and lets it write its own bytes into every packet. A Hummingbird path needs both,
 * type 5 and MACs that cover the length of the packet.
 */
class PathSubclassTest {

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

  /**
   * A path with end-to-end options: the packet gets an extension header between the path and the
   * UDP header, and the packet length writePath sees includes it.
   */
  @Test
  void testEndToEndOptions() throws IOException {
    // Option type 3 with 3 bytes of data: 2 + 5 bytes, so the extension needs 1 byte of padding
    byte[] options = {3, 3, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
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
          public void writePath(ByteBuffer buffer, int packetLength) {
            packetLengthSeen[0] = packetLength;
            super.writePath(buffer, packetLength);
          }

          @Override
          public byte[] getEndToEndOptions() {
            return options;
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

    ByteBuffer packet = ByteBuffer.wrap(sent.get(0));
    int ext = 36 + SCION_PATH.length; // the extension follows the path
    assertEquals(201, packet.get(4) & 0xFF); // next header: end-to-end extension
    assertEquals(8 + 8 + 5, packet.getShort(6)); // payload: extension, UDP header, "hello"
    assertEquals(packet.limit(), packetLengthSeen[0]);
    // The extension: next header UDP, one 4-byte line beyond the first, the option, Pad1
    assertEquals(17, packet.get(ext));
    assertEquals(1, packet.get(ext + 1));
    byte[] written = new byte[5];
    packet.position(ext + 2);
    packet.get(written);
    assertArrayEquals(options, written);
    assertEquals(0, packet.get(ext + 7)); // Pad1 fills the 8 bytes
    assertEquals(44444, packet.getShort(ext + 8) & 0xFFFF); // then the UDP header
  }
}
