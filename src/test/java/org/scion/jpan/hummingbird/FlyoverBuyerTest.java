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

import com.google.protobuf.MessageLite;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.PackageVisibilityHelper;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.hummingbird.Marketplace;

/**
 * A mock marketplace that replays one search, buy and redeem captured with curl on tiny4: at AS 112
 * with the pair (0, 1), 10 s of 1000 kbit/s from 2026-11-03 13:01:01 UTC. The buyPath tests build
 * their answers instead: they only check which AS and pair each slot gets, the rest is buy's.
 */
class FlyoverBuyerTest {

  private static final long IA_112 = ScionUtil.parseIA("1-ff00:0:112");
  private static final long IA_110 = ScionUtil.parseIA("1-ff00:0:110");
  private static final long IA_111 = ScionUtil.parseIA("1-ff00:0:111");
  private static final long START = 1793710861L;
  private static final int RES_ID = 100;

  /** Made up: three ASes in one segment (SegLen 3, ConsDir), hop fields 0>1, 2>3, 4>0. */
  private static final String ONE_SEGMENT_PATH =
      "00003000010000006a7f08be003f00000001000000000000003f00020003000000000000"
          + "003f00040000000000000000";

  private static final String SEARCH_REQUEST =
      "109282808080e07f1800200128e8073206088db6a7d7063a060897b6a7d706";

  /** Assets 17, 19 and 21, all of them cover the window. */
  private static final String SEARCH_RESPONSE =
      "0a390a080000000000000011109282808080e07f1800200128e807300a38c0843d420608d4d7"
          + "a6d7064a0608fdbffcda06500a580a6080a3056801"
          + "0a3a0a080000000000000013109282808080e07f1800200128f0f43c300a38c0843d420608fd"
          + "93edd6064a0608fdbffcda06500a580a6080a3056801"
          + "0a390a080000000000000015109282808080e07f1800200128e807300a38c0843d420608bc84"
          + "a7d7064a0608fdbffcda06500a580a6080a3056801";

  /** The window from asset 17, at most 100000. */
  private static final String BUY_REQUEST =
      "0a1d0a0800000000000000111206088db6a7d7061a060897b6a7d70620e80710a08d06";

  /** Asset 22 for 10109. */
  private static final String BUY_RESPONSE = "0a0a0a08000000000000001610fd4e";

  private static final String REDEEM_REQUEST = "1a080000000000000016";

  /**
   * Reservation ID 0 (not on the wire), 1004 kbit/s as codepoint 276. The real key is replaced by
   * 00 to 0f.
   */
  private static final String REDEEM_RESPONSE = "0a10000102030405060708090a0b0c0d0e0f18ec07209402";

  private final MockWebServer server = new MockWebServer();

  @AfterEach
  void afterEach() throws IOException {
    server.shutdown();
  }

  @Test
  void testBuy() throws Exception {
    server.enqueue(protoResponse(SEARCH_RESPONSE));
    server.enqueue(protoResponse(BUY_RESPONSE));
    server.enqueue(protoResponse(REDEEM_RESPONSE));

    Flyover flyover = buyer().buy(IA_112, 0, 1, 1000, START, 10);
    assertEquals(0, flyover.getIngress());
    assertEquals(1, flyover.getEgress());
    assertEquals(0, flyover.getResID());
    assertEquals(276, flyover.getBw());
    assertEquals(START, flyover.getStartTime());
    assertEquals(10, flyover.getDuration());
    assertArrayEquals(hex("000102030405060708090a0b0c0d0e0f"), flyover.getAk());

    assertRequest("SearchAssets", SEARCH_REQUEST);
    assertRequest("BuyAssets", BUY_REQUEST);
    assertRequest("RedeemAsset", REDEEM_REQUEST);
  }

  /** If no asset covers the window, nothing is bought. */
  @Test
  void testBuyNoAsset() throws Exception {
    server.enqueue(protoResponse(""));
    FlyoverBuyer buyer = buyer();
    assertThrows(IOException.class, () -> buyer.buy(IA_112, 0, 1, 1000, START, 10));
    assertEquals(1, server.getRequestCount()); // only the search
  }

