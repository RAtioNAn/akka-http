/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.util.ByteString
import headers._

import java.net.InetAddress
import docs.CompileOnlySpec

class MiscDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "extractClientIP-example" in {
    //#extractClientIP-example
    val route = extractClientIP { ip =>
      complete("Client's ip is " + ip.toOption.map(_.getHostAddress).getOrElse("unknown"))
    }

    // tests:
    Get("/").withHeaders(`X-Forwarded-For`(RemoteAddress(InetAddress.getByName("192.168.3.12")))) ~> route ~> check {
      responseAs[String] shouldEqual "Client's ip is 192.168.3.12"
    }
    //#extractClientIP-example
  }

  "rejectEmptyResponse-example" in {
    //#rejectEmptyResponse-example
    val route = rejectEmptyResponse {
      path("even" / IntNumber) { i =>
        complete {
          // returns Some(evenNumberDescription) or None
          Option(i).filter(_ % 2 == 0).map { num =>
            s"Number $num is even."
          }
        }
      }
    }

    // tests:
    Get("/even/23") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    Get("/even/28") ~> route ~> check {
      responseAs[String] shouldEqual "Number 28 is even."
    }
    //#rejectEmptyResponse-example
  }

  "requestEntityEmptyPresent-example" in {
    //#requestEntityEmptyPresent-example
    val route =
      concat(
        requestEntityEmpty {
          complete("request entity empty")
        },
        requestEntityPresent {
          complete("request entity present")
        }
      )

    // tests:
    Post("/", "text") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "request entity present"
    }
    Post("/") ~> route ~> check {
      responseAs[String] shouldEqual "request entity empty"
    }
    //#requestEntityEmptyPresent-example
  }

  "selectPreferredLanguage-example" in {
    //#selectPreferredLanguage-example
    val request = Get() ~> `Accept-Language`(
      Language("en-US"),
      Language("en") withQValue 0.7f,
      LanguageRange.`*` withQValue 0.1f,
      Language("de") withQValue 0.5f)

    request ~> {
      selectPreferredLanguage("en", "en-US") { lang =>
        complete(lang.toString)
      }
    } ~> check { responseAs[String] shouldEqual "en-US" }

    request ~> {
      selectPreferredLanguage("de-DE", "hu") { lang =>
        complete(lang.toString)
      }
    } ~> check { responseAs[String] shouldEqual "de-DE" }
    //#selectPreferredLanguage-example
  }

  "validate-example" in {
    //#validate-example
    val route =
      extractUri { uri =>
        validate(uri.path.toString.size < 5, s"Path too long: '${uri.path.toString}'") {
          complete(s"Full URI: $uri")
        }
      }

    // tests:
    Get("/234") ~> route ~> check {
      responseAs[String] shouldEqual "Full URI: http://example.com/234"
    }
    Get("/abcdefghijkl") ~> route ~> check {
      rejection shouldEqual ValidationRejection("Path too long: '/abcdefghijkl'", None)
    }
    //#validate-example
  }

  "withSizeLimit-example" in {
    //#withSizeLimit-example
    val route = withSizeLimit(500) {
      entity(as[String]) { _ =>
        complete(HttpResponse())
      }
    }

    // tests:
    def entityOfSize(size: Int) =
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, List.fill(size)('0').mkString)

    Post("/abc", entityOfSize(500)) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Post("/abc", entityOfSize(501)) ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.ContentTooLarge
    }

    //#withSizeLimit-example
  }

  "withSizeLimit-execution-moment-example" in {
    //#withSizeLimit-execution-moment-example
    val route = withSizeLimit(500) {
      complete(HttpResponse())
    }

    // tests:
    def entityOfSize(size: Int) =
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, List.fill(size)('0').mkString)

    Post("/abc", entityOfSize(500)) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Post("/abc", entityOfSize(501)) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
    //#withSizeLimit-execution-moment-example
  }

  "withSizeLimit-nested-example" in {
    //#withSizeLimit-nested-example
    val route =
      withSizeLimit(500) {
        withSizeLimit(800) {
          entity(as[String]) { _ =>
            complete(HttpResponse())
          }
        }
      }

    // tests:
    def entityOfSize(size: Int) =
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, List.fill(size)('0').mkString)
    Post("/abc", entityOfSize(800)) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Post("/abc", entityOfSize(801)) ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.ContentTooLarge
    }
    //#withSizeLimit-nested-example
  }

  "withoutSizeLimit-example" in {
    //#withoutSizeLimit-example
    val route =
      withoutSizeLimit {
        entity(as[String]) { _ =>
          complete(HttpResponse())
        }
      }

    // tests:
    def entityOfSize(size: Int) =
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, List.fill(size)('0').mkString)

    // will work even if you have configured akka.http.parsing.max-content-length = 500
    Post("/abc", entityOfSize(501)) ~> route ~> check {
      status shouldEqual StatusCodes.OK
    }
    //#withoutSizeLimit-example
  }

}
