package com.wavesplatform.dex.load

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import akka.actor.ActorSystem
import cats.instances.future._
import cats.syntax.either._
import cats.syntax.option._
import cats.{catsInstancesForId, Id}
import com.softwaremill.diffx._
import com.wavesplatform.dex.api.ws.protocol.{WsAddressChanges, WsOrderBookChanges}
import com.wavesplatform.dex.cli.ScoptImplicits
import com.wavesplatform.dex.domain.account.AddressScheme
import com.wavesplatform.dex.error.Implicits.ThrowableOps
import com.wavesplatform.dex.it.time.GlobalTimer
import com.wavesplatform.dex.it.time.GlobalTimer.TimerOpsImplicits
import com.wavesplatform.dex.load.WavesDexLoadCli.WsCheckType.{CheckLeaps, CheckUpdates}
import com.wavesplatform.dex.load.ws.WsCollectChangesClient
import com.wavesplatform.dex.test.matchers.DiffMatcherWithImplicits
import com.wavesplatform.dex.{cli, Version}
import scopt.{OParser, RenderingMode}

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

object WavesDexLoadCli extends ScoptImplicits with DiffMatcherWithImplicits {

  def main(rawArgs: Array[String]): Unit = {
    val executor = Executors.newCachedThreadPool()
    implicit val global = ExecutionContext.fromExecutor(executor)

    val builder = OParser.builder[Args]

    val parser = {
      import builder._
      OParser.sequence(
        programName("dex-cli"),
        head("DEX CLI", Version.VersionString),
        opt[Char]("address-scheme")
          .abbr("as")
          .text("The network byte as char. By default it is the testnet: 'T'")
          .valueName("<one char>")
          .action((x, s) => s.copy(addressSchemeByte = x)),
        opt[File]("feeder-file")
          .abbr("ff")
          .text("Where to save/read the feeder data")
          .action((x, s) => s.copy(feederFile = x)),
        cmd(Command.CreateFeederFile.name)
          .action((_, s) => s.copy(command = Command.CreateFeederFile.some))
          .text("Creates files for Gatling feeder")
          .children(
            opt[Int]("accounts-number")
              .abbr("an")
              .text("The number of generated accounts")
              .required()
              .action((x, s) => s.copy(accountsNumber = x)),
            opt[String]("raw-seed-prefix")
              .abbr("rsp")
              .text("The raw seed prefix. Each account generated by formula: seedPrefix + accountNumber")
              .action((x, s) => s.copy(seedPrefix = x)),
            opt[File]("pairs-file")
              .abbr("pf")
              .text("The path to the file with asset pairs. The format: one asset pair per line")
              .required()
              .action((x, s) => s.copy(pairsFile = x.some)),
            opt[Int]("order-book-number-per-account")
              .abbr("obnpa")
              .text("The number of subscribed order book per account. Must be less than number of asset pairs in pairs-file")
              .required()
              .action((x, s) => s.copy(orderBookNumberPerAccount = x)),
            opt[File]("auth-services-private-key-file")
              .abbr("aspkf")
              .text("The path to file with Auth Services' private key. The public key should be in the DEX's config")
              .required()
              .action((x, s) => s.copy(authServicesPrivateKeyFile = x))
          ),
        cmd(Command.Check.name)
          .action((_, s) => s.copy(command = Command.Check.some))
          .text("Runs multiple WebSocket consumers and then checks that all data was received")
          .children(
            opt[String]("dex-rest-api")
              .abbr("dra")
              .text("DEX REST API uri. Format: scheme://host:port (default scheme will be picked if none was specified)")
              .required()
              .action((x, s) => s.copy(dexRestApi = x)),
            opt[Int]("accounts-number")
              .abbr("an")
              .text("The number of checked accounts")
              .required()
              .action((x, s) => s.copy(accountsNumber = x)),
            opt[FiniteDuration]("collect-time")
              .abbr("ct")
              .text("The time to collect the data")
              .action((x, s) => s.copy(collectTime = x)),
            opt[FiniteDuration]("ws-response-wait-time")
              .abbr("wrwt")
              .text("The time to wait data on second stage")
              .action((x, s) => s.copy(wsResponseWaitTime = x)),
            opt[WsCheckType]("ws-check-type")
              .abbr("wct")
              .text("Type of checking data with ws")
              .action((x, s) => s.copy(wsCheckType = x))
          ),
        cmd(Command.CreateRequests.name)
          .action((_, s) => s.copy(command = Command.CreateRequests.some))
          .text("Creates file with requests for yandex-tank")
          .children(
            opt[Int]("requests-type")
              .abbr("rt")
              .text("The type of requests (1. Places 2. Places & Cancels 3. Matching 4. Order History 5. All of types)")
              .required()
              .action((x, s) => s.copy(requestsType = x)),
            opt[Int]("requests-count")
              .abbr("rc")
              .text("The count of needed requests")
              .required()
              .action((x, s) => s.copy(requestsCount = x)),
            opt[File]("pairs-file")
              .abbr("pf")
              .text("The path to the file with asset pairs. The format: one asset pair per line")
              .action((x, s) => s.copy(pairsFile = x.some)),
            opt[File]("requests-file")
              .abbr("rf")
              .text("The output file")
              .action((x, s) => s.copy(requestsFile = x)),
            opt[String]("raw-seed-prefix")
              .abbr("rsp")
              .text("The raw seed prefix. Each account generated by formula: seedPrefix + accountNumber")
              .action((x, s) => s.copy(seedPrefix = x)),
            opt[Int]("accounts-number")
              .abbr("an")
              .text("The number of checked accounts")
              .required()
              .action((x, s) => s.copy(accountsNumber = x))
          ),
        cmd(Command.DeleteRequests.name)
          .action((_, s) => s.copy(command = Command.DeleteRequests.some))
          .text("Delete number of requests")
          .children(
            opt[File]("requests-file")
              .abbr("rf")
              .text("The output file")
              .action((x, s) => s.copy(requestsFile = x)),
            opt[Int]("requests-count")
              .abbr("rc")
              .text("The count of needed requests")
              .required()
              .action((x, s) => s.copy(requestsCount = x))
          )
      )
    }

    try {
      OParser.parse(parser, rawArgs, Args()).foreach { args =>
        args.command match {
          case None => println(OParser.usage(parser, RenderingMode.TwoColumns))
          case Some(command) =>
            println(s"Running '${command.name}' command, chain id: ${args.addressSchemeByte}")
            AddressScheme.current = new AddressScheme {
              override val chainId: Byte = args.addressSchemeByte.toByte
            }

            command match {
              case Command.CreateRequests =>
                cli.log[Id](
                  s"""Arguments:
                     |  Requests type                     : ${args.requestsType}
                     |  Requests count                    : ${args.requestsCount}
                     |  Pairs file                        : ${args.pairsFile.get}
                     |  Seed prefix                       : ${args.seedPrefix}
                     |  Output file                       : ${args.requestsFile.getAbsoluteFile}
                     |  Count of accounts                 : ${args.accountsNumber}\n""".stripMargin
                )

                TankGenerator
                  .mkRequests(args.seedPrefix, args.pairsFile, args.requestsFile, args.requestsCount, args.requestsType, args.accountsNumber)

              case Command.DeleteRequests =>
                cli.log[Id](
                  s"""Arguments:
                     |  Requests file                     : ${args.requestsFile.getAbsolutePath}
                     |  Requests count                    : ${args.requestsCount}\n""".stripMargin
                )

                RequestDeleter.delRequests(args.requestsFile, args.requestsCount)

              case Command.CreateFeederFile =>
                cli.log[Id](
                  s"""Arguments:
                     |  Count of accounts                 : ${args.accountsNumber}
                     |  Seed prefix                       : ${args.seedPrefix}
                     |  Pairs file                        : ${args.pairsFile.get}
                     |  Count of order books per account  : ${args.orderBookNumberPerAccount}
                     |  Auth key file                     : ${args.authServicesPrivateKeyFile}\n""".stripMargin
                )

                val authPrivateKey = new String(Files.readAllBytes(args.authServicesPrivateKeyFile.toPath), StandardCharsets.UTF_8)

                GatlingFeeder.mkFile(
                  accountsNumber = args.accountsNumber,
                  seedPrefix = args.seedPrefix,
                  authKp = GatlingFeeder.authServiceKeyPair(authPrivateKey),
                  pairsFile = args.pairsFile.get,
                  orderBookNumberPerAccount = args.orderBookNumberPerAccount,
                  feederFile = args.feederFile
                )

              case Command.Check =>
                implicit val system = ActorSystem()

                def checkLeaps() = {
                  val clients: Seq[WsCollectChangesClient] =
                    WsAccumulateChanges.createClients(args.dexWsApi, args.feederFile, args.accountsNumber, args.wsCheckType)

                  val withLeaps =
                    try {
                      Future.traverse(clients)(_.run())
                      Thread.sleep(args.wsResponseWaitTime.toMillis)

                      clients.filter(_.addressUpdateLeaps.size != 0)
                    } finally {
                      clients.foreach(_.close())
                      system.terminate()
                      GlobalTimer.instance.stop()
                    }

                  if (withLeaps.size > 0) {
                    println("Balance leaps:")
                    println(s"Count of addresses with leaps: ${withLeaps.size}")
                    println(s"Leaps: ${withLeaps.size}")
                    withLeaps.foreach { c =>
                      println(c.getAddress)
                      c.addressUpdateLeaps.foreach(println(_))
                    }
                    sys.exit(1)
                  } else
                    println("Congratulations! All checks passed!")
                }

                def checkUpdates() = {
                  val clients: Seq[WsCollectChangesClient] = cli.wrapByLogs[Id, Seq[WsCollectChangesClient]]("Creating clients.. ") {
                    WsAccumulateChanges.createClients(args.dexWsApi, args.feederFile, args.accountsNumber, args.wsCheckType)
                  }

                  val r =
                    try {
                      val checks = for {
                        _ <- cli.wrapByLogs("Stage 1. Running clients... ")(Future.traverse(clients)(_.run()))
                        _ <- cli.wrapByLogs(s"Stage 1. Waiting ${args.collectTime}... ")(GlobalTimer.instance.sleep(args.collectTime))
                        watchedAddresses <- cli.wrapByLogs("Stage 1. Getting collected addresses... ") {
                          Future.successful(getOrThrow(checkUniq(clients.map(_.collectedAddressState).groupBy(_.address))))
                        }
                        watchedOrderBooks <- cli.wrapByLogs("Stage 1. Getting collected order books... ") {
                          Future.successful(getOrThrow(checkUniq(switch(clients.map(_.collectedOrderBooks)))))
                        }
                        _ <- cli.wrapByLogs("Stage 1. Checking amount of pings... ") {
                          Future.successful(println(clients.map(_.pingsNumber).mkString(", ")))
                        }
                        _ <- cli.wrapByLogs("Stage 1. Stopping clients... ")(Future.traverse(clients)(_.close()))

                        _ <- cli.wrapByLogs("Stage 2. Running new clients... ")(Future.traverse(clients)(_.run()))
                        _ <- cli.wrapByLogs(s"Stage 2. Waiting ${args.wsResponseWaitTime}... ") {
                          GlobalTimer.instance.sleep(args.wsResponseWaitTime)
                        }
                        finalAddresses <- cli.wrapByLogs("Stage 2. Getting final addresses... ") {
                          Future.successful {
                            getOrThrow(checkUniq(clients.map(_.collectedAddressState).groupBy(_.address)))
                          }
                        }
                        finalOrderBooks <- cli.wrapByLogs("Stage 2. Getting final order books... ") {
                          Future.successful {
                            getOrThrow(checkUniq(switch(clients.map(_.collectedOrderBooks))))
                          }
                        }

                        _ <- cli.wrapByLogs("Stage 2. Stopping clients... ")(Future.traverse(clients)(_.close()))

                        _ <- cli.wrapByLogs("Stage 3. Comparing collected and final addresses... ") {
                          Future {
                            val addressesCompareResult = compare(watchedAddresses, finalAddresses)
                            if (!addressesCompareResult.isIdentical) {
                              println(s"Found issues:\n${addressesCompareResult.show}")
                              throw new IllegalStateException("Found issues")
                            }
                          }
                        }

                        _ <- cli.wrapByLogs("Stage 3. Comparing collected and final order books... ") {
                          Future {
                            val orderBooksCompareResult = compare(watchedOrderBooks, finalOrderBooks)
                            if (!orderBooksCompareResult.isIdentical) {
                              println(s"Found issues:\n${orderBooksCompareResult.show}")
                              throw new IllegalStateException("Found issues")
                            }
                          }
                        }
                      } yield ()

                      val r = checks
                        .map(_ => "Congratulations! All checks passed!".asRight[String])
                        .recover { case x => x.getWithStackTrace.asLeft[String] }

                      Await.result(r, Duration.Inf)
                    } finally {
                      clients.foreach(_.close())
                      system.terminate()
                      GlobalTimer.instance.stop()
                    }

                  r match {
                    case Right(diagnosticNotes) => println(diagnosticNotes)
                    case Left(error) =>
                      println(error)
                      sys.exit(1)
                  }

                }

                cli.log[Id](
                  s"""Arguments:
                     |  DEX REST API            : ${args.dexRestApi}
                     |  DEX WebSocket API       : ${args.dexWsApi}
                     |  Feeder file             : ${args.feederFile.getAbsolutePath}
                     |  Accounts number         : ${args.accountsNumber}
                     |  Collect time            : ${args.collectTime}
                     |  WebSocket response time : ${args.wsResponseWaitTime}
                     |  WebSocket check type    : ${args.wsCheckType}\n""".stripMargin
                )

                args.wsCheckType match {
                  case CheckLeaps => checkLeaps()
                  case CheckUpdates => checkUpdates()
                }
            }
            println("Done")
        }
      }
    } finally executor.shutdownNow()
  }

