package com.wavesplatform.dex.error

import akka.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCode}
import com.wavesplatform.dex.api.ws.headers.{`X-Error-Code`, `X-Error-Message`}
import com.wavesplatform.dex.domain.account.{Address, PublicKey}
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.feature.BlockchainFeature
import com.wavesplatform.dex.domain.model.Denormalization
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.error.Class.{common => commonClass, _}
import com.wavesplatform.dex.error.Entity.{common => commonEntity, _}
import com.wavesplatform.dex.error.Implicits._
import com.wavesplatform.dex.settings.{DeviationsSettings, OrderRestrictionsSettings}
import play.api.libs.json.{JsObject, Json, OWrites}

sealed trait HasErrorCode {
  val code: Int
}

//helper for companion objects of MatcherError children in order to reduce boilerplate
sealed abstract class MatcherErrorCodeProvider(obj: Entity, part: Entity, cls: Class) extends HasErrorCode {
  final override val code = MatcherError.mkCode(obj, part, cls)
}

sealed abstract class MatcherError(override val code: Int, val message: MatcherErrorMessage)
    extends Product
    with Serializable
    with HasErrorCode {
  def this(obj: Entity, part: Entity, cls: Class, message: MatcherErrorMessage) = this(
    MatcherError.mkCode(obj, part, cls),
    message
  )

  override def toString: String = s"${getClass.getCanonicalName}(error=$code,message=${message.text})"
}

object MatcherError {

  private[error] def mkCode(obj: Entity, part: Entity, cls: Class): Int =
    //32 bits = (12 for object) + (12 for part) + (8 for class)
    (obj.code << 20) + (part.code << 8) + cls.code

  implicit val matcherErrorWrites: OWrites[MatcherError] = OWrites { x =>
    val obj = x.message
    val wrappedParams = if (obj.params == JsObject.empty) obj.params else Json.obj("params" -> obj.params)
    Json
      .obj(
        "error" -> x.code,
        "message" -> obj.text,
        "template" -> obj.template
      )
      .deepMerge(wrappedParams)
  }

  implicit final class MatcherErrorOps(val self: MatcherError) extends AnyVal {

    def toWsHttpResponse(statusCode: StatusCode): HttpResponse =
      HttpResponse(
        status = statusCode,
        headers = List[HttpHeader](`X-Error-Message`(self.message.text), `X-Error-Code`(self.code.toString))
      )

  }

}

final case class Amount(asset: Asset, volume: BigDecimal)

object Amount {

  private[error] def apply(asset: Asset, volume: Long)(implicit efc: ErrorFormatterContext): Amount =
    new Amount(asset, Denormalization.denormalizeAmountAndFee(volume, efc.unsafeAssetDecimals(asset)))

}

final case class Price(assetPair: AssetPair, volume: BigDecimal)

object Price {

  private[error] def apply(assetPair: AssetPair, volume: Long)(implicit efc: ErrorFormatterContext): Price =
    new Price(
      assetPair,
      Denormalization.denormalizePrice(volume, efc.unsafeAssetDecimals(assetPair.amountAsset), efc.unsafeAssetDecimals(assetPair.priceAsset))
    )

}

final case class MatcherErrorMessage(text: String, template: String, params: JsObject)

case object MatcherIsStarting extends MatcherError(commonEntity, commonEntity, starting, e"System is starting. Please retry later")
case object MatcherIsStopping extends MatcherError(commonEntity, commonEntity, stopping, e"System is shutting down. Please retry later")
case object RequestTimeout extends MatcherError(request, commonEntity, stopping, e"Request timed out. Please retry later")
case object FeatureNotImplemented extends MatcherError(commonEntity, feature, unsupported, e"This feature is not implemented")
case object FeatureDisabled extends MatcherError(commonEntity, feature, disabled, e"This feature is disabled, contact with the administrator")
case object Balancing extends MatcherError(webSocket, commonEntity, optimization, e"System is balancing the load. Please reconnect")

final case class OrderBookBroken(assetPair: AssetPair)
    extends MatcherError(
      OrderBookBroken.code,
      e"The order book for ${"assetPair" -> assetPair} is unavailable, please contact with the administrator"
    )

