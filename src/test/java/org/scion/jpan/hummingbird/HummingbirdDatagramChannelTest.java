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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.RequestPath;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDatagramChannel;

class HummingbirdDatagramChannelTest {

  /** A service for AS 112 from JPAN's tiny4 test topology; JPAN needs it to send over a path. */
  private static final String TOPO_112 = "topologies/tiny4/ASff00_0_112/topology.json";

  /** The path of the recorded example packet: one info field and two hop fields, 36 bytes. */
  private static final byte[] SCION_PATH =
      Arrays.copyOfRange(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING, 48, 84);

  private final List<byte[]> sent = new ArrayList<>();

  /** A mock underlay that records every datagram instead of sending it. */
  private MockDatagramChannel recordingMock() throws IOException {
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setSendCallback(
        (buffer, address) -> {
          byte[] packet = new byte[buffer.remaining()];
          buffer.get(packet);
          sent.add(packet);
          return packet.length;
        });
    return mock;
  }

  private static RequestPath path() {
    return PackageVisibilityHelper.createDummyPath(
        ScionUtil.parseIA("1-ff00:0:112"),
        ScionUtil.parseIA("1-ff00:0:111"),
        new byte[] {127, 0, 0, 1},
        8080,
        SCION_PATH,
        new InetSocketAddress("127.0.0.1", 31000));
  }

  /**
   * IPv4 source and destination: 12 bytes common header, 16 bytes ISD-AS, 4 + 4 bytes host
   * addresses and a 44-byte Hummingbird path give an 80-byte header, HdrLen 20. The SCION packet
   * JPAN built had a 72-byte header.
   */
  private void assertHummingbird(byte[] packet, int payloadLength) {
    assertEquals(5, packet[8]); // PathType Hummingbird
    assertEquals(20, packet[5]); // HdrLen: 80 bytes / 4
    assertEquals(80 + 8 + payloadLength, packet.length); // header, UDP header, payload
  }

  @Test
  void testSend() throws IOException {
    try (Scion.CloseableService service = Scion.newServiceWithTopologyFile(TOPO_112);
        HummingbirdDatagramChannel channel =
            HummingbirdDatagramChannel.open(service, recordingMock())) {
      channel.bind(new InetSocketAddress("127.0.0.1", 44444));
      int n = channel.send(ByteBuffer.wrap("Hello scion".getBytes()), path());
      assertEquals(11, n); // the payload, exactly as a ScionDatagramChannel reports it
    }
    assertEquals(1, sent.size());
    assertHummingbird(sent.get(0), 11);
  }

  @Test
  void testWrite() throws IOException {
    try (Scion.CloseableService service = Scion.newServiceWithTopologyFile(TOPO_112);
        HummingbirdDatagramChannel channel =
            HummingbirdDatagramChannel.open(service, recordingMock())) {
      channel.bind(new InetSocketAddress("127.0.0.1", 44444));
      channel.connect(path());
      ByteBuffer payload = ByteBuffer.wrap("Hello scion".getBytes());
      assertEquals(11, channel.write(payload)); // write() throws if sendRaw misreports
      assertFalse(payload.hasRemaining());
    }
    assertEquals(1, sent.size());
    assertHummingbird(sent.get(0), 11);
  }

  /** Every packet carries the time it was sent, and the counter goes up by one per packet. */
  @Test
  void testTimestamps() throws IOException {
    long before = System.currentTimeMillis() / 1000;
    try (Scion.CloseableService service = Scion.newServiceWithTopologyFile(TOPO_112);
        HummingbirdDatagramChannel channel =
            HummingbirdDatagramChannel.open(service, recordingMock())) {
      channel.bind(new InetSocketAddress("127.0.0.1", 44444));
      channel.send(ByteBuffer.wrap("one".getBytes()), path());
      channel.send(ByteBuffer.wrap("two".getBytes()), path());
    }
    long after = System.currentTimeMillis() / 1000;
    assertEquals(2, sent.size());

    // The path starts at byte 36: 12 bytes common header, 16 bytes ISD-AS, 4 + 4 bytes addresses.
    ByteBuffer packet1 = ByteBuffer.wrap(sent.get(0));
    packet1.position(36);
    HummingbirdPathRaw path1 = HummingbirdPathRaw.create(packet1);
    ByteBuffer packet2 = ByteBuffer.wrap(sent.get(1));
    packet2.position(36);
    HummingbirdPathRaw path2 = HummingbirdPathRaw.create(packet2);

    assertTrue(before <= path1.getBaseTimestamp() && path1.getBaseTimestamp() <= after);
    assertTrue(path1.getMillis() < 1000);
    assertEquals(0, path1.getCounter()); // a new channel starts at 0
    assertEquals(1, path2.getCounter());
  }
}
