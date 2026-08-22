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
 * Read-only parser for a Hummingbird dataplane path (SCION path type 5).
 *
 * <p>The layout differs from a SCION path in two ways: the path meta header is 12 bytes instead of
 * 4, and a hop field carrying a flyover reservation is 20 bytes instead of 12. {@code CurrHF} and
 * {@code SegLen} count 4-byte <b>lines</b>, not hop fields, which makes the line count ambiguous
 * (15 lines is 5 standard hop fields or 3 flyover hop fields). Hop fields are therefore walked
 * rather than indexed, using the flyover bit of each hop field to decide the stride.
 */
public class HummingbirdPathRaw {

  /** Length of the path meta header in bytes (4 bytes in SCION). */
  public static final int META_LEN = 12;

  /** Length of one 4-byte line, the unit counted by {@code CurrHF} and {@code SegLen}. */
  public static final int LINE_LEN = 4;

  /** Lines occupied by a hop field without a flyover. */
  public static final int HOP_LINES = 3;

  /** Lines occupied by a hop field carrying a flyover. */
  public static final int FLYOVER_LINES = 5;

  /**
   * Upper bound on hop fields, matching the bound PathRawParser uses for SCION. A 7-bit SegLen
   * allows up to 381 lines, hence up to 127 hop fields, so a malformed header can exceed this; the
   * walk rejects that explicitly instead of overrunning the array.
   */
  private static final int MAX_HOP_FIELDS = 64;

  // path meta header
  private int currINF; // 2 bits
  private int currHF; // 8 bits, counts lines
  private int reserved; // 1 bit
  private final int[] segLen = new int[3]; // 7 bits each, counts lines
  private int baseTsRaw; // 32 bits, "raw" because the field type is unsigned
  private int highResTsRaw; // 32 bits: MillisTimestamp (10) | Counter (22)

  private final InfoField[] info = new InfoField[3];
  private final FlyoverHopField[] hops = new FlyoverHopField[MAX_HOP_FIELDS];
  private int numHops;
  private int len;

  public static HummingbirdPathRaw create(byte[] rawPath) {
    HummingbirdPathRaw p = new HummingbirdPathRaw();
    if (rawPath.length != 0) {
      p.read(ByteBuffer.wrap(rawPath));
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
    reserved = readInt(i0, 10, 1);
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
    while (lines < totalLines) {
      if (numHops == MAX_HOP_FIELDS) {
        throw new IllegalArgumentException("Too many hop fields, maximum is " + MAX_HOP_FIELDS);
      }
      hops[numHops].read(data);
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

  /** Number of bytes consumed by this path. */
  public int length() {
    return len;
  }

  public int getCurrINF() {
    return currINF;
  }

  /** Offset of the current hop field, in 4-byte lines from the first hop field. */
  public int getCurrHF() {
    return currHF;
  }

  /** Length of segment {@code i} in 4-byte lines. */
  public int getSegLen(int i) {
    return segLen[i];
  }

  public int getSegmentCount() {
    int n = 0;
    for (int i = 0; i < segLen.length && segLen[i] > 0; i++) {
      n++;
    }
    return n;
  }

  public InfoField getInfoField(int i) {
    return info[i];
  }

  public int getHopFieldCount() {
    return numHops;
  }

  public FlyoverHopField getHopField(int i) {
    return hops[i];
  }

  /** Seconds part of the packet timestamp, as unsigned Unix seconds. */
  public long getBaseTimestamp() {
    return Integer.toUnsignedLong(baseTsRaw);
  }

  /**
   * Sub-second part of the packet timestamp in milliseconds, 0..999. This is the millisecond part
   * of the same instant as {@link #getBaseTimestamp()}, not an offset from it.
   */
  public int getMillis() {
    return highResTsRaw >>> 22;
  }

  /** Per-packet counter, 22 bits, used to keep the flyover MAC input unique. */
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

    /** The SCION hop field part, 12 bytes, unchanged from SCION. */
    private final HopField hop = new HopField();

    /** 1 bit : set if this hop field carries a flyover. SCION's reserved bit. */
    private boolean flyover;

    // 22 bits : reservation ID, shares a 32-bit word with bw.
    private int resID;
    // 10 bits : reserved bandwidth, encoded as exponent(5) and mantissa(5).
    private int bw;
    // 16 bits : offset subtracted from BaseTimestamp to get the reservation start.
    private int resStartOffset;
    // 16 bits : reservation duration in seconds.
    private int resDuration;

    FlyoverHopField() {}

    public void read(ByteBuffer data) {
      // Peek the flyover bit without advancing: the SCION hop field must be read from byte 0.
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