object OrderBookBroken extends MatcherErrorCodeProvider(orderBook, commonEntity, broken)

final case class OrderBookUnexpectedState(assetPair: AssetPair)
    extends MatcherError(
      OrderBookUnexpectedState.code,
      e"The order book for ${"assetPair" -> assetPair} is unexpected state, please contact with the administrator"
    )

object OrderBookUnexpectedState extends MatcherErrorCodeProvider(orderBook, commonEntity, unexpected)

final case class OrderBookStopped(assetPair: AssetPair)
    extends MatcherError(
      OrderBookStopped.code,
      e"The order book for ${"assetPair" -> assetPair} is stopped, please contact with the administrator"
    )

object OrderBookStopped extends MatcherErrorCodeProvider(orderBook, commonEntity, disabled)

case object CanNotPersistEvent
    extends MatcherError(commonEntity, producer, broken, e"Can not persist command, please retry later or contact with the administrator")

case object CancelRequestIsIncomplete extends MatcherError(request, commonEntity, unexpected, e"Either timestamp or orderId must be specified")

final case class UnexpectedMatcherPublicKey(required: PublicKey, given: PublicKey)
    extends MatcherError(
      UnexpectedMatcherPublicKey.code,
      e"The required matcher public key for this DEX is ${"required" -> required}, but given ${"given" -> given}"
    )

object UnexpectedMatcherPublicKey extends MatcherErrorCodeProvider(commonEntity, pubKey, unexpected)

final case class OrderInvalidSignature(orderId: Order.Id, details: String)
    extends MatcherError(
      OrderInvalidSignature.code,
      e"The signature of order ${"id" -> orderId} is invalid: ${"details" -> details}"
    )

object OrderInvalidSignature extends MatcherErrorCodeProvider(order, signature, commonClass)

final case class UnexpectedFeeAsset(required: Set[Asset], given: Asset)
    extends MatcherError(
      UnexpectedFeeAsset.code,
      e"Required one of the following fee asset: ${"required" -> required}. But given ${"given" -> given}"
    )

object UnexpectedFeeAsset extends MatcherErrorCodeProvider(order, fee, unexpected)

final case class FeeNotEnough(required: Amount, given: Amount)
    extends MatcherError(
      FeeNotEnough.code,
      e"Required ${"required" -> required} as fee for this order, but given ${"given" -> given}"
    )

object FeeNotEnough extends MatcherErrorCodeProvider(order, fee, notEnough) {

  def apply(required: Long, given: Long, asset: Asset)(implicit efc: ErrorFormatterContext): FeeNotEnough = {
    val decimals = efc.unsafeAssetDecimals(asset)
    new FeeNotEnough(
      required = Amount(asset, Denormalization.denormalizeAmountAndFee(required, decimals)),
      given = Amount(asset, Denormalization.denormalizeAmountAndFee(given, decimals))
    )
  }

}

final case class AssetNotFound(asset: IssuedAsset) extends MatcherError(AssetNotFound.code, e"The asset ${"assetId" -> asset} not found")

object AssetNotFound extends MatcherErrorCodeProvider(asset, commonEntity, notFound)

final case class CanNotCreateExchangeTransaction(details: String)
    extends MatcherError(
      CanNotCreateExchangeTransaction.code,
      e"Can't verify the order by an exchange transaction: ${"details" -> details}"
    )

object CanNotCreateExchangeTransaction extends MatcherErrorCodeProvider(exchangeTx, order, commonClass)

final case class WrongExpiration(currentTs: Long, minExpirationOffset: Long, givenExpiration: Long)
    extends MatcherError(
      WrongExpiration.code,
      e"""The expiration should be at least
       |${"currentTimestamp" -> currentTs} + ${"minExpirationOffset" -> minExpirationOffset} =
       |${"minExpiration" -> (currentTs + minExpirationOffset)},
       |but it is ${"given" -> givenExpiration}"""
    )

object WrongExpiration extends MatcherErrorCodeProvider(order, expiration, notEnough)

final case class OrderCommonValidationFailed(details: String)
    extends MatcherError(OrderCommonValidationFailed.code, e"The order is invalid: ${"details" -> details}")

