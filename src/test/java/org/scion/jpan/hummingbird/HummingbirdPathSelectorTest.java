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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.Path;
import org.scion.jpan.PathPolicy;
import org.scion.jpan.Scion;
import org.scion.jpan.ScionSocketAddress;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.hummingbird.Marketplace;
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
   * 13 s reservations start 10 s back, so they expire 3 s after open(); renewed 1 s ahead, the
   * timer buys again after about 2 s and switches to the new path.
   */
  @Test
  void testRenews() throws Exception {
    try (MockNetwork2 nw = MockNetwork2.startPS(MockNetwork2.Topology.TINY4, "ASff00_0_112")) {
      HummingbirdPathSelector selector = selector(13, 1);
      int requests = enqueuePurchase();
      enqueuePurchase();
      enqueuePurchase(); // spare, so that a late third renewal does not wait for an answer
      selector.open(REMOTE);
      try {
        Path first = selector.getPath();
        for (int i = 0; i < 50 && selector.getPath() == first; i++) {
          Thread.sleep(100);
        }
        assertNotSame(first, selector.getPath());
        assertEquals(2 * requests, marketplace.getRequestCount());
      } finally {
        selector.close();
      }
    }
  }

  private HummingbirdPathSelector selector(int duration, int renewBefore) throws IOException {
    marketplace.start();
    MarketplaceClient client = new MarketplaceClient(marketplace.url("/").toString(), "test-jwt");
    FlyoverBuyer buyer = new FlyoverBuyer(client, 100000);
    return HummingbirdPathSelector.create(
        Scion.defaultService(), PathPolicy.MIN_HOPS, buyer, 1000, duration, renewBefore);
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
