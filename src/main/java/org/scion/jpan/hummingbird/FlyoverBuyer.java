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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.scion.jpan.proto.hummingbird.Marketplace;

/**
 * Gets flyovers from a Hummingbird marketplace: it searches for an asset, buys the part of it that
 * is needed and redeems that part. Each step is one call of {@link MarketplaceClient}.
 */
public final class FlyoverBuyer {

  private final MarketplaceClient marketplace;
  private final long maxPrice;

  /**
   * A buyer that pays at most maxPrice for one flyover.
   *
   * @param marketplace where the flyovers are bought
   * @param maxPrice the most one flyover may cost, in the unit of {@link MarketplaceClient#info()}
   */
  public FlyoverBuyer(MarketplaceClient marketplace, long maxPrice) {
    this.marketplace = marketplace;
    this.maxPrice = maxPrice;
  }

  /**
   * Buys one flyover for one AS from the first asset that has the bandwidth for the whole time
   * window.
   *
   * @param isdAs the AS, as {@link org.scion.jpan.ScionUtil#parseIA} returns it
   * @param ingress interface in travel direction, 0 if the reservation starts in this AS
   * @param egress interface in travel direction, 0 if the reservation ends in this AS
   * @param bandwidth in kbit/s, not a codepoint
   * @param startTime absolute Unix seconds, in the past or the router drops the packets
   * @param duration length of the reservation in seconds
   * @return the flyover, with the key the AS derived for it
   * @throws IOException if no asset fits or a call to the marketplace fails
   */
  public Flyover buy(
      long isdAs, int ingress, int egress, int bandwidth, long startTime, int duration)
      throws IOException {
    List<Marketplace.SearchAsset> assets =
        marketplace.searchAssets(isdAs, ingress, egress, bandwidth, startTime, duration);
    if (assets.size() < 1) {
      throw new IOException("Empty Asset List");
    }
    Marketplace.SearchAsset chosenAsset = assets.get(0); // improve for cheapest instead of first
    Marketplace.BuyAsset order =
        MarketplaceClient.order(chosenAsset.getAssetId(), bandwidth, startTime, duration);
    Marketplace.BuyAssetsResponse bought =
        marketplace.buyAssets(Collections.singletonList(order), maxPrice);
    Marketplace.RedeemAssetResponse reservation =
        marketplace.redeemAsset(bought.getAssets(0).getAssetId());
    return new Flyover(
        ingress,
        egress,
        reservation.getReservationId(),
        reservation.getBwDataplaneEncoding(),
        startTime,
        duration,
        reservation.getAuthenticationKey().toByteArray());
  }
}
