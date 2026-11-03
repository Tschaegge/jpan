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
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.proto.hummingbird.Marketplace;

/**
 * A mock marketplace that answers like the test marketplace of tiny4: a self-signed certificate for
 * marketplace.local, protobuf on success, JSON on error.
 */
class MarketplaceClientTest {

  private static final HeldCertificate MARKETPLACE_CERT = certificate();

  private final MockWebServer server = new MockWebServer();

  @AfterEach
  void afterEach() throws IOException {
    server.shutdown();
  }

  @Test
  void testInfo() throws Exception {
    Marketplace.MarketplaceInfoResponse info =
        Marketplace.MarketplaceInfoResponse.newBuilder()
            .setCurrency("CHF")
            .setCurrencyExponent(2)
            .build();
    start(MARKETPLACE_CERT);
    server.enqueue(
        new MockResponse()
            .addHeader("Content-Type", "application/proto")
            .setBody(new Buffer().write(info.toByteArray())));

    Marketplace.MarketplaceInfoResponse answer = client(MARKETPLACE_CERT).info();
    assertEquals("CHF", answer.getCurrency());
    assertEquals(2, answer.getCurrencyExponent());

    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/proto.hummingbird.v1.MarketplaceService/Info", request.getPath());
    assertEquals("application/proto", request.getHeader("Content-Type"));
    assertEquals("Bearer test-jwt", request.getHeader("Authorization"));
    assertEquals(0, request.getBodySize()); // an empty MarketplaceInfoRequest has no bytes
  }

  /**
   * A search on tiny4 at AS 112 with the pair (0, 1) for 10 s of 1000 kbit/s from 2026-11-03
   * 11:29:26 UTC, as captured with curl. It sends ingress 0 as "18 00": 0 means "starts in this
   * AS", so it must be on the wire.
   */
  private static final String SEARCH_REQUEST =
      "109282808080e07f1800200128e807320608968ba7d7063a0608a08ba7d706";

  /**
   * The answer of the tiny4 marketplace to an earlier search at AS 112 with the pair (0, 1), as
   * captured with curl: one asset, 1 Gbit/s, valid for 100 days.
   */
  @Test
  void testSearchAssets() throws Exception {
    String captured =
        "0a3a0a080000000000000009109282808080e07f1800200128c0843d300a38c0843d420608fd93edd606"
            + "4a0608fdbffcda06500a580a6080a3056801";
    start(MARKETPLACE_CERT);
    server.enqueue(protoResponse(captured));
    long ia112 = ScionUtil.parseIA("1-ff00:0:112");

    List<Marketplace.SearchAsset> assets =
        client(MARKETPLACE_CERT).searchAssets(ia112, 0, 1, 1000, 1793705366L, 10);
    assertEquals(1, assets.size());
    assertEquals(ia112, assets.get(0).getIa());
    assertEquals(1000000, assets.get(0).getBandwidth());

    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/proto.hummingbird.v1.MarketplaceService/SearchAssets", request.getPath());
    assertArrayEquals(hex(SEARCH_REQUEST), request.getBody().readByteArray()); // the same as curl
  }

  /**
   * A purchase on the tiny4 marketplace, as captured with curl: 10 s of 1000 kbit/s from asset 14.
   */
  private static final String BUY_REQUEST =
      "0a1d0a08000000000000000e120608cad7a6d7061a0608d4d7a6d70620e80710a08d06";

  /** The marketplace's answer to BUY_REQUEST: asset 15, cost 10109. */
  @Test
  void testBuyAssets() throws Exception {
    start(MARKETPLACE_CERT);
    server.enqueue(protoResponse("0a0a0a08000000000000000f10fd4e"));
    Marketplace.BuyAssetsRequest order = Marketplace.BuyAssetsRequest.parseFrom(hex(BUY_REQUEST));

    Marketplace.BuyAssetsResponse bought =
        client(MARKETPLACE_CERT).buyAssets(order.getAssetsList(), order.getMaxPrice());
    assertEquals(15, bought.getAssets(0).getAssetId().byteAt(7));
    assertEquals(10109, bought.getCost());

    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/proto.hummingbird.v1.MarketplaceService/BuyAssets", request.getPath());
    assertArrayEquals(hex(BUY_REQUEST), request.getBody().readByteArray()); // the same as curl
  }

