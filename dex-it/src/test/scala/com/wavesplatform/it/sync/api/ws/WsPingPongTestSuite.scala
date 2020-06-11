package com.wavesplatform.it.sync.api.ws

import akka.http.scaladsl.model.ws.TextMessage
import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.api.ws.connection.WsConnection
import com.wavesplatform.dex.api.ws.{WsClientMessage, WsError, WsMessage, WsPingOrPong}
import com.wavesplatform.dex.error.InvalidJson
import com.wavesplatform.it.WsSuiteBase

import scala.concurrent.Await
import scala.concurrent.duration._

class WsPingPongTestSuite extends WsSuiteBase {

  private val maxConnectionLifetime = 6.seconds
  private val pingInterval          = 1.second
  private val pongTimeout           = pingInterval * 3
  private val delta                 = 0.5.seconds

  private implicit def duration2Long(d: FiniteDuration): Long = d.toMillis

  override protected val dexInitialSuiteConfig: Config = ConfigFactory
    .parseString(
      s"""waves.dex.web-sockets.web-socket-handler {
        |    max-connection-lifetime = $maxConnectionLifetime
        |    ping-interval = $pingInterval
        |    pong-timeout = $pongTimeout
        | }
        |""".stripMargin
    )
    .withFallback(jwtPublicKeyConfig)

  "Web socket connection should be closed " - {

    s"by max-connection-lifetime = $maxConnectionLifetime" in {

      val wsac               = mkWsAddressConnection(alice, dex1)
      val connectionLifetime = Await.result(wsac.connectionLifetime, maxConnectionLifetime + delta)

      connectionLifetime should (be >= maxConnectionLifetime and be <= maxConnectionLifetime + delta)
      wsac.pings.size should be >= 5
      wsac.isClosed shouldBe true

      wsac.collectMessages[WsError].head should matchTo(
        WsError(
          timestamp = 0L, // ignored
          code = 109077767, // WsConnectionMaxLifetimeExceeded
          message = "WebSocket has reached max allowed lifetime"
        )
      )
    }

    s"by pong timeout (ping-interval = $pingInterval, pong-timeout = 3 * ping-interval = $pongTimeout)" - {

      "without sending pong" in {
        val wsac                       = mkWsAddressConnection(alice, dex1, keepAlive = false)
        val expectedConnectionLifetime = pingInterval + pongTimeout
        val connectionLifetime         = Await.result(wsac.connectionLifetime, expectedConnectionLifetime + delta)

        connectionLifetime should (be >= expectedConnectionLifetime and be <= expectedConnectionLifetime + delta)
        wsac.pings.size should (be >= 3 and be <= 4)
        wsac.isClosed shouldBe true

        wsac.collectMessages[WsError].head should matchTo(
          WsError(
            timestamp = 0L, // ignored
            code = 109077772, // WsConnectionPongTimeout
            message = "WebSocket has reached pong timeout"
          )
        )
      }

      "with sending pong" in {

        val wsac = mkWsAddressConnection(alice, dex1, keepAlive = false)

        Thread.sleep(pingInterval + 0.1.second)
        wsac.isClosed shouldBe false
        wsac.pings should have size 1

        wsac.send(wsac.pings.last) // sending pong to keep connection alive

        Thread.sleep(pingInterval + 0.1.second)
        wsac.isClosed shouldBe false
        wsac.pings should have size 2

        wsac.send(wsac.pings.head) // sending outdated pong will not prolong connection lifetime

        val connectionLifetime         = Await.result(wsac.connectionLifetime, pongTimeout + delta)
        val expectedConnectionLifetime = pingInterval * 2 + pongTimeout

        connectionLifetime should (be >= expectedConnectionLifetime and be <= expectedConnectionLifetime + delta)
        wsac.pings.size should (be >= 4 and be <= 5)
        wsac.isClosed shouldBe true
      }

      "even if pong is sent from another connection" in {
        val wsac1 = mkWsAddressConnection(alice, dex1, keepAlive = false)
        val wsac2 = mkWsAddressConnection(alice, dex1, keepAlive = false)

        wsac1.isClosed shouldBe false
        wsac2.isClosed shouldBe false

        Thread.sleep(pingInterval + 0.1.second)

        Seq(wsac1, wsac2).foreach {
          _.pings should have size 1
        }

        wsac1.send(wsac2.pings.head) // send correct pong but from another connection
        wsac2.send(wsac1.pings.head) // send correct pong but from another connection

        val expectedConnectionsLifetime = pingInterval + pongTimeout
        val connection1Lifetime         = Await.result(wsac1.connectionLifetime, pongTimeout + delta)
        val connection2Lifetime         = Await.result(wsac2.connectionLifetime, pongTimeout + delta)

        Seq(wsac1 -> connection1Lifetime, wsac2 -> connection2Lifetime).foreach {
          case (conn, connLifetime) =>
            connLifetime should (be >= expectedConnectionsLifetime and be <= expectedConnectionsLifetime + delta)
            conn.pings.size should (be >= 3 and be <= 4)
            conn.isClosed shouldBe true
        }
      }
    }
  }

  "Web socket connection should not be closed " - {

    "when incorrect message has been sent to te Matcher" in {

      val wsc = new WsConnection(getWsStreamUri(dex1), keepAlive = false) {
        override def stringifyClientMessage(cm: WsClientMessage): TextMessage.Strict = cm match {
          case WsPingOrPong(timestamp) if timestamp == -1 => TextMessage.Strict(s"broken")
          case other: WsClientMessage                     => WsMessage.toStrictTextMessage(other)(WsClientMessage.wsClientMessageWrites)
        }
      }

      Thread.sleep(pingInterval + 0.1.second)
      wsc.isClosed shouldBe false
      wsc.pings should have size 1

      wsc.send(wsc.pings.last)
      wsc.send(wsc.pings.last.copy(timestamp = -1))

      val connectionLifetime          = Await.result(wsc.connectionLifetime, pingInterval + pongTimeout + delta)
      val expectedConnectionsLifetime = pingInterval * 2 + pongTimeout
      connectionLifetime should (be >= expectedConnectionsLifetime and be <= expectedConnectionsLifetime + delta)

      wsc.pings should have size 4
      wsc.isClosed shouldBe true

      val expectedError = InvalidJson(Nil)

      wsc.collectMessages[WsError] should matchTo {
        List(
          WsError(0L, expectedError.code, expectedError.message.text),
          WsError(
            timestamp = 0L, // ignored
            code = 109077772, // WsConnectionPongTimeout
            message = "WebSocket has reached pong timeout"
          )
        )
      }
    }
  }
}
