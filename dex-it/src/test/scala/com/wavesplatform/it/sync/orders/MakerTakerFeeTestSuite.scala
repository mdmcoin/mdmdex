package com.wavesplatform.it.sync.orders

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.asset.Asset
import com.wavesplatform.dex.domain.asset.Asset.Waves
import com.wavesplatform.dex.domain.model.Denormalization._
import com.wavesplatform.dex.domain.model.Normalization._
import com.wavesplatform.dex.domain.order.OrderType.{BUY, SELL}
import com.wavesplatform.dex.error.FeeNotEnough
import com.wavesplatform.it.MatcherSuiteBase
import org.scalatest.prop.TableDrivenPropertyChecks

class MakerTakerFeeTestSuite extends MatcherSuiteBase with TableDrivenPropertyChecks {

  private val maker = bob
  private val taker = alice

  override protected val dexInitialSuiteConfig: Config = ConfigFactory.parseString(
    s"""TN.dex.price-assets = [ "$UsdId", "TN" ]""".stripMargin
  ).withFallback(mkCompositeDynamicFeeSettings(EthId, offset = -1, makerFee = 0.03.waves, takerFee = 0.05.waves))

  override protected def beforeAll(): Unit = {
    wavesNode1.start()

    broadcastAndAwait(IssueUsdTx, IssueEthTx)
    broadcastAndAwait(mkTransfer(alice, bob, 100.eth, eth))

    dex1.start()
    dex1.api.upsertAssetRate(eth, 0.00567593)
  }

  "DEX with static non-default DynamicSettings" - {

    "should reject orders with insufficient fee" in {
      dex1.tryApi.place(mkOrderDP(maker, wavesUsdPair, SELL, 1.waves, 3.00, 0.00499999.waves)) should failWith(
        FeeNotEnough.code,
        s"Required 0.05 TN as fee for this order, but given 0.00499999 TN"
      )

      dex1.tryApi.place(mkOrderDP(maker, wavesUsdPair, SELL, 1.waves, 3.00, 0.00002837.eth, eth)) should failWith(
        FeeNotEnough.code,
        s"Required 0.0002838 $EthId as fee for this order, but given 0.00002837 $EthId"
      )
    }

    "should charge different fees for makers (SELL) and takers (BUY)" in {
      // format: off
      forAll(
        Table(
          ("M amt", "M fee", "M fee asset", "T amt", "T fee", "T fee asset", "M expected balance change", "T expected balance change", "is T market"),
          (1.waves,  0.05, Waves, 1.waves,  0.05, Waves, -1.03.waves,  0.95.waves, false), // symmetric
          (2.waves,  0.05, Waves, 10.waves, 0.05, Waves, -2.03.waves,  1.99.waves, false), // little maker - big taker
          (10.waves, 0.05, Waves, 2.waves,  0.05, Waves, -2.006.waves, 1.95.waves, false), //    big maker - little taker
          (1.waves,  0.05, Waves, 1.waves,  0.05, Waves, -1.03.waves,  0.95.waves, true),  // symmetric, MARKET taker
          (2.waves,  0.05, Waves, 10.waves, 0.05, Waves, -2.03.waves,  1.99.waves, true),  // little maker - big MARKET taker
          (10.waves, 0.05, Waves, 2.waves,  0.05, Waves, -2.006.waves, 1.95.waves, true),  //    big maker - little MARKET taker
          /** fee in ETH, 0.001.waves = 0.00000568.eth, 0.005.waves = 0.00002838.eth */
          (1.waves,  0.0002838, eth, 1.waves,  0.0002838, eth, -0.00017028.eth, -0.0002838.eth, false), // symmetric
          (2.waves,  0.0002838, eth, 10.waves, 0.0002838, eth, -0.00017028.eth, -0.00005676.eth, false), // little maker - big taker
          (10.waves, 0.0002838, eth, 2.waves,  0.0002838, eth, -0.00003405.eth, -0.0002838.eth, false), //    big maker - little taker
          (1.waves,  0.0002838, eth, 1.waves,  0.0002838, eth, -0.00017028.eth, -0.0002838.eth, true),  // symmetric, MARKET taker
          (2.waves,  0.0002838, eth, 10.waves, 0.0002838, eth, -0.00017028.eth, -0.00005676.eth, true),  // little maker - big MARKET taker
          (10.waves, 0.0002838, eth, 2.waves,  0.0002838, eth, -0.00003405.eth, -0.0002838.eth, true)   //    big maker - little MARKET taker
        )
      ) { (mAmt: Long, mFee: Double, mFeeAsset: Asset, tAmt: Long, tFee: Double, tFeeAsset: Asset, mExpectedBalanceChange: Long, tExpectedBalanceChange: Long, isTMarket: Boolean) =>
        // format: on
        val normalizedMakerFee = normalizeAmountAndFee(mFee, assetDecimalsMap(mFeeAsset))
        val normalizedTakerFee = normalizeAmountAndFee(tFee, assetDecimalsMap(tFeeAsset))

        val makerInitialFeeAssetBalance = wavesNode1.api.balance(maker, mFeeAsset)
        val takerInitialFeeAssetBalance = wavesNode1.api.balance(taker, tFeeAsset)

        val makerOrder = mkOrderDP(maker, wavesUsdPair, SELL, mAmt, 3.0, normalizedMakerFee, mFeeAsset)
        val takerOrder = mkOrderDP(taker, wavesUsdPair, BUY, tAmt, 3.0, normalizedTakerFee, tFeeAsset)

        placeAndAwaitAtDex(makerOrder)
        placeAndAwaitAtNode(takerOrder, isMarketOrder = isTMarket)

        dex1.api.cancelAllOrdersWithSig(maker)
        dex1.api.cancelAllOrdersWithSig(taker)

        def printAmount(value: Long, asset: Asset): String = s"${denormalizeAmountAndFee(value, assetDecimalsMap(asset))} $asset"

        withClue(
          s"""
             |maker amount                            = ${printAmount(mAmt, Waves)}
             |maker fee                               = $mFee $mFeeAsset
             |maker initial fee asset balance         = ${printAmount(makerInitialFeeAssetBalance, mFeeAsset)}
             |expected maker fee asset balance change = ${printAmount(mExpectedBalanceChange, mFeeAsset)}
             |expected maker fee asset balance        = ${printAmount(makerInitialFeeAssetBalance + mExpectedBalanceChange, mFeeAsset)}
             |
             |taker amount                            = ${printAmount(tAmt, Waves)}
             |taker fee                               = $tFee $tFeeAsset
             |taker initial fee asset balance         = ${printAmount(takerInitialFeeAssetBalance, tFeeAsset)}
             |expected taker fee asset balance change = ${printAmount(tExpectedBalanceChange, tFeeAsset)}
             |expected taker fee asset balance        = ${printAmount(takerInitialFeeAssetBalance + tExpectedBalanceChange, tFeeAsset)}
             |is taker market                         = $isTMarket
             |
             |""".stripMargin
        ) {
          wavesNode1.api.balance(maker, mFeeAsset) shouldBe makerInitialFeeAssetBalance + mExpectedBalanceChange
          wavesNode1.api.balance(taker, tFeeAsset) shouldBe takerInitialFeeAssetBalance + tExpectedBalanceChange
        }
      }
    }
  }