  /** The marketplace answers an order it does not fill with 200 and no assets, as seen on tiny4. */
  @Test
  void testBuyAssetsNotBought() throws Exception {
    start(MARKETPLACE_CERT);
    server.enqueue(protoResponse(""));
    Marketplace.BuyAssetsRequest order = Marketplace.BuyAssetsRequest.parseFrom(hex(BUY_REQUEST));
    MarketplaceClient client = client(MARKETPLACE_CERT);
    assertThrows(
        IOException.class, () -> client.buyAssets(order.getAssetsList(), order.getMaxPrice()));
  }

  /**
   * Redeeming asset 15 from testBuyAssets on tiny4. The real 16-byte key is replaced by 00 to 0f.
   */
  @Test
  void testRedeemAsset() throws Exception {
    start(MARKETPLACE_CERT);
    server.enqueue(protoResponse("0a10000102030405060708090a0b0c0d0e0f18ec07209402"));
    com.google.protobuf.ByteString asset15 =
        com.google.protobuf.ByteString.copyFrom(hex("000000000000000f"));

    Marketplace.RedeemAssetResponse reservation = client(MARKETPLACE_CERT).redeemAsset(asset15);
    assertArrayEquals(
        hex("000102030405060708090a0b0c0d0e0f"), reservation.getAuthenticationKey().toByteArray());
    assertEquals(0, reservation.getReservationId()); // not on the wire: 0 is the default
    assertEquals(276, reservation.getBwDataplaneEncoding()); // the codepoint of 1004 kbit/s

    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/proto.hummingbird.v1.MarketplaceService/RedeemAsset", request.getPath());
    assertArrayEquals(hex("1a08000000000000000f"), request.getBody().readByteArray()); // as curl
  }

  private static MockResponse protoResponse(String hex) {
    return new MockResponse()
        .addHeader("Content-Type", "application/proto")
        .setBody(new Buffer().write(ByteString.decodeHex(hex)));
  }

  private static byte[] hex(String hex) {
    return ByteString.decodeHex(hex).toByteArray();
  }

  /** The answer of the tiny4 marketplace to a call without a token, as captured with curl. */
  @Test
  void testError() {
    start(MARKETPLACE_CERT);
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"unauthenticated\",\"message\":\"missing bearer token\"}"));

    IOException e = assertThrows(IOException.class, () -> client(MARKETPLACE_CERT).info());
    assertTrue(e.getMessage().contains("unauthenticated"), e.getMessage());
    assertTrue(e.getMessage().contains("missing bearer token"), e.getMessage());
  }

  /** A server with any other certificate, even one for the same name, is not the marketplace. */
  @Test
  void testRejectsOtherCertificate() {
    start(certificate());
    assertThrows(SSLException.class, () -> client(MARKETPLACE_CERT).info());
  }

  private void start(HeldCertificate serverCert) {
    HandshakeCertificates handshake =
        new HandshakeCertificates.Builder().heldCertificate(serverCert).build();
    server.useHttps(handshake.sslSocketFactory(), false);
    try {
      server.start();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Like curl --cacert server.crt --resolve marketplace.local:port:127.0.0.1. */
  private MarketplaceClient client(HeldCertificate trusted) throws IOException {
    return new MarketplaceClient(
        "https://marketplace.local:" + server.getPort(),
        "test-jwt",
        trusted.certificate(),
        InetAddress.getByName(server.getHostName()));
  }

  private static HeldCertificate certificate() {
    return new HeldCertificate.Builder()
        .commonName("marketplace.local")
        .addSubjectAlternativeName("marketplace.local")
        .build();
  }
}
