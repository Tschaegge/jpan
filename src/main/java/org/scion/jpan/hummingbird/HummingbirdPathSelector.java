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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A path selector for connected channels that sends with Hummingbird reservations. When the channel
 * connects, it buys a flyover for every AS of the best SCION path in one purchase, and then returns
 * that path as a {@link HummingbirdRequestPath} for every packet. Shortly before the reservations
 * expire, it buys new ones and switches to them. Use it with {@code
 * ScionDatagramChannel.newBuilder().pathSelectorForConnect(...)}.
 *
 * <p>Not yet: buying again as soon as the SCION path changes, and what to send with once a renewal
 * has failed until the old reservations expired.
 */
public class HummingbirdPathSelector extends PathSelectorWithRefresh {

  private static final Logger LOG = LoggerFactory.getLogger(HummingbirdPathSelector.class);

  /** Seconds a reservation starts before it is bought, see {@link #open}. */
  private static final int BACKDATE = 10;

  /** How often the timer checks whether the reservations must be renewed. */
  private static final int CHECK_INTERVAL_MS = 1000;

  private final FlyoverBuyer buyer;
  private final int bandwidth;
  private final int duration;
  private final int renewBefore;
  // Written by open() and the timer, read by every write() of the channel.
  private volatile HummingbirdRequestPath path;
  // When the reservations of path expire, in Unix seconds.
  private volatile long expiry;

  /**
   * @param service finds the SCION paths
   * @param policy ranks the SCION paths, as for {@link PathSelectorWithRefresh}
   * @param buyer buys the flyovers
   * @param bandwidth in kbit/s, for every AS
   * @param duration length of the reservations in seconds
   * @param renewBefore how many seconds before the reservations expire new ones are bought
   * @throws IllegalArgumentException unless 0 &lt; renewBefore &lt; duration - 10: a reservation
   *     starts 10 s before it is bought, so a larger value would renew it right away again
   */
  public static HummingbirdPathSelector create(
      ScionService service,
      PathPolicy policy,
      FlyoverBuyer buyer,
      int bandwidth,
      int duration,
      int renewBefore) {
    return new HummingbirdPathSelector(service, policy, buyer, bandwidth, duration, renewBefore);
  }

  protected HummingbirdPathSelector(
      ScionService service,
      PathPolicy policy,
      FlyoverBuyer buyer,
      int bandwidth,
      int duration,
      int renewBefore) {
    super(service, policy);
    if (renewBefore <= 0 || renewBefore >= duration - BACKDATE) {
      throw new IllegalArgumentException(
          String.format(
              "renewBefore must be between 0 and %d s for %d s reservations, not %d",
              duration - BACKDATE, duration, renewBefore));
    }
    this.buyer = buyer;
    this.bandwidth = bandwidth;
    this.duration = duration;
    this.renewBefore = renewBefore;
  }

  /**
   * Finds the SCION paths to remote and buys flyovers for the best one.
   *
   * @throws java.io.UncheckedIOException if the purchase fails; the selector is closed again
   */
  @Override
  public void open(ScionSocketAddress remote) {
    super.open(remote);
    try {
      buy();
    } catch (IOException e) {
      close();
      throw new UncheckedIOException(e);
    }
    addScheduledTask(this::renew, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS);
  }

  /**
   * Runs every {@link #CHECK_INTERVAL_MS} on the selector's timer. Once the reservations expire in
   * less than renewBefore seconds, buys new ones for the current SCION path and switches to them.
   * If the purchase fails, the old path stays and the next run tries again.
   */
  private void renew() {
    long now = System.currentTimeMillis() / 1000;
    if (now < expiry - renewBefore) {
      return; // the reservations still last long enough
    }
    try {
      buy();
    } catch (Exception e) {
      // Not only IOException: any exception that leaves this method stops the timer task for good.
      LOG.warn("Renewing the Hummingbird reservations failed, trying again in 1 s", e);
    }
  }

  /**
   * Buys flyovers for the current best SCION path and switches to them. Does nothing while there is
   * no path to the destination; the channel then reports it on write().
   */
  private void buy() throws IOException {
    Path scionPath = super.getPath(); // not getPath(): that is the Hummingbird path
    if (scionPath == null) {
      return;
    }
    // In the past: the packet carries BaseTimestamp - start as an unsigned offset, so a future
    // start wraps it and the routers derive a different Ak and drop every packet.
    long start = System.currentTimeMillis() / 1000 - BACKDATE;
    Flyover[] flyovers = buyer.buyPath(scionPath, bandwidth, start, duration);
    path = new HummingbirdRequestPath(scionPath, flyovers);
    expiry = start + duration;
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
