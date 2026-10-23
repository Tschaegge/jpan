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
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.RequestPath;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;

/**
 * A Hummingbird path for requests, with flyovers for sending with reservations. It wraps the SCION
 * {@link RequestPath} it was built from rather than extending it, because its bytes differ from
 * those of the SCION path. Send it through {@link
 * org.scion.jpan.ScionDatagramChannel#send(java.nio.ByteBuffer, Path)}, or through a {@link
 * org.scion.jpan.ScionDatagramSocket} with a {@link org.scion.jpan.ScionDatagramPacket}.
 */
public class HummingbirdRequestPath extends Path {

  private final Path scionPath;
  private final FlyoverAuthenticator authenticator;
  private final byte[] e2eOptions; // the reverse reservation option, null for none
  private boolean e2eOptionsSent; // guarded by this: the option goes with one packet only

  public HummingbirdRequestPath(Path scionPath, Flyover[] flyovers) {
    super(
        toHummingbird(scionPath, flyovers),
        scionPath.getFirstHopAddress(),
        scionPath.getLocalIsdAs(),
        scionPath.getRemoteIsdAs(),
        scionPath.getRemoteAddress(),
        scionPath.getRemotePort());
    this.scionPath = scionPath;
    this.authenticator = new FlyoverAuthenticator(getRawPath(), flyovers);
    this.e2eOptions = null;
  }

  /** The same path as {@code original}, to dstIP and dstPort, sending e2eOptions once. */
  private HummingbirdRequestPath(
      HummingbirdRequestPath original, InetAddress dstIP, int dstPort, byte[] e2eOptions) {
    super(
        original.getRawPath(),
        original.getFirstHopAddress(),
        original.getLocalIsdAs(),
        original.getRemoteIsdAs(),
        dstIP,
        dstPort);
    this.scionPath = original.scionPath;
    // Shared, so the counter of the two paths keeps counting up together.
    this.authenticator = original.authenticator;
    this.e2eOptions = e2eOptions;
  }

