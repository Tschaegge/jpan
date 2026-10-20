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
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.HummingbirdExamplePacket;

/**
 * Checks the reply path in AS 111 to the packet the Go prototype sent over 112 -> 110 -> 111, with
 * and without flyovers for the reply. The same with real routers is {@code make live} in
 * hbird-conformance.
 */
class HummingbirdResponsePathTest {

  private static final long IA_111 = ScionUtil.parseIA("1-ff00:0:111");
  private static final long IA_112 = ScionUtil.parseIA("1-ff00:0:112");

  /** The reversed path, as the parser hands it to the reply: four hop fields, no flyovers. */
  private static final byte[] REPLY =
      HummingbirdPathConverter.reverse(HummingbirdExamplePacket.PATH_RAW_HBIRD_112_111);

  private static final byte[] AK = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

  /** Without flyovers the reply is written as it was received. */
  @Test
  void testWithoutFlyovers() {
    assertArrayEquals(REPLY, write(reply(), 100));
  }

  /**
   * A flyover at every AS: each such hop field carries the reservation and the aggregated MAC over
   * this packet, keyed for the reply's destination, AS 112. AS 110 has two hop fields, its flyover
   * goes on the first one (Appendix A.5).
   */
  @Test
  void testWithFlyovers() {
    long start = System.currentTimeMillis() / 1000 - 10;
    Flyover[] flyovers = new Flyover[4];
    for (int i : new int[] {0, 1, 3}) {
      flyovers[i] = new Flyover(0, 0, i + 1, 100, start, 60, AK);
    }
    HummingbirdResponsePath received = reply();
    HummingbirdResponsePath path = received.withFlyovers(flyovers);
    assertEquals(5, path.getPathType());
    assertEquals(REPLY.length + 3 * 8, path.getRawPath().length); // 8 more per flyover

    HummingbirdPathRaw before = HummingbirdPathRaw.create(ByteBuffer.wrap(REPLY));
    assertEquals(4, before.getHopFieldCount());
    ByteBuffer bb = ByteBuffer.wrap(write(path, 100));
    HummingbirdPathRaw after = HummingbirdPathRaw.create(bb);
    int highResTs = bb.getInt(8);
    assertFalse(after.getHopField(2).isFlyover());
    for (int i : new int[] {0, 1, 3}) {
      assertTrue(after.getHopField(i).isFlyover());
      assertEquals(i + 1, after.getHopField(i).getResID());
      int resStartOffset = after.getHopField(i).getResStartOffset();

      // AggMAC = SCION MAC XOR the first 6 bytes of Vk, as in ReservedPathTest.testFlyover().
      int scionMac = before.getHopFieldOffset(i) + 6;
      int aggMac = after.getHopFieldOffset(i) + 6;
      byte[] vk = new HummingbirdMac(AK).flyoverMac(IA_112, 100, resStartOffset, highResTs);
      for (int j = 0; j < HummingbirdMac.MAC_LEN; j++) {
        assertEquals((byte) (REPLY[scionMac + j] ^ vk[j]), bb.get(aggMac + j), "hop " + i);
      }
    }

    // The received path is unchanged.
    assertArrayEquals(REPLY, received.getRawPath());
  }

  @Test
  void testRejects() {
    HummingbirdResponsePath path = reply().withFlyovers(new Flyover[4]);
    // Its bytes have flyovers already, inserting them again would shift the hop fields.
    assertThrows(IllegalStateException.class, () -> path.withFlyovers(new Flyover[4]));
    // A copy would have the flyovers but no authenticator.
    assertThrows(
        UnsupportedOperationException.class, () -> path.copy(InetAddress.getLoopbackAddress(), 1));
  }

  /** The path bytes written for one packet of the given length. */
  private static byte[] write(HummingbirdResponsePath path, int packetLength) {
    ByteBuffer buffer = ByteBuffer.allocate(path.getRawPath().length);
    path.writePath(buffer, packetLength);
    assertFalse(buffer.hasRemaining(), "writePath must write exactly getRawPath().length bytes");
    return buffer.array();
  }

  private static HummingbirdResponsePath reply() {
    InetAddress localhost = InetAddress.getLoopbackAddress();
    return new HummingbirdResponsePath(
        REPLY.clone(),
        IA_111,
        localhost,
        32000,
        IA_112,
        localhost,
        40000,
        new InetSocketAddress("127.0.0.17", 31008));
  }
}
