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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.scion.jpan.Path;
import org.scion.jpan.PathMetadata;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.proto.hummingbird.Marketplace;

/**
 * Gets flyovers from a Hummingbird marketplace: it searches for an asset, buys the part of it that
 * is needed and redeems that part. Each step is one call of {@link MarketplaceClient}.
 */
public final class FlyoverBuyer {

  private final MarketplaceClient marketplace;
  private final long maxPrice;

  /**
   * A buyer that pays at most maxPrice for one purchase.
   *
   * @param marketplace where the flyovers are bought
   * @param maxPrice the most one purchase of {@link #buyPath}, all flyovers of the path together,
   *     may cost, in the smallest unit of the marketplace's currency (CHF cents on tiny4)
   */
  public FlyoverBuyer(MarketplaceClient marketplace, long maxPrice) {
    this.marketplace = marketplace;
    this.maxPrice = maxPrice;
  }

  /**
   * Buys one flyover for every AS of a SCION path in a single purchase and redeems them. The
   * purchase is all or nothing: if it would cost more than maxPrice, nothing is bought. Redeeming
   * takes one call per flyover, since each AS derives the key for its own flyover. If one call
   * fails, no flyover is returned, but everything stays paid. HummingbirdPathSelector then tries
   * again for the same window, and takes what was paid before it buys anew.
   *
   * @param scionPath a {@link org.scion.jpan.RequestPath} from JPAN's path lookup
   * @param bandwidth in kbit/s, not a codepoint, the same for every AS
   * @param startTime absolute Unix seconds, in the past or the router drops the packets
   * @param duration length of the reservations in seconds, at least; each AS may round it up
   * @return one slot per hop field, ready for {@link HummingbirdRequestPath}. null in the second
   *     hop field of an AS at a segment change, which carries no flyover (Appendix A.5)
   * @throws IOException if an AS has no fitting asset, the purchase costs more than maxPrice or a
   *     call to the marketplace fails
   * @throws IllegalStateException if {@code scionPath} is not a SCION {@link
   *     org.scion.jpan.RequestPath}
   * @throws IllegalArgumentException if the path is empty, as for a destination in the local AS, or
   *     its metadata has more than two interfaces per link
   */
  public Flyover[] buyPath(Path scionPath, int bandwidth, long startTime, int duration)
      throws IOException {
    return buyPath(scionPath, bandwidth, startTime, duration, false);
  }

