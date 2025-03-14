/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import akka.annotation.InternalApi
import akka.parboiled2.CharUtils
import akka.stream.{ Attributes, SourceShape }
import akka.util.ByteString
import akka.event.LoggingAdapter
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import akka.http.scaladsl.model.ContentTypes._
import akka.stream.stage.GraphStage
import akka.stream._
import akka.stream.scaladsl.{ Flow, Sink, Source }

import scala.collection.immutable

/**
 * INTERNAL API
 */
@InternalApi
private[http] object RenderSupport {
  val DefaultStatusLineBytes = "HTTP/1.1 200 OK\r\n".asciiBytes
  val StatusLineStartBytes = "HTTP/1.1 ".asciiBytes
  val ChunkedBytes = "chunked".asciiBytes
  val KeepAliveBytes = "Keep-Alive".asciiBytes
  val CloseBytes = "close".asciiBytes
  val CrLf = "\r\n".asciiBytes
  val ContentLengthBytes = "Content-Length: ".asciiBytes

  private def preRenderContentType(ct: ContentType): Array[Byte] =
    (new ByteArrayRendering(64) ~~ headers.`Content-Type` ~~ ct ~~ CrLf).get

  private val ApplicationJsonContentType = preRenderContentType(`application/json`)
  private val TextPlainContentType = preRenderContentType(`text/plain(UTF-8)`)
  private val TextXmlContentType = preRenderContentType(`text/xml(UTF-8)`)
  private val TextHtmlContentType = preRenderContentType(`text/html(UTF-8)`)
  private val TextCsvContentType = preRenderContentType(`text/csv(UTF-8)`)

  implicit val trailerRenderer: Renderer[immutable.Iterable[HttpHeader]] =
    Renderer.genericSeqRenderer[Renderable, HttpHeader](Rendering.CrLf, Rendering.Empty)

  val defaultLastChunkBytes: ByteString = renderChunk(HttpEntity.LastChunk)

  def CancelSecond[T, Mat](first: Source[T, Mat], second: Source[T, Any]): Source[T, Mat] =
    Source.fromGraph(GraphDSL.createGraph(first) { implicit b => frst =>
      import GraphDSL.Implicits._
      second ~> Sink.cancelled
      SourceShape(frst.out)
    })

  def renderEntityContentType(r: Rendering, entity: HttpEntity): r.type = {
    val ct = entity.contentType

    if (ct eq NoContentType) r
    else if (ct eq `application/json`) r ~~ ApplicationJsonContentType
    else if (ct eq `text/plain(UTF-8)`) r ~~ TextPlainContentType
    else if (ct eq `text/xml(UTF-8)`) r ~~ TextXmlContentType
    else if (ct eq `text/html(UTF-8)`) r ~~ TextHtmlContentType
    else if (ct eq `text/csv(UTF-8)`) r ~~ TextCsvContentType
    else
      r ~~ headers.`Content-Type` ~~ ct ~~ CrLf
  }

  object ChunkTransformer {
    val flow = Flow.fromGraph(new ChunkTransformer).named("renderChunks")
  }

  class ChunkTransformer extends GraphStage[FlowShape[HttpEntity.ChunkStreamPart, ByteString]] {
    val in: Inlet[HttpEntity.ChunkStreamPart] = Inlet("ChunkTransformer.in")
    val out: Outlet[ByteString] = Outlet("ChunkTransformer.out")
    val shape: FlowShape[HttpEntity.ChunkStreamPart, ByteString] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = {
          val chunk = grab(in)
          val bytes = renderChunk(chunk)
          push(out, bytes)
          if (chunk.isLastChunk) completeStage()
        }

        override def onPull(): Unit = pull(in)

        override def onUpstreamFinish(): Unit = {
          emit(out, defaultLastChunkBytes)
          completeStage()
        }
        setHandlers(in, out, this)
      }
  }

  object CheckContentLengthTransformer {
    def flow(contentLength: Long) = Flow[ByteString].via(new CheckContentLengthTransformer(contentLength))
  }

  final class CheckContentLengthTransformer(length: Long) extends SimpleLinearGraphStage[ByteString] {
    override def initialAttributes: Attributes = Attributes.name("CheckContentLength")

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        override def toString = s"CheckContentLength(sent=$sent)"

        private var sent = 0L

        override def onPush(): Unit = {
          val elem = grab(in)
          sent += elem.length
          if (sent <= length) {
            push(out, elem)
          } else {
            failStage(InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity data stream amounts to more bytes"))
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (sent < length) {
            failStage(InvalidContentLengthException(s"HTTP message had declared Content-Length $length but entity data stream amounts to ${length - sent} bytes less"))
          } else {
            completeStage()
          }
        }

        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }

    override def toString = "CheckContentLength"
  }

  private def renderChunk(chunk: HttpEntity.ChunkStreamPart): ByteString = {
    import chunk._
    val renderedSize = // buffer space required for rendering (without trailer)
      CharUtils.numberOfHexDigits(data.length) +
        (if (extension.isEmpty) 0 else extension.length + 1) +
        data.length +
        2 + 2
    val r = new ByteStringRendering(renderedSize)
    r ~~% data.length
    if (extension.nonEmpty) r ~~ ';' ~~ extension
    r ~~ CrLf
    chunk match {
      case HttpEntity.Chunk(data, _)        => r ~~ data
      case HttpEntity.LastChunk(_, Nil)     => // nothing to do
      case HttpEntity.LastChunk(_, trailer) => r ~~ trailer ~~ CrLf
    }
    r ~~ CrLf
    r.get
  }

  def suppressionWarning(log: LoggingAdapter, h: HttpHeader,
                         msg: String = "the akka-http-core layer sets this header automatically!"): Unit =
    log.warning("Explicitly set HTTP header '{}' is ignored, {}", h, msg)
}
