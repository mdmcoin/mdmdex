package com.wavesplatform.it.sync.orders

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.order.OrderType.{BUY, SELL}
import com.wavesplatform.dex.settings.AssetType._
import com.wavesplatform.dex.settings.FeeMode.PERCENT

class OrderPercentFeeReceivingTestSuite extends OrderFeeBaseTestSuite {

  val version   = 3.toByte
  val assetType = RECEIVING

  override protected def dexInitialSuiteConfig: Config = ConfigFactory.parseString(s"""
                                                                                      |TN.dex {
                                                                                      |  allowed-order-versions = [1, 2, 3]
                                                                                      |  price-assets = [ "$UsdId", "$BtcId", "TN" ]
                                                                                      |  order-fee.-1 {
                                                                                      |    mode = $PERCENT
                                                                                      |    $PERCENT {
                                                                                      |      asset-type = $assetType
                                                                                      |      min-fee = $percentFee
                                                                                      |    }
                                                                                      |  }
                                                                                      |}""".stripMargin)

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx, IssueBtcTx)
    dex1.start()
  }

  s"V$version orders (fee asset type: $assetType) & fees processing" - {

    s"users should pay correct fee when fee asset-type = $assetType and order fully filled" in {

      val accountBuyer  = createAccountWithBalance(minimalFeeWaves  -> Waves, fullyAmountUsd -> usd)
      val accountSeller = createAccountWithBalance(fullyAmountWaves -> Waves, minimalFee     -> usd)

      placeAndAwaitAtDex(mkOrder(accountBuyer, wavesUsdPair, BUY, fullyAmountWaves, price, matcherFee = minimalFeeWaves, version = version))
      placeAndAwaitAtNode(
        mkOrder(accountSeller, wavesUsdPair, SELL, fullyAmountWaves, price, matcherFee = minimalFee, version = version, feeAsset = usd))

      wavesNode1.api.balance(accountBuyer, Waves) should be(fullyAmountWaves)
      wavesNode1.api.balance(accountBuyer, usd) shouldBe 0L
      wavesNode1.api.balance(accountSeller, Waves) should be(0L)
      wavesNode1.api.balance(accountSeller, usd) shouldBe fullyAmountUsd

      dex1.api.reservedBalance(accountBuyer).getOrElse(Waves, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountBuyer).getOrElse(usd, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountSeller).getOrElse(Waves, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountSeller).getOrElse(usd, 0L) shouldBe 0L
    }

    s"users should pay correct fee when fee asset-type = $assetType and order partially filled" in {

      val accountBuyer  = createAccountWithBalance(minimalFeeWaves      -> Waves, fullyAmountUsd -> usd)
      val accountSeller = createAccountWithBalance(partiallyAmountWaves -> Waves, minimalFee     -> usd)

      placeAndAwaitAtDex(mkOrder(accountBuyer, wavesUsdPair, BUY, fullyAmountWaves, price, matcherFee = minimalFeeWaves, version = version))
      placeAndAwaitAtNode(
        mkOrder(accountSeller, wavesUsdPair, SELL, partiallyAmountWaves, price, matcherFee = minimalFee, version = version, feeAsset = usd))

      wavesNode1.api.balance(accountBuyer, Waves) shouldBe partiallyAmountWaves + (minimalFeeWaves - partiallyFeeWaves)
      wavesNode1.api.balance(accountBuyer, usd) shouldBe fullyAmountUsd - partiallyAmountUsd
      wavesNode1.api.balance(accountSeller, Waves) shouldBe 0L
      wavesNode1.api.balance(accountSeller, usd) shouldBe partiallyAmountUsd

      dex1.api.reservedBalance(accountBuyer).getOrElse(Waves, 0L) shouldBe 1.5.waves // 3.75 - 9 / 15 * 3.75
      dex1.api.reservedBalance(accountBuyer).getOrElse(usd, 0L) shouldBe fullyAmountUsd - partiallyAmountUsd
      dex1.api.reservedBalance(accountSeller).getOrElse(Waves, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountSeller).getOrElse(usd, 0L) shouldBe 0L
      dex1.api.cancelAll(accountBuyer)
    }

    s"order should be processed if amount less then fee when fee asset-type = $assetType" in {
      val accountBuyer  = createAccountWithBalance(minimalFeeWaves  -> Waves, fullyAmountUsd -> usd)
      val accountSeller = createAccountWithBalance(fullyAmountWaves -> Waves, tooHighFee     -> usd)

      placeAndAwaitAtDex(mkOrder(accountBuyer, wavesUsdPair, BUY, fullyAmountWaves, price, matcherFee = minimalFeeWaves, version = version))
      placeAndAwaitAtNode(
        mkOrder(accountSeller, wavesUsdPair, SELL, fullyAmountWaves, price, matcherFee = tooHighFee, version = version, feeAsset = usd))

      wavesNode1.api.balance(accountBuyer, Waves) should be(fullyAmountWaves)
      wavesNode1.api.balance(accountBuyer, usd) shouldBe 0L
      wavesNode1.api.balance(accountSeller, Waves) should be(0L)
      wavesNode1.api.balance(accountSeller, usd) shouldBe fullyAmountUsd

      dex1.api.reservedBalance(accountBuyer).getOrElse(Waves, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountBuyer).getOrElse(usd, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountSeller).getOrElse(Waves, 0L) shouldBe 0L
      dex1.api.reservedBalance(accountSeller).getOrElse(usd, 0L) shouldBe 0L
    }

    s"buy order should be rejected if fee less then minimum possible fee when fee asset-type = $assetType" in {
      dex1.api
        .tryPlace(
          mkOrder(
            createAccountWithBalance(fullyAmountUsd + minimalFee -> usd),
            wavesUsdPair,
            BUY,
            fullyAmountWaves,
            price,
            tooLowFeeWaves,
            version = version
          )
        ) should failWith(9441542, // FeeNotEnough
                          "Required 2.1 TN as fee for this order, but given 2.09 TN")
    }

    s"sell order should be rejected if fee less then minimum possible fee when fee asset-type = $assetType" in {
      dex1.api
        .tryPlace(
          mkOrder(
            createAccountWithBalance(fullyAmountWaves -> Waves, minimalFee -> usd),
            wavesUsdPair,
            SELL,
            fullyAmountWaves,
            price,
            tooLowFee,
            version = version,
            feeAsset = usd
          )
        ) should failWith(9441542, // FeeNotEnough
                          s"Required 2.52 ${UsdId.toString} as fee for this order, but given 2.51 ${UsdId.toString}")
    }
  }
}
