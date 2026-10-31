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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.scion.jpan.proto.hummingbird.Marketplace;
import org.scion.jpan.proto.hummingbird.MarketplaceServiceGrpc;

/**
 * Talks to a Hummingbird marketplace with the Connect protocol: every call is an HTTPS POST of a
 * protobuf message from marketplace.proto to /proto.hummingbird.v1.MarketplaceService/[method].
 */
public final class MarketplaceClient {

  private static final String SERVICE_PATH = "/" + MarketplaceServiceGrpc.SERVICE_NAME + "/";

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String jwt;

  /**
   * For a marketplace whose certificate comes from a CA the JVM trusts.
   *
   * @param baseUrl for example https://marketplace.example.org:31888
   * @param jwt the token of the marketplace account, sent with every call
   */
  public MarketplaceClient(String baseUrl, String jwt) {
    this(baseUrl, jwt, new OkHttpClient());
  }

  /**
   * For a marketplace with a self-signed certificate, like the test marketplace of a local
   * topology. *
   *
   * @param baseUrl for example https://marketplace.local:31888
   * @param jwt the token of the marketplace account, sent with every call
   * @param certificate the only certificate this client trusts
   * @param address where the host of baseUrl is, for example 127.0.0.1
   */
  public MarketplaceClient(
      String baseUrl, String jwt, X509Certificate certificate, InetAddress address) {
    this(baseUrl, jwt, pinnedClient(HttpUrl.get(baseUrl).host(), certificate, address));
  }

  private MarketplaceClient(String baseUrl, String jwt, OkHttpClient httpClient) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.jwt = jwt;
    this.httpClient = httpClient;
  }

  /** The attributes of the marketplace, for example its currency. */
  public Marketplace.MarketplaceInfoResponse info() throws IOException {
    byte[] request = Marketplace.MarketplaceInfoRequest.getDefaultInstance().toByteArray();
    return Marketplace.MarketplaceInfoResponse.parseFrom(post("Info", request));
  }

  /**
   * Calls one method of the marketplace.
   *
   * @param method the name of the rpc in marketplace.proto
   * @param request the protobuf bytes of the request message
   * @return the protobuf bytes of the response message
   * @throws IOException if the marketplace cannot be reached or answers with an error
   */
  private byte[] post(String method, byte[] request) throws IOException {

    Request httpRequest =
        new Request.Builder()
            .url(this.baseUrl + SERVICE_PATH + method)
            .addHeader("Content-Type", "application/proto")
            .addHeader("Authorization", "Bearer " + jwt)
            .post(RequestBody.create(request))
            .build();
    try (Response response = httpClient.newCall(httpRequest).execute()) {
      if (response.isSuccessful()) {
        return response.body().bytes();
      }
      String text = response.body().string();
      JsonObject error = JsonParser.parseString(text).getAsJsonObject();
      String code = error.get("code").getAsString();
      String message = error.get("message").getAsString();
      throw new IOException("Marketplace call " + method + " failed: " + code + ": " + message);
    }
  }

  private static OkHttpClient pinnedClient(
      String host, X509Certificate certificate, InetAddress address) {
    try {
      // A trust store with this one certificate, and none of the CAs the JVM trusts.
      KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
      trusted.load(null, null);
      trusted.setCertificateEntry("marketplace", certificate);
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(trusted);
      X509TrustManager trustManager = (X509TrustManager) factory.getTrustManagers()[0];
      SSLContext tls = SSLContext.getInstance("TLS");
      tls.init(null, new TrustManager[] {trustManager}, null);

      // Like curl --resolve: the host of baseUrl is at address, any other name goes to DNS.
      Dns resolve =
          name -> name.equals(host) ? Collections.singletonList(address) : Dns.SYSTEM.lookup(name);
      return new OkHttpClient.Builder()
          .sslSocketFactory(tls.getSocketFactory(), trustManager)
          .dns(resolve)
          .build();
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException("Cannot trust the marketplace certificate", e);
    }
  }
}
