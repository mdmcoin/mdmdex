package com.wavesplatform.dex.model

import cats.data.EitherT
import cats.implicits.catsStdInstancesForFuture
import cats.instances.long.catsKernelStdGroupForLong
import cats.instances.map.catsKernelStdCommutativeMonoidForMap
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.semigroup.catsSyntaxSemigroup
import com.wavesplatform.dex.actors.orderbook.AggregatedOrderBookActor.MarketStatus
import com.wavesplatform.dex.caches.RateCache
import com.wavesplatform.dex.domain.account.{Address, PublicKey}
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.crypto.Verifier
import com.wavesplatform.dex.domain.feature.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.dex.domain.model.Normalization
import com.wavesplatform.dex.domain.model.Normalization._
import com.wavesplatform.dex.domain.order.OrderOps._
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.domain.transaction.ExchangeTransaction
import com.wavesplatform.dex.domain.utils.{EitherExt2, ScorexLogging}
import com.wavesplatform.dex.effect._
import com.wavesplatform.dex.error
import com.wavesplatform.dex.error._
import com.wavesplatform.dex.grpc.integration.clients.{RunScriptResult, WavesBlockchainClient}
import com.wavesplatform.dex.grpc.integration.dto.BriefAssetDescription
import com.wavesplatform.dex.metrics.TimerExt
import com.wavesplatform.dex.model.Events.OrderExecuted
import com.wavesplatform.dex.settings.OrderFeeSettings._
import com.wavesplatform.dex.settings.{AssetType, DeviationsSettings, MatcherSettings, OrderFeeSettings, OrderRestrictionsSettings}
import com.wavesplatform.dex.time.Time
import kamon.Kamon

import scala.Either.cond
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode

object OrderValidator extends ScorexLogging {

  type Result[T] = Either[MatcherError, T]

  private val timer = Kamon.timer("matcher.validation").withTag("type", "blockchain")

  val MinExpiration: Long = 60 * 1000L

  val exchangeTransactionCreationFee: Long = 4000000L
  val ScriptExtraFee = 4000000L

  private[dex] def multiplyAmountByDouble(a: Long, d: Double): Long = (BigDecimal(a) * d).setScale(0, RoundingMode.HALF_UP).toLong
  private[dex] def multiplyPriceByDouble(p: Long, d: Double): Long = (BigDecimal(p) * d).setScale(0, RoundingMode.HALF_UP).toLong
  private[dex] def multiplyFeeByBigDecimal(f: Long, d: BigDecimal): Long = (BigDecimal(f) * d).setScale(0, RoundingMode.CEILING).toLong
  private[dex] def multiplyByDiscount(a: Long, d: BigDecimal): Long = (BigDecimal(a) * d).setScale(0, RoundingMode.CEILING).toLong

  private def verifySignature(order: Order): FutureResult[Unit] = liftAsync {
    Verifier
      .verifyAsEllipticCurveSignature(order)
      .bimap(
        e => error.OrderInvalidSignature(order.id(), e.toString),
        _ => ()
      )
  }

  private def verifyOrderByAccountScript(blockchain: WavesBlockchainClient, address: Address, order: Order, handleProofs: Order => Order)(
    implicit ec: ExecutionContext
  ): FutureResult[Unit] = {

    lazy val verifyAddressScript: FutureResult[Unit] = {

      lazy val verifyScript: FutureResult[Unit] = {
        if (order.version <= 1) liftErrorAsync[Unit](error.AccountNotSupportOrderVersion(address, 2, order.version))
        else
          liftFutureAsync(blockchain.runScript(address, handleProofs(order))) subflatMap {
            case RunScriptResult.ScriptError(execError) => error.AccountScriptReturnedError(address, execError).asLeft
            case RunScriptResult.Denied => error.AccountScriptDeniedOrder(address).asLeft
            case RunScriptResult.Allowed => success
            case RunScriptResult.UnexpectedResult(x) => error.AccountScriptUnexpectResult(address, x).asLeft
            case RunScriptResult.Exception(name, message) => error.AccountScriptException(address, name, message).asLeft
          }
      }

      liftFutureAsync(blockchain.isFeatureActivated(BlockchainFeatures.SmartAccountTrading.id))
        .ifM(verifyScript, liftErrorAsync[Unit](error.AccountFeatureUnsupported(BlockchainFeatures.SmartAccountTrading)))
    }

    liftFutureAsync(blockchain.hasScript(address)).ifM(verifyAddressScript, verifySignature(order))
  }

