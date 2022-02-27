package com.wavesplatform.it.matcher.api.http.markets

import sttp.model.StatusCode
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.api.http.entities.HttpOrderStatus.Status
import com.wavesplatform.dex.domain.order.OrderType.SELL
import com.wavesplatform.dex.error.{InvalidAsset, OrderBookBroken}
import com.wavesplatform.dex.it.docker.apiKey
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.it.matcher.api.http.ApiKeyHeaderChecks

class DeleteOrderBookWithKeySpec extends MatcherSuiteBase with ApiKeyHeaderChecks {

  override protected def dexInitialSuiteConfig: Config = ConfigFactory.parseString(
    s"""TN.dex {
       |  price-assets = [ "$UsdId", "TN" ]
       |}""".stripMargin
  )

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx)
    dex1.start()
  }

  "DELETE /matcher/orderbook/{amountAsset}/{priceAsset}" - {

    "should remove order book" in {
      val order = mkOrder(alice, wavesUsdPair, SELL, 10.waves, 1.usd)
      placeAndAwaitAtDex(order)

      validate202Json(dex1.rawApi.deleteOrderBookWithKey(wavesUsdPair)).message should be("Deleting order book")

      eventually {
        dex1.api.waitForOrderStatus(order, Status.Cancelled)
      }
    }

    "should return an error if orderbook doesn't exists" in {
      validateMatcherError(
        dex1.rawApi.deleteOrderBookWithKey(wavesUsdPair),
        StatusCode.ServiceUnavailable,
        OrderBookBroken.code,
        s"The order book for TN-$UsdId is unavailable, please contact with the administrator"
      )
    }

    "should return an error exception when the amount asset is not correct base58 string" in {
      validateMatcherError(
        dex1.rawApi.deleteOrderBookWithKey("null", UsdId.toString, Map("X-API-KEY" -> apiKey)),
        StatusCode.BadRequest,
        InvalidAsset.code,
        s"The asset 'null' is wrong, reason: requirement failed: Wrong char 'l' in Base58 string 'null'"
      )
    }

    "should return an error exception when the price asset is not correct base58 string" in {
      validateMatcherError(
<<<<<<< HEAD:dex-it/src/test/scala/com/wavesplatform/it/matcher/api/http/markets/DeleteOrderBookSpec.scala
        dex1.rawApi.deleteOrderBook("TN", "null", Map("X-API-KEY" -> apiKey)),
=======
        dex1.rawApi.deleteOrderBookWithKey("WAVES", "null", Map("X-API-KEY" -> apiKey)),
>>>>>>> 9aa4136ebc2f545b9314b414eb32c447249b4344:dex-it/src/test/scala/com/wavesplatform/it/matcher/api/http/markets/DeleteOrderBookWithKeySpec.scala
        StatusCode.BadRequest,
        InvalidAsset.code,
        s"The asset 'null' is wrong, reason: requirement failed: Wrong char 'l' in Base58 string 'null'"
      )
    }

<<<<<<< HEAD:dex-it/src/test/scala/com/wavesplatform/it/matcher/api/http/markets/DeleteOrderBookSpec.scala
    shouldReturnErrorWithoutApiKeyHeader(dex1.rawApi.deleteOrderBook("TN", UsdId.toString, Map.empty))

    shouldReturnErrorWithIncorrectApiKeyValue(dex1.rawApi.deleteOrderBook("TN", UsdId.toString, incorrectApiKeyHeader))
=======
    shouldReturnErrorWithoutApiKeyHeader(dex1.rawApi.deleteOrderBookWithKey("WAVES", UsdId.toString, Map.empty))

    shouldReturnErrorWithIncorrectApiKeyValue(dex1.rawApi.deleteOrderBookWithKey("WAVES", UsdId.toString, incorrectApiKeyHeader))
>>>>>>> 9aa4136ebc2f545b9314b414eb32c447249b4344:dex-it/src/test/scala/com/wavesplatform/it/matcher/api/http/markets/DeleteOrderBookWithKeySpec.scala
  }

}
