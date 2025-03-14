/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.marshalling

import java.util.{ Optional, function }

import akka.annotation.InternalApi
import akka.http.impl.util.JavaMapping
import akka.http.javadsl.model.{ ContentType, HttpHeader, HttpResponse, MediaType, RequestEntity, StatusCode }
import akka.http.scaladsl
import akka.http.scaladsl.marshalling
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.{ FormData, HttpCharset }
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.annotation.unchecked.uncheckedVariance

object Marshaller {

  import JavaMapping.Implicits._

  def fromScala[A, B](scalaMarshaller: marshalling.Marshaller[A, B]): Marshaller[A, B] = new Marshaller()(scalaMarshaller)

  def toOption[T](opt: Optional[T]): Option[T] = if (opt.isPresent) Some(opt.get()) else None

  /**
   * Safe downcasting of the output type of the marshaller to a superclass.
   *
   * Marshaller is covariant in B, i.e. if B2 is a subclass of B1,
   * then Marshaller[X,B2] is OK to use where Marshaller[X,B1] is expected.
   */
  def downcast[A, B1, B2 <: B1](m: Marshaller[A, B2]): Marshaller[A, B1] = m.asInstanceOf[Marshaller[A, B1]]

  /**
   * Safe downcasting of the output type of the marshaller to a superclass.
   *
   * Marshaller is covariant in B, i.e. if B2 is a subclass of B1,
   * then Marshaller[X,B2] is OK to use where Marshaller[X,B1] is expected.
   */
  def downcast[A, B1, B2 <: B1](m: Marshaller[A, B2], target: Class[B1]): Marshaller[A, B1] = m.asInstanceOf[Marshaller[A, B1]]

  def stringToEntity: Marshaller[String, RequestEntity] = fromScala(marshalling.Marshaller.StringMarshaller)

  def byteArrayToEntity: Marshaller[Array[Byte], RequestEntity] = fromScala(marshalling.Marshaller.ByteArrayMarshaller)

  def charArrayToEntity: Marshaller[Array[Char], RequestEntity] = fromScala(marshalling.Marshaller.CharArrayMarshaller)

  def byteStringToEntity: Marshaller[ByteString, RequestEntity] = fromScala(marshalling.Marshaller.ByteStringMarshaller)

  def formDataToEntity: Marshaller[FormData, RequestEntity] = fromScala(marshalling.Marshaller.FormDataMarshaller)

  def byteStringMarshaller(t: ContentType): Marshaller[ByteString, RequestEntity] =
    fromScala(scaladsl.marshalling.Marshaller.byteStringMarshaller(t.asScala))

  /**
   * Marshals an Optional[A] to a RequestEntity an empty optional will yield an empty entity.
   */
  def optionMarshaller[A](m: Marshaller[A, RequestEntity]): Marshaller[Optional[A], RequestEntity] = {
    val scalaMarshaller = m.asScalaCastOutput
    fromScala(marshalling.Marshaller.optionMarshaller(scalaMarshaller, EmptyValue.emptyEntity).compose(toOption))
  }

  // TODO make sure these are actually usable in a sane way
  def wrapEntity[A, C](f: function.BiFunction[ExecutionContext, C, A], m: Marshaller[A, RequestEntity], mediaType: MediaType): Marshaller[C, RequestEntity] = {
    val scalaMarshaller = m.asScalaCastOutput
    fromScala(scalaMarshaller.wrapWithEC(mediaType.asScala) { ctx => (c: C) => f(ctx, c) }(ContentTypeOverrider.forEntity))
  }

  def wrapEntity[A, C, E <: RequestEntity](f: function.Function[C, A], m: Marshaller[A, E], mediaType: MediaType): Marshaller[C, RequestEntity] = {
    val scalaMarshaller = m.asScalaCastOutput
    fromScala(scalaMarshaller.wrap(mediaType.asScala)((in: C) => f.apply(in))(ContentTypeOverrider.forEntity))
  }

