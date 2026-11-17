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

import java.nio.ByteBuffer;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;

/**
 * Authenticates every packet sent on a Hummingbird path with flyovers, by writing the fields the
 * routers check: the timestamps, every flyover's ResStartOffset and its aggregated MAC. Shared by
 * the paths that send with reservations.
 */
final class FlyoverAuthenticator {

  private int counter = 0;
  private final Flyover[] flyovers;
  private final HummingbirdMac[] macs; // one per flyover
  private final int[] hopOffsets; // where each flyover's hop field starts in the template

  /**
   * @param template the Hummingbird path with the flyovers already inserted
   * @param flyovers one slot per hop field, null for a hop without reservation
   */
  FlyoverAuthenticator(byte[] template, Flyover[] flyovers) {
    this.flyovers = flyovers.clone();
    this.macs = new HummingbirdMac[flyovers.length];
    this.hopOffsets = new int[flyovers.length];
    // The template never changes, so neither do the positions of its hop fields.
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(template));
    for (int i = 0; i < flyovers.length; i++) {
      if (flyovers[i] != null) {
        macs[i] = new HummingbirdMac(flyovers[i].getAk());
        hopOffsets[i] = path.getHopFieldOffset(i);
      }
    }
  }

  /**
   * Checks that the interfaces of every flyover match the path given in {@code rawPath}, in travel
   * direction, and that at a segment change the flyover is in the first hop field of its AS. An
   * empty array means no flyovers at all and always passes.
   *
   * @throws IllegalArgumentException if they do not fit
   */
  static void checkFlyovers(byte[] rawPath, Flyover[] flyovers) {
    if (flyovers.length == 0) {
      return;
    }
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(rawPath));
    if (flyovers.length != path.getHopFieldCount()) {
      throw new IllegalArgumentException(
          String.format(
              "%d flyover slots, the path has %d hop fields",
              flyovers.length, path.getHopFieldCount()));
    }
    // The key Ak only works for its interfaces. With others the routers drop every packet.
    for (int i = 0; i < flyovers.length; i++) {
      Flyover f = flyovers[i];
      int in = path.getFlyoverIngress(i);
      int eg = path.getFlyoverEgress(i);
      if (f != null && (f.getIngress() != in || f.getEgress() != eg)) {
        throw new IllegalArgumentException(
            String.format(
                "Flyover %d is for %d>%d, the path has %d>%d",
                i, f.getIngress(), f.getEgress(), in, eg));
      }
      // Appendix A.5: at a segment change, both hop fields of the AS have the same interfaces, but
      // only the first may carry the flyover.
      if (f != null && path.getCrossOver(i) == 1) {
        throw new IllegalArgumentException(
            String.format(
                "Flyover %d belongs in slot %d, the first hop field of its AS", i, i - 1));
      }
    }
  }

  /** Inserts a flyover hop field for every non-null slot of {@code flyovers}. */
  static byte[] insertFlyovers(byte[] path, Flyover[] flyovers) {
    for (int i = 0; i < flyovers.length; i++) {
      Flyover f = flyovers[i];
      if (f != null) {
        path =
            HummingbirdPathConverter.insertFlyover(
                path, i, f.getResID(), f.getBw(), 0, f.getDuration());
      }
    }
    return path;
  }

  /**
   * Authenticates the template that was just written to {@code buffer} at {@code start}.
   * Synchronized: counter and the arrays in macs change with every packet.
   *
   * @param buffer the packet being written, with the template already in it
   * @param start the position of the template in {@code buffer}
   * @param dstIsdAs the packet's destination ISD and AS
   * @param packetLength the length of the whole packet, header and payload
   */
  synchronized void authenticate(ByteBuffer buffer, int start, long dstIsdAs, int packetLength) {
    long now = System.currentTimeMillis();
    int baseTs = (int) (now / 1000);
    int millis = (int) (now % 1000);
    int highResTs = (millis << 22) | counter;
    buffer.putInt(start + 4, baseTs);
    buffer.putInt(start + 8, highResTs);

    for (int i = 0; i < flyovers.length; i++) {
      Flyover f = flyovers[i];
      if (f == null) {
        continue; // no reservation at this hop
      }
      int hop = hopOffsets[i];

      // Appendix A.4: ResStart = BaseTimestamp - ResStartOffset, 0 to 18 h, never in the future.
      int resStartOffset = (int) (now / 1000 - f.getStartTime());
      buffer.putShort(start + hop + 16, (short) resStartOffset);

      // The SCION MAC is already in the buffer, the aggregated MAC replaces it.
      macs[i].aggregateMac(
          buffer, start + hop + 6, dstIsdAs, packetLength, resStartOffset, highResTs);
    }

    counter = (counter + 1) & 0x3FFFFF;
  }
}
