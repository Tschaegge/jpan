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

import java.net.InetAddress;
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
 * Checks the path bytes a HummingbirdRequestPath writes for each packet: fresh timestamps and, for
 * a flyover, its fields and the aggregated MAC. That the channel hands writePath the right packet
 * length is tested in PathSubclassTest, and the same with real routers is {@code make live} in
 * hbird-conformance.
 */
class HummingbirdRequestPathTest {

  private static final long DST_ISD_AS = ScionUtil.parseIA("1-ff00:0:111");

  /** The path of the recorded example packet: one info field and two hop fields, 36 bytes. */
  private static final byte[] SCION_PATH =
      Arrays.copyOfRange(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING, 48, 84);

  // Hop field 0 follows the 12-byte meta header and one 8-byte info field.
  private static final int HOP_0 = 12 + 8;

  private static final byte[] AK = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

  @Test
  void testNoFlyover() {
    HummingbirdRequestPath path = new HummingbirdRequestPath(scionPath(), new Flyover[0]);
    assertEquals(5, path.getPathType()); // Hummingbird
    assertEquals(36 + 8, path.getRawPath().length); // the meta header grows from 4 to 12 bytes
  }

  /** Every packet carries the time it was sent, and the counter goes up by one per packet. */
  @Test
  void testTimestamps() {
    HummingbirdRequestPath path = new HummingbirdRequestPath(scionPath(), new Flyover[0]);
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
    Flyover flyover = new Flyover(0, 2, 1, 1023, start, 60, AK); // the path leaves through 2
    HummingbirdRequestPath path =
        new HummingbirdRequestPath(scionPath(), new Flyover[] {flyover, null});
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
    assertThrows(
        IllegalArgumentException.class, () -> new HummingbirdRequestPath(local, new Flyover[0]));

    // Its bytes are Hummingbird already, wrapping it would convert them a second time.
    HummingbirdRequestPath reserved = new HummingbirdRequestPath(scionPath(), new Flyover[0]);
    assertThrows(
        IllegalStateException.class, () -> new HummingbirdRequestPath(reserved, new Flyover[0]));
  }

  /**
   * At a segment change, a flyover belongs in the first hop field of its AS (Appendix A.5). On the
   * tiny4 path 112 -> 110 -> 111, AS 110 has hop fields 1 and 2, both traversed as 2>1.
   */
  @Test
  void testRejectsMisplacedFlyovers() {
    Flyover[] flyovers = new Flyover[4];
    flyovers[2] = new Flyover(2, 1, 1, 100, 0, 60, AK);
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new HummingbirdRequestPath(tiny4Path(), flyovers));
    assertEquals("Flyover 2 belongs in slot 1, the first hop field of its AS", e.getMessage());

    flyovers[1] = flyovers[2];
    flyovers[2] = null;
    assertDoesNotThrow(() -> new HummingbirdRequestPath(tiny4Path(), flyovers));
  }

  /** The path bytes the HummingbirdRequestPath writes for one packet of the given length. */
  private static byte[] write(HummingbirdRequestPath path, int packetLength) {
    ByteBuffer buffer = ByteBuffer.allocate(path.getRawPath().length);
    path.writePath(buffer, packetLength);
    assertFalse(buffer.hasRemaining(), "writePath must write exactly getRawPath().length bytes");
    return buffer.array();
  }

  /**
   * The SCION path 112 -> 110 -> 111 of the packet captured on tiny4: the info and hop fields of
   * HummingbirdExamplePacket.PATH_RAW_HBIRD_112_111, in SCION's format.
   */
  private static final byte[] TINY4_PATH =
      fromHex(
          "000020800000bbfe6a7f08be0100e85c6a7f08b9003f00010000e3fcf2dbcd93003f000000025d7b73e0d131"
              + "003f00000001c47cf0e2eb51003f00290000a9be1d3a07e6");

  /** A copy goes to another host or port with the same path and authenticator. */
  @Test
  void testCopy() {
    HummingbirdRequestPath path = new HummingbirdRequestPath(tiny4Path(), new Flyover[4]);
    HummingbirdRequestPath copy =
        (HummingbirdRequestPath) path.copy(InetAddress.getLoopbackAddress(), 9999);
    assertEquals(9999, copy.getRemotePort());
    assertEquals(5, copy.getPathType());
    // One authenticator for both: the counter goes on from the original to the copy.
    int first = HummingbirdPathRaw.create(ByteBuffer.wrap(write(path, 100))).getCounter();
    int second = HummingbirdPathRaw.create(ByteBuffer.wrap(write(copy, 100))).getCounter();
    assertEquals(first + 1, second);
  }

  private static RequestPath tiny4Path() {
    return PackageVisibilityHelper.createDummyPath(
        ScionUtil.parseIA("1-ff00:0:112"),
        DST_ISD_AS,
        new byte[] {127, 0, 0, 1},
        8080,
        TINY4_PATH,
        new InetSocketAddress("127.0.0.1", 31000));
  }

  private static byte[] fromHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
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
