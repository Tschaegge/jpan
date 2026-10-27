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
import java.nio.ByteBuffer;
import org.scion.jpan.Path;
import org.scion.jpan.ResponsePath;
import org.scion.jpan.internal.header.HeaderConstants;

public class HummingbirdResponsePath extends ResponsePath {

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