object OrderCommonValidationFailed extends MatcherErrorCodeProvider(order, commonEntity, commonClass)

final case class InvalidAsset(asset: String, reason: String = "It should be 'TN' or a Base58 string")
    extends MatcherError(
      InvalidAsset.code,
      e"The asset '${"assetId" -> asset}' is wrong, reason: ${"reason" -> reason}"
    )

object InvalidAsset extends MatcherErrorCodeProvider(asset, commonEntity, broken)

final case class AssetBlacklisted(asset: IssuedAsset)
    extends MatcherError(AssetBlacklisted.code, e"The asset ${"assetId" -> asset} is blacklisted")

object AssetBlacklisted extends MatcherErrorCodeProvider(asset, commonEntity, blacklisted)

final case class AmountAssetBlacklisted(asset: IssuedAsset)
    extends MatcherError(AmountAssetBlacklisted.code, e"The amount asset ${"assetId" -> asset} is blacklisted")

object AmountAssetBlacklisted extends MatcherErrorCodeProvider(asset, amount, blacklisted)

final case class PriceAssetBlacklisted(asset: IssuedAsset)
    extends MatcherError(PriceAssetBlacklisted.code, e"The price asset ${"assetId" -> asset} is blacklisted")

object PriceAssetBlacklisted extends MatcherErrorCodeProvider(asset, price, blacklisted)

final case class FeeAssetBlacklisted(asset: IssuedAsset)
    extends MatcherError(FeeAssetBlacklisted.code, e"The fee asset ${"assetId" -> asset} is blacklisted")

object FeeAssetBlacklisted extends MatcherErrorCodeProvider(asset, fee, blacklisted)

final case class AddressIsBlacklisted(address: Address)
    extends MatcherError(AddressIsBlacklisted.code, e"The account ${"address" -> address} is blacklisted")

object AddressIsBlacklisted extends MatcherErrorCodeProvider(account, commonEntity, blacklisted)

final case class BalanceNotEnough(required: List[Amount], actual: List[Amount])
    extends MatcherError(
      BalanceNotEnough.code,
      e"Not enough tradable balance. The order requires at least ${"required" -> required} on balance, but available are ${"actual" -> actual}"
    )

object BalanceNotEnough extends MatcherErrorCodeProvider(account, balance, notEnough) {

  def apply(required: Map[Asset, Long], actual: Map[Asset, Long])(implicit efc: ErrorFormatterContext): BalanceNotEnough =
    new BalanceNotEnough(mk(required), mk(actual))

  private def mk(input: Map[Asset, Long])(implicit efc: ErrorFormatterContext): List[Amount] = {
    import Ordered._
    input
      .map { case (id, v) => Amount(id, Denormalization.denormalizeAmountAndFee(v, efc.unsafeAssetDecimals(id))) }
      .toList
      .sortWith((l, r) => l.asset.compatId < r.asset.compatId)
  }

}

final case class ActiveOrdersLimitReached(maxActiveOrders: Long)
    extends MatcherError(ActiveOrdersLimitReached.code, e"The limit of ${"limit" -> maxActiveOrders} active orders has been reached")

object ActiveOrdersLimitReached extends MatcherErrorCodeProvider(account, order, limitReached)

final case class OrderDuplicate(id: Order.Id) extends MatcherError(OrderDuplicate.code, e"The order ${"id" -> id} has already been placed")

object OrderDuplicate extends MatcherErrorCodeProvider(account, order, duplicate)

final case class OrderNotFound(id: Order.Id) extends MatcherError(OrderNotFound.code, e"The order ${"id" -> id} not found")

object OrderNotFound extends MatcherErrorCodeProvider(order, commonEntity, notFound)

final case class OrderIsPlacing(id: Order.Id)
    extends MatcherError(OrderIsPlacing.code, e"The order ${"id" -> id} is in the process of placing, please retry later")

object OrderIsPlacing extends MatcherErrorCodeProvider(order, commonEntity, pending)

final case class OrderCanceled(id: Order.Id) extends MatcherError(OrderCanceled.code, e"The order ${"id" -> id} is canceled")

object OrderCanceled extends MatcherErrorCodeProvider(order, commonEntity, canceled)

