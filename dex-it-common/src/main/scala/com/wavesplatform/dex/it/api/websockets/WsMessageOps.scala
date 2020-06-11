package com.wavesplatform.dex.it.api.websockets

import com.wavesplatform.dex.api.ws.{WsBalances, WsOrder, WsOrderBookChanges}
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.order.Order

trait WsMessageOps {
  implicit class WsBalancesChangesListOps(self: List[Map[Asset, WsBalances]]) {
    def squashed: Map[Asset, WsBalances] = self.foldLeft(Map.empty[Asset, WsBalances])(_ ++ _)
  }

  implicit class WsOrderChangesListOps(self: List[WsOrder]) {
    def squashed: Map[Order.Id, WsOrder] =
      self
        .groupBy(_.id)
        .map {
          case (id, orderChanges) =>
            id -> orderChanges.foldLeft(orderChanges.head) {
              case (acc, oc) =>
                acc.copy(status = oc.status, filledAmount = oc.filledAmount, filledFee = oc.filledFee, avgWeighedPrice = oc.avgWeighedPrice)
            }
        }
  }

  implicit class WsOrderBookChangesListOps(self: List[WsOrderBookChanges]) {
    def squashed: Map[AssetPair, WsOrderBookChanges] = self.foldLeft(Map.empty[AssetPair, WsOrderBookChanges]) {
      case (r, x) =>
        val orig = r.getOrElse(x.assetPair, WsOrderBookChanges.empty(x.assetPair))
        r.updated(
          x.assetPair,
          WsOrderBookChanges(
            assetPair = x.assetPair,
            asks = orig.asks ++ x.asks,
            bids = orig.bids ++ x.bids,
            lastTrade = orig.lastTrade.orElse(x.lastTrade),
            updateId = x.updateId,
            timestamp = x.timestamp,
            settings = orig.settings.orElse(x.settings)
          )
        )
    }
  }
}
