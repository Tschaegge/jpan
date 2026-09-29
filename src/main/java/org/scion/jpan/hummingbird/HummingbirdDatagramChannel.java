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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionDatagramChannel;
import org.scion.jpan.ScionService;
import org.scion.jpan.internal.header.HummingbirdPacket;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;
import org.scion.jpan.selectors.PathSelectorWithRefresh;

/**
 * A {@link ScionDatagramChannel} that sends every packet as a Hummingbird packet (SCION path type
 * 5). JPAN builds the SCION packet as usual, and {@link #sendRaw} rewrites it just before it goes
 * to the network. There are no flyovers yet, so the routers forward it best effort.
 */
public class HummingbirdDatagramChannel extends ScionDatagramChannel {

  // One per channel, reused for every packet. JPAN never builds a SCION packet larger than
  // DEFAULT_BUFFER_SIZE, and without flyovers the Hummingbird packet is 8 bytes longer (meta header
  // 12 instead of 4 bytes). Each flyover will add 8 more. sendRaw still grows the buffer should
  // JPAN ever hand over a larger packet.
  private ByteBuffer hbirdBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE + 8);

  // The low 22 bits of HighResTS, one step per packet, so that packets sent in the same
  // millisecond still differ. Wraps like the reference's counter (pkg/snet/path/hummingbird.go).
  // Only sendRaw touches it, and JPAN calls sendRaw under the channel's write lock.
  private int counter = 0;

  protected HummingbirdDatagramChannel(
      ScionService service,
      DatagramChannel channel,
      PathSelector connectSelector,
      PathSelectorFactory factory)
      throws IOException {
    super(service, channel, connectSelector, factory);
  }

  public static HummingbirdDatagramChannel open() throws IOException {
    return open(Scion.defaultService());
  }

  public static HummingbirdDatagramChannel open(ScionService service) throws IOException {
    return open(service, DatagramChannel.open());
  }

  /** Uses the same path selectors as {@code ScionDatagramChannel.Builder.open()}. */
  public static HummingbirdDatagramChannel open(ScionService service, DatagramChannel channel)
      throws IOException {
    PathSelector selector = null;
    PathSelectorFactory factory = null;
    if (service != null) {
      selector = PathSelectorWithRefresh.create(service, PathPolicy.DEFAULT);
      factory = PathSelectorWithRefresh.Factory.create(PathPolicy.DEFAULT);
    }
    return new HummingbirdDatagramChannel(service, channel, selector, factory);
  }

  /**
   * Receives the finished SCION packet from JPAN and sends it as a Hummingbird packet instead.
   * Reports back as if the SCION packet had been sent, because JPAN's {@code send()} and {@code
   * write()} compute their results from the packet they built.
   */
  @Override
  protected int sendRaw(ByteBuffer scionPacket, Path path) throws IOException {
    if (path.getRawPath().length == 0) {
      // No path, the destination is in the local AS: nothing to reserve, stays SCION.
      return super.sendRaw(scionPacket, path);
    }
    int scionLength = scionPacket.remaining();
    int requiredSize = scionLength + 8; // the meta header grows from 4 to 12 bytes
    if (hbirdBuffer.capacity() < requiredSize) {
      hbirdBuffer = ByteBuffer.allocateDirect(requiredSize);
    }
    hbirdBuffer.clear();
    long now = System.currentTimeMillis();
    HummingbirdPacket.fromScion(scionPacket, hbirdBuffer, now / 1000, (int) (now % 1000), counter);
    counter = (counter + 1) & 0x3FFFFF; // 22 bits
    hbirdBuffer.flip();

    int hbirdLength = hbirdBuffer.remaining();
    int sent = super.sendRaw(hbirdBuffer, path);
    if (sent != hbirdLength) {
      return 0; // a datagram is sent completely or not at all
    }
    scionPacket.position(scionPacket.limit()); // write() checks that its buffer was consumed
    return scionLength;
  }
}
