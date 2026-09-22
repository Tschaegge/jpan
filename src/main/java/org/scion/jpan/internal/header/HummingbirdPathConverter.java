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

import static org.scion.jpan.internal.util.ByteUtil.readInt;
import static org.scion.jpan.internal.util.ByteUtil.writeInt;

import java.nio.ByteBuffer;
import org.scion.jpan.internal.util.ByteUtil;

public class HummingbirdPathConverter {

  private static final int INFO_FIELD_LEN = HummingbirdPathRaw.INFO_FIELD_LEN;
  private static final int HOP_FIELD_LEN =
      HummingbirdPathRaw.HOP_LINES * HummingbirdPathRaw.LINE_LEN;
  private static final int FLYOVER_FIELD_LEN =
      HummingbirdPathRaw.FLYOVER_LINES * HummingbirdPathRaw.LINE_LEN;
  private static final int FLYOVER_EXTRA =
      HummingbirdPathRaw.FLYOVER_LINES - HummingbirdPathRaw.HOP_LINES;
  private static final int FLYOVER_FLAG = 0x80; // Constant used to set the flyover flag to true

  /**
   * Widens a SCION path to a Hummingbird path without flyovers.
   *
   * <p>Only the meta header changes: 4 to 12 bytes, and CurrHF and SegLen switch from counting hop
   * fields to counting 4-byte lines. Info fields and hop fields are copied unchanged.
   *
   * @param baseTs seconds of the packet timestamp, a field SCION does not have
   * @param millis sub-second part of the same instant, 0..999
   * @param counter 22-bit per-packet counter. Together with millis it forms HighResTS
   */
  public static byte[] convertFromScion(byte[] scionPath, long baseTs, int millis, int counter) {
    int i0 = ByteBuffer.wrap(scionPath).getInt();

    // SCION counts hop fields, Hummingbird counts 4-byte lines. A hop field is 3 lines.
    // Reading the SCION path meta header and converting it to lines
    int currINF = readInt(i0, 0, 2);
    int currHF = readInt(i0, 2, 6) * HummingbirdPathRaw.HOP_LINES;
    int[] segLen = new int[3];
    segLen[0] = readInt(i0, 14, 6) * HummingbirdPathRaw.HOP_LINES;
    segLen[1] = readInt(i0, 20, 6) * HummingbirdPathRaw.HOP_LINES;
    segLen[2] = readInt(i0, 26, 6) * HummingbirdPathRaw.HOP_LINES;

    // Converting it to the Hummingbird path meta header
    byte[] out = new byte[scionPath.length + 8]; // meta header grows from 4 to 12 bytes
    ByteBuffer bb = ByteBuffer.wrap(out);
    bb.putInt(metaHeaderFirstWord(currINF, currHF, segLen));
    bb.putInt(ByteUtil.toInt(baseTs));
    bb.putInt((millis << 22) | counter);
    bb.put(scionPath, 4, scionPath.length - 4); // info fields and hop fields
    return out;
  }

  /** Strips every flyover from a Hummingbird path mainly to prepare for reverse direction */
  public static byte[] removeFlyovers(byte[] input) {
    HummingbirdPathRaw p = HummingbirdPathRaw.create(ByteBuffer.wrap(input));
    int numHopFields = p.getHopFieldCount();
    int numInfoFields = p.getSegmentCount();

    // Count the flyovers: in total, per segment and before the current hop field.
    int flyovers = 0;
    int[] flyoversPerSegment = new int[3];
    int flyoversBeforeCurrHF = 0;
    for (int i = 0; i < numHopFields; i++) {
      if (!p.getHopField(i).isFlyover()) {
        continue;
      }
      flyovers++;
      flyoversPerSegment[p.getSegmentIndex(i)]++;
      if (p.getHopStartLine(i) < p.getCurrHF()) {
        flyoversBeforeCurrHF++;
      }
    }
    if (flyovers == 0) {
      return input.clone();
    }

    // Only the hop fields shrink. The meta header and the info fields keep their size.
    int hopFieldsOffset = HummingbirdPathRaw.META_LEN + numInfoFields * INFO_FIELD_LEN;
    ByteBuffer bb = ByteBuffer.allocate(hopFieldsOffset + numHopFields * HOP_FIELD_LEN);

    // Every removed flyover takes two lines off its segment and off CurrHF if it came before it.
    int currHF = p.getCurrHF() - FLYOVER_EXTRA * flyoversBeforeCurrHF;
    int[] segLen = new int[3];
    for (int seg = 0; seg < 3; seg++) {
      segLen[seg] = p.getSegLen(seg) - FLYOVER_EXTRA * flyoversPerSegment[seg];
    }
    bb.putInt(metaHeaderFirstWord(p.getCurrINF(), currHF, segLen));

    // Timestamps and info fields are unaffected.
    bb.put(input, 4, hopFieldsOffset - 4);

    // Copy the 12 SCION bytes of every hop field, with the flyover bit cleared in the first byte.
    for (int i = 0; i < numHopFields; i++) {
      int src = hopFieldsOffset + p.getHopStartLine(i) * HummingbirdPathRaw.LINE_LEN;
      bb.put((byte) (input[src] & ~FLYOVER_FLAG));
      bb.put(input, src + 1, HOP_FIELD_LEN - 1);
    }
    return bb.array();
  }

