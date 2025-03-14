/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.client

import scala.concurrent.ExecutionContext

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.reflect.ClassTag

import org.scalatest.Inside
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.util.ByteString
import akka.event.NoLogging
import akka.stream.ClosedShape
import akka.stream.TLSProtocol._
import akka.stream.testkit._
import akka.stream.scaladsl._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._
import akka.http.scaladsl.Http
import akka.testkit._

class LowLevelOutgoingConnectionSpec extends AkkaSpecWithMaterializer with Inside {
  implicit val dispatcher: ExecutionContext = system.dispatcher

  "The connection-level client implementation" should {

    "handle a request/response round-trip" which {

      "has a request with empty entity" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a request with default entity" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source.fromPublisher(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest()
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XY"))
        expectWireData("XY")
        sub.sendComplete()

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a response with a chunked entity" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = expectResponse()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("4\nDEFX\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("DEFX"))

        sendWireData("0\n\n")
        sub.request(1)
        probe.expectNext(HttpEntity.LastChunk)
        sub.request(1)
        probe.expectComplete()

        requestsSub.sendComplete()
        netOut.expectComplete()
        responses.expectComplete()
      }

      "has a response with a chunked entity and Connection: close" in new TestSetup {
        sendStandardRequest()

        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |Connection: close
            |
            |""")
        sendWireData("3\nABC\n")
        sendWireData("4\nDEFX\n")
        sendWireData("0\n\n")

        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = expectResponse()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
        val sub = probe.expectSubscription()
        sub.request(4)
        probe.expectNext(HttpEntity.Chunk("ABC"))
        probe.expectNext(HttpEntity.Chunk("DEFX"))
        probe.expectNext(HttpEntity.LastChunk)
        probe.expectComplete()

        // explicit `requestsSub.sendComplete()` not needed
        netOut.expectComplete()
        responses.expectComplete()
      }

      "has a request with a chunked entity and Connection: close" in new TestSetup {
        requestsSub.sendNext(HttpRequest(
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, Source(List("ABC", "DEFX").map(ByteString(_)))),
          headers = List(Connection("close"))
        ))

        expectWireData(
          """GET / HTTP/1.1
            |Connection: close
            |Host: example.com
            |User-Agent: akka-http/test
            |Transfer-Encoding: chunked
            |Content-Type: text/plain; charset=UTF-8
            |
            |""")
        expectWireData("3\nABC\n")
        expectWireData("4\nDEFX\n")
        expectWireData("0\n\n")

        sendWireData(
          """HTTP/1.1 200 OK
            |Connection: close
            |Content-Length: 0
            |
            |""")

        expectResponse()

        // explicit `requestsSub.sendComplete()` not needed
        responses.expectComplete()
        netOut.expectComplete()
      }

      "has a request with a overridden User-Agent RawHeader" in new TestSetup {
        val request = HttpRequest().addHeader(RawHeader("User-Agent", "akka-http/test-overridden"))
        requestsSub.sendNext(request)
        expectWireData(
          """GET / HTTP/1.1
            |User-Agent: akka-http/test-overridden
            |Host: example.com
            |
            |""")

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a request with a overridden Host RawHeader" in new TestSetup {
        val request = HttpRequest().addHeader(RawHeader("Host", "testhost.com"))
        requestsSub.sendNext(request)
        expectWireData(
          """GET / HTTP/1.1
            |Host: testhost.com
            |User-Agent: akka-http/test
            |
            |""")

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "exhibits eager request stream completion" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        requestsSub.sendComplete()
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }
    }

    "close the connection if response entity stream has been cancelled" in new TestSetup {
      // two requests are sent in order to make sure that connection
      // isn't immediately closed after the first one by the server
      requestsSub.sendNext(HttpRequest())
      requestsSub.sendNext(HttpRequest())
      requestsSub.sendComplete()

      expectWireData(
        """GET / HTTP/1.1
          |Host: example.com
          |User-Agent: akka-http/test
          |
          |""")

      // two chunks sent by server
      sendWireData(
        """HTTP/1.1 200 OK
          |Transfer-Encoding: chunked
          |
          |6
          |abcdef
          |6
          |abcdef
          |0
          |
          |""")

      inside(expectResponse()) {
        case HttpResponse(StatusCodes.OK, _, HttpEntity.Chunked(_, data), _) =>
          val dataProbe = TestSubscriber.manualProbe[ChunkStreamPart]()
          // but only one consumed by server
          data.take(1).to(Sink.fromSubscriber(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(1)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectComplete()
          // connection is closed once requested elements are consumed
          netInSub.expectCancellation()
      }
    }

    "proceed to next response once previous response's entity has been drained" in new TestSetup {
      def twice(action: => Unit): Unit = { action; action }

      twice {
        requestsSub.sendNext(HttpRequest())

        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |6
            |abcdef
            |0
            |
            |""")

