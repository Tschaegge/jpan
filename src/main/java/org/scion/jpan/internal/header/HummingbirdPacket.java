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

package org.scion.jpan.internal.header;

import static org.scion.jpan.internal.util.ByteUtil.checkWidth;

import java.nio.ByteBuffer;

/** Rewrites a finished SCION packet into a Hummingbird packet (SCION path type 5). */
public final class HummingbirdPacket {

  public static final int PATH_TYPE_HUMMINGBIRD = 5;

  private HummingbirdPacket() {}

  /**
   * Writes {@code scionPacket} as a Hummingbird packet without flyovers into {@code hbirdPacket}.
   * The SCION path is replaced by a Hummingbird path, which is 8 bytes longer because the
   * Hummingbird meta header has 12 bytes instead of 4, so HdrLen grows by 2. The bytes behind the
   * path (UDP header and payload) are copied unchanged.
   *
   * <p>Like {@link ScionHeaderParser#write}, this writes at the position of {@code hbirdPacket} and
   * leaves the position behind the packet, so the caller flips the buffer before sending. The
   * position of {@code scionPacket} does not change.
   *
   * @param scionPacket the SCION packet, from its position to its limit
   * @param hbirdPacket the buffer to write the Hummingbird packet into
   * @throws IllegalArgumentException if the packet has no path, or if the Hummingbird header would
   *     be longer than HdrLen can describe (8 bits of 4-byte units, at most 1020 bytes)
   */
  public static void fromScion(ByteBuffer scionPacket, ByteBuffer hbirdPacket) {
    // Own position and limit, and index 0 is the first byte of the packet. The ScionHeaderParser
    // helpers read at fixed indices (4, 5, 8).
    ByteBuffer in = scionPacket.slice();
    int pathStart = ScionHeaderParser.extractPathHeaderPosition(in);
    if (pathStart < 0) {
      throw new IllegalArgumentException("Packet has no path, cannot convert to Hummingbird");
    }
    int pathEnd = ScionHeaderParser.extractHeaderLength(in); // common + address + path header

    // Three windows onto the same bytes: before, on and behind the path.
    ByteBuffer head = in.duplicate();
    head.limit(pathStart);
    ByteBuffer path = in.duplicate();
    path.limit(pathEnd);
    path.position(pathStart);
    ByteBuffer tail = in.duplicate();
    tail.position(pathEnd);

    int start = hbirdPacket.position();
    hbirdPacket.put(head);
    HummingbirdPathConverter.convertFromScion(path, hbirdPacket, 0, 0, 0);
    int newHeaderLength = hbirdPacket.position() - start; // common + address + Hummingbird path
    checkWidth("HdrLen", newHeaderLength / 4, 8); // a cast to byte would silently wrap 256 to 0
    hbirdPacket.put(tail);

    hbirdPacket.put(start + 5, (byte) (newHeaderLength / 4)); // HdrLen counts 4-byte units
    hbirdPacket.put(start + 8, (byte) PATH_TYPE_HUMMINGBIRD); // PathType
  }
}
