package io.simplifier.template.api

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.RouteDirectives
import akka.stream.ActorMaterializer
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class Routes(implicit val system: ActorSystem, implicit val materialize: ActorMaterializer) {
  case class RequestData(urls: List[String])

  case class ResponseData(url: String, data: String)

  case class Response(result: List[ResponseData], error: List[String])

  implicit val requestFormat: RootJsonFormat[RequestData] = jsonFormat1(RequestData)
  implicit val responseDataFormat: RootJsonFormat[ResponseData] = jsonFormat2(ResponseData)
  implicit val responseFormat: RootJsonFormat[Response] = jsonFormat2(Response)

  val route: Route = {
    pathEndOrSingleSlash {
      complete("up and running")
    } ~ path("crawl") {
      (post & entity(as[RequestData])) { requestData =>
        val eventualResponse = Future.sequence(requestData.urls.map { url =>
          Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = url))
            .flatMap(_.entity.toStrict(2.seconds))
            .map(_.data.utf8String).transformWith {
            case Success(res) => Future(Right(ResponseData(url, res)))
            case Failure(e) => Future(Left(e.getMessage))
          }
        })

        val finalResponse: Future[Response] = eventualResponse.map { responses =>
          val (valid, errors) = responses.partition(_.isRight)
          Response(valid.map(_.right.get), errors.map(_.left.get))
        }

        RouteDirectives.complete(finalResponse)
      }
    }
  }
}
