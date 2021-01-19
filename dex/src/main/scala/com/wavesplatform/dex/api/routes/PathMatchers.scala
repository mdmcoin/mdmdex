package com.wavesplatform.dex.api.routes

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{Matched, Unmatched}
import akka.http.scaladsl.server.{PathMatcher, PathMatcher1, PathMatchers => AkkaMatchers}
import com.wavesplatform.dex.domain.account.{Address, PublicKey}
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.bytes.ByteStr
import com.wavesplatform.dex.domain.error.ValidationError

object PathMatchers {

  class Base58[A](f: String => Option[A]) extends PathMatcher1[A] {

    def apply(path: Path): PathMatcher.Matching[Tuple1[A]] = path match {
      case Path.Segment(segment, tail) => f(segment).fold[PathMatcher.Matching[Tuple1[A]]](Unmatched)(v => Matched(tail, Tuple1(v)))
      case _ => Unmatched
    }

  }

  val AssetPairPM: PathMatcher1[AssetPair] = AkkaMatchers.Segments(2).flatMap {
    case a1 :: a2 :: Nil => AssetPair.createAssetPair(a1, a2).toOption
    case _ => None
  }

  val AssetPM: PathMatcher1[Asset] = AkkaMatchers.Segment.flatMap { s =>
    AssetPair.extractAsset(s).toOption
  }

  object ByteStrPM extends Base58[ByteStr](ByteStr.decodeBase58(_).toOption)

  object PublicKeyPM extends Base58[PublicKey](PublicKey.fromBase58String(_).toOption)

  object AddressPM extends Base58[Either[ValidationError.InvalidAddress, Address]](s => Option(Address fromString s))
}