final case class OrderFull(id: Order.Id) extends MatcherError(OrderFull.code, e"The order ${"id" -> id} is filled")

object OrderFull extends MatcherErrorCodeProvider(order, commonEntity, limitReached)

final case class OrderFinalized(id: Order.Id) extends MatcherError(OrderFinalized.code, e"The order ${"id" -> id} is finalized")

object OrderFinalized extends MatcherErrorCodeProvider(order, commonEntity, immutable)

final case class OrderVersionUnsupported(version: Byte, requiredFeature: BlockchainFeature)
    extends MatcherError(
      OrderVersionUnsupported.code,
      e"The order of version ${"version" -> version} isn't yet supported, see the activation status of '${"featureName" -> requiredFeature}'"
    )

object OrderVersionUnsupported extends MatcherErrorCodeProvider(feature, order, unsupported)

case object RequestInvalidSignature extends MatcherError(request, signature, commonClass, e"The request has an invalid signature")

final case class RequestArgumentInvalid(name: String)
    extends MatcherError(RequestArgumentInvalid.code, e"The request argument '${"name" -> name}' is invalid")

object RequestArgumentInvalid extends MatcherErrorCodeProvider(request, commonEntity, commonClass)

final case class AccountFeatureUnsupported(x: BlockchainFeature)
    extends MatcherError(
      AccountFeatureUnsupported.code,
      e"An account's feature isn't yet supported, see the activation status of '${"featureName" -> x}'"
    )

object AccountFeatureUnsupported extends MatcherErrorCodeProvider(feature, account, unsupported)

final case class AccountNotSupportOrderVersion(address: Address, requiredVersion: Byte, givenVersion: Byte)
    extends MatcherError(
      AccountNotSupportOrderVersion.code,
      e"The account ${"address" -> address} requires the version >= ${"required" -> requiredVersion}, but given ${"given" -> givenVersion}"
    )

object AccountNotSupportOrderVersion extends MatcherErrorCodeProvider(account, order, unsupported)

final case class AccountScriptReturnedError(address: Address, scriptMessage: String)
    extends MatcherError(
      AccountScriptReturnedError.code,
      e"The account's script of ${"address" -> address} returned the error: ${"scriptError" -> scriptMessage}"
    )

object AccountScriptReturnedError extends MatcherErrorCodeProvider(account, script, commonClass)

final case class AccountScriptDeniedOrder(address: Address)
    extends MatcherError(AccountScriptDeniedOrder.code, e"The account's script of ${"address" -> address} rejected the order")

object AccountScriptDeniedOrder extends MatcherErrorCodeProvider(account, script, denied)

final case class AccountScriptUnexpectResult(address: Address, returnedObject: String)
    extends MatcherError(
      AccountScriptUnexpectResult.code,
      e"""The account's script of ${"address" -> address} is broken, please contact with the owner.
       |The returned object is '${"returnedObject" -> returnedObject}'"""
    )

object AccountScriptUnexpectResult extends MatcherErrorCodeProvider(account, script, unexpected)

final case class AccountScriptException(address: Address, errorName: String, errorText: String)
    extends MatcherError(
      AccountScriptException.code,
      e"""The account's script of ${"address" -> address} is broken, please contact with the owner.
       |The returned error is ${"errorName" -> errorName}, the text is: ${"errorText" -> errorText}"""
    )

object AccountScriptException extends MatcherErrorCodeProvider(account, script, broken)

final case class AssetFeatureUnsupported(x: BlockchainFeature, asset: IssuedAsset)
    extends MatcherError(
      AssetFeatureUnsupported.code,
      e"""An asset's feature isn't yet supported for '${"assetId" -> asset}',
       |see the activation status of '${"featureName" -> x.description}'"""
    )

object AssetFeatureUnsupported extends MatcherErrorCodeProvider(feature, asset, unsupported)

final case class AssetScriptReturnedError(asset: IssuedAsset, scriptMessage: String)
    extends MatcherError(
      AssetScriptReturnedError.code,
      e"The asset's script of ${"assetId" -> asset} returned the error: ${"scriptError" -> scriptMessage}"
    )

object AssetScriptReturnedError extends MatcherErrorCodeProvider(asset, script, commonClass)

