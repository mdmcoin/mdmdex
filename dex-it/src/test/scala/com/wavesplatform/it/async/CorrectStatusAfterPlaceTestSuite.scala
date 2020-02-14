package com.wavesplatform.it.async

import java.nio.charset.StandardCharsets

import com.typesafe.config.{Config, ConfigFactory}
<<<<<<< HEAD
import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.it._
import com.wavesplatform.it.api.AsyncMatcherHttpApi._
import com.wavesplatform.it.api.UnexpectedStatusCodeException
import com.wavesplatform.it.async.CorrectStatusAfterPlaceTestSuite._
import com.wavesplatform.it.sync.config.MatcherPriceAssetConfig._
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.IssueTransactionV1
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}
import com.wavesplatform.transaction.transfer.MassTransferTransaction
=======
import com.wavesplatform.dex.domain.account.KeyPair
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.asset.AssetPair
import com.wavesplatform.dex.domain.order.Order.Id
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.it.api.responses.dex.OrderStatus
import com.wavesplatform.dex.it.waves.MkWavesEntities.IssueResults
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.wavesj.Transfer
>>>>>>> 0303166a0a72de75548e378e233b25aa0b2f6b9d

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class CorrectStatusAfterPlaceTestSuite extends MatcherSuiteBase {

<<<<<<< HEAD
  private val matcherConfig = ConfigFactory.parseString(
    s"""TN {
       |  dex {
       |    price-assets = ["${Asset1.id()}", "${Asset2.id()}"]
       |    rest-order-limit = 100
       |    events-queue {
       |      local {
       |        polling-interval = 1s
       |        max-elements-per-poll = 100
       |      }
       |
       |      kafka.consumer {
       |        fetch-max-duration = 1s
       |        max-buffer-size = 100
       |      }
=======
  private val issuer = alice
  private val now    = System.currentTimeMillis()

  private val IssueResults(issueAsset1Tx, issuedAsset1Id, issuedAsset1) =
    mkIssueExtended(issuer, "asset1", Long.MaxValue, decimals = 0, timestamp = now)

  private val IssueResults(issueAsset2Tx, issuedAsset2Id, issuedAsset2) =
    mkIssueExtended(issuer, "asset2", Long.MaxValue, decimals = 0, timestamp = now + 1)

  private val issueAssetTxs = List(issueAsset1Tx, issueAsset2Tx)

  override protected val dexInitialSuiteConfig: Config = ConfigFactory.parseString(
    s"""waves.dex {
       |  price-assets = ["$issuedAsset1Id", "$issuedAsset2Id"]
       |  rest-order-limit = 100
       |  events-queue {
       |    local {
       |      polling-interval = 1s
       |      max-elements-per-poll = 200
       |    }
       |    kafka.consumer {
       |      fetch-max-duration = 1s
       |      max-buffer-size = 200
>>>>>>> 0303166a0a72de75548e378e233b25aa0b2f6b9d
       |    }
       |  }
       |}""".stripMargin
  )

  private val pairs =
    Seq(
      AssetPair(Waves, issuedAsset1),
      AssetPair(Waves, issuedAsset2),
      AssetPair(issuedAsset2, issuedAsset1),
    )

  private val traders: Seq[KeyPair] = (1 to 10).map(i => KeyPair(s"trader-$i".getBytes(StandardCharsets.UTF_8)))

  override protected def beforeAll(): Unit = {
    val sendAmount = Long.MaxValue / (traders.size + 1)
<<<<<<< HEAD
    val issueAndDistribute = for {
      // distribute waves
      transferWavesTx <- {
        val transferTx = MassTransferTransaction
          .selfSigned(
            sender = bob,
            assetId = Waves,
            transfers = traders.map(x => MassTransferTransaction.ParsedTransfer(x.toAddress, 100.TN)).toList,
            timestamp = startTs,
            feeAmount = 0.12.TN,
            attachment = Array.emptyByteArray
          )
          .explicitGet()

        node.broadcastRequest(transferTx.json())
      }

      // issue
      issueTxs <- Future.traverse(Assets)(asset => node.broadcastRequest(asset.json()))
      _        <- Future.traverse(issueTxs)(tx => node.waitForTransaction(tx.id))

      // distribute assets
      transferAssetsTxs <- Future.sequence {
        Assets.map { issueTx =>
          val transferTx = MassTransferTransaction
            .selfSigned(
              sender = Issuer,
              assetId = IssuedAsset(issueTx.id()),
              transfers = traders.map(x => MassTransferTransaction.ParsedTransfer(x.toAddress, sendAmount)).toList,
              timestamp = startTs,
              feeAmount = 0.12.TN,
              attachment = Array.emptyByteArray
            )
            .explicitGet()

          node.broadcastRequest(transferTx.json())
        }
      }
=======

    val transferWavesTx =
      mkMassTransfer(
        bob,
        Waves,
        traders.map(x => new Transfer(x.toAddress, 100.waves))(collection.breakOut)
      )
>>>>>>> 0303166a0a72de75548e378e233b25aa0b2f6b9d

    wavesNode1.start()
    wavesNode1.api.broadcast(transferWavesTx)
    broadcastAndAwait(issueAssetTxs: _*)

    val transferAssetsTxs = issueAssetTxs.map { issueTx =>
      mkMassTransfer(issuer, IssuedAsset(issueTx.getId), traders.map(x => new Transfer(x.toAddress, sendAmount))(collection.breakOut))
    }

    broadcastAndAwait(transferAssetsTxs: _*)
    wavesNode1.api.waitForTransaction(transferWavesTx)

    dex1.start()
  }

  "place orders and check their statuses" in {
    val ts                 = System.currentTimeMillis()
    val accountOrderInPair = 60

    val orders = for {
      account <- traders
      pair    <- pairs
<<<<<<< HEAD
      i       <- 1 to 60
    } yield node.prepareOrder(account, pair, OrderType.SELL, 100000L, 10000L, 0.04.TN, 1, timestamp = ts + i)

    val r = Await.result(Future.traverse(orders.grouped(orders.size / 5))(requests), 5.minutes).flatten
    r.foreach {
      case (id, status) => withClue(id)(status should not be "NotFound")
    }
=======
      i       <- 1 to accountOrderInPair
    } yield mkOrder(account, pair, OrderType.SELL, 100000L, 10000L, ts = ts + i)

    Await
      .result(
        for {
          r <- Future.traverse { orders.grouped(orders.size / 5) }(requests)
          _ <- {
            val totalSent = r.map(_.count(_._3)).sum
            dex1.asyncApi.waitForCurrentOffset(_ == totalSent - 1)
          }
        } yield r,
        10.minutes
      )
      .flatten
      .foreach {
        case (id, status, sent) => if (sent) withClue(s"$id")(status should not be OrderStatus.NotFound)
      }
>>>>>>> 0303166a0a72de75548e378e233b25aa0b2f6b9d
  }

  private def request(order: Order): Future[(Order.Id, OrderStatus, Boolean)] = {
    for {
<<<<<<< HEAD
      _ <- node.placeOrder(order).recover {
        case e: UnexpectedStatusCodeException if e.statusCode == 503 || e.responseBody.contains("has already been placed") => // Acceptable
      }
      status <- node.orderStatus(order.idStr(), order.assetPair, waitForStatus = false)
    } yield (order.idStr(), status.status)

  private def requests(orders: Seq[Order]): Future[Seq[(String, String)]] = Future.traverse(orders)(request)
}

object CorrectStatusAfterPlaceTestSuite {
  private val Issuer = alice

  private val Asset1 = IssueTransactionV1
    .selfSigned(
      sender = Issuer,
      name = "asset1".getBytes,
      description = Array.emptyByteArray,
      quantity = Long.MaxValue,
      decimals = 0,
      reissuable = false,
      fee = 100000000000L,
      timestamp = System.currentTimeMillis()
    )
    .explicitGet()

  private val Asset2 = IssueTransactionV1
    .selfSigned(
      sender = Issuer,
      name = "asset2".getBytes,
      description = Array.emptyByteArray,
      quantity = Long.MaxValue,
      decimals = 0,
      reissuable = false,
      fee = 100000000000L,
      timestamp = System.currentTimeMillis()
    )
    .explicitGet()
=======
      // TODO happens rarely, try to remove after migration to new akka-http
      sent   <- dex1.asyncApi.tryPlace(order).map(_ => true).recover { case x => log.error("Some error with order placement occurred:", x); false }
      status <- dex1.asyncApi.orderStatus(order)
    } yield (order.id(), status.status, sent)
  }
>>>>>>> 0303166a0a72de75548e378e233b25aa0b2f6b9d

  private def requests(orders: Seq[Order]): Future[Seq[(Id, OrderStatus, Boolean)]] = Future.traverse(orders)(request)
}
