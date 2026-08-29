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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;

public class HummingbirdConverterTest {

  private static final byte[] scionPathUCD = ExamplePacket.PATH_RAW_UP_CORE_DOWN;
  private static final byte[] scionPathtiny = ExamplePacket.PATH_RAW_TINY_110_112;

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
}