final case class AssetScriptDeniedOrder(asset: IssuedAsset)
    extends MatcherError(AssetScriptDeniedOrder.code, e"The asset's script of ${"assetId" -> asset} rejected the order")

object AssetScriptDeniedOrder extends MatcherErrorCodeProvider(asset, script, denied)

final case class AssetScriptUnexpectResult(asset: IssuedAsset, returnedObject: String)
    extends MatcherError(
      AssetScriptUnexpectResult.code,
      e"""The asset's script of ${"assetId" -> asset} is broken, please contact with the owner.
       |The returned object is '${"returnedObject" -> returnedObject}'"""
    )

object AssetScriptUnexpectResult extends MatcherErrorCodeProvider(asset, script, unexpected)

final case class AssetScriptException(asset: IssuedAsset, errorName: String, errorText: String)
    extends MatcherError(
      AssetScriptException.code,
      e"""The asset's script of ${"assetId" -> asset} is broken, please contact with the owner.
       |The returned error is ${"errorName" -> errorName}, the text is: ${"errorText" -> errorText}"""
    )

object AssetScriptException extends MatcherErrorCodeProvider(asset, script, broken)

final case class DeviantOrderPrice(orderType: OrderType, orderPrice: Price, deviationSettings: DeviationsSettings)
    extends MatcherError(
      DeviantOrderPrice.code,
      if (orderType == OrderType.BUY)
        e"""The buy order's price ${"price" -> orderPrice} is out of deviation bounds. It should meet the following matcher's requirements:
         |${"bestBidPercent" -> (100 - deviationSettings.maxPriceProfit)}% of best bid price <= order price <=
         |${"bestAskPercent" -> (100 + deviationSettings.maxPriceLoss)}% of best ask price"""
      else
        e"""The sell order's price ${"price" -> orderPrice} is out of deviation bounds. It should meet the following matcher's requirements:
         |${"bestBidPercent" -> (100 - deviationSettings.maxPriceLoss)}% of best bid price <= order price <=
         |${"bestAskPercent" -> (100 + deviationSettings.maxPriceProfit)}% of best ask price"""
    )

object DeviantOrderPrice extends MatcherErrorCodeProvider(order, price, outOfBound) {

  def apply(ord: Order, deviationSettings: DeviationsSettings)(implicit efc: ErrorFormatterContext): DeviantOrderPrice =
    DeviantOrderPrice(ord.orderType, Price(ord.assetPair, ord.price), deviationSettings)

}

final case class DeviantOrderMatcherFee(orderType: OrderType, matcherFee: Amount, deviationSettings: DeviationsSettings)
    extends MatcherError(
      DeviantOrderMatcherFee.code,
      if (orderType == OrderType.BUY)
        e"""The buy order's matcher fee ${"matcherFee" -> matcherFee} is out of deviation bounds. It should meet the following matcher's requirements:
       |matcher fee >= ${"bestAskFeePercent" -> (100 - deviationSettings.maxFeeDeviation)}% of fee which should be paid in case of matching with best ask"""
      else
        e"""The sell order's matcher fee ${"matcherFee" -> matcherFee} is out of deviation bounds. It should meet the following matcher's requirements:
         |matcher fee >= ${"bestBidFeePercent" -> (100 - deviationSettings.maxFeeDeviation)}% of fee which should be paid in case of matching with best bid"""
    )

object DeviantOrderMatcherFee extends MatcherErrorCodeProvider(order, fee, outOfBound) {

  def apply(ord: Order, deviationSettings: DeviationsSettings)(implicit efc: ErrorFormatterContext): DeviantOrderMatcherFee =
    DeviantOrderMatcherFee(ord.orderType, Amount(ord.feeAsset, ord.matcherFee), deviationSettings)

}

final case class AssetPairSameAssets(asset: Asset)
    extends MatcherError(
      AssetPairSameAssets.code,
      e"The amount and price assets must be different, but they are: ${"assetId" -> asset}"
    )

object AssetPairSameAssets extends MatcherErrorCodeProvider(order, assetPair, duplicate)

final case class AssetPairIsDenied(assetPair: AssetPair)
    extends MatcherError(AssetPairIsDenied.code, e"Trading is denied for the ${"assetPair" -> assetPair} asset pair")