  /** Writes one flyover into hop field {@code hopIndex} and returns the new path. */
  public static byte[] insertFlyover(
      byte[] input, int hopIndex, int resID, int bw, int resStartOffset, int resDuration) {
    checkWidth("resID", resID, 22);
    checkWidth("bw", bw, 10);
    checkWidth("resStartOffset", resStartOffset, 16);
    checkWidth("resDuration", resDuration, 16);

    HummingbirdPathRaw p = HummingbirdPathRaw.create(ByteBuffer.wrap(input));
    if (p.getCrossOver(hopIndex) == 1) {
      throw new IllegalArgumentException(
          "Hop field " + hopIndex + " is the first of a later segment (Appendix A.5)");
    }
    if (p.getHopField(hopIndex).isFlyover()) {
      throw new IllegalArgumentException("Hop field " + hopIndex + " already has a flyover");
    }

    int hopOffset = p.getHopFieldOffset(hopIndex);

    // The hop field is two lines longer now, so we need to adjust segLen
    int[] segLen = new int[3];
    for (int seg = 0; seg < 3; seg++) {
      segLen[seg] = p.getSegLen(seg);
    }
    segLen[p.getSegmentIndex(hopIndex)] += FLYOVER_EXTRA;

    // Write the new path front to back: 8 more bytes than the input, inserted after the SCION part
    // of the hop field. CurrHF unchanged, like the reference: a sender's path has CurrHF 0.
    int cut = hopOffset + HOP_FIELD_LEN;
    ByteBuffer bb = ByteBuffer.allocate(input.length + FLYOVER_FIELD_LEN - HOP_FIELD_LEN);
    bb.putInt(metaHeaderFirstWord(p.getCurrINF(), p.getCurrHF(), segLen));
    bb.put(input, 4, cut - 4); // timestamps, info fields, hop fields up to and including this one
    bb.put(hopOffset, (byte) (input[hopOffset] | FLYOVER_FLAG)); // set the flyover bit in place
    bb.putInt((resID << 10) | bw); // the two new flyover words
    bb.putInt((resStartOffset << 16) | resDuration);
    bb.put(input, cut, input.length - cut); // the remaining hop fields
    return bb.array();
  }

  /** Builds the first word of the Hummingbird meta header. The caller writes the other two. */
  private static int metaHeaderFirstWord(int currINF, int currHF, int[] segLen) {
    checkWidth("CurrHF", currHF, 8);
    int word = 0;
    word = writeInt(word, 0, 2, currINF);
    word = writeInt(word, 2, 8, currHF);
    for (int seg = 0; seg < 3; seg++) {
      checkWidth("SegLen" + seg, segLen[seg], 7);
      word = writeInt(word, 11 + 7 * seg, 7, segLen[seg]);
    }
    return word;
  }

  /**
   * {@link ByteUtil#writeInt} does not mask, so a value that does not fit its field would leak into
   * the neighbouring field. Check first.
   */
  private static void checkWidth(String name, int value, int bits) {
    if (value < 0 || value >= (1 << bits)) {
      throw new IllegalArgumentException(
          name + " must fit " + bits + " bits (0.." + ((1 << bits) - 1) + "), got " + value);
    }
  }
}
