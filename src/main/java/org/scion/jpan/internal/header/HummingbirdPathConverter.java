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

/**
 * Builds Hummingbird paths: SCION to Hummingbird, flyovers in, flyovers out. MACs are never
 * touched.
 */
public class HummingbirdPathConverter {

  /** Length of one info field in bytes, one per non-empty segment. */
  private static final int INFO_FIELD_LEN = 8;

  /** Length of a hop field without a flyover, in bytes. */
  private static final int HOP_FIELD_LEN =
      HummingbirdPathRaw.HOP_LINES * HummingbirdPathRaw.LINE_LEN;

  /** Length of a hop field carrying a flyover, in bytes. */
  private static final int FLYOVER_FIELD_LEN =
      HummingbirdPathRaw.FLYOVER_LINES * HummingbirdPathRaw.LINE_LEN;

  /** Lines a flyover adds to a hop field, and therefore to its segment. */
  private static final int FLYOVER_EXTRA =
      HummingbirdPathRaw.FLYOVER_LINES - HummingbirdPathRaw.HOP_LINES;

  /** Bit 0 of a hop field's first byte: set when it carries a flyover. */
  private static final int FLYOVER_FLAG = 0x80;

  public static byte[] convertFromScion(byte[] scionPath, long baseTs, int millis, int counter) {
    int i0 = ByteBuffer.wrap(scionPath).getInt();

    // SCION counts hop fields, Hummingbird counts 4-byte lines. A hop field is 3 lines.
    int currINF = readInt(i0, 0, 2);
    int currHF = readInt(i0, 2, 6) * HummingbirdPathRaw.HOP_LINES;
    int[] segLen = new int[3];
    segLen[0] = readInt(i0, 14, 6) * HummingbirdPathRaw.HOP_LINES;
    segLen[1] = readInt(i0, 20, 6) * HummingbirdPathRaw.HOP_LINES;
    segLen[2] = readInt(i0, 26, 6) * HummingbirdPathRaw.HOP_LINES;

    byte[] out = new byte[scionPath.length + 8]; // the meta header grows from 4 to 12 bytes
    ByteBuffer bb = ByteBuffer.wrap(out);
    bb.putInt(metaHeaderWord(currINF, currHF, segLen));
    bb.putInt(ByteUtil.toInt(baseTs));
    bb.putInt((millis << 22) | counter);
    System.arraycopy(scionPath, 4, out, 12, scionPath.length - 4); // info fields and hop fields
    return out;
  }

  /**
   * Strips every flyover from a Hummingbird path, leaving a best-effort path of the same shape.
   *
   * <p>Mainly to prepare a path for the reverse direction: a reservation is bought for one
   * ingress/egress pair, so it does not apply when the path is reversed and has to be removed.
   *
   * <p>The MACs are left as they are. A hop field that carried a flyover holds an aggregated MAC,
   * so the result is structurally valid but its MACs are not: whoever reverses a path has to
   * restore the pristine SCION MACs separately.
   */
  public static byte[] removeFlyovers(byte[] input) {
    HummingbirdPathRaw p = HummingbirdPathRaw.create(input);
    int numHopFields = p.getHopFieldCount();
    int numInfoFields = p.getSegmentCount();

    // Count the flyovers: in total, per segment, and before the current hop field.
    int flyovers = 0;
    int[] flyoversPerSegment = new int[3];
    int flyoversBeforeCurrHF = 0;
    for (int i = 0; i < numHopFields; i++) {
      if (!p.getHopField(i).isFlyover()) {
        continue;
      }
      flyovers++;
      flyoversPerSegment[p.getInfoFieldIndex(i)]++;
      if (p.getHopLine(i) < p.getCurrHF()) {
        flyoversBeforeCurrHF++;
      }
    }
    if (flyovers == 0) {
      return input.clone();
    }

    // Only the hop fields shrink; the meta header and the info fields keep their size.
    int hopFieldsOffset = HummingbirdPathRaw.META_LEN + numInfoFields * INFO_FIELD_LEN;
    byte[] out = new byte[hopFieldsOffset + numHopFields * HOP_FIELD_LEN];

    // Every removed flyover takes two lines off its segment, and off CurrHF if it came before it.
    int currHF = p.getCurrHF() - FLYOVER_EXTRA * flyoversBeforeCurrHF;
    int[] segLen = new int[3];
    for (int seg = 0; seg < 3; seg++) {
      segLen[seg] = p.getSegLen(seg) - FLYOVER_EXTRA * flyoversPerSegment[seg];
    }
    ByteBuffer.wrap(out).putInt(metaHeaderWord(p.getCurrINF(), currHF, segLen));

    // Timestamps and info fields are unaffected.
    System.arraycopy(input, 4, out, 4, hopFieldsOffset - 4);

    // Copy the 12 SCION bytes of every hop field and clear its flyover bit.
    for (int i = 0; i < numHopFields; i++) {
      int src = hopFieldsOffset + p.getHopLine(i) * HummingbirdPathRaw.LINE_LEN;
      int dst = hopFieldsOffset + i * HOP_FIELD_LEN;
      System.arraycopy(input, src, out, dst, HOP_FIELD_LEN);
      out[dst] = (byte) (out[dst] & ~FLYOVER_FLAG);
    }
    return out;
  }