object AssetPairIsDenied extends MatcherErrorCodeProvider(order, assetPair, denied)

final case class OrderAssetPairReversed(assetPair: AssetPair)
    extends MatcherError(OrderAssetPairReversed.code, e"The ${"assetPair" -> assetPair} asset pair should be reversed")

object OrderAssetPairReversed extends MatcherErrorCodeProvider(order, assetPair, unsupported)

final case class OrderVersionDenied(version: Byte, allowedVersions: Set[Byte])
    extends MatcherError(
      OrderVersionDenied.code,
      e"""The orders of version ${"version" -> version} are denied by matcher.
       |Allowed order versions are: ${"allowedOrderVersions" -> allowedVersions.toList.sorted}"""
    )

object OrderVersionDenied extends MatcherErrorCodeProvider(order, version, denied)

final case class UnsupportedOrderVersion(version: Byte)
    extends MatcherError(
      UnsupportedOrderVersion.code,
      e"""The orders of version ${"version" -> version} is not supported.
       |Supported order versions can be obtained via /matcher/settings GET method"""
    )

object UnsupportedOrderVersion extends MatcherErrorCodeProvider(order, version, unsupported)

final case class OrderInvalidAmount(orderAmount: Amount, amtSettings: OrderRestrictionsSettings)
    extends MatcherError(
      OrderInvalidAmount.code,
      e"""The order's amount
       |${"amount" -> orderAmount}
       |does not meet matcher's requirements:
       |max amount = ${"max" -> amtSettings.maxAmount},
       |min amount = ${"min" -> amtSettings.minAmount},
       |step amount = ${"step" -> amtSettings.stepAmount}"""
    )

object OrderInvalidAmount extends MatcherErrorCodeProvider(order, amount, denied) {

  def apply(ord: Order, amtSettings: OrderRestrictionsSettings)(implicit efc: ErrorFormatterContext): OrderInvalidAmount =
    OrderInvalidAmount(Amount(ord.assetPair.amountAsset, ord.amount), amtSettings)

}

final case class PriceLastDecimalsMustBeZero(insignificantDecimals: Int)
    extends MatcherError(
      PriceLastDecimalsMustBeZero.code,
      e"Invalid price, last ${"insignificantDecimals" -> insignificantDecimals} digits must be 0"
    )

object PriceLastDecimalsMustBeZero extends MatcherErrorCodeProvider(order, price, unexpected)

final case class OrderInvalidPrice(orderPrice: Price, prcSettings: OrderRestrictionsSettings)
    extends MatcherError(
      OrderInvalidPrice.code,
      e"""The order's price
       |${"price" -> orderPrice}
       |does not meet matcher's requirements:
       |max price = ${"max" -> prcSettings.maxPrice},
       |min price = ${"min" -> prcSettings.minPrice},
       |step price = ${"step" -> prcSettings.stepPrice}"""
    )

object OrderInvalidPrice extends MatcherErrorCodeProvider(order, price, denied) {

  def apply(ord: Order, prcSettings: OrderRestrictionsSettings)(implicit efc: ErrorFormatterContext): OrderInvalidPrice =
    OrderInvalidPrice(Price(ord.assetPair, ord.price), prcSettings)

}

final case class MarketOrderCancel(id: Order.Id)
    extends MatcherError(MarketOrderCancel.code, e"The market order ${"id" -> id} cannot be cancelled manually")

object MarketOrderCancel extends MatcherErrorCodeProvider(marketOrder, commonEntity, disabled)

final case class InvalidMarketOrderPrice(orderType: OrderType, orderPrice: Price)
    extends MatcherError(
      InvalidMarketOrderPrice.code,
      if (orderType == OrderType.BUY)
        e"""Price of the buy market order
         |(${"orderPrice" -> orderPrice})
         |is too low for its full execution with the current market state"""
      else
        e"""Price of the sell market order
         |(${"orderPrice" -> orderPrice})
         |is too high for its full execution with the current market state"""
    )

object InvalidMarketOrderPrice extends MatcherErrorCodeProvider(marketOrder, price, outOfBound) {

