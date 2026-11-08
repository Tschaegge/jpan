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
import java.io.UncheckedIOException;
import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.selectors.PathSelectorWithRefresh;

/**
 * A path selector for connected channels that sends with Hummingbird reservations. When the channel
 * connects, it buys a flyover for every AS of the best SCION path in one purchase, and then returns
 * that path as a {@link HummingbirdRequestPath} for every packet. Use it with {@code
 * ScionDatagramChannel.newBuilder().pathSelectorForConnect(...)}.
 *
 * <p>Not yet: renewing the reservations before they expire, and buying again when the SCION path
 * changes.
 */
public class HummingbirdPathSelector extends PathSelectorWithRefresh {

  private final FlyoverBuyer buyer;
  private final int bandwidth;
  private final int duration;
  // Written by open(), read by every write() of the channel.
  private volatile HummingbirdRequestPath path;

  /**
   * @param service finds the SCION paths
   * @param policy ranks the SCION paths, as for {@link PathSelectorWithRefresh}
   * @param buyer buys the flyovers
   * @param bandwidth in kbit/s, for every AS
   * @param duration length of the reservations in seconds
   */
  public static HummingbirdPathSelector create(
      ScionService service, PathPolicy policy, FlyoverBuyer buyer, int bandwidth, int duration) {
    return new HummingbirdPathSelector(service, policy, buyer, bandwidth, duration);
  }

  protected HummingbirdPathSelector(
      ScionService service, PathPolicy policy, FlyoverBuyer buyer, int bandwidth, int duration) {
    super(service, policy);
    this.buyer = buyer;
    this.bandwidth = bandwidth;
    this.duration = duration;
  }

  /**
   * Finds the SCION paths to remote and buys flyovers for the best one.
   *
   * @throws java.io.UncheckedIOException if the purchase fails; the selector is closed again
   */
  @Override
  public void open(ScionSocketAddress remote) {
    super.open(remote);
    Path scionPath = super.getPath(); // not getPath(): that is the Hummingbird path, still null
    if (scionPath == null) {
      return; // no path to remote: the channel reports it on write()
    }
    // In the past: the packet carries BaseTimestamp - start as an unsigned offset, so a future
    // start wraps it and the routers derive a different Ak and drop every packet.
    long start = System.currentTimeMillis() / 1000 - 10;
    try {
      Flyover[] flyovers = buyer.buyPath(scionPath, bandwidth, start, duration);
      path = new HummingbirdRequestPath(scionPath, flyovers);
    } catch (IOException e) {
      close();
      throw new UncheckedIOException(e);
    }
  }

  /**
   * @return the Hummingbird path with the bought flyovers, null before {@link #open} or if there is
   *     no path to the destination
   */
  @Override
  public Path getPath() {
    return path;
  }
}