  private def verifySmartToken(blockchain: WavesBlockchainClient, asset: IssuedAsset, tx: ExchangeTransaction, hasAssetScript: Asset => Boolean)(
    implicit ec: ExecutionContext
  ): FutureResult[Unit] = {

    lazy val verifySmartAssetScript: FutureResult[Unit] = {

      lazy val verifyScript: FutureResult[Unit] = liftFutureAsync(blockchain.runScript(asset, tx)) subflatMap {
        case RunScriptResult.ScriptError(execError) => error.AssetScriptReturnedError(asset, execError).asLeft
        case RunScriptResult.Denied => error.AssetScriptDeniedOrder(asset).asLeft
        case RunScriptResult.Allowed => success
        case RunScriptResult.UnexpectedResult(x) => error.AssetScriptUnexpectResult(asset, x).asLeft
        case RunScriptResult.Exception(name, message) => error.AssetScriptException(asset, name, message).asLeft
      }

      liftFutureAsync(blockchain.isFeatureActivated(BlockchainFeatures.SmartAssets.id))
        .ifM(verifyScript, liftErrorAsync[Unit](error.AssetFeatureUnsupported(BlockchainFeatures.SmartAssets, asset)))
    }

    liftValueAsync(hasAssetScript(asset)).ifM(verifySmartAssetScript, successAsync)
  }

  private def validateDecimals(assetDecimals: Asset => Int, o: Order)(implicit ec: ExecutionContext): FutureResult[(Int, Int)] = liftAsync {

    val pd = assetDecimals(o.assetPair.priceAsset)
    val ad = assetDecimals(o.assetPair.amountAsset)

    val insignificantDecimals = (pd - ad).max(0)

    cond(o.price % BigDecimal(10).pow(insignificantDecimals).toLongExact == 0, ad -> pd, error.PriceLastDecimalsMustBeZero(insignificantDecimals))
  }

  private def validateAmountAndPrice(order: Order, decimalsPair: (Int, Int), orderRestrictions: Option[OrderRestrictionsSettings])(implicit
    ec: ExecutionContext,
    efc: ErrorFormatterContext
  ): FutureResult[Order] =
    orderRestrictions.fold(liftValueAsync(order)) { restrictions =>
      val (amountAssetDecimals, priceAssetDecimals) = decimalsPair

      def normalizeAmount(amt: Double): Long = normalizeAmountAndFee(amt, amountAssetDecimals)
      def normalizePrice(prc: Double): Long = Normalization.normalizePrice(prc, amountAssetDecimals, priceAssetDecimals)

      liftValueAsync(order)
        .ensure(error.OrderInvalidAmount(order, restrictions)) { o =>
          normalizeAmount(restrictions.minAmount) <= o.amount && o.amount <= normalizeAmount(restrictions.maxAmount) &&
          o.amount % normalizeAmount(restrictions.stepAmount).max(1) == 0
        }
        .ensure(error.OrderInvalidPrice(order, restrictions)) { o =>
          normalizePrice(restrictions.minPrice) <= o.price && o.price <= normalizePrice(restrictions.maxPrice) &&
          o.price % normalizePrice(restrictions.stepPrice).max(1) == 0
        }
    }

  private[dex] def checkOrderVersion(version: Byte, isFeatureActivated: Short => Future[Boolean])(implicit
    ec: ExecutionContext
  ): FutureResult[Byte] = {

    def checkFeatureSupport(feature: BlockchainFeature): FutureResult[Byte] =
      liftFutureAsync(isFeatureActivated(feature.id))
        .ifM(liftValueAsync(version), liftErrorAsync(error.OrderVersionUnsupported(version, feature)))

    version match {
      case 1 => liftValueAsync(version)
      case 2 => checkFeatureSupport(BlockchainFeatures.SmartAccountTrading)
      case 3 => checkFeatureSupport(BlockchainFeatures.OrderV3)
      case _ => liftErrorAsync(error.UnsupportedOrderVersion(version))
    }
  }

