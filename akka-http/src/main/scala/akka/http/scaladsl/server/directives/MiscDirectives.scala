/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.directives.BasicDirectives._
import akka.http.scaladsl.server.RequestEntityExpectedRejection
import headers._

import scala.annotation.nowarn

/**
 * @groupname misc Miscellaneous directives
 * @groupprio misc 140
 */
trait MiscDirectives {
  import RouteDirectives._

  /**
   * Checks the given condition before running its inner route.
   * If the condition fails the route is rejected with a [[ValidationRejection]].
   *
   * @group misc
   */
  def validate(check: => Boolean, errorMsg: String): Directive0 =
    Directive { inner => if (check) inner(()) else reject(ValidationRejection(errorMsg)) }

  /**
   * Extracts the client's IP from either the X-Forwarded-For, Remote-Address, X-Real-IP header
   * or [[akka.http.scaladsl.model.AttributeKeys.remoteAddress]] attribute
   * (in that order of priority).
   *
   * @group misc
   */
  def extractClientIP: Directive1[RemoteAddress] = MiscDirectives._extractClientIP

  /**
   * Rejects if the request entity is non-empty.
   *
   * @group misc
   */
  def requestEntityEmpty: Directive0 = MiscDirectives._requestEntityEmpty

  /**
   * Rejects with a [[RequestEntityExpectedRejection]] if the request entity is empty.
   * Non-empty requests are passed on unchanged to the inner route.
   *
   * @group misc
   */
  def requestEntityPresent: Directive0 = MiscDirectives._requestEntityPresent

  /**
   * Converts responses with an empty entity into (empty) rejections.
   * This way you can, for example, have the marshalling of a ''None'' option
   * be treated as if the request could not be matched.
   *
   * @group misc
   */
  def rejectEmptyResponse: Directive0 = MiscDirectives._rejectEmptyResponse

  /**
   * Inspects the request's `Accept-Language` header and determines,
   * which of the given language alternatives is preferred by the client.
   * (See http://tools.ietf.org/html/rfc7231#section-5.3.5 for more details on the
   * negotiation logic.)
   * If there are several best language alternatives that the client
   * has equal preference for (even if this preference is zero!)
   * the order of the arguments is used as a tie breaker (First one wins).
   *
   * @group misc
   */
  def selectPreferredLanguage(first: Language, more: Language*): Directive1[Language] =
    BasicDirectives.extractRequest.map { request =>
      LanguageNegotiator(request.headers).pickLanguage(first :: List(more: _*)) getOrElse first
    }

  /**
   * Fails the stream with [[akka.http.scaladsl.model.EntityStreamSizeException]] if its request entity size exceeds
   * given limit. Limit given as parameter overrides limit configured with `akka.http.parsing.max-content-length`.
   *
   * Beware that request entity size check is executed when entity is consumed.
   *
   * @group misc
   */
  def withSizeLimit(maxBytes: Long): Directive0 =
    mapRequestContext(_.mapRequest(_.mapEntity(_.withSizeLimit(maxBytes))))

  /**
   *
   * Disables the size limit (configured by `akka.http.parsing.max-content-length` by default) checking on the incoming
   * [[HttpRequest]] entity.
   * Can be useful when handling arbitrarily large data uploads in specific parts of your routes.
   *
   * @note  Usage of `withoutSizeLimit` is not recommended as it turns off the too large payload protection. Therefore,
   *        we highly encourage using `withSizeLimit` instead, providing it with a value high enough to successfully
   *        handle the route in need of big entities.
   *
   * @group misc
   */
  def withoutSizeLimit: Directive0 = MiscDirectives._withoutSizeLimit
}

object MiscDirectives extends MiscDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._
  import RouteResult._

  @nowarn("msg=deprecated")
  private val _extractClientIP: Directive1[RemoteAddress] =
    headerValuePF { case `X-Forwarded-For`(Seq(address, _*)) => address } |
      headerValuePF { case `X-Real-Ip`(address) => address } |
      headerValuePF { case `Remote-Address`(address) => address } |
      extractRequest.map { request =>
        request.attribute(AttributeKeys.remoteAddress).getOrElse(RemoteAddress.Unknown)
      }

  private val _requestEntityEmpty: Directive0 =
    extract(_.request.entity.isKnownEmpty).flatMap(if (_) pass else reject)

  private val _requestEntityPresent: Directive0 =
    extract(_.request.entity.isKnownEmpty).flatMap(if (_) reject(RequestEntityExpectedRejection) else pass)

  private val _rejectEmptyResponse: Directive0 =
    mapRouteResult {
      case Complete(response) if response.entity.isKnownEmpty => Rejected(Nil)
      case x => x
    }

  private val _withoutSizeLimit: Directive0 =
    mapRequestContext(_.mapRequest(_.mapEntity(_.withoutSizeLimit)))
}
