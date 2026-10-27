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

  /**
   * The ReversePath option a Go client sends along for this reply: flyovers for AS 111, 110 and
   * 112. Made by the Go prototype's BuildReverseReservationExtn, from corpus/golden/
   * reverse_reservation_extn.json in hbird-conformance. Its Aks are synthetic.
   */
  static final byte[] REVERSE_RESERVATION =
      fromHex(
          "040b0b0001ff00000001110000002900000464101112131415161718191a1b1c1d1e1f6a7f08b8003c0000"
              + "0001ff00000001100001000200000864202122232425262728292a2b2c2d2e2f6a7f08b8003c0000"
              + "0001ff00000001120001000000000c64303132333435363738393a3b3c3d3e3f6a7f08b8003c0000"
              + "a9be1d3a07e6c47cf0e2eb515d7b73e0d131e3fcf2dbcd93");

  /** The same, but AS 110 is listed without a flyover: 12 bytes, and its flyover bit is 0. */
  private static final byte[] REVERSE_RESERVATION_NO_FLYOVER_AT_110 =
      fromHex(
          "040b090001ff00000001110000002900000464101112131415161718191a1b1c1d1e1f6a7f08b8003c0000"
              + "0001ff0000000110000100020001ff00000001120001000000000c64303132333435363738393a3b3c"
              + "3d3e3f6a7f08b8003c0000a9be1d3a07e6c47cf0e2eb515d7b73e0d131e3fcf2dbcd93");

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
    // The interfaces in the direction of the reply: 111 0>41, 110 1>2 across the crossover, 112 1>0
    flyovers[0] = new Flyover(0, 41, 1, 100, start, 60, AK);
    flyovers[1] = new Flyover(1, 2, 2, 100, start, 60, AK);
    flyovers[3] = new Flyover(1, 0, 4, 100, start, 60, AK);
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

      // AggMAC = SCION MAC XOR the first 6 bytes of Vk, as in HummingbirdRequestPathTest
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
    Flyover[] at111 = new Flyover[4];
    at111[0] = new Flyover(0, 41, 1, 100, 0, 60, AK);
    HummingbirdResponsePath path = reply().withFlyovers(at111);
    // Its bytes have flyovers already, inserting them again would shift the hop fields.
    assertThrows(IllegalStateException.class, () -> path.withFlyovers(new Flyover[4]));
    // A flyover for interfaces this path does not use: its Ak would not match at the router.
    Flyover[] wrongInterfaces = new Flyover[4];
    wrongInterfaces[1] = new Flyover(2, 1, 1, 100, 0, 60, AK);
    assertThrows(IllegalArgumentException.class, () -> reply().withFlyovers(wrongInterfaces));
    // Fewer slots than hop fields.
    assertThrows(IllegalArgumentException.class, () -> reply().withFlyovers(new Flyover[3]));
  }

  /** Each flyover lands in its hop field's slot, with the values Go wrote. */
  @Test
  void testParseReverseReservation() {
    Flyover[] flyovers = HummingbirdResponsePath.parseReverseReservation(REVERSE_RESERVATION);
    assertEquals(4, flyovers.length);
    assertNull(flyovers[2]); // AS 110's second hop field
    int[][] interfaces = {{0, 41}, {1, 2}, null, {1, 0}};
    int resID = 1;
    for (int i : new int[] {0, 1, 3}) {
      Flyover f = flyovers[i];
      assertEquals(interfaces[i][0], f.getIngress(), "hop " + i);
      assertEquals(interfaces[i][1], f.getEgress(), "hop " + i);
      assertEquals(resID, f.getResID(), "hop " + i);
      assertEquals(100, f.getBw());
      assertEquals(1786710200L, f.getStartTime());
      assertEquals(60, f.getDuration());
      for (int j = 0; j < 16; j++) {
        assertEquals((byte) (0x10 * resID + j), f.getAk()[j], "hop " + i + ", Ak byte " + j);
      }
      resID++;
    }
  }

  /** A hop listed without a flyover gets none, and the hop after it is still read correctly. */
  @Test
  void testParseReverseReservationWithoutFlyoverAt110() {
    Flyover[] flyovers =
        HummingbirdResponsePath.parseReverseReservation(REVERSE_RESERVATION_NO_FLYOVER_AT_110);
    assertEquals(4, flyovers.length);
    assertNull(flyovers[1]);
    assertNull(flyovers[2]);
    assertEquals(1, flyovers[0].getResID());
    assertEquals(3, flyovers[3].getResID());
    assertEquals((byte) 0x3f, flyovers[3].getAk()[15]);
    assertEquals(60, flyovers[3].getDuration());
  }

  /**
   * The reply gets the offered flyovers. Only the template is checked: writing a packet needs a
   * reservation that started less than 65536 s ago, and this one is from the recorded packet.
   */
  @Test
  void testAcceptReverseReservation() {
    HummingbirdResponsePath received = reply();
    HummingbirdResponsePath path = received.acceptReverseReservation(REVERSE_RESERVATION);
    HummingbirdPathRaw template = HummingbirdPathRaw.create(ByteBuffer.wrap(path.getRawPath()));
    assertFalse(template.getHopField(2).isFlyover());
    int resID = 1;
    for (int i : new int[] {0, 1, 3}) {
      assertTrue(template.getHopField(i).isFlyover(), "hop " + i);
      assertEquals(resID++, template.getHopField(i).getResID(), "hop " + i);
    }
    assertArrayEquals(REPLY, received.getRawPath());

    // An option for 3 hop fields does not fit this path's 4.
    byte[] wrongCount = REVERSE_RESERVATION.clone();
    wrongCount[0] = 3;
    // It comes from the network: the reply then goes without flyovers instead of failing.
    assertSame(received, received.acceptReverseReservation(wrongCount));
  }

  /** A copy goes to another host or port in the same AS and keeps stamping the flyovers. */
  @Test
  void testCopy() {
    long start = System.currentTimeMillis() / 1000 - 10;
    Flyover[] flyovers = new Flyover[4];
    flyovers[0] = new Flyover(0, 41, 1, 100, start, 60, AK);
    HummingbirdResponsePath path = reply().withFlyovers(flyovers);
    HummingbirdResponsePath copy =
        (HummingbirdResponsePath) path.copy(InetAddress.getLoopbackAddress(), 40001);
    assertEquals(40001, copy.getRemotePort());
    assertEquals(5, copy.getPathType());
    assertArrayEquals(path.getRawPath(), copy.getRawPath());
    // One authenticator for both: the counter goes on from the original to the copy.
    int first = HummingbirdPathRaw.create(ByteBuffer.wrap(write(path, 100))).getCounter();
    int second = HummingbirdPathRaw.create(ByteBuffer.wrap(write(copy, 100))).getCounter();
    assertEquals(first + 1, second);
  }

  /** A cache keeps a running reservation against later packets without one. */
  @Test
  void testKeepsReservation() {
    long start = 1786710200L;
    Flyover[] flyovers = new Flyover[4];
    flyovers[0] = new Flyover(0, 41, 1, 100, start, 60, AK);
    flyovers[3] = new Flyover(1, 0, 4, 100, start, 30, AK);
    HummingbirdResponsePath plain = reply();
    HummingbirdResponsePath reserved = plain.withFlyovers(flyovers);
    assertEquals(0, plain.getReservationExpiry());
    assertEquals(start + 30, reserved.getReservationExpiry()); // the first flyover to end

    assertTrue(HummingbirdResponsePath.keepsReservation(reserved, plain, start + 10));
    // Replaced when it has expired, by a new reservation, or when nothing is cached yet
    assertFalse(HummingbirdResponsePath.keepsReservation(reserved, plain, start + 30));
    assertFalse(HummingbirdResponsePath.keepsReservation(reserved, reserved, start + 10));
    assertFalse(HummingbirdResponsePath.keepsReservation(null, plain, start));
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

  private static byte[] fromHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
