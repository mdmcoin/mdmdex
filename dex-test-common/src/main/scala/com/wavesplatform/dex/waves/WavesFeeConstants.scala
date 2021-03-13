package com.wavesplatform.dex.waves

import com.wavesplatform.dex.asset.DoubleOps.NumericOps

object WavesFeeConstants extends WavesFeeConstants

trait WavesFeeConstants {
  val smartFee: Long = 0.04.waves
  val minFee: Long = 0.02.waves
  val leasingFee: Long = 0.02.waves
  val issueFee: Long = 1000.waves
  val smartIssueFee: Long = 1000.waves + smartFee
  val burnFee: Long = 0.2.waves
  val sponsorFee: Long = 10.waves
  val setAssetScriptFee: Long = 1.04.waves
  val setScriptFee: Long = 1.waves
  val transferAmount: Long = 10.waves
  val leasingAmount: Long = transferAmount
  val issueAmount: Long = transferAmount
  val massTransferFeePerTransfer: Long = 0.01.waves
  val massTransferDefaultFee: Long = 0.32.waves
  val someAssetAmount: Long = 9999999999999L
  val matcherFee: Long = 0.04.waves
  val orderFee: Long = matcherFee
  val smartMatcherFee: Long = 0.08.waves
  val smartMinFee: Long = minFee + smartFee
  val invokeScriptFee: Long = 1.waves

  val tradeFee: Long = 0.04.waves
  val smartTradeFee: Long = tradeFee + smartFee
  val twoSmartTradeFee: Long = tradeFee + 2 * smartFee
}
