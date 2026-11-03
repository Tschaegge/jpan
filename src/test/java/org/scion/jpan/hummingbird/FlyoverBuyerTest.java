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
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;

/**
 * A mock marketplace that replays one search, buy and redeem captured with curl on tiny4: at AS 112
 * with the pair (0, 1), 10 s of 1000 kbit/s from 2026-11-03 13:01:01 UTC.
 */
class FlyoverBuyerTest {

  private static final long IA_112 = ScionUtil.parseIA("1-ff00:0:112");
  private static final long START = 1793710861L;

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

  private static byte[] hex(String hex) {
    return ByteString.decodeHex(hex).toByteArray();
  }
}
