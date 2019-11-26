package com.wavesplatform.it.api.dex

import play.api.libs.json.{Format, Json}

case class RatesResponse(message: String)
object RatesResponse {
  implicit val format: Format[RatesResponse] = Json.format
}