  def blockchainAware(
    blockchain: WavesBlockchainClient,
    transactionCreator: ExchangeTransactionCreator.CreateTransaction,
    time: Time,
    orderFeeSettings: OrderFeeSettings,
    orderRestrictions: Option[OrderRestrictionsSettings],
    assetDescriptions: Asset => BriefAssetDescription,
    rateCache: RateCache,
    hasMatcherAccountScript: Boolean,
    handleProofs: Order => Order
  )(order: Order)(implicit ec: ExecutionContext, efc: ErrorFormatterContext): FutureResult[Order] =
    timer.measure {

      val assetPair = order.assetPair
      val amountAsset = assetPair.amountAsset
      val priceAsset = assetPair.priceAsset
      val feeAsset = order.feeAsset

      lazy val exchangeTx: Result[ExchangeTransaction] = {
        val fakeOrder: Order = order.updateType(order.orderType.opposite)
        val oe: OrderExecuted =
          OrderExecuted(
            LimitOrder(fakeOrder),
            LimitOrder(order),
            time.correctedTime(),
            order.matcherFee,
            order.matcherFee,
            None // don't need it at validation level
          )
        transactionCreator(oe).toEither leftMap (txValidationError => error.CanNotCreateExchangeTransaction(txValidationError.toString))
      }

      def verifyAssetScript(assetId: Asset): FutureResult[Unit] = assetId.fold(successAsync) { assetId =>
        liftAsync(exchangeTx).flatMap(verifySmartToken(blockchain, assetId, _, assetDescriptions(_).hasScript))
      }

      lazy val verifyMatcherFeeAssetScript: FutureResult[Unit] = {
        if (feeAsset == amountAsset || feeAsset == priceAsset) successAsync else verifyAssetScript(feeAsset)
      }

      /** Checks whether order fee is enough to cover matcher's expenses for the Exchange transaction issue */
      lazy val validateOrderFeeByTransactionRequirements: FutureResult[Order] =
        liftAsync {
          for {
            minFee <-
              getMinValidTxFeeForSettings(
                OrderParams.fromOrder(order),
                orderFeeSettings,
                hasMatcherAccountScript,
                assetDescriptions,
                rateCache
              )
            _ <- cond(order.matcherFee >= minFee, order, error.FeeNotEnough(minFee, order.matcherFee, feeAsset))
          } yield order
        }

      for {
        _ <- checkOrderVersion(order.version, blockchain.isFeatureActivated)
        _ <- validateOrderFeeByTransactionRequirements
        decimalsPair <- validateDecimals(assetDescriptions(_).decimals, order)
        _ <- validateAmountAndPrice(order, decimalsPair, orderRestrictions)
        _ <- verifyOrderByAccountScript(blockchain, order.sender, order, handleProofs)
        _ <- verifyAssetScript(amountAsset)
        _ <- verifyAssetScript(priceAsset)
        _ <- verifyMatcherFeeAssetScript
      } yield order
    }

  private[dex] def isDiscountAsset(
    asset: Asset,
    orderFeeSettings: OrderFeeSettings
  ): Boolean =
    orderFeeSettings match {
      case cs: CompositeSettings => cs.discount.exists(_.asset == asset)
      case _ => false
    }

  @tailrec
  private[dex] def getValidFeeAssetsForSettings(
    assetPair: AssetPair,
    orderType: OrderType,
    orderFeeSettings: OrderFeeSettings,
    maybeDiscountAsset: Option[Asset] = None
  ): Set[Asset] =
    orderFeeSettings match {
      case _: DynamicSettings => Set(Waves) ++ maybeDiscountAsset
      case FixedSettings(assetId, _) => Set(assetId)
      case ps: PercentSettings => Set(ps.getFeeAsset(assetPair, orderType)) ++ maybeDiscountAsset
      case cs: CompositeSettings =>
        getValidFeeAssetsForSettings(assetPair, orderType, cs.getOrderFeeSettings(assetPair), cs.discount.map(_.asset))
    }

