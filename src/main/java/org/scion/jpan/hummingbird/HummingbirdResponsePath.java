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
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import org.scion.jpan.Path;
import org.scion.jpan.ResponsePath;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.util.ByteUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HummingbirdResponsePath extends ResponsePath {

  private static final Logger log = LoggerFactory.getLogger(HummingbirdResponsePath.class);

  private final FlyoverAuthenticator authenticator; // null: no flyovers

  public HummingbirdResponsePath(
      byte[] rawPath,
      long srcIsdAs,
      InetAddress srcIP,
      int srcPort,
      long dstIsdAs,
      InetAddress dstIP,
      int dstPort,
      InetSocketAddress firstHop) {
    super(rawPath, srcIsdAs, srcIP, srcPort, dstIsdAs, dstIP, dstPort, firstHop);
    this.authenticator = null;
  }

  /** The same reply as {@code original}, with other bytes, destination or authenticator. */
  private HummingbirdResponsePath(
      HummingbirdResponsePath original,
      byte[] rawPath,
      InetAddress dstIP,
      int dstPort,
      FlyoverAuthenticator authenticator) {
    super(
        rawPath,
        original.getLocalIsdAs(),
        original.getLocalAddress(),
        original.getLocalPort(),
        original.getRemoteIsdAs(),
        dstIP,
        dstPort,
        original.getFirstHopAddress());
    this.authenticator = authenticator;
  }

  /** Unix seconds at which the first of this reply's flyovers ends, 0 without flyovers. */
  public long getReservationExpiry() {
    return authenticator == null ? 0 : authenticator.expiry();
  }

  /**
   * Whether a cache of reply paths should keep {@code cached} instead of replacing it with {@code
   * received}: when {@code cached} still has a running reservation and {@code received} has none. A
   * sender offers its reverse reservation with one packet only, as the Go prototype's
   * HummReplyPather assumes too, so the packets after it must not replace that reply path.
   *
   * @param nowSeconds the current time in Unix seconds
   */
  public static boolean keepsReservation(Path cached, Path received, long nowSeconds) {
    if (!(cached instanceof HummingbirdResponsePath)) {
      return false;
    }
    long cachedExpiry = ((HummingbirdResponsePath) cached).getReservationExpiry();
    long receivedExpiry =
        received instanceof HummingbirdResponsePath
            ? ((HummingbirdResponsePath) received).getReservationExpiry()
            : 0;
    return cachedExpiry > nowSeconds && receivedExpiry == 0;
  }

  /**
   * Returns this reply with flyovers, to reply with reservations. This path stays unchanged. Build
   * it once and reuse it for every reply: creating it sets up a cipher per flyover.
   *
   * @param flyovers one slot per hop field in the direction of the reply, so slot 0 is the local
   *     AS. null for a hop without reservation.
   * @throws IllegalArgumentException if the flyovers do not fit this path: a different number of
   *     hop fields, or interfaces other than the ones the routers derive the Ak from
   */
  public HummingbirdResponsePath withFlyovers(Flyover[] flyovers) {
    if (authenticator != null) {
      throw new IllegalStateException("This path already has flyovers.");
    }
    FlyoverAuthenticator.checkFlyovers(getRawPath(), flyovers);
    byte[] template = FlyoverAuthenticator.insertFlyovers(getRawPath(), flyovers);
    return new HummingbirdResponsePath(
        this,
        template,
        getRemoteAddress(),
        getRemotePort(),
        new FlyoverAuthenticator(template, flyovers));
  }

  /**
   * Returns this reply with the flyovers the sender offered for it, in the Go prototype's
   * end-to-end option ReversePath (type 3). This path stays unchanged. The data comes from the
   * network, so unlike {@link #withFlyovers}, data that does not fit this path is not an error: the
   * reply then goes without flyovers, best effort.
   *
   * @param optData the option's data, as {@link HummingbirdRequestPath#withReverseReservation} or
   *     BuildReverseReservationExtn in the Go prototype's pkg/hummingbird/reverse.go writes it
   */
  public HummingbirdResponsePath acceptReverseReservation(byte[] optData) {
    try {
      return this.withFlyovers(parseReverseReservation(optData));
    } catch (IllegalArgumentException
        | UnsupportedOperationException
        | BufferUnderflowException e) {
      log.warn("Could not create a response with reservations");
      return this;
    }
  }

  /**
   * Reads the hops of a serialized Go reservation (serializeHops in pkg/snet/path/hummingbird.go):
   * the hop count, a bitset of the hops that exist, one of the hops with a flyover, then 12 bytes
   * per hop plus 28 per flyover. The SCION MACs after that are not needed, the reversed path has
   * them.
   */
  static Flyover[] parseReverseReservation(byte[] optData) {
    ByteBuffer bb = ByteBuffer.wrap(optData);
    int hops = ByteUtil.toUnsigned(bb.get());
    if (hops > 8) {
      // More hops need bitsets longer than one byte.
      throw new UnsupportedOperationException("Reverse reservation with " + hops + " hops");
    }
    int exists = ByteUtil.toUnsigned(bb.get()); // bit i is hop i
    int hasFlyover = ByteUtil.toUnsigned(bb.get());
    Flyover[] flyovers = new Flyover[hops];
    for (int i = 0; i < hops; i++) {
      if (((exists >> i) & 1) == 0) {
        continue;
      }
      bb.getLong(); // the AS, not needed
      int ingress = ByteUtil.toUnsigned(bb.getShort());
      int egress = ByteUtil.toUnsigned(bb.getShort());
      if (((hasFlyover >> i) & 1) == 0) {
        continue;
      }
      int resIdBw = bb.getInt();
      byte[] ak = new byte[16];
      bb.get(ak);
      long startTime = bb.getInt() & 0xFFFFFFFFL;
      int duration = ByteUtil.toUnsigned(bb.getShort());
      bb.getShort(); // unused: Go reserves 4 bytes for the duration but writes 2
      flyovers[i] =
          new Flyover(ingress, egress, resIdBw >>> 10, resIdBw & 0x3FF, startTime, duration, ak);
    }
    return flyovers;
  }

  @Override
  public int getPathType() {
    return HeaderConstants.PathTypes.HUMMINGBIRD.code();
  }

  @Override
  public void writePath(ByteBuffer buffer, int packetLength) {
    int start = buffer.position();
    super.writePath(buffer, packetLength);
    if (authenticator != null) {
      authenticator.authenticate(buffer, start, getRemoteIsdAs(), packetLength);
    }
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    // The flyover MACs cover the destination AS only, so they hold for any host in it.
    return new HummingbirdResponsePath(this, getRawPath(), dstIP, dstPort, authenticator);
  }
}
