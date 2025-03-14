/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine

import java.util.concurrent.CountDownLatch
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.CommonBenchmark
import akka.http.impl.engine.server.HttpServerBluePrint
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLSPlacebo
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

class ServerProcessingBenchmark extends CommonBenchmark {
  val request = ByteString("GET / HTTP/1.1\r\nHost: localhost\r\nUser-Agent: test\r\n\r\n")
  val response = HttpResponse()

  var httpFlow: Flow[ByteString, ByteString, Any] = _
  implicit var system: ActorSystem = _

  @Benchmark
  @OperationsPerInvocation(10000)
  def benchRequestProcessing(): Unit = {
    val numRequests = 10000
    val latch = new CountDownLatch(numRequests)
    Source.repeat(request)
      .take(numRequests)
      .via(httpFlow)
      .runForeach(_ => latch.countDown())

    latch.await()
  }

  @Setup
  def setup(): Unit = {
    val config =
      ConfigFactory.parseString(
        """
           akka.actor.default-dispatcher.fork-join-executor.parallelism-max = 1
           akka.http.server.server-header = "akka-http-bench"
        """)
        .withFallback(ConfigFactory.load())
    system = ActorSystem("AkkaHttpBenchmarkSystem", config)
    httpFlow =
      Flow[HttpRequest].map(_ => response) join
        (HttpServerBluePrint(ServerSettings(system), NoLogging, false, Http().dateHeaderRendering) atop
          TLSPlacebo())
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