  /** Converts fee in waves to fee in the specified asset, taking into account correction by the asset decimals */
  private[dex] def convertFeeByAssetRate(feeInWaves: Long, asset: Asset, assetDecimals: Int, rateCache: RateCache): Result[Long] =
    asset.fold(lift(feeInWaves)) { issuedAsset =>
      rateCache.getRate(issuedAsset) map { assetRate =>
        multiplyFeeByBigDecimal(
          feeInWaves,
          MatcherModel.correctRateByAssetDecimals(assetRate, assetDecimals)
        )
      } toRight error.RateNotFound(asset)
    }

  private[dex] def getMinValidFeeForPercentFeeSettings(
    orderType: OrderType,
    orderAmount: Long,
    percentSettings: PercentSettings,
    matchPrice: Long
  ): Long = {
    lazy val receiveAmount = Order.getReceiveAmount(orderType, orderAmount, matchPrice).explicitGet()
    lazy val spentAmount = Order.getSpendAmountUnsafe(orderType, orderAmount, matchPrice).explicitGet()

    val amount = percentSettings.assetType match {
      case AssetType.Amount => orderAmount
      case AssetType.Price => if (orderType == OrderType.BUY) spentAmount else receiveAmount
      case AssetType.Receiving => receiveAmount
      case AssetType.Spending => spentAmount
    }

    multiplyAmountByDouble(amount, percentSettings.minFee / 100)
  }

  private[dex] def getMinValidTxFeeForSettings(
    orderParams: OrderParams,
    orderFeeSettings: OrderFeeSettings,
    hasMatcherAccountScript: Boolean,
    assetDescriptions: Asset => BriefAssetDescription,
    rateCache: RateCache
  ): Result[Long] = {
    val extraFee = ExchangeTransactionCreator.minFee(0L, hasMatcherAccountScript, orderParams.assetPair, assetDescriptions(_).hasScript)
    getMinValidFeeForSettings(
      orderParams,
      orderFeeSettings,
      assetDescriptions(_).decimals,
      rateCache,
      extraFeeInWaves = extraFee
    )
  }

  @tailrec
  private[dex] def getMinValidFeeForSettings(
    orderParams: OrderParams,
    orderFeeSettings: OrderFeeSettings,
    assetDecimals: Asset => Int,
    rateCache: RateCache,
    extraFeeInWaves: Long = 0L,
    maybeDiscount: Option[BigDecimal] = None
  ): Result[Long] = orderFeeSettings match {
    case FixedSettings(_, fixedMinFee) => lift(fixedMinFee)
    case cs: CompositeSettings =>
      val maybeDiscount =
        cs.discount.flatMap { discountAssetSettings =>
          if (discountAssetSettings.asset == orderParams.feeAsset)
            Some(1 - (discountAssetSettings.value / 100))
          else
            None
        }
      getMinValidFeeForSettings(
        orderParams,
        cs.getOrderFeeSettings(orderParams.assetPair),
        assetDecimals,
        rateCache,
        extraFeeInWaves,
        maybeDiscount
      )
    case ds: DynamicSettings =>
      val fee = maybeDiscount.fold(ds.maxBaseFee)(multiplyByDiscount(ds.maxBaseFee, _))
      convertFeeByAssetRate(fee + extraFeeInWaves, orderParams.feeAsset, assetDecimals(orderParams.feeAsset), rateCache)
    case ps: PercentSettings =>
      convertFeeByAssetRate(ps.minFeeInWaves, orderParams.feeAsset, assetDecimals(orderParams.feeAsset), rateCache)
        .flatMap { constMinValidFee =>
          val psFeeAsset = ps.getFeeAsset(orderParams.assetPair, orderParams.orderType)
          val orderMinValidFee = {
            val fee = getMinValidFeeForPercentFeeSettings(orderParams.orderType, orderParams.amount, ps, orderParams.price)
            if (psFeeAsset == orderParams.feeAsset)
              Right(fee)
            else
              for {
                psFeeAssetRate <- rateCache.getRate(psFeeAsset).toRight(error.RateNotFound(psFeeAsset))
                discountAssetRate <- rateCache.getRate(orderParams.feeAsset).toRight(error.RateNotFound(orderParams.feeAsset))
                psFeeAssetRateCorrected = BigDecimal(MatcherModel.correctRateByAssetDecimals(psFeeAssetRate, assetDecimals(psFeeAsset)))
                discountAssetRateCorrected =
                  BigDecimal(MatcherModel.correctRateByAssetDecimals(discountAssetRate, assetDecimals(orderParams.feeAsset)))
              } yield multiplyFeeByBigDecimal(fee, discountAssetRateCorrected / psFeeAssetRateCorrected)
          }

          orderMinValidFee.map { minValidFee =>
            val fee = minValidFee max constMinValidFee
            maybeDiscount.fold(fee)(multiplyByDiscount(fee, _))
          }
        }
  }

