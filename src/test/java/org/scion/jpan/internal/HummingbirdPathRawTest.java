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
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.HummingbirdExamplePacket;

class HummingbirdPathRawTest {

  private static final byte[] pathBytes = HummingbirdExamplePacket.PATH_RAW_HBIRD_112_111;

  @Test
  void testLength() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertEquals(pathBytes.length, path.length());
    assertEquals(100, path.length());
  }

  @Test
  void testMetaHeader() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertEquals(0, path.getCurrINF());
    assertEquals(0, path.getCurrHF());
    assertEquals(10, path.getSegLen(0));
    assertEquals(8, path.getSegLen(1));
    assertEquals(0, path.getSegLen(2));
    assertEquals(2, path.getSegmentCount());
    assertEquals(1786710247L, path.getBaseTimestamp());
  }

  @Test
  void testHighResTimestamp() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertEquals(72, path.getMillis());
    assertEquals(4, path.getCounter());
    assertTrue(path.getMillis() <= 999, "millis is a sub-second value and cannot exceed 999");
  }

  @Test
  void testInfoFields() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertFalse(path.getInfoField(0).hasConstructionDirection());
    assertTrue(path.getInfoField(1).hasConstructionDirection());
    assertEquals(1786710206L, path.getInfoField(0).getTimestamp());
    assertEquals(1786710201L, path.getInfoField(1).getTimestamp());
  }

  
  @Test
  void testHopFieldCountAndStride() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertEquals(4, path.getHopFieldCount());
    assertEquals(20, path.getHopField(0).length());
    assertEquals(20, path.getHopField(1).length());
    assertEquals(12, path.getHopField(2).length());
    assertEquals(20, path.getHopField(3).length());
  }

  @Test
  void testFlyoverFields() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertTrue(path.getHopField(0).isFlyover());
    assertTrue(path.getHopField(1).isFlyover());
    assertFalse(path.getHopField(2).isFlyover());
    assertTrue(path.getHopField(3).isFlyover());

    for (int i : new int[] {0, 1, 3}) {
      HummingbirdPathRaw.FlyoverHopField hop = path.getHopField(i);
      assertEquals(1, hop.getResID(), "hop " + i);
      assertEquals(1023, hop.getBw(), "hop " + i);
      assertEquals(2, hop.getResStartOffset(), "hop " + i);
      assertEquals(9, hop.getResDuration(), "hop " + i);
    }
  }

  @Test
  void testInterfaces() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    assertEquals(1, path.getHopField(0).getIngress());
    assertEquals(0, path.getHopField(0).getEgress());
    assertEquals(0, path.getHopField(1).getIngress());
    assertEquals(2, path.getHopField(1).getEgress());
    assertEquals(0, path.getHopField(2).getIngress());
    assertEquals(1, path.getHopField(2).getEgress());
    assertEquals(41, path.getHopField(3).getIngress());
    assertEquals(0, path.getHopField(3).getEgress());
  }


  @Test
  void testSegmentBoundaryCarriesNoFlyover() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBytes);
    int lines = 0;
    int firstHopOfSeg1 = -1;
    for (int i = 0; i < path.getHopFieldCount(); i++) {
      if (lines == path.getSegLen(0)) {
        firstHopOfSeg1 = i;
        break;
      }
      lines += path.getHopField(i).length() / HummingbirdPathRaw.LINE_LEN;
    }
    assertEquals(2, firstHopOfSeg1);
    assertFalse(path.getHopField(firstHopOfSeg1).isFlyover());
  }

  /** An empty raw path is not an error; PathRawParser treats it the same way. */
  @Test
  void testEmptyPath() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(new byte[0]);
    assertEquals(0, path.length());
    assertEquals(0, path.getHopFieldCount());
    assertEquals(0, path.getSegmentCount());
  }


  @Test
  void testSegLenMismatchIsRejected() {
    byte[] bad = pathBytes.clone();
    bad[2] = (byte) 0x83;
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> HummingbirdPathRaw.create(bad));
    assertTrue(e.getMessage().contains("18 lines"), e.getMessage());
  }
}
