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
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;

public class ReservedPath extends Path {

  private final Path scionPath;
  private int counter = 0;
  private final Flyover[] flyovers;
  private final HummingbirdMac[] macs; // one per flyover
  private final int[] hopOffsets; // where each flyover's hop field starts in the template

  public ReservedPath(Path scionPath, Flyover[] flyovers) {
    super(
        toHummingbird(scionPath, flyovers),
        scionPath.getFirstHopAddress(),
        scionPath.getLocalIsdAs(),
        scionPath.getRemoteIsdAs(),
        scionPath.getRemoteAddress(),
        scionPath.getRemotePort());
    this.scionPath = scionPath;
    this.flyovers = flyovers.clone();
    this.macs = new HummingbirdMac[flyovers.length];
    this.hopOffsets = new int[flyovers.length];
    // The template never changes, so neither do the positions of its hop fields.
    HummingbirdPathRaw template = HummingbirdPathRaw.create(ByteBuffer.wrap(getRawPath()));
    for (int i = 0; i < flyovers.length; i++) {
      if (flyovers[i] != null) {
        macs[i] = new HummingbirdMac(flyovers[i].getAk());
        hopOffsets[i] = template.getHopFieldOffset(i);
      }
    }
  }

  private static byte[] toHummingbird(Path scionPath, Flyover[] flyovers) {
    if (!(scionPath instanceof RequestPath)) {
      throw new IllegalStateException("The path must be a request path.");
    }
    if (scionPath.getRawPath().length == 0) {
      throw new IllegalArgumentException("Length of Path can't be 0");
    }
    byte[] path = HummingbirdPathConverter.convertFromScion(scionPath.getRawPath(), 0, 0, 0);
    for (int i = 0; i < flyovers.length; i++) {
      Flyover f = flyovers[i];
      if (f != null) {
        path =
            HummingbirdPathConverter.insertFlyover(
                path, i, f.getResID(), f.getBw(), 0, f.getDuration());
      }
    }
    return path;
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

  // synchronized: counter and the arrays in macs change with every packet.
  @Override
  public synchronized void writePath(ByteBuffer buffer, int packetLength) {
    int start = buffer.position();
    buffer.put(getRawPath());
    long now = System.currentTimeMillis();
    int baseTs = (int) (now / 1000);
    int millis = (int) (now % 1000);
    int highResTs = (millis << 22) | counter;
    buffer.putInt(start + 4, baseTs);
    buffer.putInt(start + 8, highResTs);

    for (int i = 0; i < flyovers.length; i++) {
      Flyover f = flyovers[i];
      if (f == null) {
        continue; // no reservation at this hop
      }
      int hop = hopOffsets[i];

      int resStartOffset = (int) (now / 1000 - f.getStartTime());
      buffer.putShort(start + hop + 16, (short) resStartOffset);

      // The SCION MAC is already in the buffer, the aggregated MAC replaces it.
      macs[i].aggregateMac(
          buffer, start + hop + 6, getRemoteIsdAs(), packetLength, resStartOffset, highResTs);
    }

    counter = (counter + 1) & 0x3FFFFF;
  }
}