  private def validateFeeAsset(order: Order, orderFeeSettings: OrderFeeSettings): Result[Order] = {
    val requiredFeeAssets = getValidFeeAssetsForSettings(order.assetPair, order.orderType, orderFeeSettings)
    cond(requiredFeeAssets contains order.feeAsset, order, error.UnexpectedFeeAsset(requiredFeeAssets, order.feeAsset))
  }

  private def validateFee(
    order: Order,
    orderFeeSettings: OrderFeeSettings,
    assetDecimals: Asset => Int,
    rateCache: RateCache
  )(implicit
    efc: ErrorFormatterContext
  ): Result[Order] =
    getMinValidFeeForSettings(OrderParams.fromOrder(order), orderFeeSettings, assetDecimals, rateCache) flatMap { requiredFee =>
      cond(order.matcherFee >= requiredFee, order, error.FeeNotEnough(requiredFee, order.matcherFee, order.feeAsset))
    }

  def matcherSettingsAware(
    matcherPublicKey: PublicKey,
    blacklistedAddresses: Set[Address],
    matcherSettings: MatcherSettings,
    assetDecimals: Asset => Int,
    rateCache: RateCache,
    getActualOrderFeeSettings: => OrderFeeSettings
  )(order: Order)(implicit efc: ErrorFormatterContext): Result[Order] = {

    def validateBlacklistedAsset(asset: Asset, e: IssuedAsset => MatcherError): Result[Unit] =
      asset.fold(success)(issuedAsset => cond(!matcherSettings.blacklistedAssets.contains(issuedAsset), (), e(issuedAsset)))

    for {
      _ <- lift(order)
        .ensure(error.UnexpectedMatcherPublicKey(matcherPublicKey, order.matcherPublicKey))(_.matcherPublicKey == matcherPublicKey)
        .ensure(error.AddressIsBlacklisted(order.sender))(o => !blacklistedAddresses.contains(o.sender.toAddress))
        .ensure(error.OrderVersionDenied(order.version, matcherSettings.allowedOrderVersions))(o =>
          matcherSettings.allowedOrderVersions(o.version)
        )
      _ <- validateBlacklistedAsset(order.feeAsset, error.FeeAssetBlacklisted(_))
      _ <- validateFeeAsset(order, getActualOrderFeeSettings)
      _ <- validateFee(order, getActualOrderFeeSettings, assetDecimals, rateCache)
    } yield order
  }

  /**
   * Checks if price is in deviation bounds
   *
   *   For BUY orders:  (1 - p) * best bid <= price <= (1 + l) * best ask
   *   For SELL orders: (1 - l) * best bid <= price <= (1 + p) * best ask
   *
   * where:
   *
   *   p = max price deviation profit / 100
   *   l = max price deviation loss / 100
   *   best bid = highest price of buy
   *   best ask = lowest price of sell
   */
  private def validatePriceDeviation(order: Order, deviationSettings: DeviationsSettings, marketStatus: Option[MarketStatus])(implicit
    efc: ErrorFormatterContext
  ): Result[Order] = {

    def isPriceInDeviationBounds(subtractedPercent: Double, addedPercent: Double): Boolean = marketStatus forall { ms =>
      lazy val isPriceHigherThanMinDeviation = ms.bestBid forall { bestBid =>
        order.price >= multiplyPriceByDouble(bestBid.price, 1 - (subtractedPercent / 100))
      }

      lazy val isPriceLessThanMaxDeviation = ms.bestAsk forall { bestAsk =>
        order.price <= multiplyPriceByDouble(bestAsk.price, 1 + (addedPercent / 100))
      }

      isPriceHigherThanMinDeviation && isPriceLessThanMaxDeviation
    }

    lift(order).ensure(error.DeviantOrderPrice(order, deviationSettings)) { _ =>
      if (order.orderType == OrderType.BUY) isPriceInDeviationBounds(deviationSettings.maxPriceProfit, deviationSettings.maxPriceLoss)
      else isPriceInDeviationBounds(deviationSettings.maxPriceLoss, deviationSettings.maxPriceProfit)
    }
  }

