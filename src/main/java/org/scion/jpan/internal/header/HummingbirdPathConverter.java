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
import java.util.Arrays;
import org.scion.jpan.internal.util.ByteUtil;

public class HummingbirdPathConverter {

  /** Length of one info field in bytes, one per non-empty segment. */
  private static final int INFO_FIELD_LEN = 8;

  /** Length of a hop field without a flyover, in bytes. */
  private static final int HOP_FIELD_LEN =
      HummingbirdPathRaw.HOP_LINES * HummingbirdPathRaw.LINE_LEN;

  /** Lines a flyover adds to a hop field, and therefore to its segment. */
  private static final int FLYOVER_EXTRA =
      HummingbirdPathRaw.FLYOVER_LINES - HummingbirdPathRaw.HOP_LINES;

  /** Bit 0 of a hop field's first byte: set when it carries a flyover. */
  private static final int FLYOVER_FLAG = 0x80;

  /** Length of a hop field carrying a flyover, in bytes. */
  private static final int FLYOVER_LINES_LEN =
      HummingbirdPathRaw.FLYOVER_LINES * HummingbirdPathRaw.LINE_LEN;

  public static byte[] convertFromScion(byte[] scionPath, long baseTs, int millis, int counter) {

    int i0 = ByteBuffer.wrap(scionPath).getInt();

    int currINF = readInt(i0, 0, 2);
    int currHF = readInt(i0, 2, 6);
    int[] segLen = new int[3];
    segLen[0] = readInt(i0, 14, 6);
    segLen[1] = readInt(i0, 20, 6);
    segLen[2] = readInt(i0, 26, 6);

    int i1 = 0;
    i1 = writeInt(i1, 0, 2, currINF);
    i1 = writeInt(i1, 2, 8, currHF * HummingbirdPathRaw.HOP_LINES);
    i1 = writeInt(i1, 11, 7, segLen[0] * HummingbirdPathRaw.HOP_LINES);
    i1 = writeInt(i1, 18, 7, segLen[1] * HummingbirdPathRaw.HOP_LINES);
    i1 = writeInt(i1, 25, 7, segLen[2] * HummingbirdPathRaw.HOP_LINES);

    byte[] out = new byte[scionPath.length + 8]; // adjust for increased meta header length
    ByteBuffer bb = ByteBuffer.wrap(out);
    bb.putInt(i1);
    bb.putInt(ByteUtil.toInt(baseTs));
    bb.putInt((millis << 22) | counter);
    System.arraycopy(scionPath, 4, out, 12, scionPath.length - 4);
    return out;
  }

  /*
  at a segment boundary two hop fields describe the same AS, and A.5 requires the flyover to sit on the last hop field of the earlier segment, so the first hop field of every later segment is not eligible.
  */
  public static boolean[] flyoverEligibleHopFields(HummingbirdPathRaw path) {
    int segments = path.getSegmentCount();
    if (segments == 0) {
      return new boolean[0];
    }
    boolean[] eligible = new boolean[path.getHopFieldCount()];
    Arrays.fill(eligible, true);
    for (int seg = 0; seg < segments; seg++) {
      if (seg != 0) {
        int first = path.getFirstHopOfSegment(seg);
        eligible[first] = false;
      }
    }

    return eligible;
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

    // First pass: how much shrinks, and where. A flyover hop field loses FLYOVER_EXTRA lines,
    // which come off its own segment. CurrHF is an offset in lines, so it shifts by the same
    // amount for every flyover that starts before it -- a flyover starting exactly at CurrHF
    // does not move CurrHF, because that hop field itself stays where it is.
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

    int i0 = 0;
    i0 = writeInt(i0, 0, 2, p.getCurrINF());
    i0 = writeInt(i0, 2, 8, p.getCurrHF() - FLYOVER_EXTRA * flyoversBeforeCurrHF);
    for (int seg = 0; seg < flyoversPerSegment.length; seg++) {
      i0 =
          writeInt(i0, 11 + 7 * seg, 7, p.getSegLen(seg) - FLYOVER_EXTRA * flyoversPerSegment[seg]);
    }
    ByteBuffer.wrap(out).putInt(i0);

    // Timestamps and info fields are unaffected.
    System.arraycopy(input, 4, out, 4, hopFieldsOffset - 4);

    // A flyover hop field keeps its first HOP_FIELD_LEN bytes -- the SCION hop field -- and
    // loses the four reservation fields behind them. Reading uses the input line offsets;
    // writing is uniform, because every hop field is HOP_FIELD_LEN long in the output.
    for (int i = 0; i < numHopFields; i++) {
      int src = hopFieldsOffset + p.getHopLine(i) * HummingbirdPathRaw.LINE_LEN;
      int dst = hopFieldsOffset + i * HOP_FIELD_LEN;
      System.arraycopy(input, src, out, dst, HOP_FIELD_LEN);
      out[dst] = (byte) (out[dst] & ~FLYOVER_FLAG);
    }
    return out;
  }

  /** One reservation to write into a path: which hop field, and the four flyover fields. */
  public static final class Flyover {
    private final int hopIndex;
    private final int resID;
    private final int bw;
    private final int resStartOffset;
    private final int resDuration;

    public Flyover(int hopIndex, int resID, int bw, int resStartOffset, int resDuration) {
      this.hopIndex = hopIndex;
      this.resID = resID;
      this.bw = bw;
      this.resStartOffset = resStartOffset;
      this.resDuration = resDuration;
    }

    @Override
    public String toString() {
      return "hop="
          + hopIndex
          + ", resID="
          + resID
          + ", bw="
          + bw
          + ", resStartOffset="
          + resStartOffset
          + ", resDuration="
          + resDuration;
    }
  }

