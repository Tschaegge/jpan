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
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;
import org.scion.jpan.selectors.PathSelectorWithRefresh;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A path selector that sends with Hummingbird reservations. When the channel connects, it buys a
 * flyover for every AS of the best SCION path in one purchase, and then returns that path as a
 * {@link HummingbirdRequestPath} for every packet. Shortly before the reservations expire, it buys
 * new ones and switches to them. When the superclass picks another SCION path, it buys for it if
 * the route changed, and keeps the flyovers otherwise. Use it with {@code
 * ScionDatagramChannel.newBuilder().pathSelectorForConnect(...)}, or through {@link Factory} for
 * {@code send(buffer, address)} without connect.
 *
 * <p>Purchases run on the selector timer, which all path selectors of the JVM share: while one
 * purchase waits for the marketplace, no other selector refreshes its paths.
 */
public class HummingbirdPathSelector extends PathSelectorWithRefresh {

  private static final Logger LOG = LoggerFactory.getLogger(HummingbirdPathSelector.class);

  /** Seconds a reservation starts before it is bought, see {@link #open}. */
  private static final int BACKDATE = 10;

  /** Failed attempts of one purchase before the timer pauses it, see {@link #attempt}. */
  private static final int MAX_ATTEMPTS = 5;

  private final FlyoverBuyer buyer;
  private final int bandwidth;
  private final int duration;
  private final int renewBefore;
  // Written by open() and the timer, read by every write() of the channel.
  private volatile HummingbirdRequestPath path;
  // What path was built from, to rebuild it when the SCION path gets new bytes for the same route.
  private volatile Path scionPath;
  private volatile Flyover[] flyovers;
  // When the first reservation of path expires, in Unix seconds.
  private volatile long expiry;
  private boolean expiryLogged; // only buy() and renew(), which never run at the same time
  private volatile boolean sentSinceBought;
  // The purchase the timer is trying to make, only used under the lock of renew() and close().
  private long roundStart; // the start of every attempt, so that all ask for one window; 0: none
  private int attempts; // failed attempts of this purchase
  private long nextAttemptAt; // Unix seconds, no attempt before
  // The next run of renew(). It has a lock of its own, so that getPath() never waits for a
  // purchase.
  private final Object alarmLock = new Object();
  private Future<?> alarm;

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
    checkRenewBefore(duration, renewBefore);
    this.buyer = buyer;
    this.bandwidth = bandwidth;
    this.duration = duration;
    this.renewBefore = renewBefore;
  }

  /** The rule for renewBefore of {@link #create}, also checked by {@link Factory#create}. */
  private static void checkRenewBefore(int duration, int renewBefore) {
    if (renewBefore <= 0 || renewBefore >= duration - BACKDATE) {
      throw new IllegalArgumentException(
          String.format(
              "renewBefore must be between 0 and %d s for %d s reservations, not %d",
              duration - BACKDATE, duration, renewBefore));
    }
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
      buy(System.currentTimeMillis() / 1000 - BACKDATE);
    } catch (IOException e) {
      close();
      throw new UncheckedIOException(e);
    }
    sentSinceBought = false;
    // Nothing to do until the reservations run low.
    setAlarm(expiry - renewBefore - System.currentTimeMillis() / 1000);
  }

  /**
   * Lets {@link #renew} run once on the selector's timer, in the given number of seconds. The
   * selector has only this one alarm: an alarm that was set before is dropped.
   */
  private void setAlarm(long seconds) {
    synchronized (alarmLock) {
      if (alarm != null) {
        alarm.cancel(false);
      }
      // toMillis() stops at Long.MAX_VALUE instead of overflowing: without flyovers, expiry is
      // Long.MAX_VALUE.
      alarm = addOneTimeTask(this::renew, TimeUnit.SECONDS.toMillis(seconds));
    }
  }

  /** The superclass selected its path, which may be another one: {@link #renew} looks at it now. */
  @Override
  protected void onPathUpdate() {
    if (flyovers != null) { // not during open(), which makes the first purchase itself
      setAlarm(0);
    }
  }

  /**
   * Runs on the selector's timer when there may be something to do, and then sets the alarm for its
   * next run itself. If the selector's SCION path changed, it either keeps the flyovers (same
   * route, new bytes) or buys new ones (new route). Once the reservations expire in less than
   * renewBefore seconds, it buys new ones. It only buys if the application sent since the last
   * purchase, which {@link #getPath()} notes: an idle selector lets its reservations expire and
   * buys again with the next packet. A failed purchase keeps the old path, and {@link #attempt}
   * decides when to try again; after expiry the routers demote every packet.
   */
  private synchronized void renew() {
    if (!isOpen()) {
      return; // closed while this run waited for the lock
    }
    long now = System.currentTimeMillis() / 1000;
    boolean due = now >= expiry - renewBefore;
    try {
      Path current = super.getPath(); // refreshed by the superclass timer
      if (current != null && scionPath != null && !current.equals(scionPath)) {
        if (sameRoute(current, scionPath)) {
          // Ak covers the interfaces and the window, not the SCION hop fields: no purchase.
          path = new HummingbirdRequestPath(current, flyovers);
          scionPath = current;
        } else {
          due = true; // another route needs flyovers of its own
        }
      }
      if (due && sentSinceBought && now >= nextAttemptAt) {
        if (attempt(now)) {
          due = false;
        }
      }
    } catch (Exception e) {
      // Not only IOException: any exception that leaves this method skips the alarm below, and
      // renew() never runs again.
      LOG.warn("Renewing the Hummingbird reservations failed: {}", e.toString());
    }
    if (path != null && now >= expiry && !expiryLogged) {
      LOG.warn("Hummingbird reservations expired, the routers demote every packet until renewed");
      expiryLogged = true;
    }
    // When to run again.
    if (!due) {
      setAlarm(expiry - renewBefore - now); // nothing to do until the reservations run low
    } else if (sentSinceBought) {
      // The purchase failed or pauses. At least a second, so that it can never run in a loop.
      setAlarm(Math.max(1, nextAttemptAt - now));
    }
    // Otherwise a purchase is due, but the application has not sent since the last one: no alarm
    // until getPath() sets it with the next packet.
  }

  /**
   * One attempt of the purchase the timer is trying to make. Every attempt of it asks for the same
   * window, from the start of its first attempt.
   *
   * @return false if the purchase failed
   */
  private boolean attempt(long now) {
    if (roundStart == 0) {
      roundStart = now - BACKDATE;
    }
    try {
      buy(roundStart);
      sentSinceBought = false;
      roundStart = 0;
      attempts = 0;
      nextAttemptAt = 0;
      return true;
    } catch (Exception e) {
      attempts++;
      if (attempts >= MAX_ATTEMPTS) {
        nextAttemptAt = now + duration;
        roundStart = 0;
        attempts = 0;
        LOG.error("Maximum Number of tries exceeded");
      } else {
        nextAttemptAt = now + (1L << (attempts - 1));
      }
      return false;
    }
  }

  /**
   * Buys flyovers for the current best SCION path and switches to them. Does nothing while there is
   * no path to the destination; the channel then reports it on write().
   *
   * @param start of the reservations in Unix seconds, in the past: the packet carries the offset
   *     from start to its BaseTimestamp unsigned, so a future start wraps it, and the routers
   *     derive a different Ak and drop every packet
   */
  private void buy(long start) throws IOException {
    Path current = super.getPath(); // not getPath(): that is the Hummingbird path
    if (current == null) {
      return;
    }
    Flyover[] bought = buyer.buyPath(current, bandwidth, start, duration);
    path = new HummingbirdRequestPath(current, bought);
    scionPath = current;
    flyovers = bought;
    expiry = expiryOf(bought);
    expiryLogged = false;
  }

  /** When the first of the flyovers expires. Each AS may have rounded the duration up. */
  static long expiryOf(Flyover[] flyovers) {
    long first = Long.MAX_VALUE;
    for (Flyover f : flyovers) {
      if (f != null) {
        first = Math.min(first, f.getStartTime() + f.getDuration());
      }
    }
    return first;
  }

  /** Whether both paths cross the same ASes through the same interfaces. */
  static boolean sameRoute(Path a, Path b) {
    List<PathMetadata.PathInterface> x = a.getMetadata().getInterfaces();
    List<PathMetadata.PathInterface> y = b.getMetadata().getInterfaces();
    if (x.size() != y.size()) {
      return false;
    }
    for (int i = 0; i < x.size(); i++) {
      if (x.get(i).getIsdAs() != y.get(i).getIsdAs() || x.get(i).getId() != y.get(i).getId()) {
        return false;
      }
    }
    return true;
  }

  /** Also forgets the path, so that {@link #getPath()} returns null afterwards. */
  @Override
  public synchronized void close() {
    super.close();
    path = null;
    scionPath = null;
    flyovers = null;
    roundStart = 0;
    attempts = 0;
    nextAttemptAt = 0;
  }

  /**
   * @return the Hummingbird path with the bought flyovers, null before {@link #open}, after {@link
   *     #close} or if there is no path to the destination
   */
  @Override
  public Path getPath() {
    if (!sentSinceBought) {
      // The first packet since the last purchase: renew() may be waiting for it to buy again.
      sentSinceBought = true;
      setAlarm(0);
    }
    return path;
  }

  /**
   * Makes a HummingbirdPathSelector for every destination of {@code send(buffer, address)} on a
   * channel that is not connected, with {@code
   * ScionDatagramChannel.newBuilder().pathSelectorsForSend(Factory.create(...))}. The channel keeps
   * one selector per IP address and port. Each buys when the application first sends to its
   * address, so that send waits for the purchase, and two addresses in the same AS cost two
   * purchases. A failed purchase makes that send throw an UncheckedIOException. A selector the
   * application no longer sends to renews at most once more.
   */
  public static class Factory extends PathSelectorFactory.AbstractPathSelectorFactory {

    private final FlyoverBuyer buyer;
    private final int bandwidth;
    private final int duration;
    private final int renewBefore;

    protected Factory(
        PathPolicy policy, FlyoverBuyer buyer, int bandwidth, int duration, int renewBefore) {
      super(policy);
      this.buyer = buyer;
      this.bandwidth = bandwidth;
      this.duration = duration;
      this.renewBefore = renewBefore;
    }

    /**
     * The parameters are those of {@link HummingbirdPathSelector#create}, without the service,
     * which the channel passes to each selector.
     *
     * @throws IllegalArgumentException unless 0 &lt; renewBefore &lt; duration - 10, already here
     *     rather than on the first send
     */
    public static PathSelectorFactory create(
        PathPolicy policy, FlyoverBuyer buyer, int bandwidth, int duration, int renewBefore) {
      checkRenewBefore(duration, renewBefore);
      return new Factory(policy, buyer, bandwidth, duration, renewBefore);
    }

    @Override
    public PathSelector createPathSelector(ScionService service) {
      return HummingbirdPathSelector.create(
          service, getDefaultPolicy(), buyer, bandwidth, duration, renewBefore);
    }
  }
}