  sealed private trait Command {
    def name: String
  }

  private object Command {

    case object CreateFeederFile extends Command {
      override def name: String = "create-feeder-file"
    }

    case object Check extends Command {
      override def name: String = "check"
    }

    case object CreateRequests extends Command {
      override def name: String = "create-requests"
    }

    case object DeleteRequests extends Command {
      override def name: String = "delete-requests"
    }

  }

  sealed trait WsCheckType

  object WsCheckType {
    case object CheckLeaps extends WsCheckType
    case object CheckUpdates extends WsCheckType

    implicit val wsCheckTypeRead: scopt.Read[WsCheckType] = scopt.Read.reads {
      case "leaps" => CheckLeaps
      case "updates" => CheckUpdates
      case x => throw new IllegalArgumentException(s"Expected 'leaps', 'updates', but got '$x'")
    }

  }

  private val defaultFeederFile = new File("feeder.csv")
  private val defaultAuthFile = new File("authkey.txt")

  private case class Args(
    addressSchemeByte: Char = 'D',
    command: Option[Command] = None,
    authServicesPrivateKeyFile: File = defaultAuthFile,
    feederFile: File = defaultFeederFile,
    pairsFile: Option[File] = None,
    accountsNumber: Int = 1000,
    seedPrefix: String = "loadtest-",
    orderBookNumberPerAccount: Int = 10,
    requestsType: Int = 1,
    requestsCount: Int = 30000,
    requestsFile: File = new File(s"requests-${System.currentTimeMillis}.txt"),
    dexRestApi: String = "",
    collectTime: FiniteDuration = 5.seconds,
    wsResponseWaitTime: FiniteDuration = 5.seconds,
    wsCheckType: WsCheckType = CheckUpdates
  ) {
    def dexWsApi: String = s"${prependScheme(dexRestApi)}/ws/v0"
  }

