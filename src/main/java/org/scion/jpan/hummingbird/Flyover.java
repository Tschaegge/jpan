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

import java.util.Arrays;

/**
 * One Hummingbird reservation as the endhost receives it from the issuing AS: which interface pair
 * it covers, what was reserved, when it is valid, and the authentication key Ak that signs every
 * packet sent over it.
 *
 * <p>Immutable. {@code ingress} and {@code egress} are in traversal direction, the direction the
 * packet travels, which is what the router derives Ak from; see {@code
 * HummingbirdPathRaw.getReservationInterfaces}.
 */
public final class Flyover {

  private final int ingress; // 16 bits, 0 at the source AS
  private final int egress; // 16 bits, 0 at the destination AS
  private final int resID; // 22 bits
  private final int bw; // 10 bits, the encoded bandwidth
  private final long startTime; // absolute Unix seconds; the router requires startTime < now
  private final int duration; // 16 bits, seconds
  private final byte[] ak; // 16 bytes, AES-128 key

  public Flyover(
      int ingress, int egress, int resID, int bw, long startTime, int duration, byte[] ak) {
    // TODO: range checks like HummingbirdPathConverter.checkWidth: ingress/egress 16 bits,
    //  resID 22, bw 10, duration 16; ak != null && ak.length == HummingbirdMac.KEY_LEN.
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

  /** Returns a copy of the authentication key. */
  public byte[] getAk() {
    return Arrays.copyOf(ak, ak.length);
  }

  /** Never prints the key. */
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