  "DEX should correctly charge different fees when settings changes" in {

    val offsetInitial = dex1.api.getCurrentOffset
    val offset0 = offsetInitial + 1
    val offset1 = offset0 + 1
    val offset2 = offset1 + 1
    val offset3 = offset2 + 1

    dex1.safeRestartWithNewSuiteConfig(
      ConfigFactory.parseString(
        s""" TN.dex.price-assets = [ "$UsdId", "TN" ] """.stripMargin
      )
        .withFallback(mkCompositeDynamicFeeSettings(EthId, offset = -1, makerFee = 0.04.waves, takerFee = 0.04.waves))
        .withFallback(mkCompositeDynamicFeeSettings(EthId, offset = offset0, makerFee = 0.04.waves, takerFee = 0.04.waves))
        .withFallback(mkCompositeDynamicFeeSettings(EthId, offset = offset1, makerFee = 0.001.waves, takerFee = 0.005.waves))
        .withFallback(mkCompositeDynamicFeeSettings(EthId, offset = offset3, makerFee = 0.002.waves, takerFee = 0.004.waves))
    )

    withClue("maker - DynamicSettings(0.04.waves, 0.04.waves), taker - DynamicSettings(0.03.waves, 0.05.waves), fee in TN") {

      val makerOrder = mkOrderDP(maker, wavesUsdPair, SELL, 10.waves, 3.00, 0.4.waves)
      val takerOrder = mkOrderDP(taker, wavesUsdPair, BUY, 10.waves, 3.00, 0.05.waves)

      dex1.api.getCurrentOffset shouldBe offsetInitial

      placeAndAwaitAtDex(makerOrder)
      dex1.api.getCurrentOffset shouldBe offset0

      val tx = placeAndAwaitAtNode(takerOrder).head
      dex1.api.getCurrentOffset shouldBe offset1

      tx.sellMatcherFee() shouldBe 0.08.waves
      tx.buyMatcherFee() shouldBe 0.05.waves

      dex1.api.cancelAllOrdersWithSig(maker)
      dex1.api.cancelAllOrdersWithSig(taker)
    }

    withClue(
      "maker - DynamicSettings(0.001.waves, 0.005.waves), taker (market, 25% filled) - DynamicSettings(0.002.waves, 0.004.waves), fee in ETH"
    ) {

      dex1.api.getCurrentOffset shouldBe offset1

      val makerOrder = mkOrderDP(maker, wavesUsdPair, SELL, 10.waves, 3.00, 0.00002838.eth, eth) // 0.005.waves = 0.00002838.eth
      val takerOrder = mkOrderDP(taker, wavesUsdPair, BUY, 40.waves, 3.00, 0.00002271.eth, eth) // 0.004.waves = 0.00002271.eth

      placeAndAwaitAtDex(makerOrder)
      dex1.api.getCurrentOffset shouldBe offset2

      val tx = placeAndAwaitAtNode(takerOrder, isMarketOrder = true).head

      tx.sellMatcherFee() shouldBe 0.00001419.eth
      tx.buyMatcherFee() shouldBe 0.00000567.eth

      dex1.api.cancelAllOrdersWithSig(maker)
      dex1.api.cancelAllOrdersWithSig(taker)
    }
  }
}