        val whenComplete = expectResponse().entity.dataBytes.runWith(Sink.ignore)
        whenComplete.futureValue should be(akka.Done)
      }
    }

    "handle several requests on one persistent connection" which {
      "has a first response that was chunked" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        val HttpResponse(_, _, HttpEntity.Chunked(_, chunks), _) = expectResponse()

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("0\n\n")
        sub.request(1)
        probe.expectNext(HttpEntity.LastChunk)
        sub.request(1)
        probe.expectComplete()

        // simulate that response is received before method bypass reaches response parser
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)

        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")
        requestsSub.sendComplete()
        responses.expectNext(HttpResponse())

        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }
    }

    "process the illegal response header value properly" which {

      val illegalChar = '\u0001'
      val escapeChar = "\\u%04x" format illegalChar.toInt

      "catch illegal response header value by default" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          s"""HTTP/1.1 200 OK
              |Some-Header: value1$illegalChar
              |Other-Header: value2
              |
              |""")

        responsesSub.request(1)
        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual s"""Illegal character '$escapeChar' in header value"""
        netOut.expectError(error)
        requestsSub.expectCancellation()
        netInSub.expectCancellation()
      }

      val ignoreConfig =
        """
          akka.http.parsing.illegal-response-header-value-processing-mode = ignore
        """
      "ignore illegal response header value if setting the config to ignore" in new TestSetup(config = ignoreConfig) {
        sendStandardRequest()
        sendWireData(
          s"""HTTP/1.1 200 OK
              |Some-Header: value1$illegalChar
              |Other-Header: value2
              |
              |""")

        val HttpResponse(_, headers, _, _) = expectResponse()
        val headerStr = headers.map(h => s"${h.name}: ${h.value}").mkString(",")
        headerStr shouldEqual "Some-Header: value1,Other-Header: value2"
      }

      val ignoreNameConfig =
        """
          akka.http.parsing.illegal-response-header-name-processing-mode = ignore
        """
      "ignore illegal response header name if setting the config to ignore" in new TestSetup(config = ignoreNameConfig) {
        sendStandardRequest()
        sendWireData(
          s"""HTTP/1.1 200 OK
              |Some Header: value1
              |Other-Header: value2
              |
              |""")

        val HttpResponse(_, headers, _, _) = expectResponse()
        val headerStr = headers.map(h => s"${h.name}: ${h.value}").mkString(",")
        headerStr shouldEqual "Some Header: value1,Other-Header: value2"
      }

      val warnConfig =
        """
          akka.http.parsing.illegal-response-header-value-processing-mode = warn
        """
      "ignore illegal response header value and log a warning message if setting the config to warn" in new TestSetup(config = warnConfig) {
        sendStandardRequest()
        sendWireData(
          s"""HTTP/1.1 200 OK
              |Some-Header: value1$illegalChar
              |Other-Header: value2
              |
              |""")

        val HttpResponse(_, headers, _, _) = expectResponse()
        val headerStr = headers.map(h => s"${h.name}: ${h.value}").mkString(",")
        headerStr shouldEqual "Some-Header: value1,Other-Header: value2"
      }
    }

    val warnNameConfig =
      """
          akka.http.parsing.illegal-response-header-name-processing-mode = warn
        """
    "ignore illegal response header name and log a warning message if setting the config to warn" in new TestSetup(config = warnNameConfig) {
      sendStandardRequest()
      sendWireData(
        s"""HTTP/1.1 200 OK
              |Some Header: value1
              |Other-Header: value2
              |
              |""")

      val HttpResponse(_, headers, _, _) = expectResponse()
      val headerStr = headers.map(h => s"${h.name}: ${h.value}").mkString(",")
      headerStr shouldEqual "Some Header: value1,Other-Header: value2"
    }

    "produce proper errors" which {

      "catch the request entity stream being shorter than the Content-Length" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source.fromPublisher(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest()
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendComplete()

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to 2 bytes less"
        netInSub.sendComplete()
        responsesSub.request(1)
        responses.expectError().getMessage should (
          equal("HTTP message had declared Content-Length 8 but entity data stream amounts to 2 bytes less") or // with Akka 2.6
          equal("The http server closed the connection unexpectedly before delivering responses for 1 outstanding requests") // with Akka 2.5
        )
      }

      "catch the request entity stream being longer than the Content-Length" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source.fromPublisher(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest()
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XYZ"))

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to more bytes"
        netInSub.sendComplete()
        responsesSub.request(1)
        responses.expectError().getMessage should (
          equal("HTTP message had declared Content-Length 8 but entity data stream amounts to more bytes") or // with Akka 2.6
          equal("The http server closed the connection unexpectedly before delivering responses for 1 outstanding requests") // with Akka 2.5
        )
      }

      "catch illegal response starts" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.2 200 OK
            |
            |""")

        responsesSub.request(1)
        val error @ IllegalResponseException(info) = responses.expectError()
        info.formatPretty shouldEqual "The server-side protocol or HTTP version is not supported: start of response: [48 54 54 50 2F 31 2E 32 20 32 30 30 20 4F 4B 0D  | HTTP/1.2 200 OK.]"
        netOut.expectError(error)
        requestsSub.expectCancellation()
        netInSub.expectCancellation()
      }

      "catch illegal response chunks" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("4\nDEFXX")
        sub.request(1)
        val _@ EntityStreamException(info) = probe.expectError()
        info.summary shouldEqual "Illegal chunk termination"

        responses.expectComplete()
        netOut.expectComplete()
        requestsSub.expectCancellation()
        netInSub.expectCancellation()
      }

      "catch a response start truncation" in new TestSetup {
        sendStandardRequest()
        sendWireData("HTTP/1.1 200 OK")
        netInSub.sendComplete()

        responsesSub.request(1)
        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "Illegal HTTP message start"
        netOut.expectError(error)
        requestsSub.expectCancellation()
      }
    }

    def isDefinedVia = afterWord("is defined via")
    "support response length verification" which isDefinedVia {
      import HttpEntity._

      class LengthVerificationTest(maxContentLength: Int) extends TestSetup(maxContentLength) {
        val entityBase = "0123456789ABCD"

        def sendStrictResponseWithLength(bytes: Int) =
          sendWireData(
            s"""HTTP/1.1 200 OK
               |Content-Length: $bytes
               |
               |${entityBase take bytes}""")
        def sendDefaultResponseWithLength(bytes: Int) = {
          sendWireData(
            s"""HTTP/1.1 200 OK
               |Content-Length: $bytes
               |
               |${entityBase take 3}""")
          sendWireData(entityBase.slice(3, 7))
          sendWireData(entityBase.slice(7, bytes))
        }
        def sendChunkedResponseWithLength(bytes: Int) =
          sendWireData(
            s"""HTTP/1.1 200 OK
               |Transfer-Encoding: chunked
               |
               |3
               |${entityBase take 3}
               |4
               |${entityBase.slice(3, 7)}
               |${bytes - 7}
               |${entityBase.slice(7, bytes)}
               |0
               |
               |""")
        def sendCloseDelimitedResponseWithLength(bytes: Int) = {
          sendWireData(
            s"""HTTP/1.1 200 OK
               |
               |${entityBase take 3}""")
          sendWireData(entityBase.slice(3, 7))
          sendWireData(entityBase.slice(7, bytes))
          netInSub.sendComplete()
        }

        implicit class XResponse(response: HttpResponse) {
          val timeout = 500.millis.dilated

          def expectStrictEntityWithLength(bytes: Int) =
            response shouldEqual HttpResponse(
              entity = Strict(ContentTypes.`application/octet-stream`, ByteString(entityBase take bytes)))

          def expectEntity[T <: HttpEntity: ClassTag](bytes: Int) =
            inside(response) {
              case HttpResponse(_, _, entity: T, _) =>
                entity.toStrict(100.millis.dilated).awaitResult(timeout).data.utf8String shouldEqual entityBase.take(bytes)
            }

          def expectSizeErrorInEntityOfType[T <: HttpEntity: ClassTag](limit: Int, actualSize: Option[Long] = None) =
            inside(response) {
              case HttpResponse(_, _, entity: T, _) =>
                def gatherBytes = entity.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(timeout)
                (the[RuntimeException] thrownBy gatherBytes).getCause shouldEqual EntityStreamSizeException(limit, actualSize)
            }
        }
      }

      "the config setting (strict entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStandardRequest()
        sendStrictResponseWithLength(10)
        expectResponse().expectStrictEntityWithLength(10)

        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStandardRequest()
        sendStrictResponseWithLength(11)
        expectResponse().expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "the config setting (default entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStandardRequest()
        sendDefaultResponseWithLength(10)
        expectResponse().expectEntity[Default](10)

        sendStandardRequest()
        sendDefaultResponseWithLength(11)
        expectResponse().expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "the config setting (chunked entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStandardRequest()
        sendChunkedResponseWithLength(10)
        expectResponse().expectEntity[Chunked](10)

        sendStandardRequest()
        sendChunkedResponseWithLength(11)
        expectResponse().expectSizeErrorInEntityOfType[Chunked](limit = 10)
      }

      "the config setting (close-delimited entity)" in {
        new LengthVerificationTest(maxContentLength = 10) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(10)
          expectResponse().expectEntity[CloseDelimited](10)
        }
        new LengthVerificationTest(maxContentLength = 10) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(11)
          expectResponse().expectSizeErrorInEntityOfType[CloseDelimited](limit = 10)
        }
      }

      "a smaller programmatically-set limit (strict entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStandardRequest()
        sendStrictResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectStrictEntityWithLength(10)

        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStandardRequest()
        sendStrictResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a smaller programmatically-set limit (default entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStandardRequest()
        sendDefaultResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Default](10)

        sendStandardRequest()
        sendDefaultResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a smaller programmatically-set limit (chunked entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStandardRequest()
        sendChunkedResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Chunked](10)

        sendStandardRequest()
        sendChunkedResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10).expectSizeErrorInEntityOfType[Chunked](limit = 10)
      }

      "a smaller programmatically-set limit (close-delimited entity)" in {
        new LengthVerificationTest(maxContentLength = 12) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(10)
          expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[CloseDelimited](10)
        }
        new LengthVerificationTest(maxContentLength = 12) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(11)
          expectResponse().mapEntity(_ withSizeLimit 10).expectSizeErrorInEntityOfType[CloseDelimited](limit = 10)
        }
      }

      "a larger programmatically-set limit (strict entity)" in new LengthVerificationTest(maxContentLength = 8) {
        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStandardRequest()
        sendStrictResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Default](10)

        sendStandardRequest()
        sendStrictResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a larger programmatically-set limit (default entity)" in new LengthVerificationTest(maxContentLength = 8) {
        sendStandardRequest()
        sendDefaultResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Default](10)

        sendStandardRequest()
        sendDefaultResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a larger programmatically-set limit (chunked entity)" in new LengthVerificationTest(maxContentLength = 8) {
        sendStandardRequest()
        sendChunkedResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Chunked](10)

        sendStandardRequest()
        sendChunkedResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Chunked](limit = 10)
      }

      "a larger programmatically-set limit (close-delimited entity)" in {
        new LengthVerificationTest(maxContentLength = 8) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(10)
          expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[CloseDelimited](10)
        }
        new LengthVerificationTest(maxContentLength = 8) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(11)
          expectResponse().mapEntity(_ withSizeLimit 10).expectSizeErrorInEntityOfType[CloseDelimited](limit = 10)
        }
      }
    }

    "support requests with an `Expect: 100-continue` headers" which {

      "have a strict entity and receive a `100 Continue` response" in new TestSetup {
        requestsSub.sendNext(HttpRequest(POST, headers = List(Expect.`100-continue`), entity = "ABCDEF"))
        expectWireData(
          """POST / HTTP/1.1
            |Expect: 100-continue
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 6
            |
            |""")
        netOutSub.request(1)
        netOut.expectNoMessage(50.millis)

        sendWireData(
          """HTTP/1.1 100 Continue
            |
            |""")

        netOut.expectNext().utf8String shouldEqual "ABCDEF"

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "have a default entity and receive a `100 Continue` response" in new TestSetup {
        val entityParts = List("ABC", "DE", "FGH").map(ByteString(_))
        requestsSub.sendNext(HttpRequest(POST, headers = List(Expect.`100-continue`),
          entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(entityParts))))
        expectWireData(
          """POST / HTTP/1.1
            |Expect: 100-continue
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        netOutSub.request(1)
        netOut.expectNoMessage(50.millis)

        sendWireData(
          """HTTP/1.1 100 Continue
            |
            |""")

        netOut.expectNext().utf8String shouldEqual "ABC"
        expectWireData("DE")
        expectWireData("FGH")

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "receive a normal response" in new TestSetup {
        requestsSub.sendNext(HttpRequest(POST, headers = List(Expect.`100-continue`), entity = "ABCDEF"))
        expectWireData(
          """POST / HTTP/1.1
            |Expect: 100-continue
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 6
            |
            |""")
        netOutSub.request(1)
        netOut.expectNoMessage(50.millis)

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        expectWireData("ABCDEF")

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "receive an error response" in new TestSetup {
        requestsSub.sendNext(HttpRequest(POST, headers = List(Expect.`100-continue`), entity = "ABCDEF"))
        requestsSub.sendComplete()
        expectWireData(
          """POST / HTTP/1.1
            |Expect: 100-continue
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 6
            |
            |""")
        netOutSub.request(1)
        netOut.expectNoMessage(50.millis)

        sendWireData(
          """HTTP/1.1 400 Bad Request
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse(400)

        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }
    }

    "ignore interim 1xx responses" in new TestSetup {
      sendStandardRequest()
      sendWireData(
        """HTTP/1.1 102 Processing
          |Content-Length: 0
          |
          |""")
      sendWireData(
        """HTTP/1.1 102 Processing
          |Content-Length: 0
          |
          |""")
      sendWireData(
        """HTTP/1.1 200 OK
          |Content-Length: 0
          |
          |""")

      expectResponse() shouldEqual HttpResponse()

      requestsSub.sendComplete()
      netOut.expectComplete()
      netInSub.sendComplete()
      responses.expectComplete()
    }

    "receive Content-Length for HEAD requests" in new TestSetup {
      requestsSub.sendNext(HttpRequest(method = HttpMethods.HEAD))
      expectWireData(
        """HEAD / HTTP/1.1
          |Host: example.com
          |User-Agent: akka-http/test
          |
          |""")
      sendWireData(
        """HTTP/1.1 200 OK
          |Content-Length: 6
          |
          |""")

      val HttpResponse(StatusCodes.OK, _, HttpEntity.Default(_, contentLength, data), _) = expectResponse()
      contentLength shouldEqual 6

      val chunks = data.runWith(Sink.seq).awaitResult(3.seconds.dilated)
      chunks.length shouldEqual 0

      requestsSub.sendComplete()
      netOut.expectComplete()
      netInSub.sendComplete()
      responses.expectComplete()
    }
  }

  class TestSetup(maxResponseContentLength: Int = -1, config: String = "") {
    val requests = TestPublisher.manualProbe[HttpRequest]()
    val responses = TestSubscriber.manualProbe[HttpResponse]()

    def settings = {
      val s = ClientConnectionSettings(
        ConfigFactory.parseString(config).withFallback(system.settings.config)
      ).withUserAgentHeader(Some(`User-Agent`(List(ProductVersion("akka-http", "test")))))
      if (maxResponseContentLength < 0) s
      else s.withParserSettings(s.parserSettings.withMaxContentLength(maxResponseContentLength))
    }

    val (netOut, netIn) = {
      val netOut = TestSubscriber.manualProbe[ByteString]()
      val netIn = TestPublisher.manualProbe[ByteString]()

      RunnableGraph.fromGraph(GraphDSL.createGraph(OutgoingConnectionBlueprint(Host("example.com"), settings, NoLogging)) { implicit b => client =>
        import GraphDSL.Implicits._
        Source.fromPublisher(netIn) ~> Flow[ByteString].map(SessionBytes(null, _)) ~> client.in2
        client.out1 ~> Flow[SslTlsOutbound].collect { case SendBytes(x) => x } ~> Sink.fromSubscriber(netOut)
        Source.fromPublisher(requests) ~> client.in1
        client.out2 ~> Sink.fromSubscriber(responses)
        ClosedShape
      }).run()

      netOut -> netIn
    }

    def wipeDate(string: String) =
      string.fastSplit('\n').map {
        case s if s.startsWith("Date:") => "Date: XXXX\r"
        case s                          => s
      }.mkString("\n")

    val netInSub = netIn.expectSubscription()
    val netOutSub = netOut.expectSubscription()
    val requestsSub = requests.expectSubscription()
    val responsesSub = responses.expectSubscription()

    requestsSub.expectRequest(16)
    netInSub.expectRequest(16)

    def sendWireData(data: String): Unit = sendWireData(ByteString(data.stripMarginWithNewline("\r\n"), "ASCII"))
    def sendWireData(data: ByteString): Unit = netInSub.sendNext(data)

    def expectWireData(s: String) = {
      netOutSub.request(1)
      netOut.expectNext().utf8String shouldEqual s.stripMarginWithNewline("\r\n")
    }

    def closeNetworkInput(): Unit = netInSub.sendComplete()

    def sendStandardRequest() = {
      requestsSub.sendNext(HttpRequest())
      expectWireData(
        """GET / HTTP/1.1
          |Host: example.com
          |User-Agent: akka-http/test
          |
          |""")
    }

    def expectResponse() = {
      responsesSub.request(1)
      responses.expectNext()
    }

  }
}
