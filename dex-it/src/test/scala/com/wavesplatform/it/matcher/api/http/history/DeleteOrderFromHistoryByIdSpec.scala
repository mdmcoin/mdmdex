package com.wavesplatform.it.matcher.api.http.history

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.order.OrderType.BUY
import com.wavesplatform.dex.it.api.RawHttpChecks
import com.wavesplatform.it.MatcherSuiteBase

class DeleteOrderFromHistoryByIdSpec extends MatcherSuiteBase with RawHttpChecks {

  override protected def dexInitialSuiteConfig: Config =
    ConfigFactory.parseString(
      s"""TN.dex {
         |  price-assets = [ "$UsdId", "TN" ]
         |}""".stripMargin
    )

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx)
    dex1.start()
  }

  "POST /matcher/orderbook/{amountAsset}/{priceAsset}/delete" - {

    "should delete order from history" in { // only one positive test because this method is deprecated

      val order = mkOrder(alice, wavesUsdPair, BUY, 10.waves, 1.usd)
      placeAndAwaitAtDex(order)

      val r = validate200Json(dex1.rawApi.deleteOrderFromHistoryById(alice, wavesUsdPair, order.idStr()))

      r.orderId should be(order.id())
      r.status should be("OrderDeleted")
    }
  }

}
