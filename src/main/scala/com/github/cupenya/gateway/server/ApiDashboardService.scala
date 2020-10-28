package com.github.cupenya.gateway.server

import scala.concurrent._

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.util._
import com.github.cupenya.gateway.configuration._
import com.github.cupenya.gateway.model._
import spray.json.DefaultJsonProtocol

trait Protocols extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val serviceRouteFormat         = jsonFormat3(ServiceRoute)
  implicit val serviceRoutesFormat        = jsonFormat1(ServiceRoutes)
  implicit val registerServiceRouteFormat = jsonFormat5(RegisterServiceRoute)
}

trait ApiDashboardService extends Directives with Protocols {
  import scala.concurrent.duration._
  import scala.language.postfixOps

  implicit val system: ActorSystem
  implicit def ec: ExecutionContext
  implicit val timeout = Timeout(5 seconds)

  private val DEFAULT_PORT = 80

  val dashboardRoute =
    pathPrefix("services") {
      pathEndOrSingleSlash {
        get {
          complete {
            ServiceRoutes(
              GatewayConfigurationManager
                .currentConfig()
                .targets
                .map {
                  case (key, target) => ServiceRoute(key, target.host, target.port)
                }
                .toList
            )
          }
        } ~
          post {
            entity(as[RegisterServiceRoute]) {
              case RegisterServiceRoute(name, host, resource, maybePort, maybeSecured) =>
                complete {
                  GatewayConfigurationManager.upsertGatewayTarget(
                    GatewayTarget(resource, host, maybePort.getOrElse(DEFAULT_PORT), maybeSecured.getOrElse(true))
                  )
                  StatusCodes.NoContent -> None
                }
            }
          }
      }
    }
}

case class ServiceRoute(resource: String, host: String, port: Int)

case class ServiceRoutes(services: List[ServiceRoute])

case class RegisterServiceRoute(
    name: String,
    host: String,
    resource: String,
    port: Option[Int] = None,
    secured: Option[Boolean] = None
)
