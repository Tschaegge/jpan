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

import static org.scion.jpan.internal.util.ByteUtil.readBoolean;
import static org.scion.jpan.internal.util.ByteUtil.readInt;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.scion.jpan.internal.header.PathRawParser.HopField;
import org.scion.jpan.internal.header.PathRawParser.InfoField;

/**
 * Read-only parser for a Hummingbird dataplane path (SCION path type 5). The wire format is
 * specified in Appendix A of the Hummingbird paper, https://doi.org/10.1145/3718958.3750495.
 */
public class HummingbirdPathRaw {

  public static final int META_LEN = 12;
  public static final int LINE_LEN = 4;
  public static final int HOP_LINES = 3;
  public static final int FLYOVER_LINES = 5;
  static final int INFO_FIELD_LEN = 8;
  static final int MAX_HOP_FIELDS = 64;

  // path meta header
  private int currINF; // 2 bits
  private int currHF; // 8 bits, counts lines
  private final int[] segLen = new int[3]; // 7 bits each, counts lines
  private int baseTsRaw; // 32 bits, "raw" because the field type is unsigned
  private int highResTsRaw; // 32 bits: MillisTimestamp (10) | Counter (22)

  private final InfoField[] info = new InfoField[3];
  private final FlyoverHopField[] hops = new FlyoverHopField[MAX_HOP_FIELDS];
  private int numHops;
  private final int[] hopsPerSegment = new int[3];
  private final int[] hopStartLine = new int[MAX_HOP_FIELDS];

  private int len;

  public static HummingbirdPathRaw create(ByteBuffer data) {
    HummingbirdPathRaw p = new HummingbirdPathRaw();
    if (data.hasRemaining()) {
      p.read(data.duplicate()); // same bytes, own position: data's position stays where it is
    }
    return p;
  }

  private HummingbirdPathRaw() {
    Arrays.setAll(info, value -> new InfoField());
    Arrays.setAll(hops, value -> new FlyoverHopField());
  }

  private void read(ByteBuffer data) {
    int start = data.position();

    // path meta header
    int i0 = data.getInt();
    currINF = readInt(i0, 0, 2);
    currHF = readInt(i0, 2, 8);
    // bit 10 is reserved
    for (int i = 0; i < segLen.length; i++) {
      segLen[i] = readInt(i0, 11 + 7 * i, 7);
    }
    baseTsRaw = data.getInt();
    highResTsRaw = data.getInt();

    // info fields, one per non-empty segment
    for (int i = 0; i < segLen.length && segLen[i] > 0; i++) {
      info[i].read(data);
    }

    // hopfield / flyoverhopfield reading
    int totalLines = segLen[0] + segLen[1] + segLen[2];
    int lines = 0;
    numHops = 0;
    // Hop fields are 3 or 5 lines wide, so the line count does not tell us how many there are.
    while (lines < totalLines) {
      if (numHops == MAX_HOP_FIELDS) {
        throw new IllegalArgumentException("Too many hop fields, maximum is " + MAX_HOP_FIELDS);
      }
      hopsPerSegment[segmentOfLine(lines)]++;
      hopStartLine[numHops] = lines;
      hops[numHops].read(data); // reads (flyover) hop fields
      lines += hops[numHops].length() / LINE_LEN;
      numHops++;
    }
    if (lines != totalLines) {
      // The last hop field claimed a flyover but only HOP_LINES lines were left.
      throw new IllegalArgumentException(
          "Hop fields do not fit the segment lengths: " + lines + " lines, expected " + totalLines);
    }

    len = data.position() - start;
  }

  /** Returns which segment the connection is part of: up (0), core (1), or down (2). */
  private int segmentOfLine(int line) {
    if (line < segLen[0]) {
      return 0;
    }
    return (line < segLen[0] + segLen[1]) ? 1 : 2;
  }

  /** Returns the number of bytes used by this path. */
  public int length() {
    return len;
  }

  public int getCurrINF() {
    return currINF;
  }

