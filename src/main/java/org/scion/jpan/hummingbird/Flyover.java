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

import static org.scion.jpan.internal.util.ByteUtil.checkWidth;

import java.util.Arrays;
import org.scion.jpan.internal.header.HummingbirdMac;

/**
 * A bandwidth reservation at one AS, with the key Ak that the AS issued for it. The ingress and
 * egress must be the interfaces the packet really uses at that AS, otherwise the router drops it.
 */
public final class Flyover {

  private final int ingress;
  private final int egress;
  private final int resID;
  private final int bw;
  private final long startTime;
  private final int duration;
  private final byte[] ak;

  /**
   * @param ingress interface where the packet enters the AS, in travel direction, 0 at the source
   * @param egress interface where the packet leaves the AS, in travel direction, 0 at the
   *     destination
   * @param resID 22-bit reservation ID, unique per interface pair while the reservation is valid
   * @param bw 10-bit bandwidth codepoint, not kbps
   * @param startTime absolute Unix seconds, must lie in the past or the router drops the packets
   * @param duration length of the reservation in seconds
   * @param ak 16-byte key the AS issued for this reservation
   */
  public Flyover(
      int ingress, int egress, int resID, int bw, long startTime, int duration, byte[] ak) {
    checkWidth("ingress", ingress, 16);
    checkWidth("egress", egress, 16);
    checkWidth("resID", resID, 22);
    checkWidth("bw", bw, 10);
    checkWidth("duration", duration, 16);
    if (startTime < 0 || startTime > 0xFFFFFFFFL) { // Ak takes it as an unsigned 32-bit value
      throw new IllegalArgumentException("startTime must fit 32 bits unsigned, got " + startTime);
    }
    if (ak == null || ak.length != HummingbirdMac.KEY_LEN) {
      throw new IllegalArgumentException("ak must be " + HummingbirdMac.KEY_LEN + " bytes");
    }
    this.ingress = ingress;
    this.egress = egress;
    this.resID = resID;
    this.bw = bw;
    this.startTime = startTime;
    this.duration = duration;
    this.ak = Arrays.copyOf(ak, ak.length); // own copy: the caller cannot change our key later
  }

  public int getIngress() {
    return ingress;
  }

  public int getEgress() {
    return egress;
  }

  public int getResID() {
    return resID;
  }

  public int getBw() {
    return bw;
  }

  public long getStartTime() {
    return startTime;
  }

  public int getDuration() {
    return duration;
  }

  public byte[] getAk() {
    return Arrays.copyOf(ak, ak.length);
  }

  @Override
  public String toString() {
    return "Flyover{ingress="
        + ingress
        + ", egress="
        + egress
        + ", resID="
        + resID
        + ", bw="
        + bw
        + ", startTime="
        + startTime
        + ", duration="
        + duration
        + "}";
  }
}
