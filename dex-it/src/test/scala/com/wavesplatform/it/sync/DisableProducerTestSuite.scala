package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.error.FeatureDisabled
import com.wavesplatform.it.MatcherSuiteBase

class DisableProducerTestSuite extends MatcherSuiteBase {

  override protected val dexInitialSuiteConfig: Config = ConfigFactory.parseString(
    """TN.dex.events-queue {
      |  local.enable-storing  = no
      |  kafka.producer.enable = no
      |}""".stripMargin
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    broadcastAndAwait(IssueEthTx)
  }

  "Check no commands are written to queue" - {
    "check assets's balances" in {
      wavesNode1.api.balance(alice, eth) shouldBe IssueEthTx.quantity()
      wavesNode1.api.balance(matcher, eth) shouldBe 0L
    }

    "place an order and wait some time" in {
      def test(order: Order): Unit = dex1.tryApi.place(order) should failWith(FeatureDisabled.code)

      List(
        mkOrder(alice, ethWavesPair, OrderType.SELL, 500, 2.waves * Order.PriceConstant),
        mkOrder(alice, ethWavesPair, OrderType.BUY, 500, 2.waves * Order.PriceConstant)
      ).foreach(test)

      Thread.sleep(5000)

      dex1.api.getCurrentOffset should be(-1)
      dex1.api.getLastOffset should be(-1)
    }

    "Commands aren't written to queue after restart" in {
      dex1.restart()

      dex1.api.getCurrentOffset should be(-1)
      dex1.api.getLastOffset should be(-1)
    }
  }
}