  /**
   * Like {@link #buyPath(Path, int, long, int)}, but with lookFirst it first takes what this
   * account already has for the window, and buys only for the ASes left: a reservation from an
   * earlier redemption, or else an asset from an earlier purchase that is not redeemed yet. For a
   * retry, whose failed attempt may have bought or redeemed without the answer arriving.
   *
   * @param lookFirst false for a first attempt, which cannot find anything
   * @throws IOException also if a lookup fails; then nothing is bought or redeemed
   */
  Flyover[] buyPath(Path scionPath, int bandwidth, long startTime, int duration, boolean lookFirst)
      throws IOException {
    // The same type-5 bytes the HummingbirdRequestPath will have, so the slots match.
    byte[] rawPath = HummingbirdRequestPath.toHummingbird(scionPath);
    HummingbirdPathRaw path = HummingbirdPathRaw.create(ByteBuffer.wrap(rawPath));
    Flyover[] flyovers = new Flyover[path.getHopFieldCount()];
    // The hop fields carry no ISD-AS, so it comes from the metadata.
    List<PathMetadata.PathInterface> interfaces = scionPath.getMetadata().getInterfaces();
    // One call for all ASes: the marketplace returns every reservation that covers the window.
    List<Marketplace.Reservation> reservations =
        lookFirst
            ? marketplace.fetchReservations(bandwidth, startTime, duration)
            : Collections.<Marketplace.Reservation>emptyList();

    List<Marketplace.BuyAsset> orders = new ArrayList<>();
    List<Integer> slots = new ArrayList<>(); // slots.get(n) is the slot of orders.get(n)
    List<Marketplace.SearchAsset> owned = new ArrayList<>(); // bought before, to redeem
    List<Integer> ownedSlots = new ArrayList<>(); // ownedSlots.get(n) is the slot of owned.get(n)
    int asCount = 0; // ASes so far, not slots: a crossover AS has two slots
    for (int i = 0; i < flyovers.length; i++) {
      if (path.getCrossOver(i) == 1) {
        continue;
      }
      // Two entries per link, so every AS except from the first and last appears twice.
      int ifIndex = asCount == 0 ? 0 : 2 * asCount - 1;
      long isdAs = interfaces.get(ifIndex).getIsdAs();
      asCount++;
      int ingress = path.getFlyoverIngress(i);
      int egress = path.getFlyoverEgress(i);
      if (lookFirst) {
        Marketplace.Reservation reservation =
            findReservation(reservations, isdAs, ingress, egress, startTime);
        if (reservation != null) {
          flyovers[i] = flyover(ingress, egress, reservation);
          continue;
        }
        Marketplace.SearchAsset asset =
            findAsset(
                marketplace.ownedAssets(isdAs, ingress, egress, bandwidth, startTime, duration),
                isdAs,
                ingress,
                egress,
                startTime);
        if (asset != null) {
          owned.add(asset);
          ownedSlots.add(i);
          continue;
        }
      }
      // chooseOrder throws if no asset fits. Nothing is bought or redeemed before the loop ends.
      orders.add(chooseOrder(isdAs, ingress, egress, bandwidth, startTime, duration));
      slots.add(i);
    }
    // Before buying we want to make sure the number of interfaces is what we expect
    if (interfaces.size() != 2 * (asCount - 1)) {
      throw new IllegalArgumentException(
          String.format(
              "The path has %d ASes, so its metadata needs %d interfaces, not %d",
              asCount, 2 * (asCount - 1), interfaces.size()));
    }

    // Assets an earlier attempt bought: redeemed over the window they were bought for.
    for (int n = 0; n < owned.size(); n++) {
      int slot = ownedSlots.get(n);
      Marketplace.SearchAsset asset = owned.get(n);
      flyovers[slot] =
          redeem(
              asset.getAssetId(),
              path.getFlyoverIngress(slot),
              path.getFlyoverEgress(slot),
              asset.getStartsAt().getSeconds(),
              asset.getStopsAt().getSeconds());
    }
    if (orders.isEmpty()) {
      return flyovers; // earlier attempts paid for every AS
    }

    // One purchase for the whole path: the marketplace buys all of it or nothing.
    Marketplace.BuyAssetsResponse bought = marketplace.buyAssets(orders, maxPrice);
    // RedeemAsset has no batch form: each AS derives the key for its own flyover. The response
    // lists the bought assets in the order of the request.
    for (int n = 0; n < orders.size(); n++) {
      int slot = slots.get(n);
      Marketplace.BuyAsset order = orders.get(n);
      flyovers[slot] =
          redeem(
              bought.getAssets(n).getAssetId(),
              path.getFlyoverIngress(slot),
              path.getFlyoverEgress(slot),
              order.getStartsAtExactly().getSeconds(),
              order.getStopsAtExactly().getSeconds());
    }
    return flyovers;
  }

  /**
   * The reservation for this AS and interface pair that starts at startTime, or null. The
   * marketplace returns every reservation that covers the window, also one that started earlier:
   * that one comes from another purchase, maybe of another selector of this account.
   */
  private static Marketplace.Reservation findReservation(
      List<Marketplace.Reservation> reservations,
      long isdAs,
      int ingress,
      int egress,
      long startTime) {
    for (Marketplace.Reservation r : reservations) {
      if (r.getIa() == isdAs
          && r.getIngressId() == ingress
          && r.getEgressId() == egress
          && r.getStartsAt().getSeconds() == startTime) {
        return r;
      }
    }
    return null;
  }

  /** The asset for this AS and interface pair that starts at startTime, or null; as above. */
  private static Marketplace.SearchAsset findAsset(
      List<Marketplace.SearchAsset> assets, long isdAs, int ingress, int egress, long startTime) {
    for (Marketplace.SearchAsset a : assets) {
      if (a.getIa() == isdAs
          && a.getIfIdIngress() == ingress
          && a.getIfIdEgress() == egress
          && a.getStartsAt().getSeconds() == startTime) {
        return a;
      }
    }
    return null;
  }

  /**
   * Redeems a bought asset and turns the reservation into a flyover. The marketplace does not send
   * the interfaces or the time window back, so they come from the caller. The window must be the
   * one bought, which may be longer than the one asked for: the AS derives Ak over it.
   *
   * @param assetId the ID of a bought asset, not the one from the search
   * @param start the start of the bought window, absolute Unix seconds
   * @param stop the end of the bought window, absolute Unix seconds
   * @throws IOException also if the reservation does not fit a flyover hop field
   */
  private Flyover redeem(ByteString assetId, int ingress, int egress, long start, long stop)
      throws IOException {
    Marketplace.RedeemAssetResponse reservation = marketplace.redeemAsset(assetId);
    return flyover(
        ingress,
        egress,
        reservation.getReservationId(),
        reservation.getBwDataplaneEncoding(),
        start,
        stop,
        reservation.getAuthenticationKey());
  }