  def apply(mo: Order)(implicit efc: ErrorFormatterContext): InvalidMarketOrderPrice =
    InvalidMarketOrderPrice(mo.orderType, Price(mo.assetPair, mo.price))

}

final case class OrderInvalidPriceLevel(orderPrice: Price, minOrderPrice: Price)
    extends MatcherError(
      OrderInvalidPriceLevel.code,
      e"""The buy order's price
       |${"price" -> orderPrice}
       |does not meet matcher's requirements:
       |price >= ${"tickSize" -> minOrderPrice} (actual tick size).
       |Orders can not be placed into level with price 0"""
    )

object OrderInvalidPriceLevel extends MatcherErrorCodeProvider(order, price, notEnough) {

  def apply(ord: Order, tickSize: Long)(implicit efc: ErrorFormatterContext): OrderInvalidPriceLevel =
    OrderInvalidPriceLevel(Price(ord.assetPair, ord.price), Price(ord.assetPair, tickSize))

}

case object WavesNodeConnectionBroken
    extends MatcherError(connectivity, commonEntity, broken, e"Waves Node is unavailable, please retry later or contact with the administrator")

case object UnexpectedError
    extends MatcherError(
      commonEntity,
      commonEntity,
      unexpected,
      e"An unexpected error occurred"
    )

case object WavesImmutableRate
    extends MatcherError(rate, commonEntity, immutable, e"The rate for ${"assetId" -> (Waves: Asset)} cannot be changed")

case object InvalidAssetRate extends MatcherError(rate, commonEntity, outOfBound, e"Asset rate should be positive and should fit into double")

final case class RateNotFound(asset: Asset)
    extends MatcherError(RateNotFound.code, e"The rate for the asset ${"assetId" -> asset} was not specified")

object RateNotFound extends MatcherErrorCodeProvider(rate, commonEntity, notFound)

final case class InvalidJson(fields: List[String])
    extends MatcherError(
      InvalidJson.code,
      if (fields.isEmpty) e"The provided JSON is invalid. Check the documentation"
      else e"The provided JSON contains invalid fields: ${"invalidFields" -> fields}. Check the documentation"
    )

object InvalidJson extends MatcherErrorCodeProvider(request, commonEntity, broken)

case object UnsupportedContentType
    extends MatcherError(request, commonEntity, unsupported, e"The provided Content-Type is not supported, please provide JSON")

case object ApiKeyIsNotProvided
    extends MatcherError(auth, commonEntity, notProvided, e"API key is not provided in the configuration, please contact with the administrator")

case object ApiKeyIsNotValid extends MatcherError(auth, commonEntity, commonClass, e"Provided API key is not correct")

final case class UserPublicKeyIsNotValid(reason: String = "invalid public key")
    extends MatcherError(UserPublicKeyIsNotValid.code, e"Provided public key is not correct, reason: ${"reason" -> reason}")

object UserPublicKeyIsNotValid extends MatcherErrorCodeProvider(account, pubKey, broken)

final case class InvalidBase58String(reason: String)
    extends MatcherError(InvalidBase58String.code, e"Provided value is not a correct base58 string, reason: ${"reason" -> reason}")

object InvalidBase58String extends MatcherErrorCodeProvider(order, commonEntity, broken)

final case class AddressAndPublicKeyAreIncompatible(address: Address, publicKey: PublicKey)
    extends MatcherError(
      AddressAndPublicKeyAreIncompatible.code,
      e"Address ${"address" -> address} and public key ${"publicKey" -> publicKey} are incompatible"
    )

object AddressAndPublicKeyAreIncompatible extends MatcherErrorCodeProvider(auth, pubKey, unexpected)

case object AuthIsRequired extends MatcherError(auth, params, notProvided, e"The authentication is required. Please read the documentation")

case object WsConnectionPongTimeout extends MatcherError(webSocket, connectivity, timedOut, e"WebSocket has reached pong timeout")

case object WsConnectionMaxLifetimeExceeded
    extends MatcherError(webSocket, connectivity, limitReached, e"WebSocket has reached max allowed lifetime")

final case class SubscriptionAuthTypeUnsupported(required: Set[String], given: String)
    extends MatcherError(
      SubscriptionAuthTypeUnsupported.code,
      e"The subscription authentication type '${"given" -> given}' is not supported. Required one of: ${"required" -> required}"
    )

