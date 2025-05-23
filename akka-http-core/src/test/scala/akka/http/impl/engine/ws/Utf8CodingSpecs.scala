/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import org.scalacheck.Gen

import scala.concurrent.duration._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.http.impl.util._
import akka.testkit._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class Utf8CodingSpecs extends AkkaSpecWithMaterializer with ScalaCheckPropertyChecks {
  "Utf8 decoding/encoding" should {
    "work for all codepoints" in {
      def isSurrogate(cp: Int): Boolean =
        cp >= Utf8Encoder.SurrogateHighMask && cp <= 0xdfff

      val cps =
        Gen.choose(0, 0x10ffff)
          .filter(!isSurrogate(_))

      def codePointAsString(cp: Int): String = {
        if (cp < 0x10000) new String(Array(cp.toChar))
        else {
          val part0 = 0xd7c0 + (cp >> 10) // constant has 0x10000 subtracted already
          val part1 = 0xdc00 + (cp & 0x3ff)
          new String(Array(part0.toChar, part1.toChar))
        }
      }

      forAll(cps) { (cp: Int) =>
        val utf16 = codePointAsString(cp)
        decodeUtf8(encodeUtf8(utf16)) shouldEqual utf16
      }
    }
  }

  def encodeUtf8(str: String): ByteString =
    Source(str.map(ch => new String(Array(ch)))) // chunk in smallest chunks possible
      .via(Utf8Encoder)
      .runFold(ByteString.empty)(_ ++ _).awaitResult(1.second.dilated)

  def decodeUtf8(bytes: ByteString): String = {
    val builder = new StringBuilder
    val decoder = Utf8Decoder.create()
    bytes
      .map(b => ByteString(b)) // chunk in smallest chunks possible
      .foreach { bs =>
        builder append decoder.decode(bs, endOfInput = false).get
      }

    builder append decoder.decode(ByteString.empty, endOfInput = true).get
    builder.toString()
  }
}
