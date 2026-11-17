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
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.RequestPath;
import org.scion.jpan.internal.header.HeaderConstants;
import org.scion.jpan.internal.header.HummingbirdPathConverter;

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

  /**
   * Turns a SCION path into a Hummingbird path that sends with the given reservations.
   *
   * @param scionPath a {@link RequestPath} from JPAN's path lookup
   * @param flyovers one slot per hop field, slot 0 is the local AS. null where there is no
   *     reservation, and an empty array for none at all
   * @throws IllegalStateException if {@code scionPath} is not a {@link RequestPath}
   * @throws IllegalArgumentException if the path is empty, as for a destination in the local AS, or
   *     the flyovers do not match it
   */
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
  }

  /** The same path as {@code original}, to dstIP and dstPort. */
  private HummingbirdRequestPath(HummingbirdRequestPath original, InetAddress dstIP, int dstPort) {
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

  @Override
  public PathMetadata getMetadata() {
    return scionPath.getMetadata();
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    // The flyover MACs cover the destination AS only, so they hold for any host in it.
    return new HummingbirdRequestPath(this, dstIP, dstPort);
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