object SubscriptionAuthTypeUnsupported extends MatcherErrorCodeProvider(auth, tpe, unsupported)

final case class JwtCommonError(text: String)
    extends MatcherError(JwtCommonError.code, e"JWT parsing and validation failed: ${"message" -> text}")

object JwtCommonError extends MatcherErrorCodeProvider(token, commonEntity, commonClass)

case object JwtBroken extends MatcherError(token, commonEntity, broken, e"JWT has invalid format")

case object JwtPayloadBroken extends MatcherError(token, payload, broken, e"JWT payload has not expected fields")

case object InvalidJwtPayloadSignature extends MatcherError(token, signature, broken, e"The token payload signature is invalid")

final case class SubscriptionTokenExpired(address: Address)
    extends MatcherError(SubscriptionTokenExpired.code, e"The subscription token for address ${"address" -> address} expired")

object SubscriptionTokenExpired extends MatcherErrorCodeProvider(token, expiration, commonClass)

final case class TokenNetworkUnexpected(required: Byte, given: Byte)
    extends MatcherError(
      TokenNetworkUnexpected.code,
      e"The required network is ${"required" -> required}, but given ${"given" -> given}"
    )

object TokenNetworkUnexpected extends MatcherErrorCodeProvider(token, network, unexpected)

final case class SubscriptionsLimitReached(limit: Int, id: String)
    extends MatcherError(
      SubscriptionsLimitReached.code,
      e"The limit of ${"limit" -> limit} subscriptions of this type was reached. The subscription of ${"id" -> id} was stopped"
    )

object SubscriptionsLimitReached extends MatcherErrorCodeProvider(webSocket, subscription, limitReached)

final case class InvalidAddress(reason: String)
    extends MatcherError(InvalidAddress.code, e"Provided address in not correct, reason: ${"reason" -> reason}")

object InvalidAddress extends MatcherErrorCodeProvider(address, commonEntity, commonClass)

final case class InvalidDepth(reason: String)
    extends MatcherError(InvalidDepth.code, e"Provided depth in not correct, reason: ${"reason" -> reason}")

object InvalidDepth extends MatcherErrorCodeProvider(request, depth, commonClass)

sealed abstract class Entity(val code: Int)

// noinspection ScalaStyle
object Entity {
  object common extends Entity(0)
  object request extends Entity(1)
  object feature extends Entity(2)
  object account extends Entity(3)
  object address extends Entity(4)

  object exchangeTx extends Entity(5)

  object balance extends Entity(6)
  object script extends Entity(7)

  object orderBook extends Entity(8)
  object order extends Entity(9)

  object version extends Entity(10)
  object asset extends Entity(11)
  object pubKey extends Entity(12)
  object signature extends Entity(13)
  object assetPair extends Entity(14)
  object amount extends Entity(15)
  object price extends Entity(16)
  object fee extends Entity(17)
  object expiration extends Entity(18)
  object marketOrder extends Entity(19)
  object rate extends Entity(20)
  object tpe extends Entity(21)
  object network extends Entity(22)

  object producer extends Entity(100)
  object connectivity extends Entity(101)
  object auth extends Entity(102)
  object params extends Entity(103)
  object webSocket extends Entity(104)
  object token extends Entity(105)
  object payload extends Entity(106)
  object subscription extends Entity(107)
  object depth extends Entity(108)
}

sealed abstract class Class(val code: Int)

// noinspection ScalaStyle
object Class {
  object common extends Class(0)
  object broken extends Class(1)
  object denied extends Class(2)
  object unsupported extends Class(3)
  object unexpected extends Class(4)
  object blacklisted extends Class(5)
  object notEnough extends Class(6)
  object limitReached extends Class(7)
  object duplicate extends Class(8)
  object notFound extends Class(9)
  object canceled extends Class(10)
  object immutable extends Class(11)
  object timedOut extends Class(12)
  object starting extends Class(13)
  object stopping extends Class(14)
  object outOfBound extends Class(15)
  object disabled extends Class(16)
  object notProvided extends Class(17)
  object optimization extends Class(18)
  object pending extends Class(19)
}
