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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.RequestPath;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;

/**
 * Checks the path bytes a ReservedPath writes for each packet: fresh timestamps and, for a flyover,
 * its fields and the aggregated MAC. That the channel hands writePath the right packet length is
 * tested in PathSubclassTest, and the same with real routers is {@code make live} in
 * hbird-conformance.
 */
class ReservedPathTest {

  private static final long DST_ISD_AS = ScionUtil.parseIA("1-ff00:0:111");

  /** The path of the recorded example packet: one info field and two hop fields, 36 bytes. */
  private static final byte[] SCION_PATH =
      Arrays.copyOfRange(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING, 48, 84);

  // Hop field 0 follows the 12-byte meta header and one 8-byte info field.
  private static final int HOP_0 = 12 + 8;

  private static final byte[] AK = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

  @Test
  void testNoFlyover() {
    ReservedPath path = new ReservedPath(scionPath(), new Flyover[0]);
    assertEquals(5, path.getPathType()); // Hummingbird
    assertEquals(36 + 8, path.getRawPath().length); // the meta header grows from 4 to 12 bytes
  }

  /** Every packet carries the time it was sent, and the counter goes up by one per packet. */
  @Test
  void testTimestamps() {
    ReservedPath path = new ReservedPath(scionPath(), new Flyover[0]);
    long before = System.currentTimeMillis() / 1000;
    HummingbirdPathRaw first = HummingbirdPathRaw.create(ByteBuffer.wrap(write(path, 100)));
    HummingbirdPathRaw second = HummingbirdPathRaw.create(ByteBuffer.wrap(write(path, 100)));
    long after = System.currentTimeMillis() / 1000;

    assertTrue(before <= first.getBaseTimestamp() && first.getBaseTimestamp() <= after);
    assertTrue(first.getMillis() < 1000);
    assertEquals(0, first.getCounter()); // a new path starts at 0
    assertEquals(1, second.getCounter());
  }

  /**
   * A flyover at hop 0: the hop field grows to 20 bytes and carries the reservation, the
   * ResStartOffset, and instead of the SCION MAC the aggregated MAC over this very packet.
   */
  @Test
  void testFlyover() {
    long start = System.currentTimeMillis() / 1000 - 10;
    Flyover flyover = new Flyover(0, 1, 1, 1023, start, 60, AK);
    ReservedPath path = new ReservedPath(scionPath(), new Flyover[] {flyover, null});
    assertEquals(36 + 8 + 8, path.getRawPath().length); // 8 more for the flyover

    byte[] scionMac = Arrays.copyOfRange(SCION_PATH, 18, 24); // hop 0 at 4 + 8, its MAC at +6
    // Two packets of different lengths: the MAC covers the length, and the first packet must not
    // overwrite the SCION MAC in the template.
    for (int packetLength : new int[] {100, 1000}) {
      ByteBuffer bb = ByteBuffer.wrap(write(path, packetLength));
      assertEquals((byte) 0x80, (byte) (bb.get(HOP_0) & 0x80)); // flyover bit
      assertEquals((1 << 10) | 1023, bb.getInt(HOP_0 + 12)); // ResID 1, BW 1023
      assertEquals(60, bb.getShort(HOP_0 + 18)); // duration

      int baseTs = bb.getInt(4);
      int highResTs = bb.getInt(8);
      int resStartOffset = bb.getShort(HOP_0 + 16) & 0xFFFF;
      assertEquals(baseTs - start, resStartOffset); // seconds since the reservation started

      // What the router checks: AggMAC = SCION MAC XOR the first 6 bytes of Vk over this packet.
      byte[] vk =
          new HummingbirdMac(AK).flyoverMac(DST_ISD_AS, packetLength, resStartOffset, highResTs);
      for (int i = 0; i < HummingbirdMac.MAC_LEN; i++) {
        assertEquals((byte) (scionMac[i] ^ vk[i]), bb.get(HOP_0 + 6 + i), "AggMAC byte " + i);
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
    assertThrows(IllegalArgumentException.class, () -> new ReservedPath(local, new Flyover[0]));

    // Its bytes are Hummingbird already, wrapping it would convert them a second time.
    ReservedPath reserved = new ReservedPath(scionPath(), new Flyover[0]);
    assertThrows(IllegalStateException.class, () -> new ReservedPath(reserved, new Flyover[0]));
  }

  /** The path bytes the ReservedPath writes for one packet of the given length. */
  private static byte[] write(ReservedPath path, int packetLength) {
    ByteBuffer buffer = ByteBuffer.allocate(path.getRawPath().length);
    path.writePath(buffer, packetLength);
    assertFalse(buffer.hasRemaining(), "writePath must write exactly getRawPath().length bytes");
    return buffer.array();
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
}