  /** tiny4: AS 110 has two hop fields, only the first carries a flyover (Appendix A.5). */
  @Test
  void testBuyPath() throws Exception {
    Path path =
        path(HummingbirdRequestPathTest.TINY4_PATH, IA_112, 1, IA_110, 2, IA_110, 1, IA_111, 41);
    Flyover[] flyovers = buyPath(path, 3);

    assertSearch(IA_112, 0, 1);
    assertSearch(IA_110, 2, 1);
    assertSearch(IA_111, 41, 0);
    Marketplace.BuyAssetsRequest buy =
        Marketplace.BuyAssetsRequest.parseFrom(server.takeRequest().getBody().readByteArray());
    assertEquals(3, buy.getAssetsCount());
    assertEquals(100000, buy.getMaxPrice()); // per purchase, not per flyover
    assertEquals(RES_ID, flyovers[0].getResID());
    assertEquals(RES_ID + 1, flyovers[1].getResID());
    assertNull(flyovers[2]);
    assertEquals(RES_ID + 2, flyovers[3].getResID());
    for (int n = 0; n < 3; n++) { // each redeem takes the n-th asset of the purchase, not the first
      Marketplace.RedeemAssetRequest redeem =
          Marketplace.RedeemAssetRequest.parseFrom(server.takeRequest().getBody().readByteArray());
      assertEquals(boughtId(n), redeem.getIfPairAssetId());
    }
    // Throws if a flyover is in a slot whose interfaces differ from its own.
    new HummingbirdRequestPath(path, flyovers);
  }

  /** The third AS is in slot 2 but in interface entry 3. On tiny4 slot and entry are equal. */
  @Test
  void testBuyPathOneSegment() throws Exception {
    buyPath(path(hex(ONE_SEGMENT_PATH), IA_112, 1, IA_110, 2, IA_110, 3, IA_111, 4), 3);
    assertSearch(IA_112, 0, 1);
    assertSearch(IA_110, 2, 3);
    assertSearch(IA_111, 4, 0);
  }

