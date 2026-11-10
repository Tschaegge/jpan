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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * Applications only create it and hand it to {@link FlyoverBuyer}; the calls themselves stay inside
 * this package, so no generated protobuf class becomes part of JPAN's API.
 */
public final class MarketplaceClient {

  /** Upper bound for the pages of one search, so a broken marketplace cannot loop forever. */
  static final int MAX_PAGES = 100;

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
   * topology.
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
  Marketplace.MarketplaceInfoResponse info() throws IOException {
    byte[] request = Marketplace.MarketplaceInfoRequest.getDefaultInstance().toByteArray();
    return Marketplace.MarketplaceInfoResponse.parseFrom(post("Info", request));
  }

  /**
   * The assets for sale at one interface pair of an AS that have the bandwidth for the whole time
   * window, from all pages of the results.
   *
   * @param isdAs the AS, as {@link org.scion.jpan.ScionUtil#parseIA} returns it
   * @param ingress interface in travel direction, 0 if the reservation starts in this AS
   * @param egress interface in travel direction, 0 if the reservation ends in this AS
   * @param bandwidth in kbit/s, not a codepoint
   * @param startTime absolute Unix seconds
   * @param duration length of the time window in seconds
   * @throws IOException also if there are more than {@value #MAX_PAGES} pages
   */
  List<Marketplace.SearchAsset> searchAssets(
      long isdAs, int ingress, int egress, int bandwidth, long startTime, int duration)
      throws IOException {
    List<Marketplace.SearchAsset> assets = new ArrayList<>();
    // The marketplace counts pages from 0 and says whether another one follows.
    for (int page = 0; page < MAX_PAGES; page++) {
      byte[] request =
          Marketplace.SearchAssetsRequest.newBuilder()
              .setOwned(false)
              .setIa(isdAs)
              .setIfIdIngress(ingress)
              .setIfIdEgress(egress)
              .setMinRequiredBw(bandwidth)
              .setStartsAtLatest(Timestamp.newBuilder().setSeconds(startTime).build())
              .setStopsAtEarliest(Timestamp.newBuilder().setSeconds(startTime + duration).build())
              .setPage(page)
              .build()
              .toByteArray();
      Marketplace.SearchAssetsResponse response =
          Marketplace.SearchAssetsResponse.parseFrom(post("SearchAssets", request));
      assets.addAll(response.getAssetsList());
      if (!response.getHasMore()) {
        return assets;
      }
    }
    throw new IOException("Marketplace has more than " + MAX_PAGES + " pages of assets");
  }

  /**
   * One part of an asset to buy with {@link #buyAssets}.
   *
   * @param assetId the asset to buy from, as {@link #searchAssets} returns it
   * @param bandwidth in kbit/s, not a codepoint
   * @param startTime absolute Unix seconds
   * @param duration length of the part in seconds
   */
  static Marketplace.BuyAsset order(
      ByteString assetId, int bandwidth, long startTime, int duration) {
    return Marketplace.BuyAsset.newBuilder()
        .setAssetId(assetId)
        .setStartsAtExactly(Timestamp.newBuilder().setSeconds(startTime).build())
        .setStopsAtExactly(Timestamp.newBuilder().setSeconds(startTime + duration).build())
        .setBandwidthExact(bandwidth)
        .build();
  }

  /**
   * Buys parts of assets, all or none: the marketplace buys nothing if one part cannot be bought or
   * the total exceeds maxPrice. Every bought part becomes an asset of its own with a new ID.
   *
   * @param assets what to buy, each part made with {@link #order}
   * @param maxPrice the most the whole purchase may cost, in the unit of {@link #info()}
   * @return the IDs of the bought assets, in the order of the request, and the cost
   * @throws IOException also if the marketplace returns fewer assets than requested
   */
  Marketplace.BuyAssetsResponse buyAssets(List<Marketplace.BuyAsset> assets, long maxPrice)
      throws IOException {
    byte[] request =
        Marketplace.BuyAssetsRequest.newBuilder()
            .addAllAssets(assets)
            .setMaxPrice(maxPrice)
            .build()
            .toByteArray();
    Marketplace.BuyAssetsResponse response =
        Marketplace.BuyAssetsResponse.parseFrom(post("BuyAssets", request));
    if (response.getAssetsCount() < assets.size()) {
      throw new IOException(
          "Marketplace bought " + response.getAssetsCount() + " of " + assets.size() + " assets");
    }
    return response;
  }

  /**
   * Turns a bought asset into a reservation: the AS, or the marketplace on its behalf, derives the
   * authentication key A_K for it. Interfaces and time window are those of the asset.
   *
   * @param assetId the ID of a bought interface-pair asset, as {@link #buyAssets} returns it
   * @return the authentication key, the reservation ID and the bandwidth as a codepoint
   */
  Marketplace.RedeemAssetResponse redeemAsset(ByteString assetId) throws IOException {
    byte[] request =
        Marketplace.RedeemAssetRequest.newBuilder().setIfPairAssetId(assetId).build().toByteArray();
    Marketplace.RedeemAssetResponse response =
        Marketplace.RedeemAssetResponse.parseFrom(post("RedeemAsset", request));
    return response;
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
      String error = describeError(response.code(), response.body().string());
      throw new IOException("Marketplace call " + method + " failed: " + error);
    }
  }

  /**
   * The code and message of a Connect error, {@code {"code": ..., "message": ...}}. A proxy or a
   * crashed server may send something else, then the HTTP status and the start of the body.
   */
  private static String describeError(int httpStatus, String body) {
    try {
      JsonObject error = JsonParser.parseString(body).getAsJsonObject();
      String code = error.get("code").getAsString();
      JsonElement message = error.get("message"); // optional in Connect
      return message == null ? code : code + ": " + message.getAsString();
    } catch (RuntimeException e) { // not JSON, not an object, or no code
      String start = body.length() > 200 ? body.substring(0, 200) + "..." : body;
      return "HTTP " + httpStatus + ": " + start;
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
