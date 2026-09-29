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
import org.scion.jpan.internal.header.HummingbirdPacket;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;
import org.scion.jpan.testutil.HummingbirdExamplePacket;

class HummingbirdPacketTest {

  /**
   * The recorded SCION packet has 103 bytes: 48 bytes of common and address header, a 36-byte path
   * (one info field, two hop fields), and 19 bytes of UDP header and payload. HdrLen is 21, that is
   * 84 bytes.
   */
  @Test
  void testFromScion() {
    ByteBuffer in = ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING);
    ByteBuffer out = ByteBuffer.allocate(200);
    HummingbirdPacket.fromScion(in, out, 0, 0, 0);
    out.flip();

    assertEquals(0, in.position()); // the input buffer did not move
    assertEquals(111, out.remaining()); // the path grew by 8 bytes
    assertEquals(23, out.get(5)); // HdrLen: 92 bytes / 4
    assertEquals(5, out.get(8)); // PathType Hummingbird

    // Common and address header unchanged, except HdrLen and PathType
    for (int i = 0; i < 48; i++) {
      if (i != 5 && i != 8) {
        assertEquals(in.get(i), out.get(i), "Mismatch at position " + i);
      }
    }
    // UDP header and payload unchanged, 8 bytes further back
    for (int i = 84; i < 103; i++) {
      assertEquals(in.get(i), out.get(i + 8), "Mismatch at position " + i);
    }

    // The new path is a Hummingbird path with the same hop fields and no flyover
    ByteBuffer pathBuffer = out.duplicate();
    pathBuffer.position(48);
    HummingbirdPathRaw path = HummingbirdPathRaw.create(pathBuffer);
    assertEquals(44, path.length());
    assertEquals(1, path.getSegmentCount());
    assertEquals(2, path.getHopFieldCount());
    assertFalse(path.getHopField(0).isFlyover());
    assertFalse(path.getHopField(1).isFlyover());
  }

  /** HdrLen and PathType are set relative to where the packet starts, not at index 5 and 8. */
  @Test
  void testFromScionWritesAtPosition() {
    ByteBuffer in = ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING);
    ByteBuffer out = ByteBuffer.allocate(200);
    out.position(10);
    HummingbirdPacket.fromScion(in, out, 0, 0, 0);

    assertEquals(10 + 111, out.position());
    assertEquals(0, out.get(5)); // untouched
    assertEquals(23, out.get(10 + 5));
    assertEquals(5, out.get(10 + 8));
  }

  /**
   * BaseTS and HighResTS follow the first word of the meta header. Stamped with the clock of the Go
   * capture, they must be the capture's bytes.
   */
  @Test
  void testFromScionTimestamps() {
    ByteBuffer in = ByteBuffer.wrap(ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING);
    ByteBuffer out = ByteBuffer.allocate(200);
    HummingbirdPacket.fromScion(in, out, 1786710247L, 72, 4);

    assertEquals(0x6a7f08e7, out.getInt(48 + 4)); // BaseTS 1786710247
    assertEquals(0x12000004, out.getInt(48 + 8)); // HighResTS: 72 ms << 22 | counter 4

    // The same 8 bytes in the Go capture, whose path starts at 36 (IPv4 destination).
    ByteBuffer go = ByteBuffer.wrap(HummingbirdExamplePacket.PACKET_BYTES_HBIRD_112_111);
    assertEquals(go.getInt(36 + 4), out.getInt(48 + 4));
    assertEquals(go.getInt(36 + 8), out.getInt(48 + 8));
  }

  /**
   * HdrLen has 8 bits of 4-byte units, so no header can be longer than 1020 bytes. A header of 1016
   * bytes (HdrLen 254) would need 1024 as Hummingbird header, HdrLen 256, which a byte cast would
   * silently turn into 0. Real SCION headers from JPAN are much shorter; flyovers will make this
   * reachable. The packet is the example packet with its path padded by zeros.
   */
  @Test
  void testFromScionHeaderTooLong() {
    byte[] example = ExamplePacket.PACKET_BYTES_CLIENT_E2E_PING;
    byte[] packet = new byte[1016 + 19]; // header, then UDP header and payload
    System.arraycopy(example, 0, packet, 0, 84); // common and address header, 36-byte path
    System.arraycopy(example, 84, packet, 1016, 19); // UDP header and payload
    packet[5] = (byte) (1016 / 4); // HdrLen 254

    ByteBuffer out = ByteBuffer.allocate(2000);
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () -> HummingbirdPacket.fromScion(ByteBuffer.wrap(packet), out, 0, 0, 0));
    assertTrue(e.getMessage().contains("HdrLen"), e.getMessage());
  }
}