  /**
   * Returns this path, which also offers the receiver flyovers for its replies: with the first
   * packet it sends the Go prototype's end-to-end option ReversePath (type 3). This path stays
   * unchanged.
   *
   * @param reverseFlyovers one slot per hop field of the reverse path, so slot 0 is the remote AS.
   *     null for a hop without reservation.
   * @throws IllegalArgumentException if the flyovers do not fit the reverse path, or do not fit
   *     into one option
   */
  public HummingbirdRequestPath withReverseReservation(Flyover[] reverseFlyovers) {
    byte[] reverse = HummingbirdPathConverter.reverse(getRawPath());
    FlyoverAuthenticator.checkFlyovers(reverse, reverseFlyovers);
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(reverse));
    int hops = path.getHopFieldCount();
    long[] forwardIas = forwardIsdAses(hops);
    long[] ias = new long[hops];
    byte[][] macs = new byte[hops][];
    for (int i = 0; i < hops; i++) {
      ias[i] = forwardIas[hops - 1 - i];
      int mac = path.getHopFieldOffset(i) + 6;
      macs[i] = Arrays.copyOfRange(reverse, mac, mac + 6);
    }
    byte[] data = serializeReverseReservation(reverseFlyovers, ias, macs);
    if (data.length > 255) {
      // Go casts the length to a byte without checking.
      throw new IllegalArgumentException("Reverse reservation of " + data.length + " bytes");
    }
    byte[] option = new byte[2 + data.length];
    option[0] = 3; // the Go prototype's OptTypeReversePath
    option[1] = (byte) data.length;
    System.arraycopy(data, 0, option, 2, data.length);
    return new HummingbirdRequestPath(this, getRemoteAddress(), getRemotePort(), option);
  }

  /**
   * The AS of each hop field of this path, from the path metadata: a hop field after a crossover
   * belongs to the same AS as the one before. 0 where the metadata does not tell; the Go receiver
   * does not read it.
   */
  private long[] forwardIsdAses(int hops) {
    long[] ias = new long[hops];
    List<PathMetadata.PathInterface> ifaces =
        scionPath.getMetadata() == null ? null : scionPath.getMetadata().getInterfacesList();
    if (ifaces == null || ifaces.isEmpty()) {
      return ias;
    }
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(getRawPath()));
    int as = 0;
    for (int i = 0; i < hops; i++) {
      if (i > 0 && path.getCrossOver(i) != 1) {
        as++;
      }
      // The list holds both ends of every link: AS 0's egress, AS 1's ingress, AS 1's egress, ...
      // So AS k >= 1 is at index 2k - 1, and AS 0 at index 0.
      int iface = as == 0 ? 0 : 2 * as - 1;
      if (iface >= ifaces.size()) {
        return new long[hops]; // the metadata does not match this path
      }
      ias[i] = ifaces.get(iface).getIsdAs();
    }
    return ias;
  }

  @Override
  public synchronized byte[] getEndToEndOptions() {
    if (e2eOptions == null || e2eOptionsSent) {
      return null;
    }
    e2eOptionsSent = true;
    return e2eOptions;
  }

  private static byte[] toHummingbird(Path scionPath, Flyover[] flyovers) {
    if (!(scionPath instanceof RequestPath)) {
      throw new IllegalStateException("The path must be a request path.");
    }
    if (scionPath.getRawPath().length == 0) {
      throw new IllegalArgumentException("Length of Path can't be 0");
    }
    byte[] path = HummingbirdPathConverter.convertFromScion(scionPath.getRawPath(), 0, 0, 0);
    FlyoverAuthenticator.checkFlyovers(path, flyovers);
    return FlyoverAuthenticator.insertFlyovers(path, flyovers);
  }

  /**
   * Writes reverse flyovers in the Go prototype's format, the counterpart of {@link
   * HummingbirdResponsePath#parseReverseReservation}: the hop count, a bitset of the hops that
   * exist, one of the hops with a flyover, 40 bytes per flyover, then the SCION MAC of every hop
   * field. A hop exists here exactly when it has a flyover.
   *
   * @param flyovers one slot per hop field of the reverse path, null for none
   * @param ias the AS of each hop field of the reverse path
   * @param scionMacs the 6-byte SCION MAC of each hop field of the reverse path
   */
  static byte[] serializeReverseReservation(Flyover[] flyovers, long[] ias, byte[][] scionMacs) {
    int hops = flyovers.length;
    if (hops > 8) {
      // More hops need bitsets longer than one byte.
      throw new UnsupportedOperationException("Reverse reservation with " + hops + " hops");
    }
    int bits = 0;
    int size = 3 + 6 * hops;
    for (int i = 0; i < hops; i++) {
      if (flyovers[i] != null) {
        bits |= 1 << i;
        size += 40;
      }
    }
    ByteBuffer bb = ByteBuffer.allocate(size);
    bb.put((byte) hops).put((byte) bits).put((byte) bits);
    for (int i = 0; i < hops; i++) {
      Flyover f = flyovers[i];
      if (f == null) {
        continue;
      }
      bb.putLong(ias[i]);
      bb.putShort((short) f.getIngress());
      bb.putShort((short) f.getEgress());
      bb.putInt(f.getResID() << 10 | f.getBw());
      bb.put(f.getAk());
      bb.putInt((int) f.getStartTime());
      bb.putShort((short) f.getDuration());
      bb.putShort((short) 0);
    }
    for (byte[] mac : scionMacs) {
      bb.put(mac);
    }
    return bb.array();
  }

  @Override
  public PathMetadata getMetadata() {
    return scionPath.getMetadata();
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    // The flyover MACs cover the destination AS only, so they hold for any host in it. The reverse
    // reservation was offered to the original receiver and does not go along.
    return new HummingbirdRequestPath(this, dstIP, dstPort, null);
  }

  @Override
  public int getPathType() {
    return HeaderConstants.PathTypes.HUMMINGBIRD.code();
  }

  @Override
  public void writePath(ByteBuffer buffer, int packetLength) {
    int start = buffer.position();
    super.writePath(buffer, packetLength);
    authenticator.authenticate(buffer, start, getRemoteIsdAs(), packetLength);
  }
}
