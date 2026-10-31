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
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
