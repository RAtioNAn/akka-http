/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

class SchemeDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {
  "example-1" in {
    //#example-1
    val route =
      extractScheme { scheme =>
        complete(s"The scheme is '${scheme}'")
      }

    // tests:
    Get("https://www.example.com/") ~> route ~> check {
      responseAs[String] shouldEqual "The scheme is 'https'"
    }
    //#example-1
  }

  "example-2" in {
    //#example-2
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.model.headers.Location
    import StatusCodes.MovedPermanently

    val route =
      concat(
        scheme("http") {
          extract(_.request.uri) { uri =>
            redirect(uri.copy(scheme = "https"), MovedPermanently)
          }
        },
        scheme("https") {
          complete(s"Safe and secure!")
        }
      )

    // tests:
    Get("http://www.example.com/hello") ~> route ~> check {
      status shouldEqual MovedPermanently
      header[Location] shouldEqual Some(Location(Uri("https://www.example.com/hello")))
    }

    Get("https://www.example.com/hello") ~> route ~> check {
      responseAs[String] shouldEqual "Safe and secure!"
    }
    //#example-2
  }
}
