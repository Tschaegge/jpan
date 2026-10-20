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

public class ReservedPath extends Path {

  private final Path scionPath;
  private final FlyoverAuthenticator authenticator;

  public ReservedPath(Path scionPath, Flyover[] flyovers) {
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

  private static byte[] toHummingbird(Path scionPath, Flyover[] flyovers) {
    if (!(scionPath instanceof RequestPath)) {
      throw new IllegalStateException("The path must be a request path.");
    }
    if (scionPath.getRawPath().length == 0) {
      throw new IllegalArgumentException("Length of Path can't be 0");
    }
    byte[] path = HummingbirdPathConverter.convertFromScion(scionPath.getRawPath(), 0, 0, 0);
    return FlyoverAuthenticator.insertFlyovers(path, flyovers);
  }

  @Override
  public PathMetadata getMetadata() {
    return scionPath.getMetadata();
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    throw new UnsupportedOperationException("not needed yet");
  }

  @Override
  public int getPathType() {
    return HeaderConstants.PathTypes.HUMMINGBIRD.code();
  }

  @Override
  public void writePath(ByteBuffer buffer, int packetLength) {
    int start = buffer.position();
    buffer.put(getRawPath());
    authenticator.authenticate(buffer, start, getRemoteIsdAs(), packetLength);
  }
}