  private def prependScheme(uri: String): String = {
    val (plain, secure) = "ws://" -> "wss://"
    if (uri.startsWith("https://")) s"${uri.replace("https://", secure)}" else s"${uri.replace("http://", plain)}"
  }

  private def getOrThrow[K, V](from: Either[Map[K, Seq[DiffResult]], Map[K, V]]): Map[K, V] = from match {
    case Right(x) => x
    case Left(xs) =>
      xs.foreach { case (k, issues) => println(s"Found issues in $k:\n${issues.map(_.show).mkString("\n")}") }
      throw new IllegalStateException("Found issues")
  }

  private def checkUniq[K, V: Diff](xs: Map[K, Seq[V]]): Either[Map[K, Seq[DiffResult]], Map[K, V]] = {
    val notIdentical = xs
      .filter(_._2.length > 1)
      .map {
        case (k, group) =>
          val first = group.head
          val notIdentical = group.tail.map(compare(first, _)).filterNot(_.isIdentical)
          k -> notIdentical
      }
      .filter(_._2.nonEmpty)
    if (notIdentical.isEmpty) xs.collect { case (k, xs) if xs.nonEmpty => k -> xs.head }.asRight
    else notIdentical.asLeft
  }

  private def switch[K, V](xs: Seq[Map[K, V]]): Map[K, Seq[V]] = xs.foldLeft(Map.empty[K, List[V]]) {
    case (r, x) => x.foldLeft(r) { case (r, (k, v)) => r.updated(k, v :: r.getOrElse(k, List.empty)) }
  }

  implicit private val wsAddressChangesDiff: Derived[Diff[WsAddressChanges]] =
    Derived(Diff.gen[WsAddressChanges].value.ignore[WsAddressChanges, Long](_.timestamp).ignore[WsAddressChanges, Long](_.updateId))

  implicit private val wsOrderBookChangesDiff: Derived[Diff[WsOrderBookChanges]] =
    Derived(Diff.gen[WsOrderBookChanges].value.ignore[WsOrderBookChanges, Long](_.timestamp).ignore[WsOrderBookChanges, Long](_.updateId))

// [error]  private method getDiff in object WavesDexLoadCli is never used
//  private def getDiff[T](comparison: (T, T) => Boolean): Diff[T] = { (left: T, right: T, _: List[FieldPath]) =>
//    if (comparison(left, right)) Identical(left) else DiffResultValue(left, right)
//  }

}