  public int getCurrHF() {
    return currHF;
  }

  /** Returns the index of the hop field that starts at {@code CurrHF}, or -1 if none does. */
  public int getCurrentHopIndex() {
    for (int i = 0; i < numHops; i++) {
      if (hopStartLine[i] == currHF) {
        return i;
      }
    }
    return -1;
  }

  /** Returns the length of segment {@code i} in 4-byte lines. */
  public int getSegLen(int i) {
    return segLen[i];
  }

  /** Returns the number of segments used for the connection. */
  public int getSegmentCount() {
    int n = 0;
    for (int i = 0; i < segLen.length && segLen[i] > 0; i++) {
      n++;
    }
    return n;
  }

  /**
   * Returns whether hop field {@code hopIdx} is one of the two hop fields of a segment crossover.
   * At a segment boundary the last hop field of the earlier segment and the first hop field of the
   * later segment describe the same AS.
   *
   * <p>A peering link also joins two segments, but is not a crossover: the two hop fields belong to
   * two different ASes and each is a real traversal, so both are reported as 0 and both may carry a
   * flyover. The destination's hop field is never reported as +1 either. Both rules follow the
   * reference's {@code IsCrossOver}.
   *
   * @return -1 for the last hop field of an earlier segment, +1 for the first hop field of a later
   *     segment, 0 otherwise. Only a -1 hop field may carry a flyover at a crossover (Appendix
   *     A.5); {@code insertFlyover} rejects +1.
   * @throws IllegalArgumentException if there is no such hop field
   */
  public int getCrossOver(int hopIdx) {
    int segmentIdx = getSegmentIndex(hopIdx);
    int firstHop = getFirstHopOfSegment(segmentIdx);
    boolean destination = hopIdx == numHops - 1;
    if (segmentIdx != 0
        && hopIdx == firstHop
        && !destination
        && !isPeeringBoundary(segmentIdx - 1)) {
      return 1;
    }
    int lastHop = firstHop + getSegmentHopCount(segmentIdx) - 1;
    int lastSegmentIdx = getSegmentCount() - 1;
    if (hopIdx == lastHop && segmentIdx != lastSegmentIdx && !isPeeringBoundary(segmentIdx)) {
      return -1;
    }
    return 0;
  }

  /** Returns whether segments {@code seg} and {@code seg + 1} are joined by a peering link. */
  private boolean isPeeringBoundary(int seg) {
    if (seg < 0 || seg >= getSegmentCount() - 1) {
      return false;
    }
    return info[seg].hasPeeringFlag() || info[seg + 1].hasPeeringFlag();
  }

  /** Returns the number of hop fields in segment {@code seg}, 0 if the segment is empty. */
  public int getSegmentHopCount(int seg) {
    if (seg < 0 || seg > 2) {
      throw new IllegalArgumentException("segment needs to be between 0 and 2");
    }
    return hopsPerSegment[seg];
  }

  /** Returns the index of the first hop field of segment {@code seg}, or -1 if it is empty. */
  public int getFirstHopOfSegment(int seg) {
    if (seg < 0 || seg > 2) {
      throw new IllegalArgumentException("segment needs to be between 0 and 2");
    }
    if (hopsPerSegment[seg] == 0) {
      return -1;
    }
    int first = 0;
    for (int s = 0; s < seg; s++) {
      first += hopsPerSegment[s];
    }
    return first;
  }

  /** Returns the line at which hop field {@code hopIdx} starts. */
  public int getHopStartLine(int hopIdx) {
    checkHopIndex(hopIdx);
    return hopStartLine[hopIdx];
  }

  /**
   * Returns the byte offset of hop field {@code hopIdx} in the raw path: behind the meta header and
   * the info fields, then {@code hopStartLine} lines of 4 bytes.
   */
  public int getHopFieldOffset(int hopIdx) {
    return META_LEN + getSegmentCount() * INFO_FIELD_LEN + getHopStartLine(hopIdx) * LINE_LEN;
  }