  /** Metadata with more interfaces than the bytes have links is refused before paying. */
  @Test
  void testBuyPathWrongMetadata() {
    long[] tooMany = {IA_112, 1, IA_110, 2, IA_110, 1, IA_111, 41, IA_111, 42, IA_112, 2};
    Path path = path(HummingbirdRequestPathTest.TINY4_PATH, tooMany);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> buyPath(path, 3));
    assertEquals("The path has 3 ASes, so its metadata needs 4 interfaces, not 6", e.getMessage());
    assertEquals(3, server.getRequestCount()); // the three searches, no BuyAssets
  }

  /** A request path with the interfaces the control service reports, as (ISD-AS, ID) pairs. */
  static Path path(byte[] raw, long... isdAsAndId) {
    PathMetadata.Builder metadata = PathMetadata.newBuilder().setRaw(raw.clone());
    for (int i = 0; i < isdAsAndId.length; i += 2) {
      metadata.addInterfaces(PathMetadata.PathInterface.create(isdAsAndId[i], isdAsAndId[i + 1]));
    }
    InetSocketAddress dst = new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080);
    return PackageVisibilityHelper.createRequestPath(metadata.build(), dst);
  }

  /** Buys a path of n ASes from a marketplace with an asset for each; answer i gets RES_ID + i. */
  private Flyover[] buyPath(Path path, int n) throws IOException {
    Marketplace.BuyAssetsResponse.Builder bought = Marketplace.BuyAssetsResponse.newBuilder();
    for (int i = 0; i < n; i++) {
      server.enqueue(found(asset(i, 1, 10, 0, START + 3600)));
      bought.addAssets(Marketplace.BoughtAsset.newBuilder().setAssetId(boughtId(i)));
    }
    server.enqueue(protoResponse(bought.build()));
    for (int i = 0; i < n; i++) {
      server.enqueue(redeemed(RES_ID + i, new byte[16]));
    }
    return buyer().buyPath(path, 1000, START, 10);
  }

  /**
   * The cheapest asset by unit price x bandwidth x seconds, after rounding. Asked for 15 s of 1000
   * kbit/s: A costs 2 x 1200 x 20 = 48000, B 1 x 1700 x 30 = 51000, D 1 x 2500 x 20 = 50000, and C
   * ends before the rounded window. Each shortcut picks another one: unit price alone or no
   * rounding B, no minimum bandwidth D, no window check C.
   */
  @Test
  void testBuyCheapest() throws Exception {
    server.enqueue(
        found(
            asset(1, 2, 10, 1200, START + 3600),
            asset(2, 1, 30, 1700, START + 3600),
            asset(3, 1, 10, 0, START + 15),
            asset(4, 1, 10, 2500, START + 3600)));
    server.enqueue(protoResponse(BUY_RESPONSE));
    server.enqueue(redeemed(RES_ID, new byte[16]));

    Flyover flyover = buyer().buy(IA_112, 0, 1, 1000, START, 15);
    assertEquals(20, flyover.getDuration()); // the window bought, which the AS derives Ak over
    server.takeRequest(); // the search
    Marketplace.BuyAsset order =
        Marketplace.BuyAssetsRequest.parseFrom(server.takeRequest().getBody().readByteArray())
            .getAssets(0);
    assertEquals(asset(1, 2, 10, 1200, START + 3600).getAssetId(), order.getAssetId());
    assertEquals(1200, order.getBandwidthExact());
    assertEquals(START + 20, order.getStopsAtExactly().getSeconds());
  }

  /** A reservation that does not fit a flyover is the marketplace's fault: an IOException. */
  @Test
  void testBuyUnusableReservation() throws Exception {
    server.enqueue(found(asset(1, 1, 10, 0, START + 3600)));
    server.enqueue(protoResponse(BUY_RESPONSE));
    server.enqueue(redeemed(RES_ID, new byte[15]));
    FlyoverBuyer buyer = buyer();
    IOException e = assertThrows(IOException.class, () -> buyer.buy(IA_112, 0, 1, 1000, START, 10));
    assertTrue(e.getMessage().contains("ak must be 16 bytes"), e.getMessage());
  }

  /** An asset like those on tiny4: 1 Gbit/s, at least 10 s, from now until stopsAt. */
  static Marketplace.SearchAsset asset(
      int id, int price, int granularity, int bandwidthMin, long stopsAt) {
    return Marketplace.SearchAsset.newBuilder()
        .setAssetId(com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) id}))
        .setBandwidth(1000000)
        .setBandwidthMin(bandwidthMin)
        .setTimeGranularity(granularity)
        .setTimeMinDuration(10)
        .setStopsAt(Timestamp.newBuilder().setSeconds(stopsAt))
        .setPrice(price)
        .build();
  }

  static MockResponse found(Marketplace.SearchAsset... assets) {
    return protoResponse(
        Marketplace.SearchAssetsResponse.newBuilder().addAllAssets(Arrays.asList(assets)).build());
  }

  /** The ID of the n-th asset a mock purchase returns: 20 + n, unlike any search ID. */
  private static com.google.protobuf.ByteString boughtId(int n) {
    return com.google.protobuf.ByteString.copyFrom(new byte[] {(byte) (20 + n)});
  }

  static MockResponse redeemed(int resId, byte[] key) {
    return protoResponse(
        Marketplace.RedeemAssetResponse.newBuilder()
            .setAuthenticationKey(com.google.protobuf.ByteString.copyFrom(key))
            .setReservationId(resId)
            .build());
  }

  private void assertSearch(long isdAs, int ingress, int egress) throws Exception {
    Marketplace.SearchAssetsRequest search =
        Marketplace.SearchAssetsRequest.parseFrom(server.takeRequest().getBody().readByteArray());
    assertEquals(ScionUtil.toStringIA(isdAs), ScionUtil.toStringIA(search.getIa()));
    assertEquals(ingress + ">" + egress, search.getIfIdIngress() + ">" + search.getIfIdEgress());
  }

  /** Over plain HTTP: TLS is tested in MarketplaceClientTest. */
  private FlyoverBuyer buyer() throws IOException {
    server.start();
    MarketplaceClient client = new MarketplaceClient(server.url("/").toString(), "test-jwt");
    return new FlyoverBuyer(client, 100000);
  }

  private void assertRequest(String method, String body) throws InterruptedException {
    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/proto.hummingbird.v1.MarketplaceService/" + method, request.getPath());
    assertArrayEquals(hex(body), request.getBody().readByteArray()); // the same as curl
  }

  private static MockResponse protoResponse(String hex) {
    return new MockResponse()
        .addHeader("Content-Type", "application/proto")
        .setBody(new Buffer().write(ByteString.decodeHex(hex)));
  }

  static MockResponse protoResponse(MessageLite message) {
    return protoResponse(ByteString.of(message.toByteArray()).hex());
  }

  private static byte[] hex(String hex) {
    return ByteString.decodeHex(hex).toByteArray();
  }
}