  /**
   * Writes reservations into a Hummingbird path, the inverse of {@link #removeFlyovers(byte[])}.
   *
   * <p>Only the structure is written: the flyover bit, the four reservation fields, and the segment
   * lengths and {@code CurrHF} that shift because a flyover makes its hop field two lines longer.
   * The hop field MACs are left untouched, so the result is structurally valid but not yet
   * authenticated -- the aggregated MAC has to be applied separately, per packet, because it
   * depends on the packet length and the counter.
   *
   * <p>A hop field that already carries a flyover has its fields overwritten and does not grow,
   * which matches the reference's {@code SetHopAndFlyover}.
   *
   * @throws IllegalArgumentException if a hop field is named twice, if Appendix A.5 forbids a
   *     flyover on it, or if a field does not fit its width on the wire
   */
  public static byte[] insertFlyovers(byte[] input, Flyover... flyovers) {
    HummingbirdPathRaw p = HummingbirdPathRaw.create(input);
    int numHopFields = p.getHopFieldCount();
    int numInfoFields = p.getSegmentCount();
    boolean[] eligible = flyoverEligibleHopFields(p);

    // One slot per hop field, so the copy loop below can ask "does this one get a flyover".
    Flyover[] byHop = new Flyover[numHopFields];
    int added = 0;
    int addedBeforeCurrHF = 0;
    int[] addedPerSegment = new int[3];
    for (Flyover f : flyovers) {
      if (f.hopIndex < 0 || f.hopIndex >= numHopFields) {
        throw new IllegalArgumentException(
            "No hop field " + f.hopIndex + ", path has " + numHopFields + " hop field(s)");
      }
      if (byHop[f.hopIndex] != null) {
        throw new IllegalArgumentException("Two flyovers for hop field " + f.hopIndex);
      }
      // A.5: at a segment boundary the two hop fields describe one AS, and the flyover has to
      // sit on the earlier one. Writing to the other half produces a packet the router demotes
      // to best-effort instead of dropping, so it would fail silently.
      if (!eligible[f.hopIndex]) {
        throw new IllegalArgumentException(
            "Hop field "
                + f.hopIndex
                + " is the first of a later segment and cannot carry a flyover (Appendix A.5)");
      }
      checkWidth("resID", f.resID, 22);
      checkWidth("bw", f.bw, 10);
      checkWidth("resStartOffset", f.resStartOffset, 16);
      checkWidth("resDuration", f.resDuration, 16);

      byHop[f.hopIndex] = f;
      if (!p.getHopField(f.hopIndex).isFlyover()) {
        added++;
        addedPerSegment[p.getInfoFieldIndex(f.hopIndex)]++;
        if (p.getHopLine(f.hopIndex) < p.getCurrHF()) {
          addedBeforeCurrHF++;
        }
      }
    }
    if (added == 0 && flyovers.length == 0) {
      return input.clone();
    }

    int hopFieldsOffset = HummingbirdPathRaw.META_LEN + numInfoFields * INFO_FIELD_LEN;
    byte[] out = new byte[input.length + added * FLYOVER_EXTRA * HummingbirdPathRaw.LINE_LEN];

    int i0 = 0;
    i0 = writeInt(i0, 0, 2, p.getCurrINF());
    i0 = writeInt(i0, 2, 8, p.getCurrHF() + FLYOVER_EXTRA * addedBeforeCurrHF);
    for (int seg = 0; seg < addedPerSegment.length; seg++) {
      i0 = writeInt(i0, 11 + 7 * seg, 7, p.getSegLen(seg) + FLYOVER_EXTRA * addedPerSegment[seg]);
    }
    ByteBuffer.wrap(out).putInt(i0);

    // Timestamps and info fields are unaffected.
    System.arraycopy(input, 4, out, 4, hopFieldsOffset - 4);

    // Both sides have uneven strides here: the input may already contain flyovers, and the
    // output gains lines wherever one is written. So the destination offset is accumulated.
    int dst = hopFieldsOffset;
    for (int i = 0; i < numHopFields; i++) {
      int src = hopFieldsOffset + p.getHopLine(i) * HummingbirdPathRaw.LINE_LEN;
      System.arraycopy(input, src, out, dst, HOP_FIELD_LEN);
      Flyover f = byHop[i];
      if (f == null) {
        // Kept as it is, flyover and all.
        int keptLen = p.getHopField(i).length();
        if (keptLen > HOP_FIELD_LEN) {
          System.arraycopy(
              input, src + HOP_FIELD_LEN, out, dst + HOP_FIELD_LEN, keptLen - HOP_FIELD_LEN);
        }
        dst += keptLen;
        continue;
      }
      out[dst] = (byte) (out[dst] | FLYOVER_FLAG);
      int i3 = 0;
      i3 = writeInt(i3, 0, 22, f.resID);
      i3 = writeInt(i3, 22, 10, f.bw);
      int i4 = 0;
      i4 = writeInt(i4, 0, 16, f.resStartOffset);
      i4 = writeInt(i4, 16, 16, f.resDuration);
      ByteBuffer bb = ByteBuffer.wrap(out);
      bb.putInt(dst + HOP_FIELD_LEN, i3);
      bb.putInt(dst + HOP_FIELD_LEN + 4, i4);
      dst += FLYOVER_LINES_LEN;
    }
    return out;
  }

  /**
   * ByteUtil.writeInt neither masks nor assigns, so a value that does not fit its field ORs into
   * the neighbouring one instead of failing. Every caller-supplied field is checked first.
   */
  private static void checkWidth(String name, int value, int bits) {
    if (value < 0 || value >= (1 << bits)) {
      throw new IllegalArgumentException(
          name + " must fit " + bits + " bits (0.." + ((1 << bits) - 1) + "), got " + value);
    }
  }
}