  /**
   * Checks if fee is in deviation bounds, i.e. orders's fee is higher than the specified percentage of fee,
   * which the client would pay for the matching with the best counter order. Only applicable to the `percent` order fee mode.
   *
   *   For BUY orders:  fee >= fs * (1 - fd) * best ask * amount
   *   For SELL orders: fee >= fs * (1 - fd) * best bid * amount
   *
   * where:
   *
   *   fs = fee in percents from order-fee settings (order-fee.percent.min-fee) / 100
   *   fd = max fee deviation / 100
   *   best bid = highest price of buy
   *   best ask = lowest price of sell
   */
  private def validateFeeDeviation(
    order: Order,
    deviationSettings: DeviationsSettings,
    orderFeeSettings: OrderFeeSettings,
    marketStatus: Option[MarketStatus]
  )(implicit efc: ErrorFormatterContext): Result[Order] = {

    def isFeeInDeviationBoundsForMatchedPrice(matchedPrice: Long): Boolean = orderFeeSettings match {
      case PercentSettings(assetType, minFee, minFeeInWaves) =>
        order.matcherFee >=
          getMinValidFeeForPercentFeeSettings(
            order.orderType,
            order.amount,
            PercentSettings(assetType, minFee * (1 - (deviationSettings.maxFeeDeviation / 100)), minFeeInWaves),
            matchedPrice
          )
      case _ => true
    }

    val isFeeInDeviationBounds = marketStatus forall { ms =>
      (order.orderType, ms.bestAsk.isDefined, ms.bestBid.isDefined) match {
        case (OrderType.BUY, true, _) =>
          isFeeInDeviationBoundsForMatchedPrice(ms.bestAsk.get.price) // validate fee for the best (lowest) sell price
        case (OrderType.SELL, _, true) =>
          isFeeInDeviationBoundsForMatchedPrice(ms.bestBid.get.price) // validate fee for the best (highest) buy price
        case _ => true
      }
    }

    Either.cond(isFeeInDeviationBounds, order, error.DeviantOrderMatcherFee(order, deviationSettings))
  }

  def marketAware(orderFeeSettings: OrderFeeSettings, deviationSettings: DeviationsSettings, marketStatus: Option[MarketStatus])(
    order: Order
  )(implicit efc: ErrorFormatterContext): Result[Order] =
    if (deviationSettings.enable)
      for {
        _ <- validatePriceDeviation(order, deviationSettings, marketStatus)
        _ <- validateFeeDeviation(order, deviationSettings, orderFeeSettings, marketStatus)
      } yield order
    else lift(order)

  def timeAware(time: Time)(order: Order): Result[Order] =
    for {
      _ <- cond(
        order.expiration > time.correctedTime() + MinExpiration,
        (),
        error.WrongExpiration(time.correctedTime(), MinExpiration, order.expiration)
      )
      _ <- order.isValid(time.correctedTime()).toEither.leftMap(error.OrderCommonValidationFailed(_))
    } yield order