  def entityToOKResponse[A](m: Marshaller[A, _ <: RequestEntity]): Marshaller[A, HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A]()(m.asScalaCastOutput))
  }

  def entityToResponse[A, R <: RequestEntity](status: StatusCode, m: Marshaller[A, R]): Marshaller[A, HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A](status.asScala)(m.asScalaCastOutput))
  }

  def entityToResponse[A](status: StatusCode, headers: java.lang.Iterable[HttpHeader], m: Marshaller[A, _ <: RequestEntity]): Marshaller[A, HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A](status.asScala, headers.asScala)(m.asScalaCastOutput))
  }

  def entityToOKResponse[A](headers: java.lang.Iterable[HttpHeader], m: Marshaller[A, _ <: RequestEntity]): Marshaller[A, HttpResponse] = {
    fromScala(marshalling.Marshaller.fromToEntityMarshaller[A](headers = headers.asScala)(m.asScalaCastOutput))
  }

  // these are methods not varargs to avoid call site warning about unchecked type params

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all passed in marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/akka/akka-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[A, B](ms: Marshaller[A, B]*): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf[A, B](ms.map(_.asScala): _*))
  }

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/akka/akka-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala))
  }

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/akka/akka-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala))
  }

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/akka/akka-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B], m4: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala, m4.asScala))
  }

  /**
   * Helper for creating a "super-marshaller" from a number of "sub-marshallers".
   * Content-negotiation determines, which "sub-marshaller" eventually gets to do the job.
   *
   * Please note that all marshallers will actually be invoked in order to get the Marshalling object
   * out of them, and later decide which of the marshallings should be returned. This is by-design,
   * however in ticket as discussed in ticket https://github.com/akka/akka-http/issues/243 it MAY be
   * changed in later versions of Akka HTTP.
   */
  def oneOf[A, B](m1: Marshaller[A, B], m2: Marshaller[A, B], m3: Marshaller[A, B], m4: Marshaller[A, B], m5: Marshaller[A, B]): Marshaller[A, B] = {
    fromScala(marshalling.Marshaller.oneOf(m1.asScala, m2.asScala, m3.asScala, m4.asScala, m5.asScala))
  }

  /**
   * Helper for creating a synchronous [[Marshaller]] to content with a fixed charset from the given function.
   */
  def withFixedContentType[A, B](contentType: ContentType, f: java.util.function.Function[A, B]): Marshaller[A, B] =
    fromScala(marshalling.Marshaller.withFixedContentType(contentType.asScala)(f.apply))

  /**
   * Helper for creating a synchronous [[Marshaller]] to content with a negotiable charset from the given function.
   */
  def withOpenCharset[A, B](mediaType: MediaType.WithOpenCharset, f: java.util.function.BiFunction[A, HttpCharset, B]): Marshaller[A, B] =
    fromScala(marshalling.Marshaller.withOpenCharset(mediaType.asScala)(f.apply))

  /**
   * Helper for creating a synchronous [[Marshaller]] to non-negotiable content from the given function.
   */
  def opaque[A, B](f: function.Function[A, B]): Marshaller[A, B] =
    fromScala(scaladsl.marshalling.Marshaller.opaque[A, B] { a => f.apply(a) })

  implicit def asScalaToResponseMarshaller[T](m: Marshaller[T, akka.http.javadsl.model.HttpResponse]): ToResponseMarshaller[T] =
    m.asScala.map(_.asScala)

  implicit def asScalaEntityMarshaller[T](m: Marshaller[T, akka.http.javadsl.model.RequestEntity]): akka.http.scaladsl.marshalling.Marshaller[T, akka.http.scaladsl.model.RequestEntity] =
    m.asScala.map(_.asScala)
}

class Marshaller[-A, +B] private (implicit val asScala: marshalling.Marshaller[A, B]) {
  import Marshaller.fromScala

  /** INTERNAL API: involves unsafe cast (however is very fast) */
  // TODO would be nice to not need this special case
  @InternalApi
  private[akka] def asScalaCastOutput[C]: marshalling.Marshaller[A, C] = asScala.asInstanceOf[marshalling.Marshaller[A, C]]

  def map[C](f: function.Function[B @uncheckedVariance, C]): Marshaller[A, C] = fromScala(asScala.map(f.apply))

  def compose[C](f: function.Function[C, A @uncheckedVariance]): Marshaller[C, B] = fromScala(asScala.compose(f.apply))
}