  /**
   * Writes one flyover into hop field {@code hopIndex} and returns the new path.
   *
   * <p>The hop field grows from 12 to 20 bytes, so its segment grows by two lines. The MACs are not
   * touched; the aggregated MAC is applied per packet.
   *
   * <p>{@code CurrHF} is left as it is, like in the reference implementation. This is for a path a
   * sender is building, where {@code CurrHF} is 0 and nothing can lie before it.
   *
   * @throws IllegalArgumentException if a value does not fit its field, if there is no such hop
   *     field, if the hop field already carries a flyover, or if it is the first of a later segment
   *     (Appendix A.5)
   */
  public static byte[] insertFlyover(
      byte[] input, int hopIndex, int resID, int bw, int resStartOffset, int resDuration) {
    checkWidth("resID", resID, 22);
    checkWidth("bw", bw, 10);
    checkWidth("resStartOffset", resStartOffset, 16);
    checkWidth("resDuration", resDuration, 16);

    HummingbirdPathRaw p = HummingbirdPathRaw.create(input);
    if (p.getCrossOver(hopIndex) == 1) {
      throw new IllegalArgumentException(
          "Hop field " + hopIndex + " is the first of a later segment (Appendix A.5)");
    }
    if (p.getHopField(hopIndex).isFlyover()) {
      throw new IllegalArgumentException("Hop field " + hopIndex + " already has a flyover");
    }

    // Where this hop field starts
    int hopOffset =
        HummingbirdPathRaw.META_LEN
            + p.getSegmentCount() * INFO_FIELD_LEN
            + p.getHopLine(hopIndex) * HummingbirdPathRaw.LINE_LEN;

    // Make room for 8 more bytes.
    int extraBytes = FLYOVER_FIELD_LEN - HOP_FIELD_LEN;
    int cut = hopOffset + HOP_FIELD_LEN;
    byte[] out = new byte[input.length + extraBytes];
    System.arraycopy(input, 0, out, 0, cut);
    System.arraycopy(input, cut, out, cut + extraBytes, input.length - cut);

    // The hop field is two lines longer now, so its segment is too.
    int[] segLen = new int[3];
    for (int seg = 0; seg < 3; seg++) {
      segLen[seg] = p.getSegLen(seg);
    }
    segLen[p.getInfoFieldIndex(hopIndex)] += FLYOVER_EXTRA;
    ByteBuffer.wrap(out).putInt(metaHeaderWord(p.getCurrINF(), p.getCurrHF(), segLen));

    // Add the new flyover fields
    out[hopOffset] = (byte) (out[hopOffset] | FLYOVER_FLAG);
    ByteBuffer bb = ByteBuffer.wrap(out);
    bb.putInt(hopOffset + HOP_FIELD_LEN, (resID << 10) | bw);
    bb.putInt(hopOffset + HOP_FIELD_LEN + 4, (resStartOffset << 16) | resDuration);
    return out;
  }

  /** The first word of the meta header. CurrHF and the segment lengths count 4-byte lines. */
  private static int metaHeaderWord(int currINF, int currHF, int[] segLen) {
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