  /** A flyover from a reservation that an earlier attempt redeemed. */
  private static Flyover flyover(int ingress, int egress, Marketplace.Reservation reservation)
      throws IOException {
    return flyover(
        ingress,
        egress,
        reservation.getReservationId(),
        reservation.getDataplaneEncoding(),
        reservation.getStartsAt().getSeconds(),
        reservation.getStopsAt().getSeconds(),
        reservation.getAuthenticationKey());
  }

  /**
   * A flyover from what the marketplace returned.
   *
   * @throws IOException if the reservation does not fit a flyover hop field
   */
  private static Flyover flyover(
      int ingress, int egress, int resId, int bw, long start, long stop, ByteString ak)
      throws IOException {
    try {
      return new Flyover(
          ingress, egress, resId, bw, start, Math.toIntExact(stop - start), ak.toByteArray());
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new IOException("Marketplace returned an unusable reservation: " + e.getMessage(), e);
    }
  }

  /**
   * Searches for an asset and orders the part of it that is needed: of the assets that still cover
   * the window once it is rounded up to their minimum duration and granularity, the cheapest. The
   * order takes that rounded window and at least the asset's minimum bandwidth.
   *
   * @throws IOException if no asset fits or the search fails
   */
  private Marketplace.BuyAsset chooseOrder(
      long isdAs, int ingress, int egress, int bandwidth, long startTime, int duration)
      throws IOException {
    List<Marketplace.SearchAsset> assets =
        marketplace.searchAssets(isdAs, ingress, egress, bandwidth, startTime, duration);
    Marketplace.SearchAsset cheapest = null;
    long cheapestPrice = Long.MAX_VALUE;
    long cheapestDuration = 0;
    long cheapestBandwidth = 0;
    for (Marketplace.SearchAsset asset : assets) {
      long billable = billableDuration(asset, duration);
      long billableBandwidth = Math.max(bandwidth, Integer.toUnsignedLong(asset.getBandwidthMin()));
      boolean fits =
          billable > 0
              && billable <= 0xFFFF // ResDuration has 16 bits in the flyover hop field
              && asset.getStopsAt().getSeconds() >= startTime + billable
              && Integer.toUnsignedLong(asset.getBandwidth()) >= billableBandwidth;
      long price = price(asset, billableBandwidth, billable);
      if (fits && price < cheapestPrice) { // on a tie the first, as the marketplace sorts by ID
        cheapest = asset;
        cheapestPrice = price;
        cheapestDuration = billable;
        cheapestBandwidth = billableBandwidth;
      }
    }
    if (cheapest == null) {
      throw new IOException(
          String.format(
              "No asset for %s %d>%d with %d kbit/s",
              ScionUtil.toStringIA(isdAs), ingress, egress, bandwidth));
    }
    return MarketplaceClient.order(
        cheapest.getAssetId(), (int) cheapestBandwidth, startTime, (int) cheapestDuration);
  }

  /**
   * The seconds an asset bills and a reservation from it lasts: at least the asset's minimum
   * duration, rounded up to its granularity.
   *
   * @return 0 if the asset has no granularity, so it cannot be bought
   */
  static long billableDuration(Marketplace.SearchAsset asset, int duration) {
    long granularity = Integer.toUnsignedLong(asset.getTimeGranularity());
    if (granularity == 0) {
      return 0;
    }
    long atLeast = Math.max(duration, Integer.toUnsignedLong(asset.getTimeMinDuration()));
    return (atLeast + granularity - 1) / granularity * granularity;
  }

  /**
   * The price the marketplace computes for an order: unit price times bandwidth times seconds. It
   * adds its transaction fee on top, so the amount charged is higher.
   *
   * @return {@link Long#MAX_VALUE} if the product overflows, so such an asset is never the cheapest
   */
  static long price(Marketplace.SearchAsset asset, long bandwidth, long billableDuration) {
    try {
      long unitPrice = Integer.toUnsignedLong(asset.getPrice());
      return Math.multiplyExact(Math.multiplyExact(unitPrice, bandwidth), billableDuration);
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }
}
