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

import java.net.InetAddress;
import java.util.List;
import javax.crypto.Cipher;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;

/**
 * A SCION path with Hummingbird reservations: the {@link Path} an application sends over to get
 * reserved bandwidth. Built once per set of reservations from a plain SCION path and one {@link
 * Flyover} per reservable hop field.
 *
 * <p>What this object holds is a <em>template</em>: the Hummingbird path with all flyover fields
 * written, the per-packet fields zero, and the hop field MACs still the original SCION MACs. The
 * template is {@link #getRawPath()} and never changes. Stamping a packet (timestamps and the
 * aggregated MACs) happens per packet in {@code writePath}, which comes with the next step; the
 * template stays untouched, so the original SCION MACs are always available for the XOR.
 *
 * <p>Mirrors {@code Reservation} in the reference implementation ({@code
 * pkg/snet/path/hummingbird.go}) without its {@code scionMacs}: the template keeps them.
 */
public final class HummingbirdPath extends Path {

  /** The SCION path this was built from; answers getMetadata() and provides the addresses. */
  private final Path basePath;

  // One entry per inserted flyover, index k = k-th flyover in path order. Everything writePath
  // needs per packet, computed once here.
  private final int[] hopFieldOffset; // byte offset of the flyover's hop field in the template
  private final Cipher[] akCipher; // AES over that flyover's Ak, from HummingbirdMac.createCipher
  private final long[] startTime; // absolute; ResStartOffset = BaseTimestamp - startTime per packet

  /**
   * Builds the template.
   *
   * @param scionPath a SCION path from the daemon
   * @param flyovers one entry per reservable hop field of the path, in path order; {@code null}
   *     leaves that AS best effort. Reservable are all hop fields that are not the second half of a
   *     crossover (Appendix A.5); ask {@code HummingbirdPathRaw.getCrossOver}.
   * @throws IllegalArgumentException if the list has the wrong length, or a flyover's interfaces do
   *     not match the hop field it would be written to
   */
  public static HummingbirdPath create(Path scionPath, List<Flyover> flyovers) {
    // TODO step 1: widen the SCION path. Timestamps are per packet, so 0 here.
    //   byte[] raw = HummingbirdPathConverter.convertFromScion(scionPath.getRawPath(), 0, 0, 0);
    //   HummingbirdPathRaw p = HummingbirdPathRaw.create(ByteBuffer.wrap(raw));
    //
    // TODO step 2: which hop fields may carry a flyover? All i with p.getCrossOver(i) != 1.
    //   Collect them in a List<Integer>. Its size must equal flyovers.size(), otherwise throw
    //   with both numbers in the message.
    //
    // TODO step 3: for every non-null flyover k, hopIdx = reservable.get(k):
    //   - int[] inEg = p.getReservationInterfaces(hopIdx);   <- needs the new getter
    //   - if inEg[0] != f.getIngress() || inEg[1] != f.getEgress(): throw, naming k and hopIdx
    //     (the reference calls this "mismatch hop parameter and data-plane")
    //   - raw = HummingbirdPathConverter.insertFlyover(raw, hopIdx, f.getResID(), f.getBw(), 0,
    //           f.getDuration());
    //   - p = HummingbirdPathRaw.create(ByteBuffer.wrap(raw));
    //     <- re-parse: everything behind hopIdx moved 8 bytes.
    //     Hop indices stay valid across insertions, byte offsets do not.
    //
    // TODO step 4: only now, with the template final, fill the three arrays for the non-null
    //   flyovers: p.getHopFieldOffset(hopIdx), HummingbirdMac.createCipher(f.getAk()),
    //   f.getStartTime().
    //
    // TODO step 5: return new HummingbirdPath(scionPath, raw, offsets, ciphers, startTimes);
    throw new UnsupportedOperationException("TODO");
  }

  private HummingbirdPath(
      Path basePath, byte[] template, int[] hopFieldOffset, Cipher[] akCipher, long[] startTime) {
    super(
        template,
        basePath.getFirstHopAddress(),
        basePath.getLocalIsdAs(),
        basePath.getRemoteIsdAs(),
        basePath.getRemoteAddress(),
        basePath.getRemotePort());
    this.basePath = basePath;
    this.hopFieldOffset = hopFieldOffset;
    this.akCipher = akCipher;
    this.startTime = startTime;
  }

  /** The SCION path this was built from. */
  public Path getBasePath() {
    return basePath;
  }

  /** Number of flyovers in the template. */
  public int getFlyoverCount() {
    return hopFieldOffset.length;
  }

  public int[] getHopFieldOffset() {
    return hopFieldOffset;
  }

  @Override
  public PathMetadata getMetadata() {
    return basePath.getMetadata();
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    // TODO: same template and reservations, new destination. Ciphers can be shared: a Cipher
    //  holds no per-packet state between doFinal calls (but is not thread-safe; that is
    //  writePath's problem, later). basePath.copy(dstIP, dstPort) gives the new base.
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public String toString() {
    // TODO: HummingbirdPathRaw.create(ByteBuffer.wrap(getRawPath())).toString() plus the flyover
    //  count. Path.toString uses ScionUtil.toStringPath, which assumes 12-byte hop fields.
    throw new UnsupportedOperationException("TODO");
  }
}
