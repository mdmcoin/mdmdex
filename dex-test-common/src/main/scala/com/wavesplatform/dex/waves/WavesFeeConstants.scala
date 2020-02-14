package com.wavesplatform.dex.waves

import com.wavesplatform.dex.asset.DoubleOps.DoubleOpsImplicits

object WavesFeeConstants extends WavesFeeConstants

trait WavesFeeConstants {
  val smartFee: Long                   = 0.04.TN
  val minFee: Long                     = 0.02.TN
  val leasingFee: Long                 = 0.02.TN
  val issueFee: Long                   = 1000.TN
  val smartIssueFee: Long              = 1000.TN + smartFee
  val burnFee: Long                    = 0.2.TN
  val sponsorFee: Long                 = 10.TN
  val setAssetScriptFee: Long          = 1.TN
  val setScriptFee: Long               = 1.TN
  val transferAmount: Long             = 10.TN
  val leasingAmount: Long              = transferAmount
  val issueAmount: Long                = transferAmount
  val massTransferFeePerTransfer: Long = 0.01.TN
  val massTransferDefaultFee: Long     = 0.02.TN
  val someAssetAmount: Long            = 9999999999999L
  val matcherFee: Long                 = 0.04.TN
  val orderFee: Long                   = matcherFee
  val smartMatcherFee: Long            = 0.08.TN
  val smartMinFee: Long                = minFee + smartFee

  val tradeFee: Long         = 0.04.TN
  val smartTradeFee: Long    = tradeFee + smartFee
  val twoSmartTradeFee: Long = tradeFee + 2 * smartFee
}
