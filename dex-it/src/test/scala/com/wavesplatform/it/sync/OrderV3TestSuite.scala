package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.api.http.entities.HttpOrderStatus.Status
import com.wavesplatform.dex.domain.order.OrderType
import com.wavesplatform.dex.error.OrderVersionDenied
import com.wavesplatform.it.MatcherSuiteBase

class OrderV3TestSuite extends MatcherSuiteBase {

  override protected val dexInitialSuiteConfig: Config = allowedOrderVersion(1, 2)

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx)
    dex1.start()
  }

  "settings of allowing orderV3" - {
    val price = 100000000L

    "try to place not allowed orderV3" in {
      val orderV3 = mkOrder(alice, wavesUsdPair, OrderType.BUY, 3, price, version = 3)
      dex1.tryApi.place(orderV3) should failWith(OrderVersionDenied.code, "The orders of version 3 are denied by matcher")
    }

    "matching orderV1 and orderV3" in {
      val orderV1 = mkOrder(alice, wavesUsdPair, OrderType.BUY, 3, price, version = 1)
      placeAndAwaitAtDex(orderV1)

      dex1.restartWithNewSuiteConfig(allowedOrderVersion(1, 2, 3))

      val orderV3 = mkOrder(bob, wavesUsdPair, OrderType.SELL, 2, price, version = 3)
      dex1.api.place(orderV3)

      dex1.api.waitForOrderStatus(orderV1, Status.PartiallyFilled)
      dex1.api.waitForOrderStatus(orderV3, Status.Filled)
    }
  }

  private def allowedOrderVersion(versions: Int*): Config =
    ConfigFactory.parseString(s"""TN.dex {
                                 |  price-assets = [ "$UsdId", "TN" ]
                                 |  allowed-order-versions = [${versions.mkString(", ")}]
                                 |}""".stripMargin)

}
