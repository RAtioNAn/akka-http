/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

//#person-case-class
case class Person(name: String, favoriteNumber: Int)
//#person-case-class

//#person-json-support
import spray.json.{ DefaultJsonProtocol, RootJsonFormat }

object PersonJsonSupport extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val personFormat: RootJsonFormat[Person] = jsonFormat2(Person.apply)
}
//#person-json-support

class MarshallingDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "example-entity-with-json" in {
    //#example-entity-with-json
    import PersonJsonSupport._

    val route = post {
      entity(as[Person]) { person =>
        complete(s"Person: ${person.name} - favorite number: ${person.favoriteNumber}")
      }
    }

    // tests:
    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        responseAs[String] shouldEqual "Person: Jane - favorite number: 42"
      }
    //#example-entity-with-json
  }

  "example-entity-with-raw-json" in {
    //#example-entity-with-raw-json
    import spray.json.JsValue
    import PersonJsonSupport._

    val route = post {
      entity(as[JsValue]) { json =>
        complete(s"Person: ${json.asJsObject.fields("name")} - favorite number: ${json.asJsObject.fields("favoriteNumber")}")
      }
    }

    // tests:
    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        responseAs[String] shouldEqual """Person: "Jane" - favorite number: 42"""
      }
    //#example-entity-with-raw-json
  }

  "example-completeWith-with-json" in {
    //#example-completeWith-with-json
    import PersonJsonSupport._

    val findPerson = (f: Person => Unit) => {

      //... some processing logic...

      //complete the request
      f(Person("Jane", 42))
    }

    val route = get {
      completeWith(instanceOf[Person]) { completionFunction => findPerson(completionFunction) }
    }

    // tests:
    Get("/") ~> route ~> check {
      mediaType shouldEqual `application/json`
      responseAs[String] should include(""""name":"Jane"""")
      responseAs[String] should include(""""favoriteNumber":42""")
    }
    //#example-completeWith-with-json
  }

  "example-handleWith-with-json" in {
    //#example-handleWith-with-json
    import PersonJsonSupport._

    val updatePerson = (person: Person) => {

      //... some processing logic...

      //return the person
      person
    }

    val route = post {
      handleWith(updatePerson)
    }

    // tests:
    Post("/", HttpEntity(`application/json`, """{ "name": "Jane", "favoriteNumber" : 42 }""")) ~>
      route ~> check {
        mediaType shouldEqual `application/json`
        responseAs[String] should include(""""name":"Jane"""")
        responseAs[String] should include(""""favoriteNumber":42""")
      }
    //#example-handleWith-with-json
  }
}
