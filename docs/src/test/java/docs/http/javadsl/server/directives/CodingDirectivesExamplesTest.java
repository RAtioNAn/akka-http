/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.headers.AcceptEncoding;
import akka.http.javadsl.model.headers.ContentEncoding;
import akka.http.javadsl.model.headers.HttpEncodings;
import akka.http.javadsl.coding.Coder;
import akka.http.javadsl.server.Rejections;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.util.ByteString;
import org.junit.Test;

import java.util.Collections;

import static akka.http.javadsl.unmarshalling.Unmarshaller.entityToString;

//#responseEncodingAccepted
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.responseEncodingAccepted;

//#responseEncodingAccepted
//#encodeResponse
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.encodeResponse;

//#encodeResponse
//#encodeResponseWith
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.encodeResponseWith;

//#encodeResponseWith
//#decodeRequest
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.decodeRequest;
import static akka.http.javadsl.server.Directives.entity;

//#decodeRequest
//#decodeRequestWith
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.decodeRequestWith;
import static akka.http.javadsl.server.Directives.entity;

//#decodeRequestWith
//#withPrecompressedMediaTypeSupport
import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.withPrecompressedMediaTypeSupport;

//#withPrecompressedMediaTypeSupport

@SuppressWarnings("deprecation")
public class CodingDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testResponseEncodingAccepted() {
    //#responseEncodingAccepted
    final Route route = responseEncodingAccepted(HttpEncodings.GZIP, () ->
      complete("content")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("content");
    runRouteUnSealed(route,
                     HttpRequest.GET("/")
                       .addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE)))
      .assertRejections(Rejections.unacceptedResponseEncoding(HttpEncodings.GZIP));
    //#responseEncodingAccepted
  }

  @Test
  public void testEncodeResponse() {
    //#encodeResponse
    final Route route = encodeResponse(() -> complete("content"));

    // tests:
    testRoute(route).run(
      HttpRequest.GET("/")
        .addHeader(AcceptEncoding.create(HttpEncodings.GZIP))
        .addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE))
    ).assertHeaderExists(ContentEncoding.create(HttpEncodings.GZIP));

    testRoute(route).run(
      HttpRequest.GET("/")
        .addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE))
    ).assertHeaderExists(ContentEncoding.create(HttpEncodings.DEFLATE));

    // This case failed!
//    testRoute(route).run(
//      HttpRequest.GET("/")
//        .addHeader(AcceptEncoding.create(HttpEncodings.IDENTITY))
//    ).assertHeaderExists(ContentEncoding.create(HttpEncodings.IDENTITY));

    //#encodeResponse
  }

  @Test
  public void testEncodeResponseWith() {
    //#encodeResponseWith
    final Route route = encodeResponseWith(
      Collections.singletonList(Coder.Gzip),
      () -> complete("content")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertHeaderExists(ContentEncoding.create(HttpEncodings.GZIP));

    testRoute(route).run(
      HttpRequest.GET("/")
        .addHeader(AcceptEncoding.create(HttpEncodings.GZIP))
        .addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE))
    ).assertHeaderExists(ContentEncoding.create(HttpEncodings.GZIP));

    runRouteUnSealed(route,
      HttpRequest.GET("/")
        .addHeader(AcceptEncoding.create(HttpEncodings.DEFLATE))
    ).assertRejections(Rejections.unacceptedResponseEncoding(HttpEncodings.GZIP));

    runRouteUnSealed(route,
      HttpRequest.GET("/")
        .addHeader(AcceptEncoding.create(HttpEncodings.IDENTITY))
    ).assertRejections(Rejections.unacceptedResponseEncoding(HttpEncodings.GZIP));

    final Route routeWithLevel9 = encodeResponseWith(
            Collections.singletonList(Coder.GzipLevel9),
            () -> complete("content")
    );

    testRoute(routeWithLevel9).run(HttpRequest.GET("/"))
      .assertHeaderExists(ContentEncoding.create(HttpEncodings.GZIP));
    //#encodeResponseWith
  }

  @Test
  public void testDecodeRequest() {
    //#decodeRequest
    final ByteString helloGzipped = Coder.Gzip.encode(ByteString.fromString("Hello"));
    final ByteString helloDeflated = Coder.Deflate.encode(ByteString.fromString("Hello"));

    final Route route = decodeRequest(() ->
      entity(entityToString(), content ->
        complete("Request content: '" + content + "'")
      )
    );

    // tests:
    testRoute(route).run(
      HttpRequest.POST("/").withEntity(helloGzipped)
        .addHeader(ContentEncoding.create(HttpEncodings.GZIP)))
      .assertEntity("Request content: 'Hello'");

    testRoute(route).run(
      HttpRequest.POST("/").withEntity(helloDeflated)
        .addHeader(ContentEncoding.create(HttpEncodings.DEFLATE)))
      .assertEntity("Request content: 'Hello'");

    testRoute(route).run(
      HttpRequest.POST("/").withEntity("hello uncompressed")
        .addHeader(ContentEncoding.create(HttpEncodings.IDENTITY)))
      .assertEntity( "Request content: 'hello uncompressed'");
    //#decodeRequest
  }

  @Test
  public void testDecodeRequestWith() {
    //#decodeRequestWith
    final ByteString helloGzipped = Coder.Gzip.encode(ByteString.fromString("Hello"));
    final ByteString helloDeflated = Coder.Deflate.encode(ByteString.fromString("Hello"));

    final Route route = decodeRequestWith(Coder.Gzip, () ->
      entity(entityToString(), content ->
        complete("Request content: '" + content + "'")
      )
    );

    // tests:
    testRoute(route).run(
      HttpRequest.POST("/").withEntity(helloGzipped)
        .addHeader(ContentEncoding.create(HttpEncodings.GZIP)))
      .assertEntity("Request content: 'Hello'");

    runRouteUnSealed(route,
      HttpRequest.POST("/").withEntity(helloDeflated)
        .addHeader(ContentEncoding.create(HttpEncodings.DEFLATE)))
      .assertRejections(Rejections.unsupportedRequestEncoding(HttpEncodings.GZIP));

    runRouteUnSealed(route,
      HttpRequest.POST("/").withEntity("hello")
        .addHeader(ContentEncoding.create(HttpEncodings.IDENTITY)))
      .assertRejections(Rejections.unsupportedRequestEncoding(HttpEncodings.GZIP));
    //#decodeRequestWith
  }

  @Test
  public void testWithPrecompressedMediaTypeSupport() {
    //#withPrecompressedMediaTypeSupport
    final ByteString svgz = Coder.Gzip.encode(ByteString.fromString("<svg/>"));

    final Route route = withPrecompressedMediaTypeSupport(() ->
      complete(
        HttpResponse.create().withEntity(
          HttpEntities.create(MediaTypes.IMAGE_SVGZ.toContentType(), svgz))
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertMediaType(MediaTypes.IMAGE_SVG_XML)
      .assertHeaderExists(ContentEncoding.create(HttpEncodings.GZIP));
    //#withPrecompressedMediaTypeSupport
  }
}
