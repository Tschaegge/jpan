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
  private int counter = 0; // low 22 bits of HighResTS, one step per packet

  public ReservedPath(Path scionPath) {

    super(
        toHummingbird(scionPath),
        scionPath.getFirstHopAddress(),
        scionPath.getLocalIsdAs(),
        scionPath.getRemoteIsdAs(),
        scionPath.getRemoteAddress(),
        scionPath.getRemotePort());
    this.scionPath = scionPath;
  }

  private static byte[] toHummingbird(Path scionPath) {
    if (!(scionPath instanceof RequestPath)) {
      throw new IllegalStateException("The path must be a request path.");
    }
    if (scionPath.getRawPath().length == 0) {
      throw new IllegalArgumentException("Length of Path can't be 0");
    }
    return HummingbirdPathConverter.convertFromScion(scionPath.getRawPath(), 0, 0, 0);
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
  public synchronized void writePath(ByteBuffer buffer, int packetLength) {
    int start = buffer.position();
    buffer.put(getRawPath());
    long now = System.currentTimeMillis();
    int baseTs = (int) (now / 1000);
    int millis = (int) (now % 1000);
    buffer.putInt(start + 4, baseTs);
    buffer.putInt(start + 8, (millis << 22) | counter);
    counter = (counter + 1) & 0x3FFFFF;
  }
}
