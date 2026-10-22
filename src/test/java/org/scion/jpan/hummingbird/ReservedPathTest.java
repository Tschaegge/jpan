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
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.MockDatagramChannel;

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

  /** Byte for byte what the Go prototype writes, the vector HummingbirdResponsePathTest parses. */
  @Test
  void testSerializeReverseReservation() {
    byte[] go = HummingbirdResponsePathTest.REVERSE_RESERVATION;
    Flyover[] flyovers = new Flyover[4];
    flyovers[0] = new Flyover(0, 41, 1, 100, 1786710200L, 60, goAk(1));
    flyovers[1] = new Flyover(1, 2, 2, 100, 1786710200L, 60, goAk(2));
    flyovers[3] = new Flyover(1, 0, 3, 100, 1786710200L, 60, goAk(3));
    long[] ias = {
      ScionUtil.parseIA("1-ff00:0:111"),
      ScionUtil.parseIA("1-ff00:0:110"),
      ScionUtil.parseIA("1-ff00:0:110"),
      ScionUtil.parseIA("1-ff00:0:112")
    };
    byte[][] macs = new byte[4][];
    for (int i = 0; i < 4; i++) {
      macs[i] = Arrays.copyOfRange(go, go.length - 24 + 6 * i, go.length - 18 + 6 * i);
    }
    assertArrayEquals(go, ReservedPath.serializeReverseReservation(flyovers, ias, macs));
  }

  /** The synthetic Ak of the Go vector: 0x10 * n, 0x10 * n + 1, ... */
  private static byte[] goAk(int n) {
    byte[] ak = new byte[16];
    for (int j = 0; j < 16; j++) {
      ak[j] = (byte) (0x10 * n + j);
    }
    return ak;
  }

  /**
   * The SCION path 112 -> 110 -> 111 the Go sender started from, the path of the Go vector:
   * forwardScionPath in corpus/golden/reverse_reservation_extn.json of hbird-conformance.
   */
  private static final byte[] TINY4_PATH =
      fromHex(
          "000020800000bbfe6a7f08be0100e85c6a7f08b9003f00010000e3fcf2dbcd93003f000000025d7b73e0d131"
              + "003f00000001c47cf0e2eb51003f00290000a9be1d3a07e6");

  /** The reverse flyovers of the Go vector, for 111 -> 110 -> 112. */
  private static Flyover[] goReverseFlyovers() {
    Flyover[] reverse = new Flyover[4];
    reverse[0] = new Flyover(0, 41, 1, 100, 1786710200L, 60, goAk(1));
    reverse[1] = new Flyover(1, 2, 2, 100, 1786710200L, 60, goAk(2));
    reverse[3] = new Flyover(1, 0, 3, 100, 1786710200L, 60, goAk(3));
    return reverse;
  }

  /** The option goes once, and carries the flyovers and the SCION MACs of the reverse path. */
  @Test
  void testReverseReservation() {
    ReservedPath forward = new ReservedPath(tiny4Path(), new Flyover[4]);
    ReservedPath path = forward.withReverseReservation(goReverseFlyovers());

    byte[] option = path.getEndToEndOptions();
    assertEquals(3, option[0]); // the Go prototype's ReversePath
    assertEquals(147, option[1] & 0xFF);
    byte[] data = Arrays.copyOfRange(option, 2, option.length);
    Flyover[] parsed = HummingbirdResponsePath.parseReverseReservation(data);
    assertNull(parsed[2]);
    assertEquals(41, parsed[0].getEgress());
    assertEquals(3, parsed[3].getResID());
    // The reverse path's SCION MACs, as Go wrote them for the same path
    byte[] go = HummingbirdResponsePathTest.REVERSE_RESERVATION;
    assertArrayEquals(
        Arrays.copyOfRange(go, go.length - 24, go.length),
        Arrays.copyOfRange(data, data.length - 24, data.length));

    assertNull(path.getEndToEndOptions()); // only once
    assertNull(forward.getEndToEndOptions()); // the original path has none
  }

  @Test
  void testReverseReservationRejects() {
    ReservedPath forward = new ReservedPath(tiny4Path(), new Flyover[4]);
    Flyover[] wrong = goReverseFlyovers();
    wrong[1] = new Flyover(2, 1, 2, 100, 1786710200L, 60, goAk(2)); // the forward direction
    assertThrows(IllegalArgumentException.class, () -> forward.withReverseReservation(wrong));
    assertThrows(
        IllegalArgumentException.class, () -> forward.withReverseReservation(new Flyover[3]));
  }

  /**
   * Through a channel: the first packet carries the extension header, the second does not, and the
   * flyover MAC of the first covers the packet with its extension header.
   */
  @Test
  void testSendReverseReservation() throws IOException {
    long start = System.currentTimeMillis() / 1000 - 10;
    Flyover[] flyovers = new Flyover[4];
    flyovers[0] = new Flyover(0, 1, 1, 1023, start, 60, AK); // AS 112, 0>1
    ReservedPath path =
        new ReservedPath(tiny4Path(), flyovers).withReverseReservation(goReverseFlyovers());

    List<byte[]> sent = new ArrayList<>();
    MockDatagramChannel mock = MockDatagramChannel.open();
    mock.setSendCallback(
        (buffer, address) -> {
          byte[] packet = new byte[buffer.remaining()];
          buffer.get(packet);
          sent.add(packet);
          return packet.length;
        });
    try (Scion.CloseableService service =
            Scion.newServiceWithTopologyFile("topologies/tiny4/ASff00_0_112/topology.json");
        ScionDatagramChannel channel = ScionDatagramChannel.open(service, mock)) {
      channel.bind(new InetSocketAddress("127.0.0.1", 44444));
      channel.send(ByteBuffer.wrap("hello".getBytes()), path);
      channel.send(ByteBuffer.wrap("hello".getBytes()), path);
    }

    ByteBuffer first = ByteBuffer.wrap(sent.get(0));
    assertEquals(201, first.get(4) & 0xFF); // end-to-end extension
    assertEquals(17, ByteBuffer.wrap(sent.get(1)).get(4)); // UDP: the option went only once
    assertEquals(sent.get(1).length + 152, sent.get(0).length);

    // AS 112's aggregated MAC in the first packet, over the packet length with the extension
    int pathStart = 36; // 12 common header, 16 ISD-AS, 4 + 4 IPv4 addresses
    int hop =
        pathStart
            + HummingbirdPathRaw.create(ByteBuffer.wrap(path.getRawPath())).getHopFieldOffset(0);
    int resStartOffset = first.getShort(hop + 16) & 0xFFFF;
    byte[] vk =
        new HummingbirdMac(AK)
            .flyoverMac(DST_ISD_AS, first.limit(), resStartOffset, first.getInt(pathStart + 8));
    byte[] scionMac = Arrays.copyOfRange(TINY4_PATH, 4 + 16 + 6, 4 + 16 + 12); // hop 0
    for (int i = 0; i < HummingbirdMac.MAC_LEN; i++) {
      assertEquals((byte) (scionMac[i] ^ vk[i]), first.get(hop + 6 + i), "AggMAC byte " + i);
    }
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
