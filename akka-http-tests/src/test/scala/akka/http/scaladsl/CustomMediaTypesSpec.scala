/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.MediaType.WithFixedCharset
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.testkit._
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._

class CustomMediaTypesSpec extends AkkaSpec with ScalaFutures
  with Directives with RequestBuilding {

  "Http" should {
    "find media types in a set if they differ in casing" in {
      val set: java.util.Set[MediaType] = new java.util.HashSet
      set.add(MediaTypes.`application/vnd.ms-excel`)
      set.add(MediaTypes.`application/vnd.ms-powerpoint`)
      set.add(MediaTypes.`application/msword`)
      set.add(MediaType.customBinary("application", "x-Akka-TEST", MediaType.NotCompressible))

      set.contains(MediaType.parse("application/msword").right.get) should ===(true)
      set.contains(MediaType.parse("application/MsWord").right.get) should ===(true)
      set.contains(MediaType.parse("application/vnd.ms-POWERPOINT").right.get) should ===(true)
      set.contains(MediaType.parse("application/VnD.MS-eXceL").right.get) should ===(true)
      set.contains(MediaType.parse("application/x-akka-test").right.get) should ===(true)
      set.contains(MediaType.parse("application/x-Akka-TEST").right.get) should ===(true)
    }

    "allow registering custom media type" in {
      import system.dispatcher
      //#application-custom

      // similarly in Java: `akka.http.javadsl.settings.[...]`
      import akka.http.scaladsl.settings.ParserSettings
      import akka.http.scaladsl.settings.ServerSettings

      // define custom media type:
      val utf8 = HttpCharsets.`UTF-8`
      val `application/custom`: WithFixedCharset =
        MediaType.customWithFixedCharset("application", "custom", utf8)

      // add custom media type to parser settings:
      val parserSettings = ParserSettings.forServer(system).withCustomMediaTypes(`application/custom`)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)

      val routes = extractRequest { r =>
        complete(r.entity.contentType.toString + " = " + r.entity.contentType.getClass)
      }
      val binding = Http().newServerAt("localhost", 0).withSettings(serverSettings).bind(routes)
      //#application-custom

      val request = Get(s"http://localhost:${binding.futureValue.localAddress.getPort}/").withEntity(HttpEntity(`application/custom`, "~~example~=~value~~"))
      val response = Http().singleRequest(request).futureValue

      response.status should ===(StatusCodes.OK)
      val responseBody = response.toStrict(1.second.dilated).futureValue.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue.utf8String
      responseBody should ===("application/custom = class akka.http.scaladsl.model.ContentType$WithFixedCharset")
    }
  }
}