  private def validateBalance(acceptedOrder: AcceptedOrder, tradableBalance: Asset => Long, orderBookCache: OrderBookAggregatedSnapshot)(implicit
    efc: ErrorFormatterContext
  ): Result[AcceptedOrder] = {

    /**
     * According to the current market state calculates cost for buy market orders or amount for sell market orders
     * that should be covered by tradable balance of the order's owner.
     * Returns InvalidMarketOrderPrice error in case of too low price of buy orders or too high price of sell orders
     */
    def getMarketOrderValue: Result[Long] = {

      /** Adds value of level to the current value of the market order */
      def accumulateLevel(level: LevelAgg, moValue: Result[Long], remainToExecute: Long): (Result[Long], Long) = {
        val levelValue: Long => Long = amount => if (acceptedOrder.isBuyOrder) MatcherModel.getCost(amount, level.price) else amount
        if (remainToExecute >= level.amount) moValue.map(_ + levelValue(level.amount)) -> (remainToExecute - level.amount)
        else moValue.map(_ + levelValue(remainToExecute)) -> 0L
      }

      @tailrec
      def go(levels: Seq[LevelAgg], currentValue: Result[Long], remainToExecute: Long): (Result[Long], Long) =
        (levels.headOption, currentValue) match {
          case (_, value) if value.isLeft => value -> remainToExecute
          case (None, fullMarketOrderValue) => fullMarketOrderValue -> remainToExecute
          case (Some(level), value) =>
            val isLevelPriceMatchable = if (acceptedOrder.isBuyOrder) acceptedOrder.price >= level.price else acceptedOrder.price <= level.price
            (isLevelPriceMatchable, remainToExecute > 0) match {
              case (true, true) => val (newVal, newRTE) = accumulateLevel(level, value, remainToExecute); go(levels.tail, newVal, newRTE)
              case (false, true) => error.InvalidMarketOrderPrice(acceptedOrder.order).asLeft[Long] -> remainToExecute
              case _ => value -> remainToExecute
            }
        }

      go(
        levels = orderBookCache.getCounterSideFor(acceptedOrder),
        currentValue = 0L.asRight[MatcherError],
        remainToExecute = acceptedOrder.amount
      )._1
    }

    def getRequiredBalanceForMarketOrder(marketOrder: MarketOrder): Map[Asset, Long] =
      Map(marketOrder.feeAsset -> marketOrder.requiredFee) |+| Map(marketOrder.spentAsset -> 1L) // spent asset minimum

    def validateTradableBalance(requiredForOrder: Map[Asset, Long]): Result[AcceptedOrder] = {
      val availableBalances = acceptedOrder.availableBalanceBySpendableAssets(tradableBalance)
      val negativeBalances = availableBalances |+| requiredForOrder.view.mapValues(-_).toMap filter { case (_, balance) => balance < 0 }
      cond(negativeBalances.isEmpty, acceptedOrder, error.BalanceNotEnough(requiredForOrder, availableBalances))
    }

    acceptedOrder match {
      case mo: MarketOrder =>
        for {
          _ <- getMarketOrderValue
          _ <- validateTradableBalance(getRequiredBalanceForMarketOrder(mo))
        } yield mo
      case _ => validateTradableBalance(acceptedOrder.requiredBalance)
    }
  }

  def accountStateAware(tradableBalance: Asset => Long, orderExists: Boolean, orderBookCache: OrderBookAggregatedSnapshot)(
    acceptedOrder: AcceptedOrder
  )(implicit efc: ErrorFormatterContext): Result[AcceptedOrder] =
    for {
      _ <- cond(!orderExists, acceptedOrder, error.OrderDuplicate(acceptedOrder.order.id()))
      _ <- validateBalance(acceptedOrder, tradableBalance, orderBookCache)
    } yield acceptedOrder

  def tickSizeAware(actualNormalizedTickSize: Long)(order: Order)(implicit efc: ErrorFormatterContext): Result[Order] =
    lift(order).ensure(error.OrderInvalidPriceLevel(order, actualNormalizedTickSize)) { o =>
      o.orderType == OrderType.SELL || OrderBook.correctPriceByTickSize(o.price, o.orderType, actualNormalizedTickSize) > 0
    }

  private def lift[T](x: T): Result[T] = x.asRight[MatcherError]
  def liftAsync[T](result: Result[T]): FutureResult[T] = EitherT(Future.successful(result))
  val success: Result[Unit] = lift(())

  final case class OrderParams(
    assetPair: AssetPair,
    orderType: OrderType,
    feeAsset: Asset,
    amount: Long,
    price: Long
  )

  object OrderParams {

    def fromOrder(order: Order): OrderParams =
      OrderParams(order.assetPair, order.orderType, order.feeAsset, order.amount, order.price)

  }

}