  public InfoField getInfoField(int i) {
    if (i < 0 || i >= getSegmentCount()) {
      throw new IllegalArgumentException(
          "No info field " + i + ", path has " + getSegmentCount() + " segment(s)");
    }
    return info[i];
  }

  public int getSegmentIndex(int hopIdx) {
    checkHopIndex(hopIdx);
    if (hopIdx < hopsPerSegment[0]) {
      return 0;
    }
    return (hopIdx < hopsPerSegment[0] + hopsPerSegment[1]) ? 1 : 2;
  }

  public int getHopFieldCount() {
    return numHops;
  }

  public FlyoverHopField getHopField(int i) {
    checkHopIndex(i);
    return hops[i];
  }

  private void checkHopIndex(int hopIdx) {
    if (hopIdx < 0 || hopIdx >= numHops) {
      throw new IllegalArgumentException(
          "No hop field " + hopIdx + ", path has " + numHops + " hop field(s)");
    }
  }

  /** Returns the seconds part of the packet timestamp, as unsigned Unix seconds. */
  public long getBaseTimestamp() {
    return Integer.toUnsignedLong(baseTsRaw);
  }

  /**
   * Returns the sub-second part of the packet timestamp in milliseconds, 0..999. This is the
   * millisecond part of the same instant as {@link #getBaseTimestamp()}, not an offset from it.
   */
  public int getMillis() {
    return highResTsRaw >>> 22;
  }

  /** Returns the per-packet counter, 22 bits, used to keep the flyover MAC input unique. */
  public int getCounter() {
    return highResTsRaw & 0x3FFFFF;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("currINF=")
        .append(currINF)
        .append(", currHF=")
        .append(currHF)
        .append(", segLen=")
        .append(Arrays.toString(segLen))
        .append(", baseTs=")
        .append(getBaseTimestamp())
        .append(", millis=")
        .append(getMillis())
        .append(", counter=")
        .append(getCounter());
    for (int i = 0; i < getSegmentCount(); i++) {
      sb.append("\n  info[").append(i).append("]: ").append(info[i]);
    }
    for (int i = 0; i < numHops; i++) {
      sb.append("\n  hop[").append(i).append("]: ").append(hops[i]);
    }
    return sb.toString();
  }

  public static class FlyoverHopField {

    // The SCION hop field part, 12 bytes, unchanged from SCION.
    private final HopField hop = new HopField();
    private boolean flyover;
    private int resID;
    private int bw;
    private int resStartOffset;
    private int resDuration;

    FlyoverHopField() {}

    public void read(ByteBuffer data) {
      // Peek the flyover bit without advancing
      flyover = readBoolean(data.getInt(data.position()), 0);
      hop.read(data);
      if (flyover) {
        int i3 = data.getInt();
        resID = readInt(i3, 0, 22);
        bw = readInt(i3, 22, 10);
        int i4 = data.getInt();
        resStartOffset = readInt(i4, 0, 16);
        resDuration = readInt(i4, 16, 16);
      }
    }

    /** Number of bytes this hop field occupies: 20 with a flyover, 12 without. */
    public int length() {
      return flyover ? FLYOVER_LINES * LINE_LEN : HOP_LINES * LINE_LEN;
    }

    public boolean isFlyover() {
      return flyover;
    }

    public int getResID() {
      return resID;
    }

    public int getBw() {
      return bw;
    }

    public int getResStartOffset() {
      return resStartOffset;
    }

    public int getResDuration() {
      return resDuration;
    }

    public int getIngress() {
      return hop.getIngress();
    }

    public int getEgress() {
      return hop.getEgress();
    }

    public boolean hasIngressAlert() {
      return hop.hasIngressAlert();
    }

    public boolean hasEgressAlert() {
      return hop.hasEgressAlert();
    }

    @Override
    public String toString() {
      if (!flyover) {
        return "flyover=false, " + hop;
      }
      return "flyover=true, "
          + hop
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
}
