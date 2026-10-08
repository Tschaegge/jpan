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
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.HummingbirdExamplePacket;

class HummingbirdPathRawTest {

  private static final byte[] pathBytes = HummingbirdExamplePacket.PATH_RAW_HBIRD_112_111;

  @Test
  void testLength() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    assertEquals(pathBytes.length, path.length());
    assertEquals(100, path.length());
  }

  @Test
  void testMetaHeader() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
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
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    assertEquals(72, path.getMillis());
    assertEquals(4, path.getCounter());
    assertTrue(path.getMillis() <= 999, "millis is a sub-second value and cannot exceed 999");
  }

  @Test
  void testInfoFields() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    assertFalse(path.getInfoField(0).hasConstructionDirection());
    assertTrue(path.getInfoField(1).hasConstructionDirection());
    assertEquals(1786710206L, path.getInfoField(0).getTimestamp());
    assertEquals(1786710201L, path.getInfoField(1).getTimestamp());
  }

  @Test
  void testHopFieldCountAndStride() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    assertEquals(4, path.getHopFieldCount());
    assertEquals(20, path.getHopField(0).length());
    assertEquals(20, path.getHopField(1).length());
    assertEquals(12, path.getHopField(2).length());
    assertEquals(20, path.getHopField(3).length());
  }

  @Test
  void testFlyoverFields() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
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
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
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
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
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

  /**
   * A peering link joins two segments without a crossover: hop fields 0 and 1 are two different
   * ASes. Compare the captured packet, where hop fields 1 and 2 are the same AS and are -1 / +1.
   */
  @Test
  void testPeeringBoundaryIsNoCrossOver() {
    HummingbirdPathRaw peering =
        HummingbirdPathRaw.create(
            ByteBuffer.wrap(HummingbirdExamplePacket.PATH_RAW_HBIRD_PEERING_DOWNSTREAM));
    assertTrue(peering.getInfoField(0).hasPeeringFlag());
    assertTrue(peering.getInfoField(1).hasPeeringFlag());
    assertEquals(1, peering.getSegmentHopCount(0));
    assertEquals(3, peering.getSegmentHopCount(1));
    for (int i = 0; i < peering.getHopFieldCount(); i++) {
      assertEquals(0, peering.getCrossOver(i), "hop " + i);
    }

    HummingbirdPathRaw crossover = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    assertFalse(crossover.getInfoField(0).hasPeeringFlag());
    assertEquals(-1, crossover.getCrossOver(1));
    assertEquals(1, crossover.getCrossOver(2));
  }

  /**
   * The interfaces the router derives Ak from, in travel direction. The path prints as {@code
   * [1-ff00:0:112 1>2 1-ff00:0:110 1>41 1-ff00:0:111]}, and 0 is the side of the end host. AS 110
   * has two hop fields, and at the crossover both get the pair from 110's ingress to its egress.
   */
  @Test
  void testFlyoverInterfaces() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    // AS 112: stored (1, 0); segment 0 runs against construction direction, so swapped.
    assertEquals(0, path.getFlyoverIngress(0));
    assertEquals(1, path.getFlyoverEgress(0));
    // AS 110, last hop field of segment 0: its own ingress 2, the egress 1 of hop field 2.
    assertEquals(2, path.getFlyoverIngress(1));
    assertEquals(1, path.getFlyoverEgress(1));
    // AS 110, first hop field of segment 1: the ingress 2 of hop field 1, its own egress 1.
    assertEquals(2, path.getFlyoverIngress(2));
    assertEquals(1, path.getFlyoverEgress(2));
    // AS 111: stored (41, 0); segment 1 runs in construction direction, so as stored.
    assertEquals(41, path.getFlyoverIngress(3));
    assertEquals(0, path.getFlyoverEgress(3));

    assertThrows(IllegalArgumentException.class, () -> path.getFlyoverIngress(4));
  }

  /**
   * A peering link is no crossover, so every hop field keeps its own interfaces. With the crossover
   * rule, hop fields 0 and 1 would both get (0, 0). Hop field 2 is the one that the reference's
   * HummingbirdFlyoverPeeringDownstream reserves, with Ak over (121, 151).
   */
  @Test
  void testFlyoverInterfacesAtPeering() {
    HummingbirdPathRaw path =
        HummingbirdPathRaw.create(
            ByteBuffer.wrap(HummingbirdExamplePacket.PATH_RAW_HBIRD_PEERING_DOWNSTREAM));
    assertEquals(0, path.getFlyoverIngress(0)); // stored (211, 0), against construction direction
    assertEquals(211, path.getFlyoverEgress(0));
    assertEquals(121, path.getFlyoverIngress(1)); // stored (121, 0), in construction direction
    assertEquals(0, path.getFlyoverEgress(1));
    assertEquals(121, path.getFlyoverIngress(2));
    assertEquals(151, path.getFlyoverEgress(2));
    assertEquals(511, path.getFlyoverIngress(3));
    assertEquals(0, path.getFlyoverEgress(3));
  }

  /** An empty raw path is not an error; PathRawParser treats it the same way. */
  @Test
  void testEmptyPath() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.allocate(0));
    assertEquals(0, path.length());
    assertEquals(0, path.getHopFieldCount());
    assertEquals(0, path.getSegmentCount());
  }

  /**
   * The path can sit anywhere in a buffer, like inside a packet: parsing starts at the position,
   * stops where the meta header says, and leaves the position where it was.
   */
  @Test
  void testCreateFromBufferInsidePacket() {
    byte[] framed = new byte[7 + pathBytes.length + 5]; // 7 bytes before the path, 5 after
    System.arraycopy(pathBytes, 0, framed, 7, pathBytes.length);
    ByteBuffer data = ByteBuffer.wrap(framed);
    data.position(7);

    HummingbirdPathRaw path = HummingbirdPathRaw.create(data);
    assertEquals(pathBytes.length, path.length(), "the 5 trailing bytes are not part of the path");
    assertEquals(7, data.position(), "create must not move the buffer");
    assertEquals(HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes)).toString(), path.toString());
  }

  /** The channel's send and receive buffers are direct buffers, which have no backing array. */
  @Test
  void testCreateFromDirectBuffer() {
    ByteBuffer direct = ByteBuffer.allocateDirect(pathBytes.length);
    direct.put(pathBytes).flip();
    HummingbirdPathRaw path = HummingbirdPathRaw.create(direct);
    assertEquals(HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes)).toString(), path.toString());
  }

  @Test
  void testSegLenMismatchIsRejected() {
    byte[] bad = pathBytes.clone();
    bad[2] = (byte) 0x83;
    ByteBuffer badPath = ByteBuffer.wrap(bad);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> HummingbirdPathRaw.create(badPath));
    assertTrue(e.getMessage().contains("18 lines"), e.getMessage());
  }

  @Test
  void testSegmentMembership() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));

    assertEquals(0, path.getSegmentIndex(0));
    assertEquals(0, path.getSegmentIndex(1));
    assertEquals(1, path.getSegmentIndex(2));
    assertEquals(1, path.getSegmentIndex(3));

    assertEquals(2, path.getSegmentHopCount(0));
    assertEquals(2, path.getSegmentHopCount(1));
    assertEquals(0, path.getSegmentHopCount(2));

    assertEquals(0, path.getFirstHopOfSegment(0));
    assertEquals(2, path.getFirstHopOfSegment(1));
    assertEquals(-1, path.getFirstHopOfSegment(2), "an empty segment has no first hop");
  }

  /** 12 meta + 2 info fields of 8, then hop fields of 20, 20, 12, 20 bytes. */
  @Test
  void testHopFieldOffset() {
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(pathBytes));
    assertEquals(28, path.getHopFieldOffset(0));
    assertEquals(48, path.getHopFieldOffset(1));
    assertEquals(68, path.getHopFieldOffset(2));
    assertEquals(80, path.getHopFieldOffset(3));
    assertThrows(IllegalArgumentException.class, () -> path.getHopFieldOffset(4));
  }
}
