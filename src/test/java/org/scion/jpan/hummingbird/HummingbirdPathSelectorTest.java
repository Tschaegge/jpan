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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionService;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.proto.hummingbird.Marketplace;
import org.scion.jpan.selectors.PathSelector;
import org.scion.jpan.selectors.PathSelectorFactory;
import org.scion.jpan.testutil.MockNetwork2;

/**
 * HummingbirdPathSelector on JPAN's mock tiny4, from AS 112 to AS 111, with a mock marketplace. No
 * router checks the flyovers here; LiveTiny4Test in hbird-conformance does.
 */
class HummingbirdPathSelectorTest {

  private static final ScionSocketAddress REMOTE =
      ScionSocketAddress.from(
          ScionUtil.parseIA("1-ff00:0:111"), InetAddress.getLoopbackAddress(), 12345);

  private final MockWebServer marketplace = new MockWebServer();

  @AfterEach
  void afterEach() throws IOException {
    marketplace.shutdown();
  }

  /** open() buys for the selector's SCION path, close() forgets the Hummingbird path. */
  @Test
  void testOpenBuys() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      HummingbirdPathSelector selector = selector(60, 20);
      int requests = enqueuePurchase();
      selector.open(REMOTE);
      try {
        assertEquals(5, selector.getPath().getPathType());
        assertEquals(requests, marketplace.getRequestCount());
        int ases = (requests - 1) / 2;
        assertEquals(ases, flyoverCount(selector.getPath()), "one flyover per AS");
      } finally {
        selector.close();
      }
      assertNull(selector.getPath());
    }
  }

  /** A failed purchase makes open() throw, and the selector is closed again. */
  @Test
  void testOpenFails() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      HummingbirdPathSelector selector = selector(60, 20);
      marketplace.enqueue(new MockResponse().setResponseCode(503).setBody("{\"code\":\"down\"}"));
      assertThrows(UncheckedIOException.class, () -> selector.open(REMOTE));
      assertFalse(selector.isOpen());
    }
  }

  /**
   * 18 s reservations start 10 s back and are renewed 4 s ahead, so the renewal is due 4 s after
   * the start of the second in which they were bought. Bought at 12.85 s, the start is 2 s, due at
   * 14 s, and the timer, checking once a second, renews 1.2 to 2.2 s later than that: so 3 to 5 s
   * after the purchase. Not after 1 s (an expiry never updated) and not only at expiry (7 s and
   * later).
   */
  @Test
  void testRenews() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      HummingbirdPathSelector selector = selector(18, 4);
      int requests = enqueuePurchase();
      enqueuePurchase();
      enqueuePurchase(); // spare, so that a late renewal does not wait for an answer
      selector.open(REMOTE);
      try {
        Path first = selector.getPath();
        Thread.sleep(2000);
        assertEquals(requests, marketplace.getRequestCount(), "no purchase before it is due");
        for (int i = 0; i < 40 && selector.getPath() == first; i++) {
          Thread.sleep(100);
        }
        assertNotSame(first, selector.getPath(), "renewed before the reservations expired");
        assertEquals(2 * requests, marketplace.getRequestCount());
      } finally {
        selector.close();
      }
    }
  }

  /**
   * A failed renewal keeps the old path, which is still valid, and the timer tries again a second
   * later. 16 s reservations renewed 4 s ahead are due 2 to 3 s after open().
   */
  @Test
  void testRenewalFailureKeepsPath() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      HummingbirdPathSelector selector = selector(16, 4);
      int requests = enqueuePurchase();
      marketplace.enqueue(new MockResponse().setResponseCode(503).setBody("{\"code\":\"down\"}"));
      enqueuePurchase();
      enqueuePurchase(); // spare
      selector.open(REMOTE);
      try {
        Path first = selector.getPath();
        for (int i = 0; i < 40 && marketplace.getRequestCount() <= requests; i++) {
          Thread.sleep(100);
        }
        Thread.sleep(300); // the failed attempt has been handled, the next one is ~0.7 s away
        assertSame(first, selector.getPath(), "the old path stays after a failed renewal");
        for (int i = 0; i < 30 && selector.getPath() == first; i++) {
          Thread.sleep(100);
        }
        assertNotSame(first, selector.getPath(), "the timer tried again");
      } finally {
        selector.close();
      }
    }
  }

  /**
   * The selector renews only while the application sends, which it notices by getPath() calls; the
   * test therefore watches the purchases through the marketplace, not through getPath(). 16 s
   * reservations renewed 4 s ahead are due 2 to 3 s after a purchase. Three phases: one packet, so
   * the first renewal happens; nothing sent for 3.5 s, so no renewal although one is due; one
   * packet again, so the next purchase follows within a second or two.
   */
  @Test
  void testRenewsOnlyWhileSending() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      HummingbirdPathSelector selector = selector(16, 4);
      int requests = enqueuePurchase();
      enqueuePurchase();
      enqueuePurchase();
      enqueuePurchase(); // spare, so that a wrong renewal does not wait for an answer
      selector.open(REMOTE);
      try {
        selector.getPath(); // one packet
        awaitRequests(2 * requests);
        assertEquals(2 * requests, marketplace.getRequestCount(), "renewed while sending");

        Thread.sleep(3500); // nothing sent
        assertEquals(2 * requests, marketplace.getRequestCount(), "no purchase while idle");

        selector.getPath(); // one packet after the pause
        awaitRequests(3 * requests);
        assertEquals(3 * requests, marketplace.getRequestCount(), "bought again once sending");
      } finally {
        selector.close();
      }
    }
  }

  /**
   * The factory's selectors buy with the factory's values: the flyovers last the 16 s it was given.
   * A wrong renewBefore fails in create(), not only when the channel makes the first selector.
   */
  @Test
  void testFactory() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      FlyoverBuyer buyer = buyer();
      assertThrows(
          IllegalArgumentException.class,
          () -> HummingbirdPathSelector.Factory.create(PathPolicy.MIN_HOPS, buyer, 1000, 16, 6));
      PathSelectorFactory factory =
          HummingbirdPathSelector.Factory.create(PathPolicy.MIN_HOPS, buyer, 1000, 16, 4);
      PathSelector selector = factory.createPathSelector(Scion.defaultService());
      assertInstanceOf(HummingbirdPathSelector.class, selector);
      enqueuePurchase();
      selector.open(REMOTE);
      try {
        HummingbirdPathRaw raw =
            HummingbirdPathRaw.create(ByteBuffer.wrap(selector.getPath().getRawPath()));
        assertTrue(raw.getHopField(0).isFlyover());
        assertEquals(16, raw.getHopField(0).getResDuration());
      } finally {
        selector.close();
      }
    }
  }

  /** The path is renewed for the flyover that ends first: each AS may round the duration up. */
  @Test
  void testExpiryOf() {
    Flyover late = new Flyover(0, 1, 1, 1, 1000, 30, new byte[16]);
    Flyover early = new Flyover(2, 1, 2, 1, 1000, 20, new byte[16]);
    assertEquals(1020, HummingbirdPathSelector.expiryOf(new Flyover[] {late, early, null}));
  }

  /** Only ASes and interfaces count: new bytes for the same route keep the flyovers. */
  @Test
  void testSameRoute() {
    long a = ScionUtil.parseIA("1-ff00:0:112");
    long b = ScionUtil.parseIA("1-ff00:0:110");
    long c = ScionUtil.parseIA("1-ff00:0:111");
    byte[] raw = HummingbirdRequestPathTest.TINY4_PATH;
    byte[] otherMac = raw.clone();
    otherMac[otherMac.length - 1] ^= 0x55;
    Path path = FlyoverBuyerTest.path(raw, a, 1, b, 2, b, 1, c, 41);
    assertTrue(sameRoute(path, FlyoverBuyerTest.path(otherMac, a, 1, b, 2, b, 1, c, 41)));
    assertFalse(sameRoute(path, FlyoverBuyerTest.path(raw, a, 1, b, 2, b, 3, c, 41))); // interface
    assertFalse(sameRoute(path, FlyoverBuyerTest.path(raw, a, 1, b, 2, b, 1, a, 41))); // AS
    assertFalse(sameRoute(path, FlyoverBuyerTest.path(raw, a, 1, b, 2))); // shorter
  }

  /** A renewal needs time between buying and renewing: 0 < renewBefore < duration - 10. */
  @Test
  void testRenewBeforeChecked() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      ScionService service = Scion.defaultService();
      PathPolicy policy = PathPolicy.MIN_HOPS;
      assertThrows(
          IllegalArgumentException.class,
          () -> HummingbirdPathSelector.create(service, policy, null, 1000, 60, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> HummingbirdPathSelector.create(service, policy, null, 1000, 60, 50));
      HummingbirdPathSelector.create(service, policy, null, 1000, 60, 49).close();
    }
  }

  private static boolean sameRoute(Path a, Path b) {
    return HummingbirdPathSelector.sameRoute(a, b);
  }

  /** Flyovers in a Hummingbird path: each takes 5 lines instead of 3. */
  private static int flyoverCount(Path path) {
    HummingbirdPathRaw raw = HummingbirdPathRaw.create(ByteBuffer.wrap(path.getRawPath()));
    int lines = raw.getSegLen(0) + raw.getSegLen(1) + raw.getSegLen(2);
    return (lines - 3 * raw.getHopFieldCount()) / 2;
  }

  /** Waits up to 5 s until the marketplace has received at least n requests. */
  private void awaitRequests(int n) throws InterruptedException {
    for (int i = 0; i < 50 && marketplace.getRequestCount() < n; i++) {
      Thread.sleep(100);
    }
  }

  private HummingbirdPathSelector selector(int duration, int renewBefore) throws IOException {
    return HummingbirdPathSelector.create(
        Scion.defaultService(), PathPolicy.MIN_HOPS, buyer(), 1000, duration, renewBefore);
  }

  /** A buyer at the mock marketplace, which this starts. */
  private FlyoverBuyer buyer() throws IOException {
    marketplace.start();
    MarketplaceClient client = new MarketplaceClient(marketplace.url("/").toString(), "test-jwt");
    return new FlyoverBuyer(client, 100000);
  }

  /**
   * Answers for one purchase on the path the selector picks: a search per AS, the purchase, a
   * redemption per AS. Reservation IDs count up, so every purchase gets new flyovers.
   *
   * @return the number of requests this purchase makes
   */
  private int enqueuePurchase() {
    Path scionPath = PathPolicy.MIN_HOPS.filter(Scion.defaultService().getPaths(REMOTE)).get(0);
    int ases = scionPath.getMetadata().getInterfaces().size() / 2 + 1;
    long stopsAt = System.currentTimeMillis() / 1000 + 3600;
    Marketplace.BuyAssetsResponse.Builder bought = Marketplace.BuyAssetsResponse.newBuilder();
    for (int i = 0; i < ases; i++) {
      marketplace.enqueue(FlyoverBuyerTest.found(FlyoverBuyerTest.asset(i, 1, 1, 0, stopsAt)));
      bought.addAssets(Marketplace.BoughtAsset.getDefaultInstance());
    }
    marketplace.enqueue(FlyoverBuyerTest.protoResponse(bought.build()));
    for (int i = 0; i < ases; i++) {
      marketplace.enqueue(FlyoverBuyerTest.redeemed(i, new byte[16]));
    }
    return 2 * ases + 1;
  }
}
