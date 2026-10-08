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
import org.scion.jpan.Path;
import org.scion.jpan.RequestPath;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDatagramChannel;

/**
 * Sends packets over a ReservedPath through a plain ScionDatagramChannel and checks the bytes that
 * leave it: path type and header length, fresh timestamps, and for a flyover its fields and the
 * aggregated MAC. The same with real routers is {@code make live} in hbird-conformance.
 */
class ReservedPathTest {

  /** A service for AS 112 from JPAN's tiny4 test topology; JPAN needs it to send over a path. */
  private static final String TOPO_112 = "topologies/tiny4/ASff00_0_112/topology.json";

  private static final long DST_ISD_AS = ScionUtil.parseIA("1-ff00:0:111");

  /** The path of the recorded example packet: one info field and two hop fields, 36 bytes. */
  private static final byte[] SCION_PATH =
      Arrays.copyOfRange(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING, 48, 84);

  // In a sent packet the path starts at byte 36: 12 bytes common header, 16 bytes ISD-AS and
  // 4 + 4 bytes IPv4 addresses. Hop field 0 follows the 12-byte meta header and one info field.
  private static final int PATH_START = 36;
  private static final int HOP_0 = PATH_START + 12 + 8;

  private static final byte[] AK = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

  private final List<byte[]> sent = new ArrayList<>();

  @Test
  void testNoFlyover() throws IOException {
    send(new ReservedPath(scionPath()), "Hello scion");

    byte[] packet = sent.get(0);
    assertEquals(5, packet[8]); // PathType Hummingbird
    assertEquals(20, packet[5]); // HdrLen: 36 + a 44-byte path = 80 bytes, / 4
    assertEquals(80 + 8 + 11, packet.length); // header, UDP header, payload
  }

  /** Every packet carries the time it was sent, and the counter goes up by one per packet. */
  @Test
  void testTimestamps() throws IOException {
    long before = System.currentTimeMillis() / 1000;
    send(new ReservedPath(scionPath()), "one", "two");
    long after = System.currentTimeMillis() / 1000;

    HummingbirdPathRaw path1 = parsePath(sent.get(0));
    HummingbirdPathRaw path2 = parsePath(sent.get(1));
    assertTrue(before <= path1.getBaseTimestamp() && path1.getBaseTimestamp() <= after);
    assertTrue(path1.getMillis() < 1000);
    assertEquals(0, path1.getCounter()); // a new path starts at 0
    assertEquals(1, path2.getCounter());
  }

  /**
   * A flyover at hop 0: the hop field grows to 20 bytes and carries the reservation, the
   * ResStartOffset, and instead of the SCION MAC the aggregated MAC over this very packet.
   */
  @Test
  void testFlyover() throws IOException {
    long start = System.currentTimeMillis() / 1000 - 10;
    Flyover flyover = new Flyover(0, 1, 1, 1023, start, 60, AK);
    // Two packets: the second shows that the first did not overwrite the template's SCION MAC.
    send(new ReservedPath(scionPath(), new Flyover[] {flyover, null}), "one", "two");

    byte[] scionMac = Arrays.copyOfRange(SCION_PATH, 18, 24); // hop 0 at 4 + 8, its MAC at +6
    for (byte[] packet : sent) {
      ByteBuffer bb = ByteBuffer.wrap(packet);
      assertEquals(22, packet[5]); // HdrLen: 36 + a 52-byte path (8 more for the flyover), / 4
      assertEquals((byte) 0x80, (byte) (packet[HOP_0] & 0x80)); // flyover bit
      assertEquals((1 << 10) | 1023, bb.getInt(HOP_0 + 12)); // ResID 1, BW 1023
      assertEquals(60, bb.getShort(HOP_0 + 18)); // duration

      int baseTs = bb.getInt(PATH_START + 4);
      int highResTs = bb.getInt(PATH_START + 8);
      int resStartOffset = bb.getShort(HOP_0 + 16) & 0xFFFF;
      assertEquals(baseTs - start, resStartOffset); // seconds since the reservation started

      // What the router checks: AggMAC = SCION MAC XOR the first 6 bytes of Vk over this packet.
      byte[] vk =
          new HummingbirdMac(AK).flyoverMac(DST_ISD_AS, packet.length, resStartOffset, highResTs);
      for (int i = 0; i < HummingbirdMac.MAC_LEN; i++) {
        assertEquals((byte) (scionMac[i] ^ vk[i]), packet[HOP_0 + 6 + i], "AggMAC byte " + i);
      }
    }
  }

  @Test
  void testRejectsUnsuitablePaths() {
    // A destination in the local AS: no hops, nothing to reserve.
    RequestPath local =
        PackageVisibilityHelper.createDummyPath(
            ScionUtil.parseIA("1-ff00:0:112"),
            DST_ISD_AS,
            new byte[] {127, 0, 0, 1},
            8080,
            new byte[0],
            new InetSocketAddress("127.0.0.1", 31000));
    assertThrows(IllegalArgumentException.class, () -> new ReservedPath(local));

    // Its bytes are Hummingbird already, wrapping it would convert them a second time.
    ReservedPath reserved = new ReservedPath(scionPath());
    assertThrows(IllegalStateException.class, () -> new ReservedPath(reserved));
  }

  /** Sends one packet per payload over the path; the mock underlay records them in sent. */
  private void send(Path path, String... payloads) throws IOException {
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
      for (String payload : payloads) {
        channel.send(ByteBuffer.wrap(payload.getBytes()), path);
      }
    }
    assertEquals(payloads.length, sent.size());
  }

  private static RequestPath scionPath() {
    return PackageVisibilityHelper.createDummyPath(
        ScionUtil.parseIA("1-ff00:0:112"),
        DST_ISD_AS,
        new byte[] {127, 0, 0, 1},
        8080,
        SCION_PATH,
        new InetSocketAddress("127.0.0.1", 31000));
  }

  private static HummingbirdPathRaw parsePath(byte[] packet) {
    ByteBuffer bb = ByteBuffer.wrap(packet);
    bb.position(PATH_START);
    return HummingbirdPathRaw.create(bb);
  }
}
