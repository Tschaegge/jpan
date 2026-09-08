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
    HummingbirdPathRaw h = HummingbirdPathRaw.create(out);
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
      assertEquals(expected[i], h.getInfoFieldIndex(i), "hop " + i);
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
    HummingbirdPathRaw h = HummingbirdPathRaw.create(out);
    assertEquals(44, h.length());
    assertEquals(6, h.getSegLen(0));
    assertEquals(0, h.getSegLen(1));
    assertEquals(0, h.getSegLen(2));
    assertEquals(1, h.getSegmentCount());
    assertEquals(2, h.getHopFieldCount());
    assertEquals(1786710247L, h.getBaseTimestamp());
    assertEquals(72, h.getMillis());
    assertEquals(4, h.getCounter());
    assertEquals(0, h.getInfoFieldIndex(0));
    assertEquals(0, h.getInfoFieldIndex(1));
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
    HummingbirdPathRaw s = HummingbirdPathRaw.create(stripped);
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
}
