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
 * Talks to a Hummingbird marketplace with the Connect protocol: every call is an HTTP POST of a
 * protobuf message from marketplace.proto to /proto.hummingbird.v1.MarketplaceService/[method].
 * Applications only create it and hand it to {@link FlyoverBuyer}. The calls themselves stay inside
 * this package, so no generated protobuf class becomes part of JPAN's API.
 *
 * <p>Every call uses OkHttp's default timeouts: 10 s each to connect, to send and between reads,
 * and no limit for the whole call.
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
   * @param jwt the token of the marketplace account, sent with every call, in cleartext if {@code
   *     baseUrl} is http
   * @throws IllegalArgumentException if {@code baseUrl} isn't an http(s) URL
   */
  public MarketplaceClient(String baseUrl, String jwt) {
    this(baseUrl, jwt, new OkHttpClient());
  }

  /**
   * For a marketplace with a self-signed certificate, like the test marketplace of a local
   * topology.
   *
   * @param baseUrl for example https://marketplace.local:31888
   * @param jwt the token of the marketplace account, sent with every call, in cleartext if {@code
   *     baseUrl} is http
   * @param certificate the only certificate this client trusts
   * @param address where the host of baseUrl is, for example 127.0.0.1
   * @throws IllegalArgumentException if {@code baseUrl} isn't an http(s) URL, or if the client
   *     cannot be set up to trust {@code certificate}
   */
  public MarketplaceClient(
      String baseUrl, String jwt, X509Certificate certificate, InetAddress address) {
    this(baseUrl, jwt, pinnedClient(HttpUrl.get(baseUrl).host(), certificate, address));
  }

  private MarketplaceClient(String baseUrl, String jwt, OkHttpClient httpClient) {
    HttpUrl.get(baseUrl); // throws unless baseUrl is an http or https URL
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.jwt = jwt;
    // Never resend on our own: a call that reached the marketplace may already have bought.
    this.httpClient = httpClient.newBuilder().retryOnConnectionFailure(false).build();
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
    return search(false, isdAs, ingress, egress, bandwidth, startTime, duration);
  }

  /**
   * Like {@link #searchAssets}, but the assets this account bought and has not redeemed yet. A
   * purchase whose answer got lost shows up here.
   */
  List<Marketplace.SearchAsset> ownedAssets(
      long isdAs, int ingress, int egress, int bandwidth, long startTime, int duration)
      throws IOException {
    return search(true, isdAs, ingress, egress, bandwidth, startTime, duration);
  }

  private List<Marketplace.SearchAsset> search(
      boolean owned,
      long isdAs,
      int ingress,
      int egress,
      int bandwidth,
      long startTime,
      int duration)
      throws IOException {
    List<Marketplace.SearchAsset> assets = new ArrayList<>();
    // The marketplace counts pages from 0 and says whether another one follows.
    for (int page = 0; page < MAX_PAGES; page++) {
      byte[] request =
          Marketplace.SearchAssetsRequest.newBuilder()
              .setOwned(owned)
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
   * The order for one slice of an asset: exactly this time window and bandwidth. It only builds the
   * message for {@link #buyAssets} and sends nothing.
   *
   * @param assetId the asset to cut the slice from, as {@link #searchAssets} returns it
   * @param bandwidth in kbit/s, not a codepoint
   * @param startTime absolute Unix seconds
   * @param duration length of the slice in seconds
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
   * Buys one slice each of several assets, all or none: the marketplace buys nothing if one slice
   * cannot be bought or the total exceeds maxPrice. Every bought slice becomes an asset of its own
   * with a new ID.
   *
   * @param assets what to buy, each slice made with {@link #order}
   * @param maxPrice the most the whole purchase may cost
   * @return the IDs of the bought assets, in the same order as {@code assets}, and the total cost
   *     including fees
   * @throws IOException if the marketplace bought nothing, or if its answer was lost or malformed:
   *     then it may have bought everything, and the assets stay unredeemed on the account
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
    // One ID per order, or the caller cannot tell which ID belongs to which order.
    if (response.getAssetsCount() != assets.size()) {
      throw new IOException(
          "Marketplace answered BuyAssets with "
              + response.getAssetsCount()
              + " asset IDs, expected "
              + assets.size());
    }
    return response;
  }

  /**
   * Turns a bought asset into a reservation, with the authentication key A_K for its flyover.
   * Interfaces and time window are those of the asset.
   *
   * @param assetId the ID of a bought asset, as {@link #buyAssets} returns it
   * @return the authentication key, the reservation ID and the bandwidth as a codepoint
   */
  Marketplace.RedeemAssetResponse redeemAsset(ByteString assetId) throws IOException {
    byte[] request =
        Marketplace.RedeemAssetRequest.newBuilder().setIfPairAssetId(assetId).build().toByteArray();
    return Marketplace.RedeemAssetResponse.parseFrom(post("RedeemAsset", request));
  }

  /**
   * The reservations of this account, in all ASes, that have the bandwidth for the whole time
   * window. A redemption whose answer got lost shows up here.
   *
   * @param bandwidth in kbit/s, not a codepoint
   * @param startTime absolute Unix seconds
   * @param duration length of the time window in seconds
   */
  List<Marketplace.Reservation> fetchReservations(int bandwidth, long startTime, int duration)
      throws IOException {
    byte[] request =
        Marketplace.FetchReservationsRequest.newBuilder()
            .setBandwidth(bandwidth)
            .setStartsAt(Timestamp.newBuilder().setSeconds(startTime).build())
            .setStopsAt(Timestamp.newBuilder().setSeconds(startTime + duration).build())
            .build()
            .toByteArray();
    return Marketplace.FetchReservationsResponse.parseFrom(post("FetchReservations", request))
        .getReservationsList();
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
      // A Connect error is JSON with code and message, but a proxy may send HTML.
      String body = response.body().string();
      String start = body.length() > 200 ? body.substring(0, 200) + "..." : body;
      throw new IOException(
          "Marketplace call " + method + " failed: HTTP " + response.code() + ": " + start);
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
