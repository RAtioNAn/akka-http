/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.Done
import akka.http.impl.util.AkkaSpecWithMaterializer
import akka.http.scaladsl.Http
import akka.stream.scaladsl._
import akka.testkit._

import scala.concurrent.duration._
import akka.util.ByteString

import scala.concurrent.{ Await, Promise }

class EntityDiscardingSpec extends AkkaSpecWithMaterializer {
  val testData = Vector.tabulate(200)(i => ByteString(s"row-$i"))

  "HttpRequest" should {

    "discard entity stream after .discardEntityBytes() call" in {

      val p = Promise[Done]()
      val s = Source
        .fromIterator[ByteString](() => testData.iterator)
        .alsoTo(Sink.onComplete(t => p.complete(t)))

      val req = HttpRequest(entity = HttpEntity(ContentTypes.`text/csv(UTF-8)`, s))
      val de = req.discardEntityBytes()

      p.future.futureValue should ===(Done)
      de.future.futureValue should ===(Done)
    }
  }

  "HttpResponse" should {

    "discard entity stream after .discardEntityBytes() call" in {

      val p = Promise[Done]()
      val s = Source
        .fromIterator[ByteString](() => testData.iterator)
        .alsoTo(Sink.onComplete(t => p.complete(t)))

      val resp = HttpResponse(entity = HttpEntity(ContentTypes.`text/csv(UTF-8)`, s))
      val de = resp.discardEntityBytes()

      p.future.futureValue should ===(Done)
      de.future.futureValue should ===(Done)
    }

    // TODO consider improving this by storing a mutable "already materialized" flag somewhere
    // TODO likely this is going to inter-op with the auto-draining as described in #18716
    "should not allow draining a second time" in {
      val bound = Http().newServerAt("localhost", 0).bindSync(req =>
        HttpResponse(entity = HttpEntity(
          ContentTypes.`text/csv(UTF-8)`, Source.fromIterator[ByteString](() => testData.iterator)))).futureValue

      try {

        val response = Http().singleRequest(HttpRequest(uri = s"http://localhost:${bound.localAddress.getPort}/")).futureValue

        val de = response.discardEntityBytes()
        de.future.futureValue should ===(Done)

        val de2 = response.discardEntityBytes()
        val secondRunException = intercept[IllegalStateException] { Await.result(de2.future, 3.seconds.dilated) }
        secondRunException.getMessage should include("cannot be materialized more than once")
      } finally bound.unbind().futureValue
    }
  }

}
