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

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.HummingbirdExamplePacket;

public class HummingbirdConverterTest {

  private static final byte[] scionPathUCD = ExamplePacket.PATH_RAW_UP_CORE_DOWN;
  private static final byte[] scionPathtiny = ExamplePacket.PATH_RAW_TINY_110_112;
  private static final byte[] hbirdPath = HummingbirdExamplePacket.PATH_RAW_HBIRD_112_111;

  @Test
  void testConvertUpCoreDown() {
    byte[] out = HummingbirdPathConverter.convertFromScion(scionPathUCD, 1786710247L, 72, 4);
    HummingbirdPathRaw h = HummingbirdPathRaw.create(ByteBuffer.wrap(out));
    assertEquals(120, h.length());
    assertEquals(6, h.getSegLen(0));
    assertEquals(9, h.getSegLen(1));
    assertEquals(6, h.getSegLen(2));
    assertEquals(3, h.getSegmentCount());
    assertEquals(7, h.getHopFieldCount());
    assertEquals(1786710247L, h.getBaseTimestamp());
    assertEquals(72, h.getMillis());
    assertEquals(4, h.getCounter());
    int[] expected = {0, 0, 1, 1, 1, 2, 2};
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], h.getSegmentIndex(i), "hop " + i);
    }
    assertEquals(2, h.getSegmentHopCount(0));
    assertEquals(3, h.getSegmentHopCount(1));
    assertEquals(2, h.getSegmentHopCount(2));
    assertEquals(0, h.getFirstHopOfSegment(0));
    assertEquals(2, h.getFirstHopOfSegment(1));
    assertEquals(5, h.getFirstHopOfSegment(2));
  }

  @Test
  void testConvertTiny() {
    byte[] out = HummingbirdPathConverter.convertFromScion(scionPathtiny, 1786710247L, 72, 4);
    HummingbirdPathRaw h = HummingbirdPathRaw.create(ByteBuffer.wrap(out));
    assertEquals(44, h.length());
    assertEquals(6, h.getSegLen(0));
    assertEquals(0, h.getSegLen(1));
    assertEquals(0, h.getSegLen(2));
    assertEquals(1, h.getSegmentCount());
    assertEquals(2, h.getHopFieldCount());
    assertEquals(1786710247L, h.getBaseTimestamp());
    assertEquals(72, h.getMillis());
    assertEquals(4, h.getCounter());
    assertEquals(0, h.getSegmentIndex(0));
    assertEquals(0, h.getSegmentIndex(1));
    assertEquals(2, h.getSegmentHopCount(0));
    assertEquals(0, h.getSegmentHopCount(1));
    assertEquals(0, h.getSegmentHopCount(2));
    assertEquals(0, h.getFirstHopOfSegment(0));
    assertEquals(-1, h.getFirstHopOfSegment(1));
    assertEquals(-1, h.getFirstHopOfSegment(2));
  }

  /**
   * Strip the three flyovers off the captured packet and write them back: byte for byte the same.
   */
  @Test
  void testRemoveAndInsertFlyoversRoundTrip() {
    byte[] stripped = HummingbirdPathConverter.removeFlyovers(hbirdPath);
    assertEquals(76, stripped.length);
    HummingbirdPathRaw s = HummingbirdPathRaw.create(ByteBuffer.wrap(stripped));
    assertEquals(6, s.getSegLen(0));
    assertEquals(6, s.getSegLen(1));
    for (int i = 0; i < 4; i++) {
      assertFalse(s.getHopField(i).isFlyover(), "hop " + i);
    }

    // The capture carries resID 1, bw 1023, offset 2, duration 9 on hop fields 0, 1 and 3.
    byte[] out = stripped;
    for (int hop : new int[] {0, 1, 3}) {
      out = HummingbirdPathConverter.insertFlyover(out, hop, 1, 1023, 2, 9);
    }
    assertArrayEquals(hbirdPath, out);
  }

  /** Inserting twice would grow the hop field a second time, so the second call is rejected. */
  @Test
  void testInsertFlyoverOnExistingFlyoverIsRejected() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> HummingbirdPathConverter.insertFlyover(hbirdPath, 0, 1, 1023, 2, 9));
    assertTrue(e.getMessage().contains("already has a flyover"), e.getMessage());
  }

  /** Hop field 2 is the first of the down segment; Appendix A.5 forbids a flyover there. */
  @Test
  void testInsertFlyoverOnSegmentBoundaryIsRejected() {
    byte[] stripped = HummingbirdPathConverter.removeFlyovers(hbirdPath);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> HummingbirdPathConverter.insertFlyover(stripped, 2, 1, 1023, 2, 9));
    assertTrue(e.getMessage().contains("A.5"), e.getMessage());
  }

  /** Behind a peering link the first hop field of the next segment is a real AS: reservable. */
  @Test
  void testInsertFlyoverAfterPeeringLinkIsAccepted() {
    byte[] peering = HummingbirdExamplePacket.PATH_RAW_HBIRD_PEERING_DOWNSTREAM;
    byte[] out = HummingbirdPathConverter.insertFlyover(peering, 1, 1, 1023, 2, 9);
    HummingbirdPathRaw p = HummingbirdPathRaw.create(ByteBuffer.wrap(out));
    assertEquals(peering.length + 8, out.length);
    assertTrue(p.getHopField(1).isFlyover());
    assertEquals(3, p.getSegLen(0));
    assertEquals(9 + 2, p.getSegLen(1)); // the segment of hop field 1 grew by two lines
  }

  @Test
  void testInsertFlyoverRejectsBadArguments() {
    byte[] stripped = HummingbirdPathConverter.removeFlyovers(hbirdPath);
    // bw is a 10-bit field, 1024 does not fit
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdPathConverter.insertFlyover(stripped, 0, 1, 1024, 2, 9));
    // the path has hop fields 0..3
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdPathConverter.insertFlyover(stripped, 4, 1, 1023, 2, 9));
  }

  @Test
  void testConvertFromScionRejectsBadTimestamp() {
    // millis is a 10-bit field
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdPathConverter.convertFromScion(scionPathtiny, 0, 1 << 10, 0));
    // counter is a 22-bit field, 1 << 22 would overwrite the lowest millis bit
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdPathConverter.convertFromScion(scionPathtiny, 0, 0, 1 << 22));
  }

  // What the reference's Decoded.Reverse (scion-hummingbird decoded.go:160-197) makes of the same
  // input bytes, computed with a small Go program. Meta header, info fields, then the hop fields.
  private static final String REVERSED_112_111 =
      "424183006a7f08e712000004"
          + "0000e85c6a7f08b9"
          + "0100bbfe6a7f08be"
          + "003f00290000a9be1d3a07e6"
          + "003f00000001c47cf0e2eb51"
          + "003f000000025d7b73e0d131"
          + "003f00010000e3fcf2dbcd93";
  private static final String REVERSED_PEERING =
      "00c241806a8ff6917d000000"
          + "020002226a8ff691"
          + "030001116a8ff691"
          + "000001ff0000000000000000"
          + "000000790097580df0563dc0"
          + "000000790000000000000000"
          + "000000d30000000000000000";

  /**
   * The reply path to 112 -> 110 -> 111: flyovers gone, AS 111's hop field first, ConsDir flipped
   * in both info fields. The bytes must equal what the reference makes of the same input.
   */
  @Test
  void testReverse() {
    byte[] out = HummingbirdPathConverter.reverse(hbirdPath);
    assertArrayEquals(fromHex(REVERSED_112_111), out);

    HummingbirdPathRaw r = HummingbirdPathRaw.create(ByteBuffer.wrap(out));
    assertEquals(76, r.length()); // 100 bytes minus 8 for each of the three flyovers
    assertEquals(6, r.getSegLen(0));
    assertEquals(6, r.getSegLen(1));
    assertEquals(1, r.getCurrINF()); // the input is a sender's path, CurrINF 0 and CurrHF 0
    assertEquals(9, r.getCurrHF());
    assertFalse(r.getInfoField(0).hasConstructionDirection()); // was segment 1, ConsDir 1
    assertTrue(r.getInfoField(1).hasConstructionDirection());
    assertEquals(41, r.getHopField(0).getIngress()); // AS 111 comes first now
    assertEquals(1, r.getHopField(3).getIngress()); // AS 112 last
    for (int i = 0; i < r.getHopFieldCount(); i++) {
      assertFalse(r.getHopField(i).isFlyover(), "hop " + i);
    }
  }

  /** Segments of different lengths: 3 and 9 lines become 9 and 3. On tiny4 both are 6. */
  @Test
  void testReverseSwapsSegmentLengths() {
    byte[] out =
        HummingbirdPathConverter.reverse(
            HummingbirdExamplePacket.PATH_RAW_HBIRD_PEERING_DOWNSTREAM);
    assertArrayEquals(fromHex(REVERSED_PEERING), out);

    HummingbirdPathRaw r = HummingbirdPathRaw.create(ByteBuffer.wrap(out));
    assertEquals(9, r.getSegLen(0));
    assertEquals(3, r.getSegLen(1));
    assertEquals(0, r.getCurrINF()); // the input has CurrINF 1 and CurrHF 6
    assertEquals(3, r.getCurrHF());
  }

  /** Reversing twice gives the input back, only without its flyovers. */
  @Test
  void testReverseTwice() {
    for (byte[] path :
        new byte[][] {hbirdPath, HummingbirdExamplePacket.PATH_RAW_HBIRD_PEERING_DOWNSTREAM}) {
      byte[] twice = HummingbirdPathConverter.reverse(HummingbirdPathConverter.reverse(path));
      assertArrayEquals(HummingbirdPathConverter.removeFlyovers(path), twice);
    }
  }

  private static byte[] fromHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
